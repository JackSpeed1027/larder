# F00 验证报告：应用底座（基础版）

- runId：`20261109-2025-f00`
- 结论：**MOCK_READY（基础版）**——应用壳/路由/HTTP+CSRF/Query key/tokens/四类状态已实现并通过构建；**截图、E2E、完整组件集尚未做**。
- 依据：`docs/14`、`docs/15`（UI-A）、`tasks/frontend-tasks.json` F00、`prompts/frontend/F00.md`。

## 1. 交付

| 文件 | 内容 |
|---|---|
| `src/shared/styles/tokens.css` | design/tokens.json 的 CSS 变量（明/暗、216/64 侧栏、56 顶栏、字号/圆角/间距），窄屏与 reduced-motion |
| `src/shared/http/client.ts` | 统一 fetch：same-origin cookie、XSRF-TOKEN 双提交、ApiError(code/message/traceId/retryable)、401 广播 `auth:unauthorized`、SSE 封装 |
| `src/shared/query/keys.ts` | Query key 工厂（items/item/revision/tags/collections/notes/jobs/job/session） |
| `src/shared/layout/AppShell.tsx` | 侧栏 216（<1280 变量降为 64）、顶栏 56、内容 max 1440、导航顺序按 docs/14、离线横幅、保存入口 |
| `src/shared/ui/index.tsx` | Button / EmptyState / ErrorState / Skeleton / StatusBadge（状态不靠颜色单独表达） |
| `src/app/App.tsx` | 路由表（12 条，未实现页面显示“待实现（Fxx）”）、404、会话过期、离线检测 |
| `src/main.tsx`（CR） | 挂载 `app/App` 并引入 tokens.css |

## 2. 四类状态

| 状态 | 实现 |
|---|---|
| loading | `Skeleton`（aria-busy + sr-only 文案）；页面接入请求后替换 |
| unauthorized | `auth:unauthorized` 事件 → “会话已过期”页 + 重新登录动作 |
| not-found | 未知 hash → 404 页 + 返回资料库 |
| offline | `online/offline` 事件 → 顶栏下方横幅，说明可读内容与失败重试 |

## 3. 验证

```
pnpm --dir frontend build → tsc --noEmit && vite build 通过（19 modules，dist 产出）
```

未运行：截图矩阵（1440×900/390×844/暗色/768）、跨页 E2E、键盘焦点检查、fixture 文件。

## 4. 未完成 / 未验证（必须补齐才能真正 TASK_LOCAL_PASS）

- 共享组件只做了 5 个；docs/14 要求的 Dialog / Drawer / Tabs / Tooltip / Pagination / ConfirmAction / PageHeader 未做；
- `frontend/mocks/` 未生成四类状态 fixture；
- 未做 1440/390/暗色/平板截图与 DOM 尺寸断言；
- 未接真实 API（F01–F15 页面仍是占位）；
- 未接 F14 登录（当前 `#/login` 是占位）；
- 旧骨架页 `src/App.tsx` 未删除（已不使用），待 F00 完整版清理。

## 5. 下一步

按 docs/15 的 UI-A → UI-B 顺序：**F14 登录**（接真实 `/api/session` + CSRF）→ **F01 资料库** → F02 保存 → F03 阅读器 → F04 任务中心 → F15 组织。每页补齐 fixture/截图/E2E 后再标 TASK_LOCAL_PASS。
