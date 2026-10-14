#!/usr/bin/env python3
"""Create a dependency-free CI snapshot; absent reports never imply zero defects."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def section(outcome, available=False, **values):
    status = "passed" if available else "unavailable"
    if outcome in ("failure", "cancelled", "timed_out"):
        status = "failed"
    return {"status": status, "available": available,
            "outcome": outcome or "unknown", **values}


def tests(build, outcome):
    files = sorted((build / "test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not files:
        return section(outcome, reason="Test XML reports are missing.")
    cases = {}
    duplicates = 0
    try:
        for path in files:
            root = ET.parse(path).getroot()
            for case in root.iter("testcase"):
                # AllTests also executes selected classes: count logical tests once,
                # retaining the worst result if the repeated executions disagree.
                identity = (case.get("classname", root.get("name", "")),
                            case.get("name", ""))
                state = ("errors" if case.find("error") is not None else
                         "failures" if case.find("failure") is not None else
                         "skipped" if case.find("skipped") is not None else "passed")
                rank = {"passed": 0, "skipped": 1, "failures": 2, "errors": 3}
                if identity in cases:
                    duplicates += 1
                    state = max((state, cases[identity]), key=rank.get)
                cases[identity] = state
        if not cases:
            return section(outcome, reason="Test XML contains no test cases.")
        counts = {key: sum(state == key for state in cases.values())
                  for key in ("passed", "failures", "errors", "skipped")}
        result = section(outcome, True, total=len(cases), **counts,
                         duplicate_executions=duplicates,
                         report="reports/tests/index.html")
        if counts["failures"] or counts["errors"]:
            result["status"] = "failed"
        return result
    except (OSError, ET.ParseError):
        return section(outcome, reason="Test XML reports could not be parsed.")


def lint(build, outcome):
    path = build / "reports/lint-results-debug.xml"
    if not path.is_file():
        return section(outcome, reason="Lint XML report is missing.")
    try:
        issues = ET.parse(path).getroot().findall("issue")
        counts = {}
        for issue in issues:
            severity = issue.get("severity", "Unknown")
            counts[severity] = counts.get(severity, 0) + 1
        result = section(outcome, True, total=len(issues), severities=counts,
                         report="reports/lint/index.html")
        if any(counts.get(key, 0) for key in ("Fatal", "Error")):
            result["status"] = "failed"
        return result
    except (OSError, ET.ParseError):
        return section(outcome, reason="Lint XML report could not be parsed.")


def coverage(build, kind, outcome):
    path = build / ("reports/jacoco/" + kind + "/jacoco.xml")
    if not path.is_file():
        return section(outcome, reason="Coverage XML report is missing.")
    try:
        root = ET.parse(path).getroot()
        counters = {}
        # Only root counters: package/class counters would double-count totals.
        for counter in root.findall("counter"):
            if counter.get("type") not in ("LINE", "BRANCH"):
                continue
            covered, missed = int(counter.get("covered")), int(counter.get("missed"))
            if min(covered, missed) < 0:
                raise ValueError
            total = covered + missed
            counters[counter.get("type").lower()] = {
                "covered": covered, "missed": missed, "total": total,
                "percent": round(100 * covered / total, 2) if total else None}
        if not counters:
            return section(outcome, reason="Coverage report contains no counters.")
        return section(outcome, True, **counters,
                       report="reports/coverage/" + kind + "/index.html")
    except (OSError, ET.ParseError, ValueError, TypeError):
        return section(outcome, reason="Coverage XML report could not be parsed.")


def sonar(root, outcome, sha):
    try:
        data = json.loads((root / "quality/sonar.json").read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError
        if not sha or data.get("sha") != sha:
            result = section(outcome, reason="Sonar snapshot belongs to another revision.",
                             metrics_status="stale", gate_status="stale")
            if result["status"] != "failed":
                result["status"] = "stale"
            return result
        result = section(outcome, data.get("available", False))
        # Explicit allowlist: never copy credentials or arbitrary collector fields.
        for key in ("gate_status", "analysis_id", "revision", "timestamp",
                    "dashboard_url", "metrics_status", "reason"):
            if key in data:
                result[key] = data[key]
        if data.get("metrics_status") == "available" and isinstance(data.get("metrics"), dict):
            result["metrics"] = data["metrics"]
        if data.get("status") == "failed":
            result["status"] = "failed"
        return result
    except (OSError, ValueError):
        return section(outcome, reason="Sonar snapshot is missing or invalid.",
                       metrics_status="unavailable", gate_status="unavailable")


def main(root=Path("."), environ=None):
    env = os.environ if environ is None else environ
    build = root / "euc-ble-core/build"
    test_outcome = env.get("TEST_OUTCOME")
    sha = env.get("GITHUB_SHA")
    result = {
        "schema_version": 1, "timestamp": datetime.now(timezone.utc).isoformat(),
        "sha": sha, "run_id": env.get("GITHUB_RUN_ID"),
        "repository": env.get("GITHUB_REPOSITORY"),
        "check_outcome": env.get("CHECK_OUTCOME", "unknown"),
        "tests": tests(build, test_outcome),
        "lint": lint(build, env.get("LINT_OUTCOME")),
        "coverage": {kind: coverage(build, kind, test_outcome)
                     for kind in ("full", "focused")},
        "sonar": sonar(root, env.get("SONAR_OUTCOME"), sha),
    }
    statuses = [result["tests"]["status"], result["lint"]["status"],
                result["sonar"]["status"],
                *(item["status"] for item in result["coverage"].values())]
    result["status"] = ("failed" if "failed" in statuses or
                        result["check_outcome"] in ("failure", "cancelled", "timed_out") else
                        "stale" if "stale" in statuses else
                        "unavailable" if "unavailable" in statuses else "passed")
    output = root / "quality/summary.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n",
                      encoding="utf-8")
    print("Quality snapshot: " + result["status"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
