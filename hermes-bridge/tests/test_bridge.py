from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from pearl_hermes_bridge.config import BridgeConfig
from pearl_hermes_bridge.runtime import normalize_session_id, render_task_prompt
from pearl_hermes_bridge.store import TaskStore


class ConfigTest(unittest.TestCase):
    def test_rejects_non_loopback_bind(self):
        with self.assertRaises(ValueError):
            BridgeConfig(host="0.0.0.0").validated()

    def test_reads_known_json_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "config.json")
            path.write_text(json.dumps({"port": 51339, "state_db": str(Path(tmp, "state.db"))}))
            self.assertEqual(51339, BridgeConfig.from_file(path).port)

    def test_rejects_unknown_json_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "config.json")
            path.write_text('{"secret_api_key":"must-not-live-here"}')
            with self.assertRaises(ValueError):
                BridgeConfig.from_file(path)


class RuntimeBoundaryTest(unittest.TestCase):
    def test_session_id_is_scoped_and_sanitized(self):
        self.assertEqual("pearl-nexus-phone-main", normalize_session_id("pearl-nexus", "phone main"))

    def test_prompt_separates_objective_and_context(self):
        prompt = render_task_prompt("build it", "safe mode")
        self.assertIn("OBJECTIVE:\nbuild it", prompt)
        self.assertIn("CONTEXT FROM NEXUS:\nsafe mode", prompt)


class TaskStoreTest(unittest.TestCase):
    def test_lifecycle_and_restart_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            first = store.create("session", "goal", "context")
            self.assertEqual("queued", first.status)
            self.assertTrue(store.mark_running(first.id))
            self.assertEqual([first.id], store.recover_interrupted())
            self.assertEqual("queued", store.get(first.id).status)
            self.assertTrue(store.mark_running(first.id))
            store.finish(first.id, "done")
            self.assertEqual("done", store.get(first.id).result)
            store.close()

    def test_queued_cancel_is_terminal(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            record = store.create("session", "goal", "")
            self.assertEqual("cancelled", store.cancel(record.id).status)
            self.assertFalse(store.mark_running(record.id))
            store.close()


if __name__ == "__main__":
    unittest.main()
