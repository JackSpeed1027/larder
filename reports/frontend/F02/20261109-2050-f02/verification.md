# F02 验证报告：保存内容

- runId：`20261109-2050-f02`
- 结论：**TASK_LOCAL_PASS（功能集成）**——网页/文本两种入口接真实 `POST /api/items`，含幂等键与错误态；截图/E2E 未做。

## 交付

| 文件 | 内容 |
|---|---|
| `features/capture/api.ts` | `createItem`（Idempotency-Key 头）、`newIdempotencyKey` |
| `features/capture/CapturePage.tsx` | WEB/TEXT 切换、标题可选、URL/正文校验、提交态、409/400/其它错误分类；改动内容后更换幂等键 |
| `app/App.tsx` | `/capture` 渲染保存页，成功后回 `/library` |

## 语义

- 一次提交意图固定一个幂等键：网络重试幂等；用户改动表单后自动换键，避免“不同内容同键”被 409；
- 400 显示具体校验信息；409 提示已换新键可重试；其它错误保留信息可重试；
- 成功后回资料库（新条目尚无正文，阅读页要等任务完成）。
