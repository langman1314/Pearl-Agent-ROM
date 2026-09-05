from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "magisk-module"


def text(name: str) -> str:
    return (MODULE / name).read_text(encoding="utf-8")


class UpgradeMaintenanceSafetyTest(unittest.TestCase):
    def test_common_declares_lifecycle_markers(self) -> None:
        common = text("lib/common.sh")
        self.assertIn('MAINTENANCE_FILE="$STATE_ROOT/maintenance"', common)
        self.assertIn('UNINSTALLED_FILE="$STATE_ROOT/uninstalled"', common)

    def test_installer_quiesces_before_stop_and_clears_last(self) -> None:
        script = text("customize.sh")
        enter = script.index('touch "$MAINTENANCE_FILE"')
        stop = script.index("stop_bridge", enter)
        unmount = script.index("unmount_chroot", stop)
        extract = script.index('"$ZSTD" -dc "$ARCHIVE"', unmount)
        publish = script.index('mv "$stage" "$ROOTFS"', extract)
        build_record = script.index('"$STATE_ROOT/installed-build.json"', publish)
        permissions = script.index("set_perm_recursive", build_record)
        state_sync = script.index(
            'sync || restore_after_activation_failure "Could not synchronize activated Agent state"',
            permissions,
        )
        leave = script.index('rm -f "$MAINTENANCE_FILE"', state_sync)
        final_sync = script.index("if ! sync; then", leave)
        retain = script.index('touch "$MAINTENANCE_FILE"', final_sync)
        success_message = script.index(
            'ui_print "- Installation staged safely and synchronized"', retain
        )
        self.assertLess(enter, stop)
        self.assertLess(stop, unmount)
        self.assertLess(unmount, extract)
        self.assertLess(extract, publish)
        self.assertLess(publish, build_record)
        self.assertLess(build_record, permissions)
        self.assertLess(permissions, state_sync)
        self.assertLess(state_sync, leave)
        self.assertLess(leave, final_sync)
        self.assertLess(final_sync, retain)
        self.assertLess(retain, success_message)

    def test_supervisor_observes_maintenance_at_every_restart_boundary(self) -> None:
        script = text("service.sh")
        sleep_function = script[
            script.index("sleep_while_enabled()") : script.index("claim_supervisor()")
        ]
        self.assertGreaterEqual(sleep_function.count("MAINTENANCE_FILE"), 2)
        self.assertIn(
            'while [ ! -f "$DISABLED_FILE" ] && [ ! -f "$MAINTENANCE_FILE" ]; do',
            script,
        )
        start = script.index("start_once")
        after_start = script.index('|| [ -f "$MAINTENANCE_FILE" ]', start)
        restart_log = script.index('pearl_log "Restarting Hermes bridge', after_start)
        self.assertLess(after_start, restart_log)

    def test_boot_mounts_and_manual_enable_fail_closed(self) -> None:
        post_fs = text("post-fs-data.sh")
        marker_check = post_fs.index('[ -f "$MAINTENANCE_FILE" ]')
        mount = post_fs.index("if ! mount_chroot")
        self.assertLess(marker_check, mount)

        action = text("action.sh")
        enable = action.index("  enable)")
        marker_check = action.index('[ -f "$MAINTENANCE_FILE" ]', enable)
        clear_disabled = action.index('rm -f "$DISABLED_FILE"', enable)
        self.assertLess(marker_check, clear_disabled)

    def test_uninstall_cleanup_and_reinstall_semantics(self) -> None:
        uninstall = text("uninstall.sh")
        mark_disabled = uninstall.index('touch "$DISABLED_FILE" "$UNINSTALLED_FILE"')
        stop = uninstall.index("stop_bridge", mark_disabled)
        cleanup = uninstall.index('rm -rf "$ROOTFS"', stop)
        self.assertLess(mark_disabled, stop)
        self.assertLess(stop, cleanup)
        self.assertIn('"$STATE_ROOT"/rootfs.failed.mount.*', uninstall)
        self.assertIn('"$STATE_ROOT"/rootfs.failed.action.*', uninstall)
        self.assertNotIn('rm -rf "$DATA_ROOT"', uninstall)

        installer = text("customize.sh")
        remember = installer.index('[ -f "$UNINSTALLED_FILE" ]')
        enter = installer.index('touch "$MAINTENANCE_FILE"')
        permissions = installer.index("set_perm_recursive")
        reinstall_clear = installer.index(
            'rm -f "$DISABLED_FILE" "$UNINSTALLED_FILE"', permissions
        )
        leave = installer.index('rm -f "$MAINTENANCE_FILE"', reinstall_clear)
        self.assertLess(remember, enter)
        self.assertLess(permissions, reinstall_clear)
        self.assertLess(reinstall_clear, leave)
    def test_installer_validates_python_inside_chroot_tree(self) -> None:
        common = text("lib/common.sh")
        self.assertIn(
            '[ "$(readlink "$tree/opt/pearl-agent/venv/bin/python3")" = /usr/bin/python3 ]',
            common,
        )
        self.assertIn('[ -x "$tree/usr/bin/python3.11" ]', common)
        self.assertIn('/usr/bin/python3.11 -B -I -S -c', common)
        self.assertIn('/opt/pearl-agent/venv/bin/python -B -c', common)
        self.assertIn('from run_agent import AIAgent', common)
        self.assertIn('from mcp.server import MCPServer', common)
        self.assertIn('import pearl_hermes_bridge', common)
        self.assertIn('if b"\\0" in p.read_bytes()', common)

    def test_installer_detects_zero_filled_runtime_and_syncs_persistence(self) -> None:
        script = text("customize.sh")
        common = text("lib/common.sh")
        self.assertIn("EXPECTED_BUILD_JSON_SHA256=", common)
        self.assertIn("EXPECTED_RE_INIT_SHA256=", common)
        self.assertIn('rootfs_runtime_is_valid "$stage" true', script)
        self.assertIn('rootfs_runtime_is_valid "$ROOTFS"', script)
        self.assertIn('rootfs_runtime_is_valid "$ROOTFS"', common)
        self.assertIn('build_record_tmp="$STATE_ROOT/installed-build.json.new.$$"', script)
        self.assertIn('mv -f "$build_record_tmp" "$STATE_ROOT/installed-build.json"', script)
        self.assertIn("restore_after_activation_failure()", script)
        self.assertIn(
            'mv "$stage" "$ROOTFS" ||\n  restore_after_activation_failure "Could not activate new rootfs"',
            script,
        )
        self.assertNotIn("previous verified rootfs restored", script)
        self.assertIn('touch "$MAINTENANCE_FILE"', script)
        self.assertIn('mv -f "$rollback_record" "$STATE_ROOT/installed-build.json"', script)
        self.assertIn('rm -f "$STATE_ROOT/installed-build.json"', script)
        self.assertGreaterEqual(script.count("sync"), 6)

    def test_corrupt_existing_rootfs_is_not_kept_as_rollback(self) -> None:
        script = text("customize.sh")
        validate = script.index('if rootfs_runtime_is_valid "$ROOTFS"; then')
        preserve = script.index('mv "$ROOTFS" "$PREVIOUS_ROOTFS"', validate)
        reject = script.index('ui_print "- Existing rootfs is corrupt; excluding it from rollback"', preserve)
        remove = script.index('rm -rf "$ROOTFS"', reject)
        self.assertLess(validate, preserve)
        self.assertLess(preserve, reject)
        self.assertLess(reject, remove)


class PayloadRejectionSafetyTest(unittest.TestCase):
    def test_installer_rescans_partition_and_flasher_names(self) -> None:
        script = text("customize.sh")
        self.assertIn("forbidden Android partition payload", script)
        self.assertIn("forbidden firmware flashing script", script)
        policy = re.compile(
            r"\(flash_all\[\^/\]\*\|flash\[\^/\]\*preloader\[\^/\]\*\|fastboot\[\^/\]\*\)"
        )
        self.assertRegex(script, policy)


if __name__ == "__main__":
    unittest.main()
