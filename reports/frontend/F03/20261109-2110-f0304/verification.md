# F03/F04 验证报告（一次两页）

- runId：`20261109-2110-f0304`
- 结论：**TASK_LOCAL_PASS（功能集成）**——阅读器与任务中心接真实 API 并通过构建；截图/E2E 未做。

## F03 阅读器

- `features/reader/api.ts`：getItem / readRevision（固定 revision）/ savePosition / markRead；
- `ReaderPage`：loading / 无正文 / 错误 / 正文；标题+阅读状态+版本号+sha 截断；滚动进度每 3s 节流保存；“标记已读”；
- 路由 `/reader?itemId=…&revisionId=…`；缺参数时给出“阅读链接不完整”并可返回。

## F04 任务中心

- `features/jobs/api.ts`：listJobs / cancelJob / retryJob；
- `JobsPage`：状态筛选、刷新、取消（进行中）、重试（失败/取消）、loading/empty/error；操作失败保留错误并可刷新。

## 验证

```
pnpm --dir frontend build → tsc --noEmit && vite build 通过（30 modules）
```

未验证：1440/390/暗色截图、浏览器 E2E（登录→保存→阅读→任务）、键盘走查、滚动进度在真实长文下的准确性。
