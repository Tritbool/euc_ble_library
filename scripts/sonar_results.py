#!/usr/bin/env python3
"""Collect the quality gate for this scan, never attributing latest metrics to it blindly."""

import base64
import json
import math
import os
from pathlib import Path
import time
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler


SERVER = "https://sonarcloud.io"
METRICS = ("coverage", "duplicated_lines_density", "bugs", "vulnerabilities",
           "code_smells", "security_hotspots")
TIMEOUT = 300


class CollectionError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError("SonarCloud redirected the API request.")


class Client:
    def __init__(self, token, deadline):
        self.authorization = "Basic " + base64.b64encode(
            (token + ":").encode()).decode()
        self.deadline = deadline
        self.opener = build_opener(NoRedirect())

    def get(self, endpoint, **params):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise CollectionError("SonarCloud collection timed out.")
        request = Request(SERVER + "/api/" + endpoint + "?" + urlencode(params),
                          headers={"Authorization": self.authorization,
                                   "Accept": "application/json"})
        try:
            with self.opener.open(request, timeout=min(30, remaining)) as response:
                return json.load(response)
        except Exception:
            # Never serialize exception strings, URLs, headers or response bodies.
            raise CollectionError("SonarCloud API request failed.") from None


def properties(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def analyses(client, project, scope, page=1, size=100):
    return client.get("project_analyses/search", project=project, p=page,
                      ps=size, **scope)


def find_revision(client, project, scope, analysis_id):
    for page in range(1, 11):
        response = analyses(client, project, scope, page)
        items = response.get("analyses", [])
        for item in items:
            if item.get("key") == analysis_id:
                return item.get("revision")
        if len(items) < 100:
            break
    return None


def is_latest(client, project, scope, analysis_id, sha):
    items = analyses(client, project, scope, size=1).get("analyses", [])
    return bool(items and items[0].get("key") == analysis_id
                and items[0].get("revision") == sha)


def collect(client, report, sha):
    task_id = report.get("ceTaskId")
    project = report.get("projectKey")
    if not task_id or not project:
        raise CollectionError("Sonar scanner report lacks task or project identifier.")
    while True:
        task = client.get("ce/task", id=task_id).get("task", {})
        status = task.get("status")
        if status == "SUCCESS":
            break
        if status not in ("PENDING", "IN_PROGRESS"):
            raise CollectionError("SonarCloud compute task failed or returned no status.")
        remaining = client.deadline - time.monotonic()
        if remaining <= 0:
            raise CollectionError("SonarCloud compute task timed out.")
        time.sleep(min(3, remaining))
    if task.get("componentKey") not in (None, project):
        raise CollectionError("SonarCloud task project does not match the scanner report.")
    analysis_id = task.get("analysisId")
    if not analysis_id:
        raise CollectionError("SonarCloud compute task lacks an analysis identifier.")
    scope = {}
    if task.get("branch"):
        scope["pullRequest" if task.get("branchType") == "PULL_REQUEST"
              else "branch"] = task["branch"]
    revision = task.get("revision")
    if not revision:
        revision = find_revision(client, project, scope, analysis_id)
    if revision != sha:
        raise CollectionError("SonarCloud analysis revision does not match GITHUB_SHA.")
    gate = client.get("qualitygates/project_status", analysisId=analysis_id)
    gate_status = gate.get("projectStatus", {}).get("status")
    if gate_status not in ("OK", "ERROR"):
        raise CollectionError("SonarCloud returned no usable quality gate.")
    result = {
        "status": "passed" if gate_status == "OK" else "failed",
        "available": True, "gate_status": gate_status,
        "analysis_id": analysis_id, "revision": revision,
        "dashboard_url": SERVER + "/dashboard?" + urlencode({"id": project, **scope}),
        "metrics_status": "unavailable",
    }
    try:
        if not is_latest(client, project, scope, analysis_id, sha):
            result["metrics_status"] = "stale"
            return result
        raw = client.get("measures/component", component=project,
                         metricKeys=",".join(METRICS), **scope)
        if not is_latest(client, project, scope, analysis_id, sha):
            result["metrics_status"] = "stale"
            return result
        metrics = {}
        for measure in raw.get("component", {}).get("measures", []):
            if measure.get("metric") in METRICS and "value" in measure:
                value = float(measure["value"])
                if math.isfinite(value):
                    metrics[measure["metric"]] = value
        if metrics:
            result["metrics"] = metrics
            result["metrics_status"] = "available"
    except (CollectionError, ValueError, TypeError, KeyError):
        result["metrics_status"] = "unavailable"
    return result


def main(root=Path("."), environ=None):
    env = os.environ if environ is None else environ
    result = {"schema_version": 1,
              "timestamp": datetime.now(timezone.utc).isoformat(),
              "sha": env.get("GITHUB_SHA"), "status": "failed",
              "available": False, "gate_status": "unavailable",
              "metrics_status": "unavailable"}
    try:
        token = env.get("SONAR_TOKEN")
        if not token:
            raise CollectionError("SONAR_TOKEN is missing; quality gate unavailable.")
        if not env.get("GITHUB_SHA"):
            raise CollectionError("GITHUB_SHA is missing; analysis cannot be verified.")
        candidates = [
            root / "euc-ble-core/build/sonar/report-task.txt",
            root / "euc-ble-core/.scannerwork/report-task.txt",
            root / ".scannerwork/report-task.txt",
        ]
        path = next((candidate for candidate in candidates if candidate.is_file()), None)
        if path is None:
            raise CollectionError("Sonar scanner report-task.txt is missing.")
        result.update(collect(Client(token, time.monotonic() + TIMEOUT),
                              properties(path), env["GITHUB_SHA"]))
    except CollectionError as error:
        result["reason"] = str(error)
    except Exception:
        result["reason"] = "SonarCloud report could not be collected."
    output = root / "quality/sonar.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n",
                      encoding="utf-8")
    print("SonarCloud quality gate: " + result["status"])
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
