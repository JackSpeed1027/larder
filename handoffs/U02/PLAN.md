# U02 最小 slice 计划（笔记版本与 CAS 后端）

- 任务：`U02`，sliceId `U02.NOTE_CAS`
- 依据：`contracts/notes.schema.json`、`docs/notes-api-freeze-v1.md`、`docs/22` §6、`docs/24` §4
- 状态：**PLANNED**（本文件只是可执行拆解，未改产品代码）

## 范围（只做这一片）

| 项 | 内容 |
|---|---|
| 迁移 | 新建 `backend/src/main/resources/db/migration/V6__note_revision.sql`（**只增不改**；V1–V5 已应用） |
| 表 | `note` 增加 `version BIGINT NOT NULL DEFAULT 1`、`content_hash CHAR(64) NOT NULL`；新增 `note_revision`（owner_id,id,note_id,revision_no,title,markdown,content_hash,created_at） |
| 回填 | 现存 note 回填 `content_hash=SHA2(markdown,256)`，并各生成 revision_no=1 |
| API | `POST /api/notes` 写 note(version=1)+revision(1)；`PATCH /api/notes/{id}` 必带 `expectedVersion`，CAS 失败返回 `NOTE_VERSION_CONFLICT` |
| 读取 | `GET /api/notes/{id}` 返回 `version`/`revisionId`/`contentHash`；`GET /api/notes/{id}/revisions` 列表 |

## 文件 allowlist

- `backend/src/main/resources/db/migration/V6__note_revision.sql`
- `backend/src/main/java/com/larder/organize/OrganizeMapper.java`
- `backend/src/main/java/com/larder/organize/OrganizeService.java`
- `backend/src/main/java/com/larder/organize/OrganizeController.java`
- `backend/src/test/java/com/larder/organize/OrganizeServiceTest.java`
- `reports/U02/<runId>/`、`handoffs/U02/`

公共契约已由 U00 冻结，本 slice **不再改 contracts**；如需改，先回 U00 走变更。

## 必须的测试（可证伪）

1. 并发 CAS：两个请求同 `expectedVersion` → 一个成功、一个 `NOTE_VERSION_CONFLICT`（真实 MySQL，两个连接）；
2. 每次成功写入都新增一条 `note_revision`，`revision_no` 单调 +1；
3. `content_hash` 等于 sha256(markdown)，改正文后 hash 变化；
4. 来源笔记：`source` 指向不存在/越权 item-revision → `NOTE_SOURCE_GONE`，不静默改绑；
5. 迁移可重复部署：Flyway 重跑不产生新 revision、不重复回填。

## 验收命令

```powershell
docker compose up -d --wait mysql rabbitmq api web
.\backend\mvnw.cmd -B -ntp -f backend\pom.xml verify -DfailIfNoTests=true
python scripts\accept.py --task U02 --profile offline   # 需先在 accept.py 注册 U02 任务
```

证据：`reports/U02/<runId>/`（verification.md + summary + logs）+ `handoffs/U02/`（含文件 hash）。

## 依赖与阻塞

- 依赖：U00（已 READY_FOR_INTEGRATION）、S00（账本/门禁）。
- 硬阻塞：无；但**必须在隔离测试库验证并发**（用 `scripts/dev/assert-isolation.ps1` 断言标识，不复用用户库）。
- 注意：`PATCH` 语义改变会影响现有前端 `NotesPanel`（当前只新建/列表），需在 U03/U04 同步；本 slice 不改前端。
