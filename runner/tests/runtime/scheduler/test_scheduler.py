"""有界调度器：并发上限、FIFO、取消语义、幂等提交。"""

from __future__ import annotations

import pytest

from larder_runner.runtime.scheduler import BoundedScheduler, RunState


def test_capacity_is_enforced_and_fifo_preserved() -> None:
    scheduler = BoundedScheduler(max_concurrent=2)
    for run_id in ("r1", "r2", "r3"):
        scheduler.submit(run_id)

    started = scheduler.try_start()
    assert [slot.run_id for slot in started] == ["r1", "r2"]
    assert scheduler.snapshot().running == 2
    assert scheduler.snapshot().queued == 1

    scheduler.finish("r1")
    assert [slot.run_id for slot in scheduler.try_start()] == ["r3"]


def test_duplicate_submit_is_idempotent() -> None:
    scheduler = BoundedScheduler(max_concurrent=1)
    first = scheduler.submit("r1")
    second = scheduler.submit("r1")

    assert first is second
    assert scheduler.snapshot().queued == 1


def test_cancel_queued_run_never_starts() -> None:
    scheduler = BoundedScheduler(max_concurrent=1)
    scheduler.submit("r1")
    scheduler.submit("r2")
    scheduler.try_start()

    assert scheduler.cancel("r2", "user cancelled") is True
    slot = next(s for s in scheduler.snapshot().slots if s.run_id == "r2")
    assert slot.state is RunState.CANCELLED

    scheduler.finish("r1")
    assert scheduler.try_start() == []


def test_cancel_running_run_flags_token_and_finish_marks_cancelled() -> None:
    scheduler = BoundedScheduler(max_concurrent=1)
    scheduler.submit("r1")
    slot = scheduler.try_start()[0]

    assert scheduler.cancel("r1", "timeout") is True
    assert slot.token.is_cancelled
    assert slot.token.reason == "timeout"

    finished = scheduler.finish("r1", ok=True)
    assert finished.state is RunState.CANCELLED


def test_finish_failure_and_unknown_ids() -> None:
    scheduler = BoundedScheduler(max_concurrent=1)
    scheduler.submit("r1")
    scheduler.try_start()

    assert scheduler.finish("r1", ok=False).state is RunState.FAILED
    assert scheduler.cancel("missing") is False
    assert scheduler.finish("missing") is None


def test_invalid_limit_is_rejected() -> None:
    with pytest.raises(ValueError):
        BoundedScheduler(max_concurrent=0)
