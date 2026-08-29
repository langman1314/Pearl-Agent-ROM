from __future__ import annotations

import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path


TERMINAL_STATES = {"completed", "failed", "cancelled"}


@dataclass(frozen=True)
class TaskRecord:
    id: str
    session_id: str
    goal: str
    context: str
    status: str
    result: str | None
    error: str | None
    cancel_requested: bool
    created_at: float
    updated_at: float


class TaskStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=FULL")
        self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                goal TEXT NOT NULL,
                context TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                error TEXT,
                cancel_requested INTEGER NOT NULL DEFAULT 0,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS tasks_status_created
                ON tasks(status, created_at);
            """
        )
        self._conn.commit()

    def recover_interrupted(self) -> list[str]:
        with self._lock:
            now = time.time()
            self._conn.execute(
                """UPDATE tasks
                   SET status='queued', error='Bridge restarted; task re-queued',
                       cancel_requested=0, updated_at=?
                   WHERE status='running'""",
                (now,),
            )
            rows = self._conn.execute(
                "SELECT id FROM tasks WHERE status='queued' ORDER BY created_at"
            ).fetchall()
            self._conn.commit()
            return [str(row["id"]) for row in rows]

    def create(self, session_id: str, goal: str, context: str) -> TaskRecord:
        task_id = uuid.uuid4().hex
        now = time.time()
        with self._lock:
            self._conn.execute(
                """INSERT INTO tasks
                   (id, session_id, goal, context, status, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 'queued', ?, ?)""",
                (task_id, session_id, goal, context, now, now),
            )
            self._conn.commit()
        return self.get(task_id)

    def get(self, task_id: str) -> TaskRecord:
        with self._lock:
            row = self._conn.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if row is None:
            raise KeyError(task_id)
        return TaskRecord(
            id=str(row["id"]),
            session_id=str(row["session_id"]),
            goal=str(row["goal"]),
            context=str(row["context"]),
            status=str(row["status"]),
            result=row["result"],
            error=row["error"],
            cancel_requested=bool(row["cancel_requested"]),
            created_at=float(row["created_at"]),
            updated_at=float(row["updated_at"]),
        )

    def mark_running(self, task_id: str) -> bool:
        with self._lock:
            cursor = self._conn.execute(
                """UPDATE tasks SET status='running', updated_at=?
                   WHERE id=? AND status='queued' AND cancel_requested=0""",
                (time.time(), task_id),
            )
            self._conn.commit()
            return cursor.rowcount == 1

    def finish(self, task_id: str, result: str) -> None:
        self._transition(task_id, "completed", result=result, error=None)

    def fail(self, task_id: str, error: str) -> None:
        self._transition(task_id, "failed", result=None, error=error)

    def cancel(self, task_id: str) -> TaskRecord:
        with self._lock:
            record = self.get(task_id)
            if record.status in TERMINAL_STATES:
                return record
            status = "cancelled" if record.status == "queued" else record.status
            self._conn.execute(
                """UPDATE tasks SET cancel_requested=1, status=?, updated_at=?
                   WHERE id=?""",
                (status, time.time(), task_id),
            )
            self._conn.commit()
        return self.get(task_id)

    def mark_cancelled(self, task_id: str) -> None:
        self._transition(task_id, "cancelled", result=None, error="Cancelled")

    def is_cancel_requested(self, task_id: str) -> bool:
        return self.get(task_id).cancel_requested

    def _transition(self, task_id: str, status: str, result: str | None, error: str | None) -> None:
        with self._lock:
            self._conn.execute(
                """UPDATE tasks SET status=?, result=?, error=?, updated_at=? WHERE id=?""",
                (status, result, error, time.time(), task_id),
            )
            self._conn.commit()

    def close(self) -> None:
        with self._lock:
            self._conn.close()
