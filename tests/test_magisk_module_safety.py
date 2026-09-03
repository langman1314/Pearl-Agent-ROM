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
        leave = script.index('rm -f "$MAINTENANCE_FILE"', permissions)
        success_message = script.index('ui_print "- Installation staged safely"', leave)
        self.assertLess(enter, stop)
        self.assertLess(stop, unmount)
        self.assertLess(unmount, extract)
        self.assertLess(extract, publish)
        self.assertLess(publish, build_record)
        self.assertLess(build_record, permissions)
        self.assertLess(permissions, leave)
        self.assertLess(leave, success_message)

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
        script = text("customize.sh")
        self.assertNotIn('[ -x "$stage/opt/pearl-agent/venv/bin/python" ]', script)
        self.assertIn(
            '[ "$(readlink "$stage/opt/pearl-agent/venv/bin/python3")" != /usr/bin/python3 ]',
            script,
        )
        self.assertIn('[ ! -x "$stage/usr/bin/python3.11" ]', script)


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
