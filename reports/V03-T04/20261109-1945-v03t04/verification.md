# V03-T04 验证报告：取消/重试/故障矩阵（后端 slice）

- runId：`20261109-1945-v03t04`
- 结论：**TASK_LOCAL_PASS**（后端 slice）——accept 五项全过（后端 90 用例 + 任务中心端到端 6 项）。
- 依赖：V03-T03、V02-T03；contractRev `5719da4c…` 未变。

## 1. 交付代码（`pipeline/control`）

| 文件 | 内容 |
|---|---|
| `JobControlMapper` | 取消（区分 QUEUED/RUNNING）、重试（仅 FAILED/CANCELLED）、列表/详情 |
| `JobControlService` | 归属校验、状态机规则、重试写 outbox 重新派发 |
| `JobControlController` | `GET /api/jobs`、`GET /api/jobs/{id}`、`POST /{id}/cancel`、`POST /{id}/retry` |
| 发布侧修改（CR） | `PublicationMapper.commitJob` 增加 `cancel_requested_at IS NULL`；迟到产物 → 409 `CANCELLED_CONFLICT` |

## 2. 取消语义（A07）

| 状态 | 取消行为 |
|---|---|
| QUEUED / RETRY_WAIT | 直接 `CANCELLED`，清租约 |
| RUNNING | 只写 `cancel_requested_at`；执行者在检查点退出；**即使不退出，发布事务也会拒绝** |
| SUCCEEDED / FAILED / CANCELLED / SUPERSEDED | 409 `CANCEL_NOT_ALLOWED` |

## 3. A02–A07 证据映射

| 编号 | 场景 | 证据 |
|---|---|---|
| A02 | DB 已提交但 MQ 不可达 | `verify-outbox.ps1`：broker 停掉时事件保留、恢复后补投（attempt=4 才 SENT） |
| A03 | confirm 丢失/消息重投 | `verify-inbox.ps1`：同 event_id 重发不重复接管；`verify-publication.ps1`：重放 `REPLAYED` 不重复写 |
| A04 | 接管前后/ack 前后崩溃 | `inbox` 与 job 接管同事务（单测）+ `verify-lease.ps1`：过期租约收回并重派 |
| A05 | lease 过期 A 被 B 接管，A 晚返回 | `JobLeaseServiceTest`：token/epoch/runId 不匹配 → 拒绝；`verify-lease.ps1`：token 1→2 |
| A06 | 两次重抓，旧 job 后完成 | `verify-publication.ps1`：旧 epoch → 409 `EPOCH_SUPERSEDED`，revision=0 |
| A07 | 取消与完成竞争 | **本任务** `verify-control.ps1`：取消后迟到产物 409 `CANCELLED_CONFLICT`，revision=0 |

## 4. 实测结果

```
accept V03-T04 --profile offline → RESULT PASS
  secrets | backend_tests(90) | contracts(29) | compose_config | control_e2e
```

真实端到端（`scripts/dev/verify-control.ps1`，6 项）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | api 就绪 | UP |
| 2 | 登录 | 200 |
| 3 | 收录进入 RUNNING | RUNNING + runId |
| 4 | 任务中心列表/详情 | total=1 |
| 5 | 取消 RUNNING | `cancelRequestedAt` 已写 |
| 6 | 取消后发布 | **409 CANCELLED_CONFLICT，revision=0** |
| 7 | 重试失败任务 | `attempt=3 QUEUED`，outbox 1→2 |

## 5. 修掉的问题

1. 单测里 `findJob` 连续 stub 覆盖前值 → 改为 `thenReturn(a, b)` 序列；
2. 端到端脚本沿用 `Idempotency-Key` 与括号表达式修正（前一轮遗留模板）。

## 6. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `content/publication/{PublicationMapper,RevisionPublisher,JobPublishRow,PublicationException}` | A07 需要发布事务检查取消标志（V02-T03 的文件） |
| CR-0002 | `scripts/accept.py` | `control_e2e` + V03-T04 |
| CR-0003 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |

## 7. 未验证 / 已知限制

- **前端 jobs 页面未做**（allowlist 含 `frontend/src/features/jobs`，本 slice 只做后端）。
- 取消不会向 Runner 发取消信号（Runner 没有 `/v1/runs` 与取消通道，V05）。
- “删除任务/条目”未实现（A07 的 delete 侧）。
- 故障矩阵是**已有端到端脚本 + 单测的组合证据**，不是独立的故障注入框架；`verify-*.ps1` 用停/起 RabbitMQ 等方式模拟。
- 重试只重置状态与派发，不清理历史产物（旧 revision 保留，符合“旧正文历史保留”）。

## 8. R1 收尾

R1 的 12 个任务全部完成（V02-T04、V03-T04 均为后端 slice，前端页面待补）。下一步建议：
**R1 集成验证**（跑全量 accept + 全部 verify 脚本 + 更新全量报告），然后进入 R2（V04 索引与搜索）。
