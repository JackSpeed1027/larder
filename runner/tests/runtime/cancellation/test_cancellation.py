"""协作式取消的边界：幂等、传播、回调时机。"""

from __future__ import annotations

import pytest

from larder_runner.runtime.cancellation import CancelledError, CancellationToken


def test_cancel_is_idempotent_and_records_reason() -> None:
    token = CancellationToken("run")
    assert token.cancel("user requested") is True
    assert token.cancel("second attempt") is False
    assert token.reason == "user requested"


def test_child_inherits_parent_cancellation() -> None:
    parent = CancellationToken("parent")
    child = parent.child("child")
    parent.cancel("budget exhausted")

    assert child.is_cancelled
    assert child.reason == "budget exhausted"
    with pytest.raises(CancelledError):
        child.raise_if_cancelled()


def test_child_cancel_does_not_affect_parent() -> None:
    parent = CancellationToken("parent")
    child = parent.child("child")

    assert child.cancel("scope ended") is True
    assert parent.is_cancelled is False


def test_callback_fires_once_and_unsubscribe_works() -> None:
    token = CancellationToken()
    seen: list[str] = []
    unsubscribe = token.on_cancel(seen.append)

    token.cancel("stop")
    token.cancel("stop again")
    assert seen == ["stop"]

    other = CancellationToken()
    unsubscribe = other.on_cancel(seen.append)
    unsubscribe()
    other.cancel("x")
    assert seen == ["stop"]


def test_callback_registered_after_cancel_runs_immediately() -> None:
    token = CancellationToken()
    token.cancel("already")

    seen: list[str] = []
    token.on_cancel(seen.append)
    assert seen == ["already"]
