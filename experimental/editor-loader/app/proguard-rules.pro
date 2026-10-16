# Feature entrypoints are loaded only after Play installs their signed DEX.
-keep interface ai.building.loader.RuntimeFeature { *; }
-keep class ai.building.loader.node.NodeRuntime { public <init>(); public *; }
# Preserve the library's local-testing controls for failure injection on this release.
-keep class com.google.android.play.core.splitinstall.testing.FakeSplitInstallManager { public *; }
