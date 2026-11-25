---
layout: default
title: Maintenance & CI
---

# Maintenance & CI

Operational notes for maintainers and CI, referenced from the [README](../README.md).

## Automated quality checks

CI runs Android Lint, unit tests and full/focused JaCoCo coverage on pushes and PRs.
SonarCloud analyzes PRs and `main` updates; other branch pushes run the local checks only.
The **Quality gate** check fails on lint errors, test failures, missing Sonar analysis or a failed
Sonar quality gate. SonarCloud receives the full JaCoCo XML and Android Lint XML reports.
The former separate Sonar workflow is consolidated into CI so reports describe the same run.

The bilingual [quality dashboard](https://tritbool.github.io/euc_ble_library/en/quality/)
is published after completed `main` push checks, including failures. It displays the commit,
timestamp, test/lint results, coverage and Sonar quality gate, with detailed report links.
The test report index includes each available module/task report and its static assets.
Detailed links are published only when their HTML report is present.
Sonar results are collected only after the submitted analysis completes. The gate is fetched by
analysis ID; metrics are omitted as stale if a newer analysis replaces that commit during collection.
Missing reports are unavailable, not zero or passing. PR artifacts are never deployed to Pages.
The dashboard anonymously checks GitHub's current `main` SHA to flag an older published snapshot;
if GitHub cannot be reached, freshness is unavailable. No authentication is sent from the website.
Manual Pages runs require a completed CI run for the current `main` commit and retained artifacts
(30 days). Dokka generation failure does not prevent publication of a failed quality snapshot.

Repository administrators must configure:

- `SONAR_TOKEN` as an Actions secret with permission to analyze this SonarCloud project.
  Disable SonarCloud automatic analysis if enabled, to use the CI-supplied reports.
- GitHub Pages source: **GitHub Actions**.
- A branch protection rule/ruleset for `main` requiring the **Quality gate** status check.
  Workflow files cannot enable required checks themselves; select the check after its first run.
- Fork PRs do not receive `SONAR_TOKEN`. Their lint/tests still run, but the gate fails explicitly
  until a maintainer opens a trusted-branch PR for the reviewed change. Do not use
  `pull_request_target` to execute fork code with secrets.

> **Work in progress — upcoming version 1.0.0.** This library is experimental: protocol support is
> incomplete, some features may be broken or behave differently across wheel/firmware versions,
> and public APIs and behavior may evolve. Validate it with the specific wheel and firmware you
> intend to use; do not assume that passing unit tests guarantees safe or complete behavior.


## Modules

| Module | Artifact | Content |
|---|---|---|
| `euc-ble-protocols` | `io.github.tritbool:euc-ble-protocols` | KMP synchronous engines, parsers, models and pure policies (`commonMain`; JVM, JavaScript and iOS targets). No Android or coroutine dependency. |
| `euc-ble-session` | `io.github.tritbool:euc-ble-session` | KMP coroutine-free session policies and BLE transport contract. Depends on protocols. |
| `euc-ble-core` | `io.github.tritbool:euc-ble-core` | KMP coroutine facades and async session drivers (JVM, JavaScript, iOS). Common production sources; exposes protocols and session through `api`. |
| `euc-ble-android` | `io.github.tritbool:euc-ble-library` | Android BLE connector (`BLEManager`) and the `EucBleClient` entry point. Depends on `euc-ble-core` (`api`). |

Android applications keep depending on `euc-ble-library`; package names are unchanged.
Rebuild consumers after the module split; this preserves package names and the JVM facade's
default time/random providers, not binary compatibility with previously compiled applications.
Direct synchronous-engine constructors now require explicit `EucClock` (and KingSong `EucRandom`).
Custom protocols should declare a stable `protocolId` for fingerprint selection and an optional
`parentProtocolId` for family refinement; selection no longer inspects Java class inheritance.
Synchronous engines and polling-query values use `CommandValue` (for example
`CommandValue.Integer(2)` or `CommandValue.Bytes(payload)`); no-value commands default to
`CommandValue.None`. JVM/Android facades retain the legacy `Any` overload. Mixed-type alarm lists
are rejected, not silently filtered; byte/list values snapshot their inputs.

The portable engines can be tested without an Android SDK (Node.js and npm must be on `PATH`):

```bash
./gradlew -Peuc.includeAndroid=false :euc-ble-protocols:check
./gradlew -Peuc.includeAndroid=false :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests
```


### Updating dependency locks

The build has two independent lock formats. Commit lock changes with the dependency or
Kotlin plugin upgrade.

- Gradle dependencies use each module's `gradle.lockfile`. Run the affected build/test
  tasks with `--write-locks` after changing their versions. Only resolved configurations
  are updated, so include JVM, JavaScript, Android and iOS tasks affected by the upgrade
  (iOS tasks require macOS). Currently, `settings.gradle` temporarily enables Gradle
  lock writing for every invocation via `setWriteDependencyLocks(true)`. This does not
  update npm locks. Remove that override once all affected configurations have been
  refreshed to restore normal Gradle lock verification.
- Kotlin/JS npm dependencies use `kotlin-js-store/package-lock.json`. After a Kotlin
  plugin upgrade or npm dependency change, use JDK 21 and Node.js 22 (including its npm),
  matching CI, and run:

  ```bash
  ./gradlew -Peuc.includeAndroid=false kotlinUpgradePackageLock
  ```

  Review and commit the generated lockfile. `--write-locks` does not update this file.
  Do not run `kotlinUpgradePackageLock` automatically in CI or disable lock mismatch
  checks: that would accept unreviewed changes instead of checking reproducibility.

After updating locks, rerun the portable CI checks without either update option:

```bash
./gradlew -Peuc.includeAndroid=false \
  :euc-ble-protocols:check :euc-ble-session:check :euc-ble-core:jsNodeTest
```

See the [architecture overview](architecture.md): module dependencies and the
client / session / protocol / BLE transport boundaries, in two diagrams.


## Testing

Coverage reports for protocol/model/frame code are generated in CI and published in each
[CI run summary](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml).
Coverage is a testing aid, not a measure of device or firmware compatibility.

### Real-device capture methodology

Protocol tests combine offline unit tests with **raw BLE captures recorded from real wheels**,
replayed via JUnit 5 without an Android device or emulator. Capture coverage is partial and does
not mean every supported model, firmware, command, or edge case has been verified on a physical wheel.

Captures are stored in `fixtures/replay/ble_frames/<brand>/RAW_WHEELLOG/` as CSV files exported from
[WheelLog](https://github.com/Wheellog/Wheellog.Android), a well-established EUC logging app.
Each CSV row contains the exact byte sequence received from the wheel over BLE.

Captures help to:

- Decoders are validated against **authentic protocol behaviour**, not assumptions
- Edge cases found in the wild (fragmented frames, garbage between headers, firmware variants) are
  permanently encoded as regression tests
- Preserve observed device behavior as regression fixtures; parser support and review are still needed for new variants

### What is tested

| Test class | What it validates |
|---|---|
| `WheelLogGotwayTest` | Full decode pipeline on real Gotway/Begode and ExtremeBull captures |
| `WheelLogKingsongTest` | Full decode pipeline on real KingSong captures |
| `WheelLogLeaperkimTest` | Full decode pipeline on real Leaperkim/Veteran captures |
| `WheelLogInMotionTest` | Full decode pipeline on real InMotion captures |
| `WheelLogNinebotTest` | Full decode pipeline on real Ninebot captures |
| `WheelLogNosfetTest` | Full decode pipeline on real Nosfet captures |
| `GotwayProtocolTest`, `KingsongProtocolTest`, … | Protocol-specific parsing, commands, and edge cases |
| `ExtremeBullProtocolTest` | ExtremeBull advertisement matching and inherited Gotway BLE requirements |
| `ProtocolParityContractTest` | Selected cross-protocol command and polling contracts |
| `BleFrequencyAnalysisTest` | Emission rate and timing characteristics per brand |

See [`euc-ble-core/README_TESTS.md`](euc-ble-core/README_TESTS.md) for the full test inventory.

