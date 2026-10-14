import contextlib
import io
import json
from html.parser import HTMLParser
from pathlib import Path
import shutil
import sys
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import quality_report
import sonar_results


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).parent / (".fixture-" + uuid.uuid4().hex)
        self.root.mkdir()
        self.addCleanup(shutil.rmtree, self.root)
        self.build = self.root / "euc-ble-core/build"

    def write(self, path, content):
        path = self.root / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


class SnapshotTests(FixtureTest):
    def test_missing_reports_are_not_zero(self):
        for result in (quality_report.tests(self.build, "success"),
                       quality_report.lint(self.build, "success"),
                       quality_report.coverage(self.build, "full", "success")):
            self.assertFalse(result["available"])
            self.assertEqual(result["status"], "unavailable")
            self.assertNotIn("total", result)
        self.assertEqual(quality_report.tests(self.build, "failure")["status"], "failed")

    def test_suite_duplicates_keep_worst_result_and_parameterized_names(self):
        self.write("euc-ble-core/build/test-results/testDebugUnitTest/TEST-AllTests.xml", """
            <testsuite name="AllTests" tests="3"><testcase classname="A" name="test[1]"/>
            <testcase classname="A" name="test[2]"><skipped/></testcase>
            <testcase classname="B" name="test"/></testsuite>""")
        self.write("euc-ble-core/build/test-results/testDebugUnitTest/TEST-A.xml", """
            <testsuite name="A"><testcase classname="A" name="test[1]"><failure/></testcase>
            <testcase classname="A" name="test[2]"><skipped/></testcase></testsuite>""")
        result = quality_report.tests(self.build, "success")
        self.assertEqual(result["total"], 3)
        self.assertEqual(result["duplicate_executions"], 2)
        self.assertEqual(result["failures"], 1)
        self.assertEqual(result["skipped"], 1)
        self.assertEqual(result["passed"], 1)
        self.assertEqual(result["status"], "failed")

    def test_junit_errors(self):
        self.write("euc-ble-core/build/test-results/testDebugUnitTest/TEST-A.xml",
                   '<testsuites><testsuite><testcase classname="A" name="a">'
                   '<error/></testcase></testsuite></testsuites>')
        result = quality_report.tests(self.build, None)
        self.assertEqual(result["errors"], 1)
        self.assertEqual(result["status"], "failed")

    def test_malformed_xml_is_unavailable(self):
        for path in ("test-results/testDebugUnitTest/TEST-A.xml",
                     "reports/lint-results-debug.xml", "reports/jacoco/full/jacoco.xml"):
            self.write("euc-ble-core/build/" + path, "<invalid")
        self.assertFalse(quality_report.tests(self.build, None)["available"])
        self.assertFalse(quality_report.lint(self.build, None)["available"])
        self.assertFalse(quality_report.coverage(self.build, "full", None)["available"])

    def test_lint_counts_issues_not_locations(self):
        self.write("euc-ble-core/build/reports/lint-results-debug.xml", """
            <issues><issue severity="Warning"><location/><location/></issue>
            <issue severity="Error"/><issue severity="Warning"/></issues>""")
        result = quality_report.lint(self.build, "success")
        self.assertEqual(result["total"], 3)
        self.assertEqual(result["severities"], {"Warning": 2, "Error": 1})
        self.assertEqual(result["status"], "failed")

    def test_empty_lint_is_available_zero(self):
        self.write("euc-ble-core/build/reports/lint-results-debug.xml", "<issues/>")
        result = quality_report.lint(self.build, "success")
        self.assertTrue(result["available"])
        self.assertEqual(result["total"], 0)

    def test_coverage_only_root_counters_and_no_denominator(self):
        self.write("euc-ble-core/build/reports/jacoco/full/jacoco.xml", """
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report><package><counter type="LINE" covered="999" missed="0"/></package>
            <counter type="LINE" covered="3" missed="1"/>
            <counter type="BRANCH" covered="0" missed="0"/></report>""")
        result = quality_report.coverage(self.build, "full", "success")
        self.assertEqual(result["line"]["percent"], 75)
        self.assertEqual(result["line"]["total"], 4)
        self.assertIsNone(result["branch"]["percent"])

    def test_stale_sonar_is_not_reused(self):
        self.write("quality/sonar.json", json.dumps({
            "sha": "old", "available": True, "status": "passed",
            "gate_status": "OK", "metrics": {"bugs": 0}}))
        result = quality_report.sonar(self.root, "success", "new")
        self.assertFalse(result["available"])
        self.assertEqual(result["status"], "stale")
        self.assertEqual(result["gate_status"], "stale")
        self.assertEqual(result["metrics_status"], "stale")
        self.assertNotIn("metrics", result)

    def test_failed_gate_and_stale_metrics_are_preserved(self):
        self.write("quality/sonar.json", json.dumps({
            "sha": "sha", "available": True, "status": "failed",
            "gate_status": "ERROR", "metrics_status": "stale",
            "metrics": {"bugs": 0}, "token": "not-copied"}))
        result = quality_report.sonar(self.root, "success", "sha")
        self.assertEqual(result["status"], "failed")
        self.assertNotIn("metrics", result)
        self.assertNotIn("token", result)

    def test_analysis_timestamp_survives_summary_allowlist(self):
        self.write("quality/sonar.json", json.dumps({
            "sha": "sha", "available": True, "status": "passed",
            "timestamp": "2026-10-14T14:25:00Z",
            "analysis_timestamp": "2026-10-14T14:20:00+0000"}))
        result = quality_report.sonar(self.root, "success", "sha")
        self.assertEqual(result["analysis_timestamp"], "2026-10-14T14:20:00+0000")
        self.assertEqual(result["timestamp"], "2026-10-14T14:25:00Z")

    def test_snapshot_cli_persists_metadata_and_failure(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(quality_report.main(self.root, {
                "GITHUB_SHA": "abc", "GITHUB_RUN_ID": "42",
                "GITHUB_REPOSITORY": "owner/repo", "TEST_OUTCOME": "failure",
                "LINT_OUTCOME": "skipped", "SONAR_OUTCOME": "skipped"}), 0)
        result = json.loads((self.root / "quality/summary.json").read_text())
        self.assertEqual(result["sha"], "abc")
        self.assertEqual(result["run_id"], "42")
        self.assertEqual(result["status"], "failed")
        self.assertFalse(result["tests"]["available"])

    def test_checks_job_failure_overrides_passing_reports(self):
        passing = {"status": "passed", "available": True}
        with patch.object(quality_report, "tests", return_value=passing), \
                patch.object(quality_report, "lint", return_value=passing), \
                patch.object(quality_report, "coverage", return_value=passing), \
                patch.object(quality_report, "sonar", return_value=passing):
            for outcome in ("failure", "cancelled", "success"):
                with self.subTest(outcome=outcome), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(quality_report.main(self.root, {
                        "GITHUB_SHA": "sha", "CHECK_OUTCOME": outcome}), 0)
                result = json.loads((self.root / "quality/summary.json").read_text())
                self.assertEqual(result["check_outcome"], outcome)
                self.assertEqual(result["status"],
                                 "passed" if outcome == "success" else "failed")


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.deadline = sonar_results.time.monotonic() + 300

    def get(self, endpoint, **params):
        self.calls.append((endpoint, params))
        expected, response = self.responses.pop(0)
        if endpoint != expected:
            raise AssertionError((endpoint, expected))
        if isinstance(response, Exception):
            raise response
        return response


class SonarTests(FixtureTest):
    report = {"projectKey": "project", "ceTaskId": "task",
              "ceTaskUrl": "https://evil.example/task",
              "serverUrl": "https://evil.example",
              "dashboardUrl": "javascript:alert(1)"}

    def task(self, **changes):
        return {"task": {"status": "SUCCESS", "analysisId": "analysis",
                         "revision": "sha", "componentKey": "project", **changes}}

    def latest(self, key="analysis", revision="sha"):
        return {"analyses": [{"key": key, "revision": revision}]}

    def gate(self, status="OK"):
        return {"projectStatus": {"status": status}}

    def test_success_metrics_and_safe_urls(self):
        client = FakeClient([
            ("ce/task", self.task()), ("qualitygates/project_status", self.gate()),
            ("project_analyses/search", self.latest()),
            ("measures/component", {"component": {"measures": [
                {"metric": "coverage", "value": "71.3"},
                {"metric": "bugs", "value": "0"}]}}),
            ("project_analyses/search", self.latest())])
        result = sonar_results.collect(client, self.report, "sha")
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["metrics"], {"coverage": 71.3, "bugs": 0})
        self.assertTrue(result["dashboard_url"].startswith("https://sonarcloud.io/"))
        self.assertEqual(client.calls[0], ("ce/task", {
            "id": "task", "additionalFields": "scannerContext"}))
        self.assertEqual(client.calls[1][1], {"analysisId": "analysis"})

    def test_metrics_stale_before_and_after(self):
        for before in (True, False):
            with self.subTest(before=before):
                responses = [
                    ("ce/task", self.task()),
                    ("qualitygates/project_status", self.gate()),
                    ("project_analyses/search", self.latest("other") if before else self.latest())]
                if not before:
                    responses += [
                        ("measures/component", {"component": {"measures": [
                            {"metric": "bugs", "value": "99"}]}}),
                        ("project_analyses/search", self.latest("other"))]
                result = sonar_results.collect(FakeClient(responses), self.report, "sha")
                self.assertEqual(result["metrics_status"], "stale")
                self.assertNotIn("metrics", result)
                self.assertEqual(result["gate_status"], "OK")

    def test_latest_revision_must_also_match(self):
        client = FakeClient([
            ("ce/task", self.task()), ("qualitygates/project_status", self.gate()),
            ("project_analyses/search", self.latest(revision="different"))])
        self.assertEqual(sonar_results.collect(client, self.report, "sha")
                         ["metrics_status"], "stale")

    def test_revision_fallback_analysis_search(self):
        client = FakeClient([
            ("ce/task", self.task(revision=None, branch="main", branchType="BRANCH")),
            ("project_analyses/search", self.latest()),
            ("qualitygates/project_status", self.gate()),
            ("project_analyses/search", self.latest("other"))])
        result = sonar_results.collect(client, self.report, "sha")
        self.assertEqual(result["revision"], "sha")
        self.assertEqual(client.calls[1][1]["branch"], "main")

    def test_pr_gate_with_scanner_revision_does_not_require_analysis_history(self):
        for status in ("OK", "ERROR"):
            with self.subTest(status=status):
                client = FakeClient([
                    ("ce/task", self.task(
                        revision=None, pullRequest="123",
                        scannerContext="Scanner properties:\n"
                        "  - sonar.scm.revision=sha\n  - sonar.token=private-token\n")),
                    ("qualitygates/project_status", self.gate(status))])
                result = sonar_results.collect(client, self.report, "sha")
                self.assertEqual(result["revision"], "sha")
                self.assertEqual(result["gate_status"], status)
                self.assertEqual(result["metrics_status"], "unavailable")
                self.assertNotIn("metrics", result)
                self.assertIn("pullRequest=123", result["dashboard_url"])
                self.assertNotIn("private-token", json.dumps(result))
                self.assertEqual([call[0] for call in client.calls],
                                 ["ce/task", "qualitygates/project_status"])

    def test_analysis_timestamp_comes_only_from_task_execution_time(self):
        for executed in ("2026-10-14T14:20:00+0000", None):
            client = FakeClient([
                ("ce/task", self.task(pullRequest="123", executedAt=executed)),
                ("qualitygates/project_status", self.gate())])
            result = sonar_results.collect(client, self.report, "sha")
            self.assertEqual(result["analysis_timestamp"], executed)

    def test_pr_scanner_context_mismatched_revision_fails(self):
        client = FakeClient([
            ("ce/task", self.task(revision=None, pullRequest="123",
                                 scannerContext="  sonar.scm.revision=other\n"))])
        with self.assertRaises(sonar_results.CollectionError):
            sonar_results.collect(client, self.report, "sha")

    def test_conflicting_context_and_task_revisions_fail(self):
        for context in ("sonar.scm.revision=other",
                        "sonar.scm.revision=sha\nsonar.scm.revision=other"):
            client = FakeClient([("ce/task", self.task(scannerContext=context))])
            with self.assertRaises(sonar_results.CollectionError):
                sonar_results.collect(client, self.report, "sha")

    def test_scanner_context_property_must_match_exact_key(self):
        self.assertIsNone(sonar_results.scanner_revision(
            "other.sonar.scm.revision=sha\nsonar.scm.revision.suffix=sha"))

    def test_revision_mismatch_and_unverifiable_revision_fail(self):
        for revision in ("different", None):
            responses = [("ce/task", self.task(revision=revision))]
            if revision is None:
                responses.append(("project_analyses/search", {"analyses": []}))
            with self.assertRaises(sonar_results.CollectionError):
                sonar_results.collect(FakeClient(responses), self.report, "sha")

    def test_failed_gate_retained_even_without_metrics(self):
        client = FakeClient([
            ("ce/task", self.task()), ("qualitygates/project_status", self.gate("ERROR")),
            ("project_analyses/search", sonar_results.CollectionError("Unavailable"))])
        result = sonar_results.collect(client, self.report, "sha")
        self.assertEqual(result["status"], "failed")
        self.assertEqual(result["gate_status"], "ERROR")
        self.assertEqual(result["metrics_status"], "unavailable")

    def test_missing_and_failed_task_and_gate_fail(self):
        for responses in (
                [("ce/task", self.task(status="FAILED"))],
                [("ce/task", self.task(analysisId=None))],
                [("ce/task", self.task(componentKey="other"))],
                [("ce/task", self.task()), ("qualitygates/project_status", {})]):
            with self.subTest(responses=responses), self.assertRaises(sonar_results.CollectionError):
                sonar_results.collect(FakeClient(responses), self.report, "sha")

    def test_poll_waits_for_success(self):
        client = FakeClient([
            ("ce/task", self.task(status="PENDING")),
            ("ce/task", self.task(status="IN_PROGRESS")),
            ("ce/task", self.task()), ("qualitygates/project_status", self.gate()),
            ("project_analyses/search", self.latest("other"))])
        with patch.object(sonar_results.time, "sleep") as sleep:
            result = sonar_results.collect(client, self.report, "sha")
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(result["status"], "passed")

    def test_deadline_bounds_polling_and_requests(self):
        client = FakeClient([("ce/task", self.task(status="PENDING"))])
        client.deadline = 0
        with self.assertRaises(sonar_results.CollectionError):
            sonar_results.collect(client, self.report, "sha")
        client = sonar_results.Client("secret", 0)
        with self.assertRaises(sonar_results.CollectionError):
            client.get("ce/task", id="task")

    def test_network_error_does_not_leak_token(self):
        client = sonar_results.Client("private-token", sonar_results.time.monotonic() + 30)
        with patch.object(client.opener, "open", side_effect=RuntimeError("private-token")):
            with self.assertRaises(sonar_results.CollectionError) as caught:
                client.get("ce/task", id="task")
        self.assertNotIn("private-token", str(caught.exception))

    def test_redirect_is_not_followed(self):
        with self.assertRaises(sonar_results.CollectionError):
            sonar_results.NoRedirect().redirect_request(None, None, 302, "", {},
                                                       "https://evil.example")

    def test_missing_token_report_and_sha_write_failure(self):
        for env in ({}, {"SONAR_TOKEN": "secret"},
                    {"SONAR_TOKEN": "secret", "GITHUB_SHA": "sha"}):
            with contextlib.redirect_stdout(io.StringIO()) as log:
                self.assertEqual(sonar_results.main(self.root, env), 1)
            artifact = (self.root / "quality/sonar.json").read_text()
            self.assertNotIn("secret", artifact + log.getvalue())
            self.assertEqual(json.loads(artifact)["status"], "failed")

    def test_cli_failed_gate_returns_failure(self):
        self.write("euc-ble-core/build/sonar/report-task.txt",
                   "projectKey=project\nceTaskId=task\n")
        for status, code in (("passed", 0), ("failed", 1)):
            with patch.object(sonar_results, "collect", return_value={
                    "status": status, "available": True, "gate_status": "ERROR"
                    if code else "OK"}), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(sonar_results.main(self.root, {
                    "SONAR_TOKEN": "secret", "GITHUB_SHA": "sha"}), code)

    def test_raw_api_failure_and_context_never_enter_artifact(self):
        self.write("euc-ble-core/build/sonar/report-task.txt",
                   "projectKey=project\nceTaskId=task\n")
        client = FakeClient([("ce/task", self.task(
            status="FAILED", errorMessage="private-token",
            scannerContext="sonar.token=private-token"))])
        with patch.object(sonar_results, "Client", return_value=client), \
                contextlib.redirect_stdout(io.StringIO()) as log:
            self.assertEqual(sonar_results.main(self.root, {
                "SONAR_TOKEN": "private-token", "GITHUB_SHA": "sha"}), 1)
        artifact = (self.root / "quality/sonar.json").read_text()
        self.assertNotIn("private-token", artifact + log.getvalue())
        self.assertNotIn("scannerContext", artifact)
        self.assertNotIn("errorMessage", artifact)

    def test_scannerwork_metadata_fallback(self):
        self.write("euc-ble-core/.scannerwork/report-task.txt",
                   "projectKey=project\nceTaskId=task\n")
        with patch.object(sonar_results, "collect", return_value={
                "status": "passed", "available": True, "gate_status": "OK"
                }) as collect, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(sonar_results.main(self.root, {
                "SONAR_TOKEN": "secret", "GITHUB_SHA": "sha"}), 0)
        self.assertEqual(collect.call_args.args[1]["ceTaskId"], "task")


class DashboardTests(unittest.TestCase):
    def test_bilingual_pages_have_matching_ids_and_baseurl_safe_paths(self):
        root = Path(__file__).resolve().parents[2]

        class Parser(HTMLParser):
            def __init__(self):
                super().__init__()
                self.ids = []

            def handle_starttag(self, tag, attrs):
                attrs = dict(attrs)
                if "id" in attrs:
                    self.ids.append(attrs["id"])

        pages = []
        for path, language in (("docs/quality/index.html", "fr"),
                               ("docs/en/quality/index.html", "en")):
            html = (root / path).read_text()
            self.assertTrue(html.startswith("---\nlayout: default\n"))
            self.assertIn('lang="' + language + '"', html)
            self.assertIn("{{ '/quality/summary.json' | relative_url }}", html)
            self.assertIn("{{ '/assets/quality.js' | relative_url }}", html)
            parser = Parser()
            parser.feed(html)
            self.assertEqual(len(parser.ids), len(set(parser.ids)))
            pages.append(set(parser.ids))
        self.assertEqual(*pages)

    def test_freshness_uses_fixed_public_main_endpoint_and_bounded_anonymous_fetch(self):
        script = (Path(__file__).resolve().parents[2] /
                  "docs/assets/quality.js").read_text()
        self.assertIn("https://api.github.com/repos/Tritbool/euc_ble_library/commits/main",
                      script)
        self.assertIn('credentials: "omit"', script)
        self.assertIn("new AbortController()", script)
        self.assertIn("controller.abort(), 8000", script)
        self.assertIn("display.textContent = t.unavailable", script)
        self.assertNotIn("innerHTML", script)
        self.assertNotIn("Authorization", script)


if __name__ == "__main__":
    unittest.main()
