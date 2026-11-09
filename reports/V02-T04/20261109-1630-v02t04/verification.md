# V02-T04 验证报告：阅读/标签/笔记基础（后端 slice）

- runId：`20261109-1630-v02t04`
- 结论：**TASK_LOCAL_PASS**（后端 slice）——accept 六项全过（后端 84 + runner 34 + 组织端到端 8 项）。
- 依赖：V02-T03；contractRev `5719da4c…` 未变。

## 1. 交付代码（`backend/.../organize`）

| 文件 | 内容 |
|---|---|
| `V5__organize.sql` | 从契约抽取 `chunk / tag / item_tag / collection / collection_item / note / reading_position`（chunk 先建，note 外键依赖它） |
| `OrganizeMapper` | 列表/详情/固定 revision/正文产物/标签/笔记/集合/阅读位置 SQL，全部带 owner 条件 |
| `OrganizeService` | 业务规则：固定 revision、正文 sha256 校验、归属与参数校验、页码收敛 |
| `OrganizeController` | `GET/PATCH /api/items`、`GET /api/items/{id}`、`GET /api/items/{id}/revisions/{revId}`、标签/笔记/集合/阅读位置路由 |

## 2. 关键语义

| 规则 | 实现 |
|---|---|
| 阅读引用固定 revision | 读接口必须带 `revisionId`；不提供“读最新” |
| 不伪造正文 | 正文从对象存储读，`sha256(bytes)` 必须等于 `artifact.sha256`，否则 500 `CORRUPT_ARTIFACT` |
| 归属校验 | 所有 SQL 带 `owner_id`；不存在的条目统一 404 |
| 标签唯一 | `normalized_name` 唯一；重复创建返回已有标签（幂等） |
| 笔记绑定版本 | `revisionId` 不属于该条目 → 400；空内容 → 400 |
| 阅读位置 | 按 `(owner,item,revision)` upsert，`progress` 必须在 0..1 |

## 3. 实测结果

```
accept V02-T04 --profile offline → RESULT PASS
  secrets | backend_tests(84) | runner_tests(34) | contracts(29) | compose_config | organize_e2e
```

真实端到端（`scripts/dev/verify-organize.ps1`，8 项）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | 收录并发布 revision | `itm_… / rev_…` |
| 2 | 列表包含新条目 | total=21 |
| 3 | 详情指针 | currentRevisionId 一致 |
| 4 | 阅读正文 | 文本与 sha 均一致 |
| 5 | 阅读状态过滤 | `READING` 命中 |
| 6 | 标签过滤 | 命中且只 1 条 |
| 7 | 笔记 + 空笔记 400 | 1 条笔记，空内容 400 |
| 8 | 阅读位置 | `progress=0.42` |

## 4. 修掉的问题

1. 端到端脚本 POST 收录忘了 `Idempotency-Key` → 400；
2. PowerShell 数组里 `'a' + [guid]…` 被拆成多个 curl 参数 → 用括号包住表达式；
3. Docker 守护进程中途停止 → 重启后重跑。

## 5. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `V5__organize.sql` | 迁移属共享资源（含 chunk，因 note 外键依赖） |
| CR-0002 | `scripts/accept.py` | `organize_e2e` + V02-T04 任务 |
| CR-0003 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |

## 6. 未验证 / 已知限制

- **前端（library/capture/reader/organization）未做**：本 slice 只做后端；F01/F02/F03/F15 页面仍缺。
- 集合只做了“列出/创建/加条目/列条目”，没有归档、排序、批量操作。
- 笔记只存 Markdown 原文，**没有渲染/消毒**（前端展示前必须安全渲染）。
- `chunk` 表已建但 **0 行**：chunk 持久化在 V04 索引构建时写。
- 列表是 offset 分页（不是游标），大库性能后续优化。
- 没有富文本/原文对照/移动端适配（前端任务）。

## 7. 下一步

R1 只剩 **V03-T04**。完成后进入 R1 集成验证与 R2（V04 索引/搜索）。
