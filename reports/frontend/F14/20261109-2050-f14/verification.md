# F14 验证报告：登录初始化

- runId：`20261109-2050-f14`
- 结论：**TASK_LOCAL_PASS（功能集成）**——登录页接真实 `/api/session` + CSRF，状态齐全并通过构建；**截图/跨页 E2E 未做**。

## 交付

| 文件 | 内容 |
|---|---|
| `features/auth/api.ts` | 改为复用 F00 统一 HTTP 客户端（CSRF/401 语义只实现一次） |
| `features/auth/LoginPage.tsx` | 400px 卡片；状态 initializing/idle/submitting/invalid/failed/expired/setup-closed；密码可见切换（aria-label + aria-pressed）；错误不区分账号是否存在 |
| `app/App.tsx`（F00 文件） | `/login` 与 `/` 路由到登录页；成功后进 `/library` |

## 安全边界

- `safeReturnTo`：只接受以单个 `/` 开头的站内路径，`//evil` 与外部 URL 一律回落 `/library`（防开放重定向）；
- 401 统一显示“账号或密码不正确”，不泄露账号是否存在；
- 403 显示“会话已失效（CSRF）”，提示刷新重登。

## 验证

```
pnpm --dir frontend build → tsc --noEmit && vite build 通过（22 modules）
```

未验证：/setup 服务端裁决（后端尚无初始化接口，页面显示“入口已关闭”）、截图矩阵、跨页 E2E、键盘走查。
