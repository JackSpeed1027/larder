# 交接资料落位记录（bootstrap layout）

- 时间：2026-11-06 19:41–19:50（Asia/Shanghai）
- 来源：`D:\Work\Project\vibe-project\larder-agent-playbook-2026-11-05`（只读，不移动、不删除、不新建）
- 目标：`D:\Work\Project\larder`（本次新建）
- 规则：**只复制缺失文件**；同名文件先比 SHA256，不同则保留现有并记录，不覆盖；不清理不删除。

## 复制结果

| 项 | 数量 |
|---|---:|
| 复制文件（AGENTS.md + docs/contracts/tasks/prompts/design/scripts） | 122 |
| 与来源同名且内容一致因而跳过的文件 | 0（目标目录原本为空） |
| 同名但内容不同（被保留未覆盖） | 0 |
| 与来源 `SHA256SUMS.txt` 逐文件比对一致 | 122 / 122（0 mismatch） |

复制内容：

```text
AGENTS.md
contracts\  13 个（含 examples\ 6 个）
design\     8 个（合同、tokens、4 张线框、参考板、截图来源）
docs\       20 个专题规范
prompts\    72 个（tasks\ 56 + frontend\ 16）
scripts\    5 个设计包校验/派工脚本
tasks\      3 个机器清单
```

## 按约定留在来源目录、未复制的文件

| 文件 | 原因 |
|---|---|
| `README.md` | 改为项目根 `README.md`（新写）+ `docs/交接资料导航.md`（保留原说明并修正相对链接） |
| `SOURCE-BASELINE.json` | 交接包基线指纹，不是本项目验收证据 |
| `SHA256SUMS.txt` | 来源包自校验清单，不搬进项目充当验证 |
| `DELIVERY-CHECK.json` | 交接包交付自检结果 |
| `reports/design-validation/*`（4 个） | 交接包当时的设计校验报告，非本项目执行结果 |

这 7 个文件不能作为"本项目已通过验收"的证据，本项目证据只在 `reports/` 下按 `<taskId>/<runId>/` 产生。

## 一处来源自身的不一致（如实记录）

来源目录 `README.md` 的当前内容与其自带 `SHA256SUMS.txt` 记录的 hash 不一致（记录值 `446678c2…ad81`，实际 `15aafc12…9f5`，文件 mtime `2026-11-06T19:10:13+08:00`，早于本会话开始时间 19:41）。本会话对来源目录只做读取，未写入该文件；因此这不是本次落位造成的改动。`docs/交接资料导航.md` 以来源目录**实际存在**的 README.md 内容为准（并补了来源说明与链接修正）。

## 复现与校验

落位脚本已归档：`reports/V01-T01/20261106-195106-v01t01/tools/init_project_layout.ps1`（当时在会话工作区执行，事后连同取证脚本一起归档到证据目录）。校验命令与结果：

```powershell
# 对 129 条来源清单逐条比对目标文件
manifest=129 ok=122 missing=7 mismatch=0
# missing 恰为上面"按约定未复制"的 7 个文件
```

## 未创建的目录

`backend/`、`runner/`、`frontend/`、`infra/`、`tests/`、`evals/`、`labs/`、`coordination/` 均未创建：按"目录按开发任务实际需要创建，不提前生成空模块/假实现"的要求，`backend/runner/frontend` 属 V01-T02，`infra/tests/evals/labs/coordination` 分别在 Compose、跨端测试、评测、中间件实验、多窗口派工任务时创建。`reports/` 与 `handoffs/` 因 V01-T01 需要证据与交接而创建。
