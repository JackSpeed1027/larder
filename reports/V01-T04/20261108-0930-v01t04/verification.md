# V01-T04 验证报告：认证与验收基础

- runId：`20261108-0930-v01t04`
- 结论：**TASK_LOCAL_PASS**——`python scripts/accept.py --task V01-T04 --profile offline` 退出码 0，六项检查全过。
- 依赖：V01-T03；contractRev `5719da4c…` 未变（本任务未改契约）。

## 1. 交付内容

### 1.1 认证（`backend/src/main/java/com/larder/identity/`）

| 文件 | 作用 |
|---|---|
| `SecurityConfig.java` | 单管理员、Cookie session、CSRF 双提交、未登录 401；账号只来自环境变量，缺配置直接启动失败 |
| `SessionController.java` | `POST/GET/DELETE /api/session`；登录成功显式 `saveContext`，登出 `invalidate` session |
| `LoginRequest.java` / `SessionView.java` | 请求/视图 record |
| `CsrfCookieFilter.java` | GET/HEAD 时确保下发 `XSRF-TOKEN`（Spring Security 6/7 token 延迟生成的坑） |

### 1.2 测试（`backend/src/test/java/com/larder/identity/`）

`SessionSecurityTest` 共 **6 个用例**，每个都能在错误实现下失败：

1. 未登录 `GET /api/session` → 401；
2. GET 必须下发 `XSRF-TOKEN`；
3. 不带 CSRF 的登录 POST → 403；
4. 密码错误 → 401 且 `code=NEED_LOGIN`、`retryable=false`；
5. 登录成功后同一 session 能读到 `admin/ADMIN`；
6. 登出后 session 被 `invalidate`，再访问 401。

### 1.3 验收与 CI

- `scripts/accept.py`：六项检查（secrets / backend / runner / frontend / contracts / compose），**零测试或失败即 FAIL**，缺环境（node_modules、docker、uv）也 FAIL 而不是 skip；结果写 `reports/<task>/<runId>/`。
- `scripts/accept.py --self-check`：7 条断言证明“零测试/失败必失败”的判定本身有效。
- `.github/workflows/ci.yml`：backend(mvn verify) / runner(pytest) / web(build) / contracts / accept 五个 job，统一 21 + 24 + 3.12 + pnpm 11.25.0。
- 前端 `frontend/src/features/auth/`：`api.ts`（credentials + CSRF 头 + 401/403 区分）与 `LoginForm.tsx`（idle/submitting/error 三态）。

## 2. 实测结果（accept 汇总）

| 检查 | 结果 | 数字 |
|---|---|---|
| secrets | PASS | 扫描 448 个 Git 跟踪文件，无真实 `.env`/私钥/硬编码凭据 |
| backend_tests | PASS | **6 passed / 0 failed / 0 skipped**（`mvn verify -DfailIfNoTests=true`） |
| runner_tests | PASS | 2 passed / 0 failed / 0 skipped |
| frontend_build | PASS | `tsc --noEmit && vite build` 成功 |
| contracts | PASS | 29 项契约检查 |
| compose_config | PASS | `docker compose config --quiet` |

`RESULT PASS`，退出码 0；`--profile live` 明确返回 **BLOCKED**（未实现，不伪装跑过）。

## 3. 本任务修掉的真实问题

1. **Boot 4 把 `@WebMvcTest` 移出 autoconfigure**：真实包名是 `org.springframework.boot.webmvc.test.autoconfigure`，需要 `spring-boot-starter-webmvc-test`。
2. **启动类上的 `@MapperScan` 污染切片测试**：`@WebMvcTest` 也会执行它，导致没有 `sqlSessionFactory` 时创建 mapper 失败；去掉冗余注解（Mapper 接口本来就有 `@Mapper`）。
3. **accept 自身三个 bug**：秘密扫描误报外部 HTML 文档；pytest 从仓库根跑导致 `ModuleNotFoundError`；Windows 上 `which("pnpm")` 找不到 `.cmd`。
4. **Maven 用例数被重复累加**：Surefire 每类一行 + 汇总一行，取最后一行才是真实数字（6 而不是 12）。
5. **pytest 双重 `-q`** 吞掉 “N passed” 汇总，被误判成零测试。

## 4. 超出 allowlist 的改动（按 CR 记录，需协调者追认）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/pom.xml` | 加 `spring-boot-starter-security`、`spring-security-test`、`spring-boot-starter-webmvc-test`（三个都是认证与其测试的必需依赖） |
| CR-0002 | `backend/src/main/resources/application.yml` | session cookie（HttpOnly/SameSite）+ `larder.identity.*` 配置 |
| CR-0003 | `compose.yaml`、`.env.example`、本地 `.env` | 把 `LARDER_ADMIN_PASSWORD` 传给 api 容器；本地密码随机生成不入库 |
| CR-0004 | `LarderApplication.java` | 去掉与 `@Mapper` 重复的 `@MapperScan` |

## 5. 未验证 / 已知限制

- **跨 owner 拒绝**：当前还没有 owner/item 数据模型（V02 才有），无法验证；本任务只覆盖未登录与 CSRF。
- session 在内存（单实例够用），docs/06 要求的 DB session 等 V02 数据层。
- 前端登录页没有 E2E（无 Playwright）；`frontend/e2e/auth/` 留空未建。
- CI 工作流已提交但**尚未在 GitHub 上实际跑过**（本地 accept 是等价证据，不能算 CI 已通过）。
- `--profile live` 未实现（需要真实模型供应商与预算配置）。

## 6. 下一步

V01 四个任务全部完成。按 `tasks/tasks.json` 的 DAG，接下来是 **R1 剩余部分**：V02（真实内容闭环）、V03（可靠异步）、V04（可信索引）——具体先做哪个由协调者按 [docs/11](D:/Work/Project/larder/docs/11-四轮十二版本与并行DAG.md) 的并行波次决定。
