# F01 验证报告：资料库

- runId：`20261109-2040-f01`
- 结论：**TASK_LOCAL_PASS（功能集成）**——列表接真实 `GET /api/items`，筛选/分页/四态齐全并通过构建；截图与浏览器 E2E 未做。

## 交付

| 文件 | 内容 |
|---|---|
| `features/library/api.ts` | listItems（query/tag/status/limit/offset，AbortSignal）、listTags |
| `features/library/LibraryPage.tsx` | 列表 + 关键词 300ms 防抖 + 状态/标签筛选 + offset 分页；loading/empty/error/无匹配；长标题省略但保留 `title`，ID/URL 等宽换行；点击进入阅读（带 itemId + revisionId） |
| `app/App.tsx`（F00 文件） | `/library` 渲染真实资料库；点击跳 `/reader?...` |

## 行为要点

- 旧请求用 AbortController 取消，避免晚返回覆盖新筛选；
- 分页显示 `offset+1–min(offset+limit,total) / total`，到边界禁用按钮；
- 无数据与“筛选无结果”文案不同，并提供“清除筛选”。

## 验证

```
pnpm --dir frontend build → tsc --noEmit && vite build 通过（dist 产出）
```

未验证：1440/390/暗色截图、浏览器 E2E（登录→列表→阅读）、键盘走查、真实数据下的长文本/空库演示。
