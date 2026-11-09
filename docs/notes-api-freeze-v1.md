# 笔记/导出契约冻结 v1（U00）

- 状态：**DESIGN FROZEN（草案）**；`contracts/notes.schema.json` 与 `contracts/error-codes.json` v2 为准。
- 不代表后端已实现、前端已接入；实现分别由 U02（后端）与 U03–U09（前端/导出）承担。

## 1. 模型决定

| 决定 | 取值 |
|---|---|
| 幂等键 | `POST /api/notes`、`PATCH /api/notes/{id}` 必须带 `Idempotency-Key`（8..128） |
| 版本 | `version` 为**十进制字符串**（BIGINT，避免 JS 精度）；`PATCH` 必须带 `expectedVersion` |
| 不可变版本 | 每次成功写入生成 `note_revision`（`nrev_*`），`contentHash` = sha256(markdown) |
| 来源 | `source` 可空；非空时必须校验 `itemId+revisionId` 同 owner 且 revision 属于该 item；**不得静默改绑** |
| 软删除 | `deletedAt` 非空表示删除；正文与 revision 保留；列表默认排除 |
| 正文上限 | markdown ≤ 1 MiB；标题 ≤ 256 字符 |
| 附件 | 首版导出上限：≤50 个、单个 ≤20 MiB、累计 ≤100 MiB（U10 实现） |

## 2. 路由（冻结形状，实现前不改）

| 路由 | 语义 |
|---|---|
| `POST /api/notes` | 201 + NoteDetail；同 key 同 body 重放返回同资源（404 语义不适用） |
| `GET /api/notes` | `sourceFilter`、`titleQuery`、`cursor`、`limit<=50`；游标绑定筛选/排序 |
| `GET /api/notes/{id}` | 当前 NoteDetail；404 统一“不存在或不可见” |
| `PATCH /api/notes/{id}` | 200 + 最新 NoteDetail；缺字段不改，`null` 仅允许显式可空字段 |
| `GET /api/notes/{id}/revisions` | 固定版本列表（正文按需取） |

## 3. 错误码（已注册 v2）

`NOTE_VERSION_CONFLICT`、`NOTE_SOURCE_GONE`、`NOTE_TOO_LARGE`、`IDEMPOTENCY_IN_PROGRESS`、
`IDEMPOTENCY_PAYLOAD_MISMATCH`、`VERSION_CONFLICT`，导出侧：
`EXPORT_SOURCE_CHANGED_OR_GONE`、`EXPORT_ATTACHMENT_MISSING`、`EXPORT_TOO_LARGE`、
`EXPORT_VERIFY_FAILED`、`EXPORT_RECEIPT_FAILED`、`LOCAL_FS_*`。

## 4. 迁移计划（未执行）

- 新迁移 `V6__note_revision.sql`：新增 `note_revision`、`note.version`（BIGINT，默认 0/1 待 U02 定）；
- 现有 `note` 行按 `contentHash` 回填一条 revision；
- **已应用迁移只增不改**；U02 负责迁移 + 并发测试，不在 U00 执行。

## 5. 未决（需 U02 前确认）

1. `version` 初始值（0 还是 1）与 `revisionId` 生成规则；
2. `GET /api/notes` 游标格式（不透明 base64 还是 `updatedAt+id`）；
3. 附件与正文的引用模型（U10 前冻结）。
