import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("sync_upstream.py")
SPEC = importlib.util.spec_from_file_location("sync_upstream", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
sync_upstream = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sync_upstream
SPEC.loader.exec_module(sync_upstream)


class SyncUpstreamTests(unittest.TestCase):
    def test_extracts_official_base_from_dual_version(self):
        self.assertEqual(
            sync_upstream.upstream_base("1.37.0-dualvot.3"),
            "1.37.0",
        )
        self.assertEqual(
            sync_upstream.upstream_base("1.38.0-dev.2-dualvot.1"),
            "1.38.0-dev.2",
        )

    def test_accepts_stable_and_prerelease_versions(self):
        for version in ("1.38.0", "1.38.0-dev.2", "1.38.0-dev.2-dualvot.1"):
            sync_upstream.validate_version(version)

    def test_rejects_version_that_could_become_a_command_argument(self):
        with self.assertRaises(sync_upstream.SyncError):
            sync_upstream.validate_version("1.38.0;echo-danger")

    def test_extracts_only_dual_changelog_sections(self):
        changelog = """## 1.38.0-dualvot.1 (2026-07-27)

Dual update.

## 1.38.0 (2026-07-27)

Upstream update.

## 1.37.0-dualvot.3 (2026-07-26)

Older Dual update.
"""
        self.assertEqual(
            sync_upstream.extract_dual_sections(changelog),
            [
                "## 1.38.0-dualvot.1 (2026-07-27)\n\nDual update.",
                "## 1.37.0-dualvot.3 (2026-07-26)\n\nOlder Dual update.",
            ],
        )

    def test_unfolds_wrapped_manifest_values(self):
        manifest = (
            "Manifest-Version: 1.0\r\n"
            "Name: Dual VoT Patches\r\n"
            "Description: Google and Yandex voice-over tran\r\n"
            " slation\r\n"
            "Version: 1.38.0-dualvot.1\r\n"
        )
        parsed = sync_upstream.parse_manifest(manifest)
        self.assertEqual(parsed["Name"], "Dual VoT Patches")
        self.assertEqual(parsed["Description"], "Google and Yandex voice-over translation")
        self.assertEqual(parsed["Version"], "1.38.0-dualvot.1")


if __name__ == "__main__":
    unittest.main()
