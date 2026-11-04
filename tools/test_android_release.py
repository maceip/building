import importlib.util
import io
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("android_release", Path(__file__).with_name("android-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class AndroidReleaseTest(unittest.TestCase):
    def test_environment_overrides_file_which_overrides_shared_defaults_including_empty_values(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "release.env"
            path.write_text("ANDROID_VERSION_CODE=20\nSUPABASE_URL=https://file.example\nSUPABASE_KEY=public\n")
            path.chmod(0o600)
            with patch.dict(os.environ, {"ANDROID_VERSION_CODE": "21", "SUPABASE_URL": "", "SUPABASE_KEY": ""}, clear=True):
                env = release.environment(path)
            self.assertEqual("21", env["ANDROID_VERSION_CODE"])
            self.assertEqual("", env["SUPABASE_URL"])
            self.assertEqual("ai.building", env["ANDROID_APPLICATION_ID"])
            self.assertEqual("false", env["ANDROID_ADS_ENABLED"])

    def test_unconfigured_build_uses_the_same_public_defaults(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(release.public_defaults(), release.environment(Path("/nonexistent/production.env")))

    def test_helper_always_selects_authoritative_environment_without_secret_command_arguments(self):
        for build in (False, True):
            command = release.gradle_command(build)
            self.assertIn("-PANDROID_RELEASE_CONFIG_SOURCE=environment", command)
            self.assertIn(":ide-android:generateReleaseConfiguration", command)
            self.assertEqual(build, ":ide-android:bundleRelease" in command)
            self.assertFalse(any("PASSWORD" in arg or "SUPABASE_KEY" in arg for arg in command))

    def test_play_build_selects_delivery_without_changing_default_sideload_mode(self):
        self.assertIn("-PIDE_RUNTIME_DELIVERY=play", release.gradle_command(True, "play"))
        self.assertIn("-PIDE_RUNTIME_DELIVERY=bundled", release.gradle_command(True))
        with self.assertRaises(ValueError):
            release.gradle_command(True, "unknown")

    def test_preflight_normalizes_version_and_clears_disabled_ad_overrides(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            store = root / "test.p12"
            store.touch()
            for relative in ("platforms/android-36.1/android.jar", "build-tools/36.0.0/apksigner", "platform-tools/adb"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            env = release.public_defaults() | {"ANDROID_VERSION_CODE": "001", "RELEASE_STORE_FILE": str(store),
                "RELEASE_STORE_PASSWORD": "test-secret", "RELEASE_KEY_ALIAS": "upload", "RELEASE_KEY_PASSWORD": "test-secret",
                "ANDROID_HOME": str(root), "ADMOB_APP_ID": "old-id", "ADMOB_NATIVE_UNIT_STORE": "old-placement"}
            output = io.StringIO()
            with patch.object(release.shutil, "which", return_value="keytool"), \
                    patch.object(release, "inspect", return_value="PrivateKeyEntry\nSHA256: " + "AB:" * 31 + "AB"), \
                    redirect_stdout(output):
                release.preflight(env)
            self.assertEqual("1", env["ANDROID_VERSION_CODE"])
            self.assertEqual("", env["ADMOB_APP_ID"])
            self.assertEqual("", env["ADMOB_NATIVE_UNIT_STORE"])
            self.assertNotIn("test-secret", output.getvalue())

    def artifact(self, root, bundle, record, duplicate=False):
        path = root / ("release.aab" if bundle else "release.apk")
        entry = ("base/" if bundle else "") + "assets/building-release-config.json"
        with zipfile.ZipFile(path, "w") as archive, warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr(entry, json.dumps(record))
            if duplicate:
                archive.writestr(entry, json.dumps(record))
        return path

    def test_verifies_public_configuration_in_both_apk_and_aab(self):
        env = release.public_defaults() | {"RELEASE_STORE_PASSWORD": "private-never-packaged"}
        with tempfile.TemporaryDirectory() as temp:
            for bundle in (False, True):
                artifact = self.artifact(Path(temp), bundle, release.public_defaults())
                release.verify_artifact_configuration(artifact, env)

    def test_rejects_wrong_backend_identity_and_ad_configuration_without_printing_values(self):
        env = release.public_defaults()
        with tempfile.TemporaryDirectory() as temp:
            for key in ("SUPABASE_URL", "SUPABASE_KEY", "JUDGE_URL", "ANDROID_APPLICATION_ID",
                        "ANDROID_VERSION_CODE", "ANDROID_VERSION_NAME", "ANDROID_ADS_ENABLED", "ADMOB_NATIVE_UNIT_STORE"):
                for bundle in (False, True):
                    artifact = self.artifact(Path(temp), bundle, env | {key: "wrong-value-must-not-be-logged"})
                    with self.assertRaises(ValueError) as error:
                        release.verify_artifact_configuration(artifact, env)
                    self.assertIn(key, str(error.exception))
                    self.assertNotIn("wrong-value", str(error.exception))

    def test_rejects_missing_duplicate_or_private_artifact_configuration(self):
        env = release.public_defaults()
        with tempfile.TemporaryDirectory() as temp:
            for record in (env | {"RELEASE_STORE_PASSWORD": "private"}, {k: v for k, v in env.items() if k != "JUDGE_URL"}):
                with self.assertRaises(ValueError):
                    release.verify_artifact_configuration(self.artifact(Path(temp), False, record), env)
            with self.assertRaisesRegex(ValueError, "exactly one"):
                release.verify_artifact_configuration(self.artifact(Path(temp), True, env, duplicate=True), env)


if __name__ == "__main__":
    unittest.main()
