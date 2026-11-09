# U00 验证报告：笔记/导出契约冻结（草案）

- runId：`20261109-2300-u00`
- 结论：**TASK_LOCAL_PASS**（契约冻结，非功能实现）
- 本轮只冻结形状与错误码；未改后端实现、未执行迁移、未安装前端依赖。

## 校验项

| 项 | 结果 | 说明 |
|---|---|---|
| NoteCreate <- valid-create-source.json | PASS | accepted |
| NoteCreate <- valid-create-standalone.json | PASS | accepted |
| NotePatch <- valid-patch.json | PASS | accepted |
| NotePatch <- invalid-patch-missing-version.json | PASS | rejected({'markdown': '缺少 expectedVersion'} is not valid under any of) |
| 错误码注册（16 个 v4 码） | PASS | all registered |

## 交付

- `contracts/notes.schema.json`：NoteCreate / NotePatch / NoteDetail / NoteSource（draft 2020-12）
- `contracts/examples/notes/*.json`：2 正例 + 1 正例 patch + 1 反例（缺 expectedVersion）
- `contracts/error-codes.json` v2：新增 16 个笔记/导出/本地文件错误码
- `docs/notes-api-freeze-v1.md`：版本/来源/CAS/软删/限额/迁移计划/未决项

## 未做

- 未实现笔记后端的 revision/CAS（U02）
- 未执行 `V6__note_revision.sql`（U02 负责，已应用迁移只增不改）
- 未安装 CodeMirror/shadcn/TanStack/IndexedDB（U01 spike）
- 未做导出/本地写入（U07–U11）
