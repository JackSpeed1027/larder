# S05 验证：SSE 有界连接（slice S05.BOUNDED）

- runId：s05-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——连接与线程有界、超限 503、许可在完成/超时/出错时归还；**持久事件日志与游标续传仍待做**。
- 依据：docs/21 §S05、docs/24 §3

## 改动

| 位置 | 内容 |
|---|---|
| `JobEventsController` | `cachedThreadPool` → 固定池（`larder.sse.pool-size`，默认 8）；连接信号量（`larder.sse.max-connections`，默认 32），超限 503；`onCompletion/onTimeout/onError` 归还许可；`@PreDestroy shutdownNow` |
| 轮询等待 | 可中断 sleep；线程中断即退出，不继续占连接 |
| 修复 | `ownerId == null` 时不再 NPE，统一 404 |
| 测试 | `SseBoundedTest`：连接耗尽 503、越权 job 404 |

## 实测

- `mvn test`：**114 passed**（新增 2）；
- `verify-lease.ps1`：**ALL LEASE CHECKS PASSED**（snapshot 首发、Last-Event-ID 重连、租约回收重派）。

## 仍未完成（S05 剩余）

1. **事件序号仍是近似**：`seq = max(seq+1, next.seq())`，不是持久事件表；跨实例/历史过期无法真正回放；
2. `Last-Event-ID` 只回显为 `resumedFrom`，没有“历史已过期 → 明确 reset”语义；
3. 前端轮询降级、隐藏页降频未接；
4. snapshot→events 竞态无专门测试。
