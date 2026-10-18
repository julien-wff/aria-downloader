import base64
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_versions_increase_across_component_boundaries(self):
        tags = ["v0.0.1", "v0.0.999", "v0.1.0", "v0.999.999", "v1.0.0", "v2099.999.999"]
        codes = [release.version(tag)[1] for tag in tags]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertEqual(release.version("v1.2.3"), ("1.2.3", 1_002_003))
        self.assertLessEqual(codes[-1], 2_100_000_000)

    def test_rejects_invalid_or_ambiguous_tags(self):
        for tag in ("v0.0.0", "1.0.0", "v01.0.0", "v1.00.0", "v1.0.00", "v2100.0.0",
                    "v1.1000.0", "v1.0.1000", "v1.0.0-rc1", "v1.0.0\n", "v1.0.0;echo secret"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.version(tag)

    def test_signing_requires_every_secret_without_disclosing_values(self):
        env = dict(ANDROID_KEYSTORE_BASE64="private-sentinel")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError) as raised:
                release.prepare_signing(Path(directory), env)
            self.assertNotIn("private-sentinel", str(raised.exception))
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_signing_file_is_private_and_never_overwrites_existing_key(self):
        env = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(b"test-key").decode(),
                   ANDROID_KEYSTORE_PASSWORD="password", ANDROID_KEY_ALIAS="alias",
                   ANDROID_KEY_PASSWORD="password")
        with tempfile.TemporaryDirectory() as directory:
            path = release.prepare_signing(Path(directory), env)
            self.assertEqual(path.read_bytes(), b"test-key")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                release.prepare_signing(Path(directory), env)
            path.unlink()
            env["ANDROID_KEYSTORE_BASE64"] = "bad-secret-value"
            with self.assertRaises(ValueError) as raised:
                release.prepare_signing(Path(directory), env)
            self.assertNotIn("bad-secret-value", str(raised.exception))
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
