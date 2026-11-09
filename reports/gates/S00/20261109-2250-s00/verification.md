# S00 基线与隔离护栏

- runId：20261109-2250-s00
- 基线：commit 10d49e2（工作区有 38 个未提交项，含 v4 规格文件；本任务未改产品代码）
- 账本：tasks/execution-state-v4.json（79 项 = 48 核心任务 + 23 项 S/U 增量，另含复核标记）
- 契约 hash：11fece5379a5…（算法：contracts/ 全部文件按路径+字节排序后 sha256）
- 版本锁 hash：docs/versions.lock.json sha256

## 本轮交付

| 文件 | 说明 |
|---|---|
| tasks/execution-state-v4.json | 单一执行账本：taskId/status/owner/baseSnapshot/contractRev/evidence/verifiedAt/blockers/dependsOn/source |
| scripts/dev/assert-isolation.ps1 | 隔离护栏：project/db/vhost/bucket 必须带 larder-it/it- 标识；-Selftest 纯离线自检 |
| reports/gates/S00/20261109-2250-s00/environment.txt | HEAD、dirty 清单、契约/版本锁/DDL hash、任务计数 |
| handoffs/S00/ | 交接与文件 hash |

## 状态口径（按 v4）

- 48 个核心任务：有历史报告的置 VERIFYING（历史证据存在，但**不是 v4 门禁证据**），无报告置 PLANNED；
- 23 个新任务：S00 IN_PROGRESS，其余 PLANNED；
- 任何项标 DONE 必须由协调者在集成树验证后写入并附 evidence/verifiedAt。

## 验证

- ssert-isolation.ps1 -Selftest 当前 **exit=1**：合规样例被判定为不合规（回归），详见 logs/assert-isolation.log；
- 因此本任务只能标 **TASK_LOCAL_FAIL**，脚本本身尚不可用于故障注入门禁；
- 账本 JSON 生成与 status 枚举校验通过（生成器 assert 通过）。

## 原验证（已失效，保留以示对照）

- ssert-isolation.ps1 -Selftest：合规样例通过、不合规样例被拒（见 logs/assert-isolation.log）；
- 账本 JSON 由生成器校验 status 枚举，非法值直接 assert 失败。

## 未做

- 未运行任何故障注入（属于 S01–S06，且必须在隔离 project 中执行）；
- 未修改迁移、未安装依赖、未改用户文档。


## v4 账本校验

- `python scripts/validate_ledger_v4.py` → exit 0，输出 `LEDGER OK: 79 tasks, contractRev=11fece5379a5…`；
- 校验内容：任务集合与 tasks.json + supplement-v4.json 一致、必填字段、状态枚举、DONE 必须有 verifiedAt/evidence 且路径存在、BLOCKED 必须有 blockers、contractRev 与 contracts/ 当前内容一致；
- 当前状态分布：PLANNED=66、VERIFYING=13（核心任务有历史报告，等待 v4 重新核验）。
