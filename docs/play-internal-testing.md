# Prepare the Node runtime test for Google Play

This builds the actual `ai.building` release with the existing upload key and an
on-demand `developer_runtime_node` feature. It does not upload, publish, enroll
testers, or install anything. The normal sideload build still bundles Node.

## Build and verify

Keep signing credentials in the existing protected `.env.production`, outside
version control. Confirm the package, **unused version code above every previous
upload**, and upload certificate against Play Console before uploading. The local
candidate uses version **1.0.1 (110)**; its availability in Console is unconfirmed.

```sh
python3 tools/runtime-pack/prepare_play.py \
  --version-code 110 --version-name 1.0.1 \
  --bundletool tools/runtime-pack/build/play-test/bundletool.jar
```

Use an official [bundletool-all JAR](https://github.com/google/bundletool/releases).
Change the code/name arguments if Console already contains this version. The
helper does not change the shared release defaults or `.env.production`.
The numeric version name also works with the repository's desktop packaging
configuration, which currently reads the Android version environment variable.

Outputs live in `dist/play-internal/110/`:

- `building-110-play-release.aab`: signed upload candidate; this is the Console upload.
- `receipt.json`, `SHA256SUMS`: exact artifact identity, upload-key fingerprint,
  local verification and remaining checks.
- `upload-certificate.pem`: public upload certificate, never the private key.
- `delivery-audit.json`, manifests: Node ownership, hashes, dependency closure,
  on-demand declaration, release identity and absence of local-testing metadata.
- `reference-device.apks`, size CSVs: normal ARM64/API-36 splits and estimated
  compressed downloads. No `--local-testing` is used; these are local inspection
  outputs, not evidence of Play installation.
- `native-alignment.json`: all native ELF LOAD segment checks and RELRO boundary
  diagnostics. This does not certify execution on 16 KB devices.
- `release-notes.txt`: text ready for the internal release notes field.
- `build.log`, `prepare.log` (when redirected): build/verification evidence.

An existing artifact can be rechecked with `--skip-build`; identity, configuration,
signatures and module checks still run, so an old or differently configured AAB
cannot silently be reused. Only a successful run writes `receipt.json`.

### Local candidate verified 2026-10-15

Version 1.0.1 (110) passed the signed release APK/AAB build, release lint,
`bundletool validate`, signature/configuration verification, runtime partition
audit, and all 14 release/preparation unit tests. The release build exposed a
Guava variant conflict in the Node module's lint checks classpath; its existing
dependency selection rule now covers that classpath too.

For the ARM64/API-36/en reference device, `bundletool` estimates **360,081,915
bytes for base** and **22,928,092 additional bytes for Node**. Every packaged
native library (272) passes the ELF LOAD alignment check. RELRO diagnostics and
16 KB device qualification remain separate, as recorded below.

Candidate AAB SHA-256:
`5c40fc1854c06031611d3b37dbe352c164a8aeac5bc3175df1c2f7c4a358663a`.
This is local preparation evidence. No Console upload or real Play download has
occurred, and code 110 and the upload certificate still need Console confirmation.

## Console preparation

1. Open the existing `ai.building` app in Play Console, or create its app entry if
   this is the first upload. Confirm its package before the first upload, which
   fixes that identity. Complete any account or signing setup shown by Console.
2. Under **Test and release → Testing → Internal testing**, create the release and
   select the AAB above. Verify that Console recognizes the intended code, target
   SDK and on-demand Node module, and resolve any reported upload blockers.
3. Compare the **upload** certificate in App integrity with `receipt.json`. The
   Play **app signing** certificate may differ; that is the key on installed APKs.
   Do not replace an existing app's key or change its identity to bypass a mismatch.
4. Add the intended Google accounts under **Testers**, using the accounts signed
   into Play Store on the test devices. Save the tester list and copy the opt-in link.
5. Review the concrete release and release notes before starting the internal
   rollout. Internal testing supports up to 100 testers and does not require a
   complete public store listing. First availability can take time.

Sources: [internal testing](https://support.google.com/googleplay/android-developer/answer/9845334),
[app signing](https://developer.android.com/studio/publish/app-signing),
[current size limits](https://support.google.com/googleplay/android-developer/answer/9859372).
Google currently documents 500 MB per base/feature module; `bundletool` estimates
are useful locally, while Console determines the actual delivered size.

## Verify real store delivery

Use a Play-enabled ARM64 device with the tester account. Opt in and **install from
Google Play**. A sideloaded build or `bundletool install-apks` cannot establish this
proof. If a previous sideload conflicts with Play's signing key, preserve projects
before choosing a clean installation; the preparation helper never uninstalls it.

1. Record the installed version and installer (`com.android.vending`).
2. On a fresh base-only install, open **Settings → Runtimes**. Node should be
   available to download while Python and shell already work. Record the splits.
3. Download Node. Capture real progress, completion and installed verification.
4. Run Node, npm and pnpm from a project/terminal. Exercise a child Node process.
5. Force-stop/reopen the app and repeat with networking disabled. Restore networking.
6. Capture the final installed splits, device/API/page size, outputs and logs.
   Test cancellation/retry on a fresh installation when practical. Record actual
   upgrade behavior separately; reinstalling the same version is not an upgrade test.

Useful read-only ADB commands after the Play install:

```sh
adb -s DEVICE shell pm list packages -i ai.building
adb -s DEVICE shell pm path ai.building
adb -s DEVICE shell dumpsys package ai.building
adb -s DEVICE shell getconf PAGE_SIZE
```

On 2026-10-15, both the local test emulator and the connected phone reported 4 KB
pages. Qualification on 16 KB devices remains open. Check the RELRO diagnostics
alongside [Android's native compatibility guidance](https://developer.android.com/guide/practices/page-sizes)
before claiming support. A green local build or accepted upload does not replace
the store-install and runtime checks above.
