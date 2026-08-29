from __future__ import annotations

import json
import os
import queue
import re
import stat
import threading
import traceback
from pathlib import Path
from typing import Any, Callable

from .config import BridgeConfig
from .store import TaskRecord, TaskStore

_SESSION_SAFE = re.compile(r"[^A-Za-z0-9._:-]+")


class TaskCancelled(RuntimeError):
    """Raised when durable cancellation wins before Hermes starts the turn."""


def normalize_session_id(prefix: str, value: str) -> str:
    cleaned = _SESSION_SAFE.sub("-", (value or "main").strip()).strip("-._:")
    if not cleaned:
        cleaned = "main"
    return f"{prefix}-{cleaned[:64]}"


def render_task_prompt(goal: str, context: str) -> str:
    context_block = context.strip()
    return (
        "You are the background specialist for the Pearl Android assistant.\n"
        "Complete the delegated objective autonomously using Hermes tools.\n"
        "Return a concrete final result, including paths, commands, caveats, and verification.\n\n"
        f"OBJECTIVE:\n{goal.strip()}\n\n"
        + (f"CONTEXT FROM NEXUS:\n{context_block}\n" if context_block else "")
    )


class HermesRuntime:
    def __init__(self, config: BridgeConfig):
        hermes_home_raw = os.environ.get("HERMES_HOME", "").strip()
        if not hermes_home_raw:
            raise RuntimeError("HERMES_HOME must be explicitly set for the Pearl bridge")
        hermes_env = Path(hermes_home_raw) / ".env"
        if not hermes_env.is_file():
            raise FileNotFoundError(f"Hermes secret environment file not found: {hermes_env}")
        if stat.S_IMODE(hermes_env.stat().st_mode) & 0o077:
            raise PermissionError("Hermes .env must not be group/world accessible")

        self.config = config
        self._agents: dict[str, Any] = {}
        self._locks: dict[str, threading.RLock] = {}
        self._guard = threading.RLock()
        Path(config.workdir).mkdir(parents=True, exist_ok=True)
        os.chdir(config.workdir)

    def _agent_for(self, session_id: str):
        with self._guard:
            agent = self._agents.get(session_id)
            if agent is not None:
                return agent
            from hermes_state import SessionDB
            from run_agent import AIAgent

            db = SessionDB()
            agent = AIAgent(
                provider=self.config.provider,
                requested_provider=self.config.provider,
                model=self.config.model,
                max_iterations=self.config.max_iterations,
                enabled_toolsets=list(self.config.enabled_toolsets),
                disabled_toolsets=list(self.config.disabled_toolsets),
                quiet_mode=True,
                platform="mcp",
                session_id=session_id,
                pass_session_id=True,
                session_db=db,
                run_budget_seconds=self.config.run_budget_seconds,
                skip_background_review=True,
            )
            self._agents[session_id] = agent
            self._locks[session_id] = threading.RLock()
            return agent

    def run(
        self,
        session_id: str,
        goal: str,
        context: str = "",
        should_cancel: Callable[[], bool] | None = None,
    ) -> str:
        agent = self._agent_for(session_id)
        lock = self._locks[session_id]
        with lock:
            # A cancel can arrive after the worker publishes its active session
            # but before this lock is acquired. Clear stale interrupts first,
            # then re-check durable state so that cancellation cannot be erased.
            agent.clear_interrupt()
            if should_cancel is not None and should_cancel():
                raise TaskCancelled("Cancelled before Hermes turn started")
            response = agent.chat(render_task_prompt(goal, context))
            text = str(response or "").strip()
            if len(text) > self.config.max_result_chars:
                text = text[: self.config.max_result_chars] + "\n[truncated by Pearl bridge]"
            return text

    def interrupt(self, session_id: str) -> None:
        with self._guard:
            agent = self._agents.get(session_id)
        if agent is not None:
            try:
                agent.hard_interrupt("Cancelled by Pearl Nexus")
            except Exception:
                agent.interrupt("Cancelled by Pearl Nexus")


class TaskWorker:
    def __init__(self, config: BridgeConfig, store: TaskStore, runtime: HermesRuntime):
        self.config = config
        self.store = store
        self.runtime = runtime
        self._queue: queue.Queue[str | None] = queue.Queue()
        self._stop = threading.Event()
        self._active: dict[str, str] = {}
        self._active_lock = threading.RLock()
        self._thread = threading.Thread(target=self._loop, name="pearl-hermes-worker", daemon=True)

    def start(self) -> None:
        for task_id in self.store.recover_interrupted():
            self._queue.put(task_id)
        self._thread.start()

    def submit(self, client_session_id: str, goal: str, context: str = "") -> TaskRecord:
        goal = goal.strip()
        context = context.strip()
        if not goal:
            raise ValueError("goal must not be blank")
        if len(goal) > self.config.max_goal_chars:
            raise ValueError("goal is too long")
        if len(context) > self.config.max_context_chars:
            raise ValueError("context is too long")
        session_id = normalize_session_id(self.config.session_prefix, client_session_id)
        record = self.store.create(session_id, goal, context)
        self._queue.put(record.id)
        return record

    def cancel(self, task_id: str) -> TaskRecord:
        record = self.store.cancel(task_id)
        with self._active_lock:
            session_id = self._active.get(task_id)
        if session_id:
            self.runtime.interrupt(session_id)
        return self.store.get(task_id)

    def _loop(self) -> None:
        while not self._stop.is_set():
            task_id = self._queue.get()
            if task_id is None:
                self._queue.task_done()
                return
            try:
                if not self.store.mark_running(task_id):
                    continue
                record = self.store.get(task_id)
                with self._active_lock:
                    self._active[task_id] = record.session_id
                result = self.runtime.run(
                    record.session_id,
                    record.goal,
                    record.context,
                    should_cancel=lambda: self.store.is_cancel_requested(task_id),
                )
                self.store.complete_or_cancel(task_id, result)
            except Exception as error:
                try:
                    if self.store.is_cancel_requested(task_id):
                        self.store.mark_cancelled(task_id)
                    else:
                        message = "".join(
                            traceback.format_exception_only(type(error), error)
                        ).strip()
                        self.store.fail(task_id, message)
                except Exception:
                    pass
            finally:
                with self._active_lock:
                    self._active.pop(task_id, None)
                self._queue.task_done()

    def stop(self, timeout: float = 30.0) -> bool:
        """Request shutdown and report whether the worker exited cleanly."""
        self._stop.set()
        with self._active_lock:
            active = list(self._active.values())
        for session_id in active:
            self.runtime.interrupt(session_id)
        self._queue.put(None)
        self._thread.join(timeout=timeout)
        return not self._thread.is_alive()


def task_json(record: TaskRecord, include_result: bool = True) -> str:
    payload = {
        "task_id": record.id,
        "session_id": record.session_id,
        "status": record.status,
        "cancel_requested": record.cancel_requested,
        "created_at": record.created_at,
        "updated_at": record.updated_at,
        "error": record.error,
    }
    if include_result:
        payload["result"] = record.result
    return json.dumps(payload, ensure_ascii=False)
