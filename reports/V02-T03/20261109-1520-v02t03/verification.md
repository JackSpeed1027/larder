# V02-T03 验证报告：真实网页/粘贴链路

- runId：`20261109-1520-v02t03`
- 结论：**TASK_LOCAL_PASS**——accept 六项全过（后端 74 + runner 34 + 发布端到端 4 项）。
- 依赖：V02-T02、V03-T03；contractRev `5719da4c…` 未变。

## 1. 交付代码

### Python（`runner/src/larder_runner/stages/`）

| 文件 | 作用 |
|---|---|
| `fetch.py` | 网页抓取：**每跳 SSRF 校验**（拒绝 loopback/private/link-local/保留地址，含 169.254.169.254）、重定向最多 3 次且每次重新校验、大小上限、内容类型白名单 |
| `extract.py` | HTML → 正文：去 script/style/noscript、标题、实体解码、空白规范化；空正文报 `EMPTY_CONTENT` |
| `chunk.py` | 正文分段：段落优先、超长硬切、偏移可回溯、hash 稳定、空内容报错 |

### Java

| 位置 | 内容 |
|---|---|
| `content/publication/RevisionPublisher` | 一个事务：锁 job → 校验 run/epoch → 存正文 artifact → 写不可变 revision → 绑定 artifact → 移动 item 指针 → job SUCCEEDED |
| `content/publication/PublicationMapper` | 发布 SQL；最终 `commitJob` 带 `run_id + epoch + RUNNING` 条件（fencing） |
| `content/publication/PublicationException` | `EPOCH_SUPERSEDED` / `STALE_RUN` / `CORRUPT_ARTIFACT` 等稳定错误码 |
| `runner/ingest/RunnerResultController` | `POST /internal/runs/{runId}/result`：`X-Internal-Token` 共享令牌校验；令牌未配置直接 503（fail closed） |
| `runner/ingest/RunnerResultRequest` | 回传 DTO（jobId/runId/epoch/text/contentSha256/extractor/chunker/manifest） |

## 2. 关键语义（对应任务卡验收）

| 验收点 | 实现 |
|---|---|
| 真实网页与粘贴各可读 | fetch/extract/chunk 对 HTML 与 text/plain 都产出正文；抽取结果写入 revision，`item.current_revision_id` 指向它 |
| 失败保留输入 | 抓取/抽取失败时 job 不成功、不写 revision；`job.input_json` 与 artifact 孤立清单仍在（V03 对账） |
| 旧 epoch 不能发布 | 发布事务先比 `processing_epoch`，不一致直接 409 EPOCH_SUPERSEDED，且不写任何行 |
| 关闭 AI 链路可用 | 整条链路无模型调用（fetch/extract/chunk/publication 都是本地确定性逻辑） |
| 每跳 SSRF | `_GuardedRedirectHandler` 在每次 302 时调用 guard；guard 解析全部 A/AAAA 记录并逐個判断 |

## 3. 实测结果

```
accept V02-T03 --profile offline → RESULT PASS
  secrets PASS | backend_tests PASS(74) | runner_tests PASS(34)
  contracts PASS(29) | compose_config PASS | publication_e2e PASS
```

runner 34 个用例 = 19（取消/调度/事件/健康）+ 15 新增（fetch 6、extract 3、chunk 5 + 1 边界），
其中 fetch 用例起真实本地 HTTP 服务，验证重定向、类型拒绝、大小上限与 SSRF 守卫。

发布端到端（`scripts/dev/verify-publication.ps1`）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | 回传 200 + revisionId | `rev_…` |
| 2 | 一次事务落库 | revision=1、指针=revision、job=SUCCEEDED、artifact 绑定=1 |
| 3 | 重复回传幂等 | `dedupe=REPLAYED`，revision 仍 1 |
| 4 | 旧 epoch | `409 EPOCH_SUPERSEDED`，revision=0 |

## 4. 修掉的问题

1. **extract 把 `<head>` 整个跳过**，导致标题永远为空 → 从 skip 集合移除 head；
2. **内部接口被 CSRF 拦截**（403）→ `/internal/**` 跳过 CSRF（服务间令牌认证）；
3. **脚本函数名 `New-Item` 覆盖 cmdlet** → 改名 `New-ItemRecord`；
4. **nginx 502**：api 重建后 IP 变化，nginx 启动时解析的上游失效 → 脚本重启 web；
5. 单测里 revisionId 随机生成，stub 写死占位值 → 改 `anyString()`；
6. Docker 守护进程中途停止 → 重启后重跑。

## 5. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/src/main/java/com/larder/identity/SecurityConfig.java` | `/internal/**` permitAll + 跳过 CSRF（共享安全配置） |
| CR-0002 | `backend/src/main/resources/application.yml` | `larder.internal.token` |
| CR-0003 | `compose.yaml`、`.env.example`、本地 `.env` | 注入 `LARDER_INTERNAL_TOKEN` |
| CR-0004 | `compose.dev.yaml` | api 开发端口 8081（8080 被 web 占用） |
| CR-0005 | `scripts/accept.py` | `publication_e2e` + V02-T03 任务 |
| CR-0006 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |

## 6. 未验证 / 已知限制（重要）

- **Runner 还没有 `/v1/runs` 执行入口**：fetch/extract/chunk 目前是库函数 + 测试，未装配成 run 协议（V05）。
- 因此发布端到端是**用 curl 模拟 Runner 回传**，不是 Runner 真实执行；报告已如实标注。
- 只支持 text/html、xhtml、text/plain、markdown；**没有 JS 渲染**，SPA 页面抓不到正文。
- artifact 只存了 `BODY`（纯文本）；`ORIGINAL`（原始 HTML）留待 V06 的产物完整性。
- chunk 只进 manifest JSON，**chunk 表还没写**（V04 索引构建时落库）。
- 内部令牌是共享密钥，未做轮换/多服务分权（V12）。

## 7. 下一步

R1 剩余：**V02-T04（阅读/标签/笔记基础）**、**V03-T04**。建议先 V02-T04，把"读正文 + 标签 + 笔记"闭环做完，
再接 V04 的索引与搜索。
