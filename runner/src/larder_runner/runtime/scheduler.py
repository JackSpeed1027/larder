"""有界并发调度器：限制同时执行的 run 数，排队 FIFO，取消可分状态处理。

它不自己起线程：谁拥有执行权（worker/线程池）谁调用 try_start() 取任务、finish() 归还。
这样调度决策是可测的纯状态机，执行模型留给 V05 的运行时。
"""

from __future__ import annotations

import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from threading import Lock

from .cancellation import CancellationToken


class RunState(str, Enum):
    """run 在调度器里的状态；与 Java 侧 job.state 不是同一套枚举。"""

    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


@dataclass
class RunSlot:
    """一个被调度器接纳的 run。token 用于协作式取消。"""

    run_id: str
    state: RunState
    token: CancellationToken
    submitted_at: float
    started_at: float | None = None
    finished_at: float | None = None

    def is_terminal(self) -> bool:
        return self.state in (RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)


@dataclass
class SchedulerSnapshot:
    """调度器瞬时状态，供 /v1/health 或调试端点展示。"""

    running: int
    queued: int
    limit: int
    slots: list[RunSlot] = field(default_factory=list)


class BoundedScheduler:
    """限制并发数的调度器。

    - submit 只是入队，不保证立即执行；
    - try_start 按 FIFO 启动，直到达到并发上限；
    - cancel：排队中的直接标记取消并移出队列；运行中的只设置 token，
      由执行者在下一次检查点退出（调度器不强行杀线程）。
    """

    def __init__(self, max_concurrent: int, clock=time.monotonic) -> None:
        if max_concurrent < 1:
            raise ValueError("max_concurrent 必须 >= 1")
        self._limit = max_concurrent
        self._clock = clock
        self._lock = Lock()
        self._slots: dict[str, RunSlot] = {}
        self._queue: deque[str] = deque()
        self._running: set[str] = set()

    def submit(self, run_id: str) -> RunSlot:
        """接纳一个 run；同 run_id 重复提交返回原 slot（幂等）。"""
        with self._lock:
            existing = self._slots.get(run_id)
            if existing is not None:
                return existing
            slot = RunSlot(run_id=run_id, state=RunState.QUEUED,
                           token=CancellationToken(name=run_id), submitted_at=self._clock())
            self._slots[run_id] = slot
            self._queue.append(run_id)
            return slot

    def try_start(self) -> list[RunSlot]:
        """启动尽可能多的排队 run，返回本轮启动的 slot。"""
        started: list[RunSlot] = []
        with self._lock:
            while self._queue and len(self._running) < self._limit:
                run_id = self._queue.popleft()
                slot = self._slots[run_id]
                if slot.state is not RunState.QUEUED:
                    continue
                slot.state = RunState.RUNNING
                slot.started_at = self._clock()
                self._running.add(run_id)
                started.append(slot)
        return started

    def finish(self, run_id: str, ok: bool = True) -> RunSlot | None:
        """执行者归还执行权；返回更新后的 slot。"""
        with self._lock:
            slot = self._slots.get(run_id)
            if slot is None or slot.is_terminal():
                return slot
            self._running.discard(run_id)
            slot.finished_at = self._clock()
            if slot.token.is_cancelled:
                slot.state = RunState.CANCELLED
            else:
                slot.state = RunState.SUCCEEDED if ok else RunState.FAILED
            return slot

    def cancel(self, run_id: str, reason: str = "cancelled") -> bool:
        """取消：排队中的直接终态；运行中的设置 token 等执行者退出。"""
        with self._lock:
            slot = self._slots.get(run_id)
            if slot is None or slot.is_terminal():
                return False
            if slot.state is RunState.QUEUED:
                slot.state = RunState.CANCELLED
                slot.finished_at = self._clock()
                try:
                    self._queue.remove(run_id)
                except ValueError:
                    pass
                return True
        return slot.token.cancel(reason)

    def snapshot(self) -> SchedulerSnapshot:
        with self._lock:
            return SchedulerSnapshot(
                running=len(self._running),
                queued=sum(1 for rid in self._queue if self._slots[rid].state is RunState.QUEUED),
                limit=self._limit,
                slots=sorted(self._slots.values(), key=lambda s: s.submitted_at),
            )
