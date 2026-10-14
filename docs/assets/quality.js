/* Static, bilingual rendering of the published CI snapshot. */
(() => {
  "use strict";
  const dashboard = document.getElementById("quality-dashboard");
  if (!dashboard) return;
  const labels = {
    en: {
      status: "Status", available: "Available", passed: "Passed", failed: "Failed",
      unavailable: "Unavailable", stale: "Stale — omitted", unknown: "Unknown",
      timestamp: "Collected at", sha: "Commit SHA", run: "CI run",
      outcome: "CI step outcome", success: "Success", failure: "Failure",
      cancelled: "Cancelled", skipped: "Skipped", timed_out: "Timed out",
      total: "Total", failures: "Failures", errors: "Errors", duplicates: "Duplicate executions (counted once)",
      line: "Line coverage", branch: "Branch coverage", covered: "covered",
      metrics: "Metrics", gate: "Quality gate", report: "Open report",
      noReport: "Report unavailable: no measurements were reported.",
      loadError: "Quality snapshot unavailable: it may not have been published yet.",
      loaded: "Published snapshot loaded.", Fatal: "Fatal", Error: "Error",
      Warning: "Warning", Information: "Information", Ignore: "Ignored", Unknown: "Unknown",
      coverage: "Coverage (%)", duplicated_lines_density: "Duplicated lines (%)",
      bugs: "Bugs", vulnerabilities: "Vulnerabilities", code_smells: "Code smells",
      security_hotspots: "Security hotspots", dashboard: "Open SonarCloud",
      OK: "Passed", ERROR: "Failed",
    },
    fr: {
      status: "État", available: "Disponible", passed: "Réussi", failed: "Échec",
      unavailable: "Indisponible", stale: "Obsolètes — omises", unknown: "Inconnu",
      timestamp: "Collecté le", sha: "SHA du commit", run: "Exécution CI",
      outcome: "Résultat de l’étape CI", success: "Réussite", failure: "Échec",
      cancelled: "Annulé", skipped: "Ignorés", timed_out: "Délai dépassé",
      total: "Total", failures: "Échecs", errors: "Erreurs", duplicates: "Exécutions dupliquées (comptées une fois)",
      line: "Couverture des lignes", branch: "Couverture des branches", covered: "couverts",
      metrics: "Métriques", gate: "Contrôle qualité", report: "Ouvrir le rapport",
      noReport: "Rapport indisponible : aucune mesure n’a été publiée.",
      loadError: "Instantané qualité indisponible : il n’a peut-être pas encore été publié.",
      loaded: "Instantané publié chargé.", Fatal: "Fatal", Error: "Erreur",
      Warning: "Avertissement", Information: "Information", Ignore: "Ignoré", Unknown: "Inconnu",
      coverage: "Couverture (%)", duplicated_lines_density: "Lignes dupliquées (%)",
      bugs: "Bugs", vulnerabilities: "Vulnérabilités", code_smells: "Défauts de code",
      security_hotspots: "Points sensibles de sécurité", dashboard: "Ouvrir SonarCloud",
      OK: "Réussi", ERROR: "Échec",
    },
  };
  const t = labels[dashboard.dataset.language] || labels.en;
  const text = value => value === null || value === undefined ? t.unavailable : String(value);
  const translated = value => t[value] || text(value);
  const summaryURL = new URL(dashboard.dataset.summaryUrl, document.baseURI);
  const reportPaths = new Set([
    "reports/tests/index.html", "reports/lint/index.html",
    "reports/coverage/full/index.html", "reports/coverage/focused/index.html",
  ]);

  function row(list, label, value) {
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    description.textContent = text(value);
    list.append(term, description);
  }

  function link(container, label, url) {
    const anchor = document.createElement("a");
    anchor.textContent = label;
    anchor.href = url.href;
    const paragraph = document.createElement("p");
    paragraph.append(anchor);
    container.append(paragraph);
  }

  function section(id, data) {
    const container = document.getElementById(id);
    container.replaceChildren();
    const list = document.createElement("dl");
    container.append(list);
    row(list, t.status, translated(data.status || "unavailable"));
    if (data.outcome) row(list, t.outcome, translated(data.outcome));
    if (!data.available) {
      const paragraph = document.createElement("p");
      paragraph.textContent = t.noReport;
      container.append(paragraph);
    }
    if (data.available && reportPaths.has(data.report)) {
      link(container, t.report, new URL(data.report, summaryURL));
    }
    return {container, list};
  }

  function render(data) {
    const snapshot = section("quality-snapshot", {status: data.status, available: true});
    row(snapshot.list, t.timestamp, data.timestamp);
    row(snapshot.list, t.sha, data.sha);
    if (/^[\w.-]+\/[\w.-]+$/.test(data.repository || "") &&
        /^\d+$/.test(data.run_id || "")) {
      link(snapshot.container, t.run, new URL("https://github.com/" +
        data.repository + "/actions/runs/" + data.run_id));
    }
    const tests = data.tests || {};
    const testView = section("quality-tests", tests);
    if (tests.available) {
      for (const key of ["total", "passed", "failures", "errors", "skipped"]) {
        row(testView.list, t[key], tests[key]);
      }
      row(testView.list, t.duplicates, tests.duplicate_executions);
    }
    const lint = data.lint || {};
    const lintView = section("quality-lint", lint);
    if (lint.available) {
      row(lintView.list, t.total, lint.total);
      const severities = {Fatal: 0, Error: 0, Warning: 0, Information: 0, Ignore: 0,
                          ...(lint.severities || {})};
      for (const [severity, count] of Object.entries(severities)) {
        row(lintView.list, translated(severity), count);
      }
    }
    for (const kind of ["full", "focused"]) {
      const coverage = (data.coverage || {})[kind] || {};
      const view = section("quality-coverage-" + kind, coverage);
      if (coverage.available) {
        for (const key of ["line", "branch"]) {
          const counter = coverage[key];
          const value = !counter || counter.percent === null ? t.unavailable :
            text(counter.percent) + "% (" + text(counter.covered) + "/" +
            text(counter.total) + " " + t.covered + ")";
          row(view.list, t[key], value);
        }
      }
    }
    const sonar = data.sonar || {};
    const sonarView = section("quality-sonar", sonar);
    row(sonarView.list, t.gate, translated(sonar.gate_status || "unavailable"));
    row(sonarView.list, t.metrics, translated(sonar.metrics_status || "unavailable"));
    if (sonar.timestamp) row(sonarView.list, t.timestamp, sonar.timestamp);
    if (sonar.metrics_status === "available") {
      for (const key of ["coverage", "duplicated_lines_density", "bugs",
                         "vulnerabilities", "code_smells", "security_hotspots"]) {
        row(sonarView.list, t[key], (sonar.metrics || {})[key]);
      }
    }
    if (sonar.dashboard_url) {
      try {
        const url = new URL(sonar.dashboard_url);
        if (url.origin === "https://sonarcloud.io" && !url.username && !url.password) {
          link(sonarView.container, t.dashboard, url);
        }
      } catch (_) { /* Invalid URLs are not links. */ }
    }
  }

  async function load() {
    const loading = document.getElementById("quality-loading");
    try {
      const response = await fetch(summaryURL, {cache: "no-store"});
      if (!response.ok) throw new Error("Snapshot unavailable");
      const data = await response.json();
      if (!data || data.schema_version !== 1) throw new Error("Unsupported snapshot");
      render(data);
      loading.textContent = t.loaded;
    } catch (_) {
      loading.textContent = t.loadError;
      for (const id of ["quality-snapshot", "quality-tests", "quality-lint",
                        "quality-coverage-full", "quality-coverage-focused", "quality-sonar"]) {
        section(id, {});
      }
    }
  }
  load();
})();
