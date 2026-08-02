import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("sync_upstream.py")
SPEC = importlib.util.spec_from_file_location("sync_upstream", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
sync_upstream = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sync_upstream
SPEC.loader.exec_module(sync_upstream)


class SyncUpstreamTests(unittest.TestCase):
    def test_channel_workflow_runs_current_controller_from_main(self):
        workflow = (
            MODULE_PATH.parents[1] / "workflows" / "upstream_sync_channel.yml"
        ).read_text(encoding="utf-8")
        self.assertEqual(workflow.count("ref: main"), 2)
        self.assertNotIn(
            "ref: ${{ inputs.channel == 'stable' && 'main' || 'dev' }}",
            workflow,
        )

    def test_dispatcher_forwards_secrets_to_channel_workflows(self):
        workflow = (
            MODULE_PATH.parents[1] / "workflows" / "upstream_sync.yml"
        ).read_text(encoding="utf-8")
        self.assertEqual(workflow.count("secrets: inherit"), 2)

    def test_publisher_uses_workflow_capable_credential(self):
        workflow = (
            MODULE_PATH.parents[1] / "workflows" / "upstream_sync_channel.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("token: ${{ secrets.UPSTREAM_SYNC_TOKEN }}", workflow)
        self.assertIn("GH_TOKEN: ${{ secrets.UPSTREAM_SYNC_TOKEN }}", workflow)

    def test_extracts_official_base_from_dual_version(self):
        self.assertEqual(
            sync_upstream.upstream_base("1.37.0-dualvot.3"),
            "1.37.0",
        )
        self.assertEqual(
            sync_upstream.upstream_base("1.38.0-dev.2-dualvot.1"),
            "1.38.0-dev.2",
        )

    def test_extracts_dual_revision_independently_of_morphe_base(self):
        self.assertEqual(
            sync_upstream.dualvot_revision("1.37.0-dualvot.7"),
            (7,),
        )
        self.assertEqual(
            sync_upstream.dualvot_revision("1.37.1-dev.1-dualvot.7-preview"),
            (7,),
        )
        self.assertEqual(
            sync_upstream.dualvot_revision("1.38.0-dev.1-dualvot.8.1"),
            (8, 1),
        )
        self.assertEqual(
            sync_upstream.format_dualvot_revision((8, 1)),
            "8.1",
        )

    def test_rejects_version_without_dual_revision(self):
        with self.assertRaises(sync_upstream.SyncError):
            sync_upstream.dualvot_revision("1.37.1-dev.1")

    def test_accepts_stable_and_prerelease_versions(self):
        for version in ("1.38.0", "1.38.0-dev.2", "1.38.0-dev.2-dualvot.1"):
            sync_upstream.validate_version(version)

    def test_rejects_version_that_could_become_a_command_argument(self):
        with self.assertRaises(sync_upstream.SyncError):
            sync_upstream.validate_version("1.38.0;echo-danger")

    def test_accepts_manager_local_datetime_without_timezone(self):
        sync_upstream.validate_manager_local_datetime("2026-07-28T10:49:38")

    def test_rejects_manager_datetime_with_timezone(self):
        for timestamp in (
            "2026-07-28T12:49:38+02:00",
            "2026-07-28T10:49:38Z",
        ):
            with self.subTest(timestamp=timestamp):
                with self.assertRaises(sync_upstream.SyncError):
                    sync_upstream.validate_manager_local_datetime(timestamp)

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

    def test_publish_configures_git_identity_before_loading_plan(self):
        events = []

        def configure_identity():
            events.append("identity")

        def fail_to_load_plan(_):
            events.append("plan")
            raise sync_upstream.SyncError("stop after identity check")

        with (
            mock.patch.object(
                sync_upstream,
                "configure_git_identity",
                side_effect=configure_identity,
            ),
            mock.patch.object(
                sync_upstream,
                "load_plan",
                side_effect=fail_to_load_plan,
            ),
        ):
            with self.assertRaises(sync_upstream.SyncError):
                sync_upstream.publish(Path("unused"), "owner/repository")

        self.assertEqual(events, ["identity", "plan"])


if __name__ == "__main__":
    unittest.main()
