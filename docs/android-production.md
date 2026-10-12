# Android production builds

The local entry point is `python3 tools/android-release.py`. It validates an explicit release
environment; `--build` builds and verifies both the signed APK and the signed AAB without publishing.
The ordinary debug build continues to work without production credentials.

## Signing identity

This app uses `ai.building` and is intended for Google Play distribution. Sign its AAB with the
dedicated upload key, then enroll it in Play App Signing with a Google-generated app-signing key.
Creating the local upload key does not register the package or enroll it in Play App Signing.

The local upload keystore is `~/.config/building/signing/google-play-upload.p12`, alias `upload`.
Its public certificate is `google-play-upload-certificate.pem` in the same directory. The private
`.env.production` contains the signing configuration. The key is RSA 3072 with 10,000 days of
certificate validity. Back up the keystore and password in your encrypted password manager before
the first upload; no off-machine backup has been created by the local setup.

For a new app, choose your own `ANDROID_APPLICATION_ID`. The upstream ID `com.tyron.code` already
belongs to a published app. Updating it requires that app's existing signing setup; a newly generated
key cannot replace it. The development install on the connected Pixel uses `com.tyron.code.dev`.

For Google Play, use a separate upload key and Play App Signing. For APK downloads, keep the same
app-signing key for every update. If Play and direct-download builds must update each other, supply
your own app-signing key to Play when enrolling, and keep a separate upload key. See the
[Android signing guide](https://developer.android.com/studio/publish/app-signing).

Keep the keystore outside the checkout, with an encrypted backup under your control. Use a long,
random password; do not reuse the Android debug key. A new RSA key of at least 2048 bits with at least
25 years of certificate validity meets the documented Android guidance. Do not generate a replacement
for an already published app's key.

## Local configuration

```sh
cp .env.production.example .env.production
chmod 600 .env.production
# Fill in the application identity, signing credentials, and your own client service configuration.
export ANDROID_HOME="$HOME/Library/Android/sdk"
python3 tools/android-release.py
python3 tools/android-release.py --build
```

The environment file is ignored by Git and is parsed as data, not executed as a shell script. Exported
environment variables take precedence. The helper prints the public certificate fingerprint, never
passwords. `keystore.properties` is another signing mechanism supported by Gradle; the helper refuses
to combine it with environment-based signing because that file takes precedence in the build script.

The four signing variables are `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
and `RELEASE_KEY_PASSWORD`. Use the same store/key password for a PKCS12 keystore. Preflight validates
the store password and private-key alias; the signed build also exercises the key password.

Set `ANDROID_VERSION_CODE` above the previous production release. Set `ANDROID_VERSION_NAME` to the
display version. The helper verifies the resulting APK's package, version, certificate, and
non-debuggable status, then verifies the AAB signature.

## Client services and secrets

The existing release defaults point at the upstream project's accounts. The release helper requires
explicit values instead:

- `SUPABASE_URL` and `SUPABASE_KEY`: both empty disables the remote backend. Otherwise use your own
  URL and a **public publishable/anon key**. A server secret or service-role key must never enter an APK.
- `JUDGE_URL`: empty disables challenge submission; otherwise use your HTTPS endpoint.
- `ANDROID_ADS_ENABLED=false`: disables release advertising, its consent flow, startup providers,
  and advertising permissions. The local production configuration uses this setting. Debug keeps test ads.
- `ADMOB_APP_ID`, `ADMOB_NATIVE_UNIT_ID`, `ADMOB_INTERSTITIAL_UNIT_ID`: required project-owned IDs only
  when release advertising is explicitly enabled.
- `ADMOB_NATIVE_UNIT_<PLACEMENT>`: optional overrides. The helper supplies empty values for unset
  placements so they use your shared unit rather than the upstream account.
- `app/ide-android/google-services.json`: optional Firebase client config for this package ID. Without
  it, push is disabled. Register the appropriate signing certificate with Firebase when configuring it.

Keystore passwords are build secrets. Backend admin credentials, Firebase service-account keys, and
other server secrets do not belong in this file or in client BuildConfig fields.

## Build environment and CI

The repository pins Gradle 9.5.1 and a JetBrains JDK 21 daemon. Install a bootstrap JDK on PATH, Python
3.11+, Git, CMake, Ninja, `patch`, `unzip`, Android platform 36.1, build-tools 36.0.0, and platform-tools.
The Gradle wrapper provisions its pinned daemon JDK when needed. Android `preBuild` also builds the
arm64 SDK helpers from locked sources; its first run downloads the source and NDK archives. See
[the SDK helper build](../tools/android-sdk/README.md) for its cache and provenance details.

For CI, inject the same variable names using the CI secret store, restore the keystore to a temporary
file, set `RELEASE_STORE_FILE` to that file, and run the same helper with `--build`. Keep passwords and
the private keystore out of logs, caches, and uploaded artifacts. Only the APK/AAB and public signing
certificate belong in release artifacts. No production key, CI secrets, or store enrollment is created
by the preflight command.
