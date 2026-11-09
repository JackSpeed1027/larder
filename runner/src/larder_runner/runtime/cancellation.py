"""协作式取消：一个 token 向所有子 token 传播，且可重复调用。

为什么要自己实现而不是用 asyncio.CancelledError：Runner 里既有同步抓取/解析，
也有异步 HTTP；取消必须对两者都生效，并且要能记录"为什么取消"（用户取消/超时/预算）。
"""

from __future__ import annotations

from collections.abc import Callable
from threading import Lock


class CancelledError(RuntimeError):
    """任务被取消；reason 是给人看的稳定短语，参与日志与状态展示。"""

    def __init__(self, reason: str) -> None:
        super().__init__(f"cancelled: {reason}")
        self.reason = reason


class CancellationToken:
    """线程安全、可继承的取消令牌。

    规则：
    - cancel() 幂等，第一次生效并返回 True；
    - 已取消时注册回调会立即执行（避免"注册太晚永远收不到"）；
    - 父 token 取消会传播到所有子 token，子取消不影响父。
    """

    def __init__(self, name: str = "root") -> None:
        self.name = name
        self._lock = Lock()
        self._cancelled = False
        self._reason = ""
        self._callbacks: list[Callable[[str], None]] = []
        self._children: list[CancellationToken] = []

    @property
    def is_cancelled(self) -> bool:
        with self._lock:
            return self._cancelled

    @property
    def reason(self) -> str:
        with self._lock:
            return self._reason

    def cancel(self, reason: str = "cancelled") -> bool:
        with self._lock:
            if self._cancelled:
                return False
            self._cancelled = True
            self._reason = reason
            callbacks = list(self._callbacks)
            children = list(self._children)
        for callback in callbacks:
            callback(reason)
        for child in children:
            child.cancel(reason)
        return True

    def raise_if_cancelled(self) -> None:
        if self.is_cancelled:
            raise CancelledError(self.reason)

    def on_cancel(self, callback: Callable[[str], None]) -> Callable[[], None]:
        """注册取消回调，返回注销函数。"""
        with self._lock:
            if self._cancelled:
                reason = self._reason
                immediate = True
            else:
                self._callbacks.append(callback)
                immediate = False
        if immediate:
            callback(reason)
            return lambda: None

        def unsubscribe() -> None:
            with self._lock:
                if callback in self._callbacks:
                    self._callbacks.remove(callback)

        return unsubscribe

    def child(self, name: str) -> CancellationToken:
        """创建子 token：父取消时子一起取消。"""
        child = CancellationToken(name)
        with self._lock:
            if self._cancelled:
                child.cancel(self._reason)
            else:
                self._children.append(child)
        return child
