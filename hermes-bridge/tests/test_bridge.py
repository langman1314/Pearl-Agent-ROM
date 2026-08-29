from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from pearl_hermes_bridge.config import BridgeConfig
from pearl_hermes_bridge.runtime import TaskWorker, normalize_session_id, render_task_prompt
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
    def test_restart_recovers_only_never_started_work(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            queued = store.create("session", "queued", "")
            ambiguous = store.create("session", "running", "")
            cancelled = store.create("session", "cancelled-running", "")
            self.assertTrue(store.mark_running(ambiguous.id))
            self.assertTrue(store.mark_running(cancelled.id))
            store.cancel(cancelled.id)

            self.assertEqual([queued.id], store.recover_interrupted())
            self.assertEqual("queued", store.get(queued.id).status)
            self.assertEqual("failed", store.get(ambiguous.id).status)
            self.assertIn("ambiguous", store.get(ambiguous.id).error or "")
            self.assertEqual("cancelled", store.get(cancelled.id).status)
            store.close()

    def test_queued_cancel_is_terminal(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            record = store.create("session", "goal", "")
            self.assertEqual("cancelled", store.cancel(record.id).status)
            self.assertFalse(store.mark_running(record.id))
            store.close()

    def test_completion_atomically_respects_cancel(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            record = store.create("session", "goal", "")
            self.assertTrue(store.mark_running(record.id))
            store.cancel(record.id)
            final = store.complete_or_cancel(record.id, "must be discarded")
            self.assertEqual("cancelled", final.status)
            self.assertIsNone(final.result)
            store.close()


class BlockingRuntime:
    def __init__(self):
        self.started = threading.Event()
        self.interrupted = threading.Event()

    def run(self, session_id, goal, context, should_cancel=None):
        self.started.set()
        while not self.interrupted.wait(0.01):
            if should_cancel is not None and should_cancel():
                raise RuntimeError("cancel observed before interrupt")
        raise RuntimeError("Hermes interrupted")

    def interrupt(self, session_id):
        self.interrupted.set()


class TaskWorkerTest(unittest.TestCase):
    def test_running_cancel_ends_cancelled_even_when_agent_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = TaskStore(Path(tmp, "bridge.db"))
            runtime = BlockingRuntime()
            config = BridgeConfig(
                workdir=tmp,
                state_db=str(Path(tmp, "bridge.db")),
            ).validated()
            worker = TaskWorker(config, store, runtime)
            worker.start()
            record = worker.submit("phone", "long goal")
            self.assertTrue(runtime.started.wait(2), "worker did not start")

            worker.cancel(record.id)
            deadline = time.monotonic() + 2
            while store.get(record.id).status not in {"cancelled", "failed"}:
                self.assertLess(time.monotonic(), deadline, "cancel did not settle")
                time.sleep(0.01)

            self.assertEqual("cancelled", store.get(record.id).status)
            self.assertTrue(worker.stop())
            store.close()


if __name__ == "__main__":
    unittest.main()
