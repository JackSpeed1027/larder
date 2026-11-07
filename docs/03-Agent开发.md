# 03 Agent / Runner 开发

## 1. 能力、进程与模块

Runner保留完整五条主线：持久运行、工具治理、跨会话记忆、上下文压缩和评测。内容流水线与真正的工具Agent分开实现，共用运行时。

| 模式 | 工作方式 |
|---|---|
| INGEST | 固定抓取/抽取/转写/分段，不让模型决定是否绕过输入限制 |
| ENRICH | 固定摘要/关键句/标签/卡片生成，结构化结果和引用校验 |
| ASSIST | 模型决定搜索、读原文、召回记忆、提出写入建议；有限循环 |
| EMBED | 文档或query向量生成；统一预算和调用记录 |

Python3.12/FastAPI/Uvicorn；单实例、单HTTP worker，有界run队列。解析在有资源限制的子进程执行；子进程不能直接写journal。默认同时运行2个run，单run只读工具最多4个并发，可配置并由压测确定。无GPU假设；ASR可选配置，不影响网页与文本处理。

```text
runner/src/larder_runner/
  api/           runs.py events.py artifacts.py embeddings.py
  runtime/       engine.py scheduler.py cancellation.py recovery.py
  transcript/    writer.py records.py segments.py replay.py checkpoint.py
  stages/        fetch.py extract.py pdf.py transcribe.py chunk.py enrich.py
  tools/         registry.py executor.py schemas.py policy.py results.py
  agent/         loop.py context.py proposals.py citations.py
  memory/        client.py retrieval.py injection.py
  compression/   budget.py facts.py compressor.py verifier.py
  providers/     model.py embeddings.py transcription.py capabilities.py
  evals/         datasets.py verifier.py metrics.py trace_export.py
```

FastAPI BackgroundTasks不能作为可靠队列。POST先持久创建run再返回202；启动扫描有效journal恢复待执行run。只有一个writer actor为每个run串行分配seq，进程级文件锁阻止同一卷第二个writer。多副本Runner不在当前支持范围，不能简单增加worker数。

## 2. JSONL权威日志与Checkpoint

### 2.1 记录、提交与派生状态

保留JSONL为权威事实源。内存状态、查询索引和Checkpoint都可从有效日志前缀重建；不引入SQLite作为另一份权威。

```text
data/runs/<runId>/
  journal/000001.jsonl, 000002.jsonl, ...
  artifacts/<immutable-id>
  checkpoints/<seq>-<hash>.json
  diagnostics/                 # 恢复和损坏报告，不覆盖原记录
```

每条记录有protocolVersion、runId、seq、eventId、type、time、prevHash、payload、recordHash。hash覆盖除recordHash外的规范化JSON字节，规范化实现固定并提供golden样例；初始prevHash为64个0。writer严格追加完整JSON+换行，flush/fsync后才能更新可见状态或发送SSE。不得以写入语言缓冲区为“已经持久化”。

v1规范化固定为Python3.12 `json.dumps(record_without_recordHash, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')`，取SHA-256；换行不参与hash。它是本协议的序列化约定，不宣称是通用跨语言JCS；其它语言实现必须通过同一golden样例和边界样例。避免日志内存浮点业务事实，金额用十进制字符串、token用整数；原始模型响应保存为产物。

产物先写同文件系统临时文件，校验后flush/fsync并移动到不可变最终名，必要时同步父目录；然后追加单条`stage.committed`，其中同时引用输入hash、产物清单和下一状态。此记录是阶段的逻辑提交点。文件和日志不是物理同事务：崩溃可能留孤立文件，但不能把未提交文件当成功结果。

工具结果也先持久产物，再写`tool.completed`；外部请求前写`call.started`并fsync，结果及usage保存后写`call.completed`。大结果不塞入每一行日志，保存artifactId、hash、字节数和脱敏摘要。记录可见工具决策与结果，不要求或保存模型隐藏推理。

### 2.2 Checkpoint与恢复算法

Checkpoint包含`throughSeq/throughHash`、run状态、阶段状态、已完成call、当前context版本、已引用artifact与预算账本。先持久不可变checkpoint文件，再追加`checkpoint.committed`引用它。只有被有效日志记录引用且hash正确的checkpoint可用于恢复。

恢复步骤：

1. 获取run写锁，按段号读journal，逐条校验换行、schema、seq、prevHash和recordHash。
2. 最后段仅尾部不完整记录可视为未提交尾写；保留该文件原样，记录诊断，下一段以有效前缀的最后hash继续，并记录恢复边界。不得静默截断原文件。中间段损坏、完整行hash错误、分叉或序号冲突必须停止并报告。
3. 找最大有效checkpoint；没有则从run.created重放。验证checkpoint所有产物hash，损坏则尝试更早有效快照；若已提交事实引用的产物缺失且无有效副本，报CORRUPT_ARTIFACT而非假造内容。
4. 从throughSeq之后重放，重建已完成阶段、在途调用、审批等待和context版本。已持久完成阶段直接复用。
5. 对Java业务写调用按actionId查询结果；对不确定供应商调用进入UNKNOWN；未开始的安全阶段按预算继续。

跨段记录格式、hash计算和损坏判定在V05先写golden tests，再写调度器。日志hash链发现意外损坏，不代表攻击者无法篡改；磁盘/文件系统承诺以实际部署和故障测试为界。

| 崩溃点 | 预期 |
|---|---|
| run.created持久后响应前 | 同runId重交返回已有run |
| artifact落盘后stage提交前 | 尚未发布；恢复可校验采用，否则留诊断 |
| stage提交后SSE前 | 重放事件，阶段不重做 |
| checkpoint文件写好但未记提交 | 忽略此未引用快照，使用旧快照+日志 |
| 压缩提交后下一模型调用前 | 从已提交context继续，不重新压缩 |
| 模型已接收但结果未落盘 | UNKNOWN，不自动声称未收费 |
| Java执行写工具后响应丢失 | 查询actionId得到原结果，禁止重复变更 |

## 3. 外部调用、预算与结果未知

call状态：PLANNED→IN_FLIGHT→SUCCEEDED/FAILED_DEFINITE/UNKNOWN。日志写入与供应商执行之间没有共同事务；call.started存在不证明一定发送，响应丢失也不证明未执行。

provider能力表显式声明是否支持幂等键、结果查询和取消，默认false；“兼容某API”不等于继承这些语义。已持久成功call重用；UNKNOWN优先按requestId查询供应商，没有能力则run进入NEEDS_DECISION。用户选择重试后记录decisionId，创建新attempt，可能再次计费；保留旧记录和保守预算。

Java给run预留预算，Runner每次调用前核对剩余tokens、调用数、工具数、总deadline和用户scope；对模型请求中的system、tools、用户问题、历史、memory、evidence和输出预留一起计数。模型tokenizer不可用时记录保守估算方法，实际usage另算。禁止无限JSON修复和重试；结构修复最多一次且计预算。

初始ASSIST限制：模型轮次8、工具总数16、只读并发4、总时长180秒；单run最大token由模型窗口与用户预算取较小值。它们是设计参数，不是性能成果。达到上限给有限证据结果或明确失败，不自行提升配额。

maxSeconds和上述180秒按累计活跃执行时间计算，包含模型/工具I/O但不含WAITING_APPROVAL或NEEDS_DECISION的人类等待；等待仍受独立expiresAt约束，默认最多24小时。恢复时累计已用执行时间，不重置预算。批准过期或run取消后必须创建新的明确请求，不能无限续命旧批准。

## 4. 工具注册、并发和结果治理

每个工具定义name、version、input/output schema、READ/PROPOSE/APPLY类别、scope、timeout、maxBytes、幂等语义和缓存规则。工具实际执行代码不可由模型生成。

| 工具 | 类别 | 授权与结果 |
|---|---|---|
| search_library | READ | 按run owner/scope搜索已发布generation，返回带版本候选 |
| read_chunks / get_neighbors | READ | 只读已授予的item/revision/chunk；Java复核未删除与归属 |
| recall_memory | READ | 召回候选后按MySQL ACTIVE/version复核，返回memory引用 |
| propose_tags / propose_collection_change / propose_cards | PROPOSE | 写入待确认建议，不直接改变标签、集合和复习队列 |
| propose_memory | PROPOSE | 明确展示要记住的文本、scope和来源，用户可编辑拒绝 |
| apply_approved_action | APPLY | 只允许执行已有批准，实际在Java单事务内完成 |

调用前验证schema、scope、版本、预算；并行仅限独立READ。同run、同toolVersion、同规范参数和同sourceVersion的已完成结果可复用；活动调用可以合并等待，不复用失败或UNKNOWN为成功。结果按callId归位，禁止按返回先后配对模型tool call。

超出32KiB的工具结果落artifact并返回有上限摘要/引用；摘要不可替代事实来源。工具类型决定可裁剪字段；裁剪必须标记truncated。模型不得通过工具访问任意URL、Shell、SQL或文件路径。任意网页抓取是受安全策略控制的INGEST能力，不暴露为ASSIST随意上网工具。

## 5. 人工审批与副作用

审批不是一个可复用的approvalId字符串。Java记录proposal：ownerId、runId、actionType、targetIds、expectedVersions、canonicalPayloadHash、expiresAt、status。用户界面展示准确差异；编辑后产生新hash并使旧批准失效。

审批状态PENDING→APPROVED/REJECTED/EXPIRED；执行APPROVED→APPLIED或STALE。apply由Java事务完成：先按actionId查已有结果→锁approval及目标→复核用户、run、作用域、hash、有效期、目标版本和撤销状态→执行业务修改→记APPLIED/result/audit同事务。已APPLIED的重交返回原结果，不产生第二次效果。未执行时过期、目标更新或取消均拒绝。

Runner进入WAITING_APPROVAL时释放昂贵执行槽，但保留持久run。Java只发送“可继续”的通知，Runner必须重新读取审批权威状态，不能相信事件payload就写库。浏览器刷新、重复批准、Runner重启都不改变审批绑定。普通标签/卡片等用户直接点按钮的操作是用户API，不必伪装成Agent审批。

不批准任何删除用户原件、运行代码、发送邮件、发布外部消息的工具。本项目的WRITE效果限定在用户可预览、可撤销的库内组织和建议应用。

## 6. 跨会话长期记忆

记忆有真实业务用途：用户长期学习目标、某专题术语约定、期望比较维度、明确纠正过的解释偏好。简单语言/摘要长度仍可作为设置；不能只实现三个配置字段就宣称长期语义记忆完成。

MySQL为记忆权威，字段包含owner、scope、key、value、source、confidence、version、status、supersedesId、createdAt、revokedAt。状态ACTIVE/SUPERSEDED/TOMBSTONED；同owner/scope/key只能有一个当前ACTIVE版本，使用memory_slot行锁与指针实现，不依赖MySQL不存在的通用部分唯一索引。

写入必须显式确认。新版本与旧版本替代、slot指针与Outbox同事务；撤销写墓碑并使active指针失效，保留审计。语义索引和Redis可滞后，但注入前必须从MySQL核对候选ID、版本、status、owner和scope。

召回：明确scope/关键槽位→关键词或embedding候选→权威复核→相关性/新鲜度排序→token预算裁剪→记录实际注入的memoryId/version/hash。默认不跨个人库scope；source是外部正文时仍是不可信资料，不得提升为系统指令。来源不足、互相矛盾的记忆提示用户处理，不由模型隐式覆盖。

**撤销语义必须精确**：Java在每次`authorize-model-call`事务中核验memory refs并发放一次性call grant，grant记录policyEpoch和记忆版本。撤销事务之后获得授权的新调用不能包含被撤销记忆；此前已获授权或已发到供应商的请求可能仍在途，不能声称可远程抹除。撤销后使下一轮重新组装context，并可取消受影响的活动run。恢复不能原封不动复用旧context继续调用模型，必须重新取得grant。

验收覆盖：新Session召回有效记忆；相同key替代只注入新值；索引/Redis残留被权威过滤；撤销与调用授权竞争；重启后不从Checkpoint复活墓碑；真实模型请求trace中能核对实际注入集合。次数与样本量来自实测，不预填“8次”。

## 7. 上下文预算与安全压缩

ContextManager维护system/tool schema、当前目标、近期交互、证据引用、记忆引用、历史工具结果与剩余预算。原文是外部数据，不作为新增系统指令。超窗口前触发压缩，预留输出和token估算误差。

压缩生成两部分：

- Key Fact Snapshot：用户目标、已完成/待完成步骤、关键实体/数字/否定/条件、未解决冲突、引用ID、审批和工具结果引用。
- LLM Summary：可读概述，不能覆盖原始证据或引入新授权；每个事实项必须能指到有效来源。

流程：冻结context输入版本→生成候选snapshot/summary→规则校验引用/数值/预算，必要人工样本评审→两个不可变产物落盘→追加一条`context.committed`，同时绑定两产物hash、throughSeq和新contextVersion→后续调用使用该版本。Checkpoint在此之后生成；不把“同时写两个文件”叫事务。

生成或提交失败继续使用上一有效context；旧context若已超预算就暂停缩小范围，不能强行发超限请求。压缩过程中出现记忆撤销或权限变化时，下一次call grant再次校验并剔除失效引用。原始transcript和正文不因压缩删除。

效果用同一语料比较完整输入、简单截断、结构化快照+摘要；记录完整请求token、人工关键事实保留、引用支持率和继续任务成功率。不能将token缩减单独当质量改善，也不能预先承诺保留95%事实。

## 8. 内容处理、引用与安全输入

网页抓取允许HTTP/HTTPS、明确域/端口规则，默认80/443；拒绝内嵌凭据、回环/私网/链路本地/IPv6私有/云元数据。逐跳检查重定向与DNS，实际连接固定到已验证IP并保持正确Host/SNI，防止先检查后重新解析绕过。默认不继承环境代理；内部Java下载客户端与外网抓取客户端分开。

设计初值：网页解压后10MiB、总20秒、最多3次redirect；PDF25MiB/200页/60秒解析；音频上限与ASR提供商在V06锁定，默认不开本地大模型。所有限额在读取流中执行，不只信Content-Length。PDF扫描件返回OCR_UNSUPPORTED；不伪造质量小数。字幕时间和PDF页码必须来自源解析，不能由模型猜。

输出通过Pydantic→引用归属→quote规范化子串→页码/时间有效→事实支持评审。引用结构包含itemId/revisionId/chunkId及可选page/time。标签/卡片引用绑定源版本；结论和模型推测分开。引用能定位仍可能断章取义，Benchmark必须人工抽检支持关系。

## 9. Benchmark、Verifier与消融

保留八类任务：网页摘要、长文事实抽取、字幕/转录章节、标签建议、四型卡片、引用问答、记忆生命周期、长上下文续跑。工具安全/审批和故障恢复为横切测试，不挤进质量评分当成自然语言正确率。

Verifier独立于被测Agent：确定性规则检查schema、引用、权限、数值来源与状态；人工rubric检查可答性、证据支持、完整性和有害遗漏；LLM judge仅辅助筛选，分歧保留并人工复核。摘要的正确答案不由同一个生成结果自证。

记录dataset/version、代码commit、promptHash、模型/参数、tokenizer、输入hash、tool/call轨迹、usage、耗时、预算终止和逐样本结论。离线stub测试和收费真实评测分开。固定配置并不保证真实模型两次分数完全相同，应报告多次结果的变化。

消融：无记忆/有记忆；无压缩/简单截断/结构化压缩；串行/有界并行；直接RAG/工具Agent；有/无引用校验。并发实验固定同一调用集合避免规划差异，质量实验另测。若复杂机制没有收益，应报告原因并调整实现，不编造提升。

## 10. 不做什么

不做通用插件平台、任意Shell或文件系统工具、多Agent协商框架、自训练、自动隐式记忆写入、无限循环、多副本Runner或绝对一次外部计费。不能用这些边界作为删除本文件五条核心主线的理由。
