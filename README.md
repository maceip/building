<div align="center">

# CodeAssist

**A lightweight, on-device IDE that edits *and builds* Android & Java projects — no laptop, no Gradle daemon.**

[![CI](https://img.shields.io/github/actions/workflow/status/tyron12233/CodeAssist/ci.yml?branch=main&label=CI&logo=githubactions&logoColor=white)](https://github.com/tyron12233/CodeAssist/actions)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/tyron12233/CodeAssist/main/.github/badges/tests.json)](https://github.com/tyron12233/CodeAssist/actions)
[![Benchmarks](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/tyron12233/CodeAssist/main/.github/badges/benchmarks.json)](#benchmarks)
[![Release](https://img.shields.io/github/v/release/tyron12233/CodeAssist?display_name=tag&logo=github&sort=semver)](https://github.com/tyron12233/CodeAssist/releases)
[![License](https://img.shields.io/github/license/tyron12233/CodeAssist)](LICENSE)
[![Stars](https://img.shields.io/github/stars/tyron12233/CodeAssist?style=flat&logo=github)](https://github.com/tyron12233/CodeAssist/stargazers)
[![Forks](https://img.shields.io/github/forks/tyron12233/CodeAssist?style=flat&logo=github)](https://github.com/tyron12233/CodeAssist/network/members)
[![Discord](https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?logo=discord&logoColor=white)](https://discord.gg/y2gycT6pDW)

<img src="docs/assets/banner.png" alt="CodeAssist: build Android apps on Android" width="720" />

</div>

## What is CodeAssist?

CodeAssist is an extensible IDE framework that runs **entirely on device** (Android/ART) and edits and
builds Android/Java projects **without hosting Gradle**. A full Gradle runtime is too heavy for a phone,
so CodeAssist models projects itself, mimics Gradle's incremental task engine without the Gradle daemon,
and drives the Android toolchain (aapt2, D8/R8, apksigner) directly.

It is designed to be **lightweight**:

- **No Gradle daemon.** Projects are a declarative model compiled into an incremental task DAG with
  fingerprint up-to-date checks and a persistent cache — Gradle's good ideas, none of its weight.
- **Flat-memory indexing.** Library/SDK symbol indices are disk-backed immutable segments queried in
  place through a bounded block cache, so heap stays flat regardless of index size.
- **Fast, incremental everything.** Editing one file re-runs only the affected build tasks; completion
  and analysis reuse a cached compilation environment instead of rebuilding it per keystroke.
- **On-device toolchain.** Pure-Java tools (Eclipse JDT/ecj, D8/R8, apksigner) run in process; native
  aapt2 is invoked as a subprocess.

It ships with a Compose Multiplatform UI and both desktop and Android launchers.

### Highlights

- ☕ **Java code intelligence** — error-tolerant parsing, ranked completion, diagnostics, and quick-fixes
  powered by Eclipse JDT.
- 🟪 **Kotlin completion (beta)** — full Kotlin code completion on device, tuned to the same ranked
  experience as Java. [How it works →](docs/kotlin-completion.md)
- 🧩 **Block editing** — project any Java file into a Scratch-style block tree and edit it there; changes
  write back to the source byte-for-byte. [How it works →](docs/block-editing.md)
- 📐 **Android XML assist** — tag, attribute, and resource completion for layouts/manifests with live
  validation.
- 📦 **Real APK builds on device** — resolve, compile, dex, package, sign, and install — no laptop.
- 🌱 **Git, on device** — stage, commit, branch, merge, stash, and push without a laptop; sign in to GitHub
  to clone private repositories and open pull requests. [How it works →](docs/version-control.md)
- 🚀 **Smart GitHub Trending & 2-Hop Social Graph Explorer** — bundles `Daily`, `Weekly`, and `Monthly`
  trending lists at build time across all 9 supported phone languages (`All`, `Kotlin`, `Swift`, `Python`,
  `TypeScript`, `JavaScript`, `Java`, `Rust`, `C++`) with 82 pre-rendered app screenshots, live GitHub
  Trending refresh, 3-tier Just-In-Time (JIT) app preview generation, 2-hop author social graph traversal,
  in-app `README.md` Markdown rendering, and both in-app and Android home-screen Trending Widgets.
- 🔌 **Extensible everywhere** — module types, build systems, language backends, analyzers, quick-fixes,
  and more all plug in through extension points.

## Smart GitHub Trending & 2-Hop Discovery Service

CodeAssist includes a built-in GitHub Trending and developer social graph engine (`GitHubDiscoveryClient`,
`SmartAppImageGenerator`, `DeveloperGraphEngine`, and `BundledTrendingCatalog`):

1. **Build-Time Bundled + Live Trending (`Daily` / `Weekly` / `Monthly`)** — ships 27 `(language, period)`
   trending lists (`discovery/bundled-trending-catalog.json`) and 82 PNG app screenshots (`discovery/shots/`)
   for instant zero-latency startup, with live HTML/REST refresh against `https://github.com/trending/{lang}?since={period}`.
2. **3-Tier Just-In-Time (JIT) App Screenshot Generation (`SmartAppImageGenerator`)**:
   - **Tier 1 (`LOCAL_BUILD_PREVIEW`, Preferred)**: Builds or interprets the app locally on demand via
     `SwiftUiPreviewInterpreter` (`ViewNode` GUI tree for SwiftUI/Cupertino), NodeGUI widget tree, or
     Jetpack Compose `@Preview` / Android XML layout renderer.
   - **Tier 2 (`REPO_OR_WEBPAGE_SCREENSHOT`, Fallback 1)**: Searches the GitHub repository (Fastlane
     `phoneScreenshots`, `README.md` images, `/screenshots`, `/assets`) **and** crawls the repository's
     linked external webpage (`homepageUrl`) for real app screenshots.
   - **Tier 3 (`DYNAMIC_SOURCE_SYNTHESIS`, Last-Ditch Effort)**: Inspects actual repository source files
     to extract declared classes/functions, routes, CLI flags, and AST tokens, synthesizing a live IDE/terminal preview PNG.
3. **Deep 2-Hop Author Social Graph & Starred Network (`DeveloperGraphEngine`)** — inspects the selected
   repository's `@author` (or top human contributors for organization-owned repos), their other repositories
   (`SAME_AUTHOR`), their starred repositories (`AUTHOR_STARRED`), who `@author` follows (`1st-hop @peer1`
   repos `FOLLOWED_PEER` & stars `PEER_STARRED`), and who `@peer1` follows (`2nd-hop @peer2` repos
   `SECOND_DEGREE_PEER` & stars `SECOND_DEGREE_STARRED`), ranking all candidates by architectural relevance.
4. **Trending Widgets & Integrated `README.md` Viewer** — includes the in-app `TrendingReposWidget` on the
   Projects Home screen, the Android home-screen `TrendingReposAppWidgetProvider`, and native Markdown
   rendering of both the app's own `README.md` and any trending repository's `README.md`.

## Screenshots

| | | |
|---|---|---|
| <img src="docs/assets/screen-01-hero.png" width="260" alt="A real IDE in your pocket" /> | <img src="docs/assets/screen-02-compose-preview.png" width="260" alt="Compose @Preview beside the code" /> | <img src="docs/assets/screen-03-kotlin-completion.png" width="260" alt="Kotlin completion" /> |
| **A real IDE, on your phone.** Write, navigate, and build end to end. | **Compose @Preview, live.** See your UI next to the code as you type. | **Completion that keeps up.** Ranked Kotlin and Java suggestions, even on half-written code. |
| <img src="docs/assets/screen-04-build-apk.png" width="260" alt="Build APKs on device" /> | <img src="docs/assets/screen-05-ai-agent.png" width="260" alt="AI coding agent" /> | |
| **Build real APKs, on device.** Resolve, compile, dex, package, sign, install. | **An AI agent, built in.** Ask for a change; it edits, builds, and shows the diff. | |

<div align="center">
<img src="docs/assets/screen-tablet-preview.png" width="800" alt="CodeAssist on a tablet: file tree, code, and a live Compose preview" />
<br />
<sub>On tablets and foldables the file tree, editor, and preview sit side by side.</sub>
</div>

## Block editor

Any Java file can be projected into interlocking, typed blocks — and edited there. The block tree is a
**live projection of the same DOM the code editor uses**, so the two views never drift; a block edit
compiles to the smallest possible source edit, leaving every untouched line and comment intact.

<div align="center">
<img src="docs/assets/block-editor.png" width="280" alt="Structured block projection" />
&nbsp;&nbsp;&nbsp;
<img src="docs/assets/block-editor-puzzle.png" width="280" alt="Scratch-style puzzle blocks" />
</div>

Typed value sockets (Scratch-style shapes for boolean/number/string/type), inline completion inside
sockets, palette search, and drag-and-drop all map back to the same surgical projection pipeline.
[Read how the projection and edit round-trip work →](docs/block-editing.md)

## Benchmarks

CodeAssist tracks completion quality, editor latency, indexing, and build performance against committed
baselines (a `regressionTest` suite that fails CI on a quality regression). The numbers below regenerate
on each release.

<!-- AUTOGEN:benchmarks:START -->
| Area | Metric | Result |
|---|---|---|
| Java completion **quality** | recall / top-1 / MRR | **100%** / 75% / 0.88 |
| Java completion **latency** (per keystroke) | member access / type ref | **4.3 ms** / 2.8 ms |
| Symbol **index** quality | recall / top-1 / MRR | **100%** / 90% / 0.95 |
| Symbol **index** query | prefix / fuzzy | ~4.4 µs / ~56 ns |
<!-- AUTOGEN:benchmarks:END -->

**What the metrics mean**

- **recall** — fraction of cases where the expected completion appears in the list at all.
- **top-1 / top-5** — fraction where the expected item is ranked first / within the first five.
- **MRR** (mean reciprocal rank) — average of `1 / rank` of the expected item; `1.0` means it is always
  first.
- **latency** — wall-clock time to compute completion at a keystroke (recorded on the maintainer's
  machine, so treat as indicative; quality metrics are deterministic across machines).
- **incremental tasks** — how many build tasks re-run after a single-file edit; fewer means more precise
  incrementality.

## Tests

<!-- AUTOGEN:tests:START -->
**2090** tests passing across **373** suites · 0 failing · 2 skipped (framework / `CI_CORE_ONLY`).
<!-- AUTOGEN:tests:END -->

The badges and the numbers in this section are regenerated automatically on each release (see
[`.github/workflows/update-readme.yml`](.github/workflows/update-readme.yml)).

## Documentation

| Doc | What's in it |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Project model, the two graphs, build abstraction, concurrency model. |
| [docs/modules.md](docs/modules.md) | The module map and responsibilities. |
| [docs/extension-points.md](docs/extension-points.md) | Extension points and the language-backend SPI. |
| [docs/language-support.md](docs/language-support.md) | DOM, completion, the language backends, indexing, analysis, block editing. |
| [docs/kotlin-completion.md](docs/kotlin-completion.md) | How on-device Kotlin completion works. |
| [docs/block-editing.md](docs/block-editing.md) | How the projectional (block) editor works. |
| [docs/build-system.md](docs/build-system.md) | The task engine, the native pipelines, Gradle compat. |
| [docs/github-action.md](docs/github-action.md) | Building CodeAssist projects in CI: the headless `codeassist` launcher and the GitHub Action. |
| [docs/version-control.md](docs/version-control.md) | Git on device: the model, running JGit on ART, accounts, and the UI. |

## Module layout

Sixty modules, grouped on disk by the layer they belong to:

```
platform/      no domain knowledge; depended on by all
lang/          reading source: the language SPI, indexes, analysis, per-language backends
build-system/  the incremental task engine and the JVM build pipelines over it
run/           executing project code on device: source interpreter, bytecode VM, AWT/Swing
android/       Android as a target: facets, variants, SDK metadata, layout preview, ART shims
services/      cross-cutting api/impl pairs: deps, version control, store, analytics, agent
plugins/       the SPI a third-party plugin compiles against, and the host that resolves it
app/           the IDE itself: the Compose UI and the desktop/Android/headless-CLI shells
tools/         test-only harnesses
```

Gradle paths stay flat, so a module's directory never has to be spelled out to build it: the Kotlin
editor backend is `:lang-kotlin` even though it lives at `lang/lang-kotlin`. `settings.gradle.kts`
maps the two, and is the only place that mapping exists.

Dependencies point downward only (acyclic). Platform modules carry no domain knowledge; domain behavior
is contributed through extension points.

```
platform-core            no domain knowledge; depended on by all
  └─ vfs-api
       └─ project-model-api
            ├─ build-api
            └─ language-api
deps-api / index-api / analysis-api / block-api  → the layers above
ide-ui (Compose UI) ← ide-core (engine→UI bridge) ← ide-desktop, ide-android
```

The full table — every module, its packages, and its responsibility — is in
[docs/modules.md](docs/modules.md).

## Building

The build uses the Gradle wrapper and a JDK 17+ (developed against the JetBrains Runtime).

```sh
# Core framework only (pure JVM; no Android SDK or Compose shells needed)
CI_CORE_ONLY=true ./gradlew check

# Full build, including the Compose UI and Android launcher (requires the Android SDK)
./gradlew build

# Run the desktop IDE
./gradlew :ide-desktop:run

# Assemble the Android launcher
./gradlew :ide-android:assembleDebug
```

The regression suite (completion quality, latency, allocation, and build-at-scale) is opt-in and runs
via the `regressionTest` tasks against committed JSON baselines.

## Conventions

- IDs are `@JvmInline value class` wrappers — no stringly-typed APIs.
- Open-ended classifications (`NodeKind`, `BuildSystemId`, `LanguageId`) are string-backed value classes
  so plugins can extend them; closed sets (`DependencyScope`, `SymbolKind`) are enums.
- Long-running entry points are `suspend` and run under the read/write-lock discipline; model and DOM
  access happens inside read/write actions.
- Mutation is transactional: stage on a `*Transaction`/`Modifiable*`, then `commit()`.
- Editor features target the neutral `DomNode`/`Symbol`/`Scope`, never a backend's native types.

## License

CodeAssist is free software licensed under the **GNU General Public License v3.0 or later**
(`GPL-3.0-or-later`). See [LICENSE](LICENSE).

The plugin SPI is the one exception. The modules a plugin author compiles against (`plugin-api`,
`plugin-ui-api`, `platform-core`, and the feature SPIs `vfs-api`, `project-model-api`, `language-api`,
`index-api`, `analysis-api`, `build-api`), published under `io.github.tyron12233`, add the Classpath
exception (`GPL-3.0-or-later WITH Classpath-exception-2.0`, see [LICENSE-EXCEPTION](LICENSE-EXCEPTION)) so
that a plugin compiled against them may be released under any license, proprietary included.

## Install

- **Google Play** — testing track (signed via Play App Signing).
- **F-Droid / IzzyOnDroid** — built from this repo, signed with the project's own key. See
  [docs/fdroid-izzyondroid.md](docs/fdroid-izzyondroid.md). (The IzzyOnDroid and Play builds have
  different signatures and cannot update across stores.)

## Community

Join the **[CodeAssist Discord](https://discord.gg/y2gycT6pDW)** to chat, get help, report bugs, and
share what you build.
