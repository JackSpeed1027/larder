# ADR-0006 Python / Node / 前端组合与降级路线

- 状态：ACCEPTED
- 日期：2026-11-06
- 任务：V01-T01
- 证据：`reports/V01-T01/20261106-195106-v01t01/{environment.txt,logs/,compat-check-frontend/pnpm-lock.yaml}`

## 固定组合

| 组件 | 版本 | 验证到哪一步 |
|---|---|---|
| CPython（uv 管理） | 3.12.15 | 真实下载 + venv + 冒烟通过 |
| uv | 0.13.1 | `uv venv` / `uv pip install` 成功 |
| FastAPI | 0.162.0 | 安装 + TestClient 真实请求 |
| Starlette（传递） | 1.10.0 | 随 FastAPI 安装，TestClient 工作 |
| Pydantic | 2.14.3 | 合法输入接受 + 未知字段拒绝均实测 |
| pydantic-settings | 2.16.2 | 与 pydantic 2.14.3 同装无冲突 |
| httpx | 0.28.1 | 安装并被 TestClient 使用 |
| pytest | 9.2.0 | 仅登记，未执行 |
| Node.js | 24.22.0（LTS Krypton） | 实际运行 pnpm |
| pnpm | 11.25.0 | `install --lockfile-only` 退出码 0 |
| React / React DOM | 19.3.1 | lockfile 解析通过 |
| Vite | 8.3.6 | lockfile 解析通过（engines 满足） |
| @vitejs/plugin-react | 6.2.1 | lockfile 解析通过 |
| TypeScript | 7.0.2 | lockfile 解析通过，未跑 tsc |
| Tailwind CSS + @tailwindcss/vite | 4.3.4 | peer 声明 `vite ^5.2 \|\| ^6 \|\| ^7 \|\| ^8` 满足 |
| @tanstack/react-query | 5.106.1 | lockfile 解析通过 |
| React Router | 8.5.1 | engines `node >=22.22.0` 满足 |
| Zustand | 5.0.16 | lockfile 解析通过 |
| Vitest | 5.1.0 | lockfile 解析通过，未跑测试 |

## 关键取舍

1. **Python 3.12.15 而不是 3.12.16**：python.org 已有 3.12.16，但本机 uv 0.13.1 的下载清单最高到 3.12.15（`uv venv --python 3.12.16` 报 `No interpreter found`）。锁"真实装成功"的 3.12.15，并在清单里写明升级条件（先升 uv）。
2. **pnpm 11.25.0 而不是 12.18.0**：12.x 会换 lockfile 格式，跨大版本升级必须单独验证并整体重生成 lockfile；本任务只冻结、不换工具链。V01-T02 若要升 12.x，需要一次显式决定并把 `pnpm-lock.yaml` 作为变更提交。
3. **TypeScript 7.0.2 是风险项**：7.x 是原生编译器大版本，Tailwind/Vitest/编辑器插件的兼容面尚未在本项目验证。因此：
   - 本任务只验证到"lockfile 能解析"，不声称"能编译"；
   - V01-T03（类型生成）第一次跑 `tsc --noEmit` 时若失败，按 ADR 允许回退 **5.9.x**（同代稳定线），并重新冻结该条目；
   - 不允许为了绕过类型错误关掉 `strict`。
4. **frontend 未建工程**：本任务的 `compat-check-frontend` 只有一个 `package.json` 与 `pnpm-lock.yaml`，没有 `src/`、没有 `vite.config.ts`——前端骨架属于 V01-T02/T03，不能抢跑。

## 未验证（BLOCKED / PARTIAL）

- `vite build`、`tsc --noEmit`、Vitest 执行、浏览器 E2E：BLOCKED（无前端工程、未安装 node_modules 包体）。
- Runner 工程、`uv.lock`、pytest 用例：BLOCKED（V01-T02 建 `runner/` 后才存在）。
- 已测的 Python 冒烟用了直接脚本而非 pytest，且 Starlette 对 `httpx` 的 TestClient 组合给出 deprecation 提示（建议 `httpx2`）——仅影响测试客户端，V01-T02 建 Runner 时复核一次。

## 后果

- 前端/后端/Runner 三端的 lockfile 都是"冻结产物"：后续任务改依赖必须先改 `docs/versions.lock.json` 再改 lockfile，不能反过来。
- Node/pnpm/Python 的补丁升级（24.22.0→24.22.2、3.12.15→3.12.16）属于允许的低风险变更，但仍需更新 lock 并记录。
