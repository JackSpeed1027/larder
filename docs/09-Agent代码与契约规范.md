# 09 Agent代码与契约规范｜强制执行

适用：所有编码窗口。目标：不同Agent产出的代码能在同一仓库构建、测试、集成和维护。本文规定实现标准，不授权提交、发布或删除用户数据。

## 1. 开工协议

执行前读取AGENTS、当前任务卡、04对应契约、本文和现有目标代码。报告BASELINE、TASK_ID、CONTRACT_REV、允许写路径、依赖证据和验证命令。未知字段先查契约，不能从旧对话或截图推断。

只修改派工单内的路径。共享文件依照12的单写者规则；必要修改通过CHANGE_REQUEST交给协调Agent。不可因缺一个字段就自行加另一套接口、状态或配置名。

契约、代码和测试不一致时停止该项依赖工作，提交准确差异；可以继续不受影响的独立部分。禁止把未知语义猜成“合理默认”后扩散到三端。

## 2. 全仓统一词汇和命名

| 项 | 统一约定 | 禁止 |
|---|---|---|
| 产品 | Larder，中文拾藏 | 每个子工程另起产品名 |
| Java包 | `com.larder.<业务包>` | 一份模块用com.example，另一份用org.larder |
| Python包 | `larder_runner`，src布局 | 临时sys.path黑魔法和跨包相对路径拼装 |
| JSON/API字段 | lowerCamelCase，如runId、revisionId、expectedVersion | Python直接暴露snake_case，前端自行猜映射 |
| 数据库列 | snake_case；表单数语义沿04 | ORM属性名与数据库列隐式猜测 |
| 状态/错误 | UPPER_SNAKE_CASE，集中定义 | 字符串“success”“done”“finished”各自代表同一状态 |
| 文件 | Java类PascalCase；Python模块snake_case；React组件PascalCase | 同业务出现三种命名格式 |
| 时间 | API UTC ISO8601；DB UTC；人类展示转owner时区 | 无时区字符串、拿客户端时间决定租约/评分 |
| 金额 | 十进制字符串或固定精度Decimal/BigDecimal，带币种 | float/double累计金额 |
| ID | 字符串，不透明，契约长度限制 | Java long在浏览器丢精度；从ID推断权限 |
| 版本 | 明确rowVersion、processingEpoch、leaseToken、generationId | 一个version字段同时承担全部并发语义 |
| hash | SHA-256小写hex；内容和请求hash区分 | multipart ETag冒充内容hash |

空值规则：未知/缺失用契约允许的null或省略；空集合用[]；不得用空字符串表示缺失ID。PATCH省略表示不改，清空字段必须契约允许显式null；PUT表示完整替换指定资源字段，不自行混用。

分页默认20、最大50；排序加稳定ID作为并列顺序，游标绑定过滤和排序版本。API请求有体积、列表长度、字符串长度、期限上限。普通对象字段不默认开放任意扩展。

## 3. Java实现标准

### 3.1 分层与依赖

api：Controller、请求响应DTO、参数验证；application：事务与用例编排；domain：纯状态、值对象、调度算法；infrastructure：Mapper、MQ、S3、Meili、Redis、HTTP。

Controller禁止直接访问Mapper、MinIO或Rabbit客户端。domain禁止依赖HTTP、Spring容器和供应商SDK。跨业务模块调用application接口，不直接写其他模块表。接口用于外部边界、可替换适配或明确测试接缝，不给每个单实现类机械生成IService。

构造器注入，字段不可变优先；禁止静态ServiceLocator。DTO可用record，持久实体不直接作为公开响应。实体、外部SDK模型、公开DTO分别映射，防止新增内部字段意外泄露。

### 3.2 事务、SQL、并发

事务边界在应用服务，网络I/O不得放在长事务中。关键状态更新明确WHERE前置条件，校验影响行数；0行表示冲突/失去租约，不能忽略。

Outbox与业务同事务；Inbox与持久接管同事务；approval应用与业务效果及action_result同事务。不可用捕获异常后返回成功的方式“保证流程继续”。死锁/乐观冲突重试只包住明确幂等的事务段，不把未知外部模型调用放进自动重试。

业务SQL参数化；禁止`${}`插值接受用户字段。动态排序列通过白名单映射；批量SQL有上限。避免SELECT *和循环N+1，读取大型正文分页/流式。性能优化必须有执行计划和前后结果，禁止凭感觉添加多个重叠索引。

Flyway编号由协调Agent登记，不按窗口各自从V2开始。已应用迁移不可改；数据库结构的冻结和变更方式见12。测试SQL不能覆盖真实库。

### 3.3 错误、异步、资源

业务异常携带集中ErrorCode与安全message，HTTP按04映射；不把所有失败返回200。保留cause用于内部诊断，但不把SQL、凭据和完整堆栈返回浏览器。

线程池显式命名、容量和拒绝策略；禁止无界队列、每请求创建线程或默认公共池承载所有阻塞IO。虚拟线程可使用，但不能代替连接池、并发和供应商限流；先通过版本兼容实验。

HTTP统一connect/read/total deadline与重试策略，关闭响应体和流。Clock注入，测试不Thread.sleep等待真实数十秒；并发故障测试用受控barrier/latch与明确超时。

## 4. Python实现标准

使用类型注解和Pydantic边界模型，业务内不要到处传无结构dict。外部JSON使用lowerCamelCase alias，Python内部snake_case；输入未知字段拒绝，输出schema按冻结契约。配置启动校验，不能运行到一半才发现Key或路径缺失。

async路由不直接执行CPU密集PDF解析或同步阻塞网络；httpx客户端复用并设连接上限。CPU任务进入受限子进程，进程池有上限和超时。只由runtime父进程writer提交journal，子进程返回产物描述。

使用明确异常类型与错误分类，禁止裸except后pass；清理异常不能遮盖原始错误。Cancellation不得吞掉；取消后不能开始新阶段或发布结果。所有重试限attempt、deadline和预算，默认不会自动重复UNKNOWN供应商请求。

不修改全局cwd来处理run文件；路径基于配置的绝对根目录解析，并验证位于所属run目录。调用子进程使用参数数组，不shell拼接用户输入。临时文件和不可变产物分目录，写入顺序遵循03。

Journal规范化由单一模块实现，不在每个stage复制hash代码。checkpoint、compression、memory不可另建权威状态；恢复后必须重新做call授权。日志不记录隐藏思维；公开trace与私密调试产物有区别。

格式/lint采用Ruff，类型检查选一种固定配置，测试pytest；具体版本在V01锁定。禁止每个窗口各自选black/isort/flake8再同时改全仓。非当前任务的格式问题不顺手重写。

## 5. 前端实现标准

TypeScript strict开启，不用any掩盖契约错误；unknown在边界解析。API类型从冻结schema生成，不手写另一套RunStatus。允许为展示派生ViewModel，不在多个store复制服务端事实。

TanStack Query管理服务端数据；queryKey统一工厂，包含owner/scope/filters/page等影响结果的参数；mutation成功明确失效哪些key。Zustand只存跨页UI状态，表单局部状态优先组件内处理。

API客户端统一处理session、CSRF、traceId、timeout、abort、HTTP错误映射；用户取消请求不显示成服务器崩溃。不能对非幂等POST统一自动retry。恢复SSE后读快照，重复事件不重复推进；未确认的前端乐观状态不能成为持久事实。

组件按功能单一职责拆分。hooks不混入任意模块业务；页面负责组合，通用ui负责展示。使用统一tokens、组件目录和交互约定；禁止各页安装不同UI库。

每页有loading/empty/error/degraded/unauthorized状态；提交中防重复点击只是体验，不替代服务端幂等。Markdown消毒，禁止不可信dangerouslySetInnerHTML。键盘操作、焦点、表单label、错误提示和减少动效偏好必须覆盖。

页面测试重点是用户行为与授权/失败反馈，不断言内部组件层数；E2E验证真实链路，Mock页面只能标为CONTRACT_READY。

## 6. API、消息和配置标准

公共API、内部工具API、Runner API三份OpenAPI分开，schema共享通过明确引用，不复制粘贴漂移。状态和错误码由契约维护者登记，所有消费者更新后才能冻结新contractRev。

消息payload只带稳定标识、协议版本、trace上下文和必要提示，不塞完整正文或秘密。消费者重新读权威库。事件区分业务事实、运行事实和SSE投影；eventId、seq和traceId不是同一个字段。

配置只从统一配置模块读，启动输出脱敏有效配置；新增变量同时改schema、.env.example、06和测试。V01确定准确名称，此后不得把LARDER_RABBIT_URL自行换为RABBITMQ_URI。

日志按JSON字段记录，禁止把异常/状态拼成不可搜索的大字符串。metrics标签禁止runId、完整URL、问题文本等高基数字段；trace可带受控ID，不能带秘密。用户正文不出现在普通错误日志。

## 7. 质量门禁与可读性

Java formatter+静态检查、Python Ruff+类型检查、前端ESLint/TypeScript由协调Agent一次配置并固定版本。可采用Spotless统一Java格式，但不能每个窗口自改规则。代码格式全仓迁移必须单独任务，避免污染业务diff。

Java类约300行、方法约50行，Python文件约200行、函数约40行是审查提示；超出要解释职责，不能通过无意义碎片化机械达标。复杂分支提取有名称的规则。注释解释为什么、事务保证和已知限制，不复述语法。TODO带taskId和触发条件。

必须验证：状态转移、并发提交、事务失败、授权、期限、恢复和引用。纯文案/低风险样式不强行单测。新增测试必须能在至少一个错误实现上失败，避免只验证mock返回自己设定的值。

单测Unit/`test_*.py`/Vitest；Java集成`*IT`由Failsafe运行；契约测试与E2E单列。固定clock/random seed与fixture版本；真实模型测试标live，不进默认离线任务。测试超时有上限，不靠增加sleep治偶发失败。

## 8. 不做什么

不换核心栈、不绕过契约、不占用别人路径、不提交秘密、不自动清理用户数据、不用全仓格式化掩盖业务差异、不把skip或0个测试当通过、不将个人格式偏好扩散成架构变更。
