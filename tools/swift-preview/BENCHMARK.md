# Android JNI bridge benchmark

Measured 2026-10-17 on `sdk_gphone64_arm64`, Android API 36, ARM64 emulator.
Swift 6.3.2, release optimization; Android host is a debug APK. These are
emulator measurements, not physical-phone or display-frame results.

| Rows | State owner | JSON p50 / p95 (ms) | Typed JNI p50 / p95 (ms) | Median speedup | Nodes sent per update |
| ---: | --- | ---: | ---: | ---: | ---: |
| 10 | leaf | 1.146 / 1.438 | 0.125 / 0.166 | 9.2× | 21 → 2 |
| 10 | root | 0.879 / 0.991 | 0.635 / 0.691 | 1.4× | 21 → 21 |
| 100 | leaf | 6.676 / 7.339 | 0.144 / 0.189 | 46.2× | 201 → 2 |
| 100 | root | 5.672 / 5.957 | 5.387 / 5.710 | 1.1× | 201 → 201 |
| 1,000 | leaf | 60.558 / 66.974 | 0.390 / 1.216 | 155.1× | 2,001 → 2 |
| 1,000 | root | 55.382 / 59.501 | 52.563 / 53.559 | 1.1× | 2,001 → 2,001 |

For 1,000 rows with state owned by the changing row, the bridge sent only its
Button and Text nodes. All 50 measured updates were patches and preserved the
untouched last row's object identity. The same assertions passed at 10 and 100
rows. With state owned by the root, every JNI patch contained the entire tree;
the root case gains little and still takes roughly 53 ms at 1,000 rows.

Build time is separate: the successful integration build reported 261.77 seconds
for Swift while setting up the generators, and switching apps still rebuilt
host generator dependencies. This harness does not establish fast warm builds.

## Method and proof boundary

`benchmark.py` runs the same `benchmark/Workload.swift` through the previous
JSON subprocess protocol and the existing AndroidSwiftUI typed JNI bridge.
Both programs are compiled together with Swift release settings. Each case
changes the middle row, warms up 15 times, then measures 50 events. Every event
asserts the new value in the resulting tree. Percentiles use nearest rank.

The clock starts immediately before dispatch on Android's main thread and
stops after TreeStore assigns the updated root. This includes Swift state and
evaluation, transport, JSON decode or typed materialization, queueing and tree
splicing. Composition, layout, drawing, touch dispatch and display presentation
are excluded. No FPS or arbitrary-app latency claim follows from this test.
The old recursive Cupertino styling conversion is omitted from the baseline,
so the comparison is conservative. Initial-tree timings in the raw receipt
are diagnostic only because process startup and native-library loading differ.

The benchmark separately passed disposal with an already-queued render and
reopening with fresh state. The sample's visible counter, toggle, text field
and reset passed on the JNI APK; closing the pane stopped its native runtime.
The screenshot is in `.build/evidence/sample-android.png`. The unchanged pinned
GitHub counter also passed increment/decrement and disposal on the default
JNI-only APK. Its installed hash matched the build receipt. That APK excludes
the JSON executable, benchmark entry point and TreeStore instrumentation;
see `.build/evidence/github-android.png`.

Reproduce with `python3 tools/swift-preview/benchmark.py`. Machine-readable aggregate
results, device fingerprint, dependency pins and build receipt are saved in
`.build/evidence/jni-benchmark.json`; the exact APKs are retained under
`.build/artifacts/jni-benchmark{,-test}.apk`. These generated artifacts are not
versioned.

Benchmark APK SHA-256: `f35d2ba8f1092b7b179529728230139be33139827f695069396addd04607a22f`.

Bridge revision: `9bbcc0bc008d43fa3e204fe5856cd41cc03f4d7c`.
