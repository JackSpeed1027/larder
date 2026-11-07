# ADR-0002 Spring Boot 3.5 已过 OSS 支持期：升级到 4.1.2（已执行）

- 状态：ACCEPTED（R2，已执行并验证）
- 日期：2026-11-06
- 任务：V01-T01 R2
- 证据：`reports/V01-T01/20261106-2045-v01t01-r2/`（`logs/mvn-boot4-test.log`、`logs/mvn-boot4-test-s3.log`、`raw/maven/spring-boot-dependencies-4.1.2.pom`）

## 事实

| 版本线 | 发布 | OSS 支持结束 | 最新补丁 |
|---|---|---|---|
| Boot 3.5 | 2025-05-31 | **2026-06-30（已过期）** | 3.5.16（该线最后一版） |
| Boot 4.0 | 2025-11-30 | 2026-12-31 | 4.0.9 |
| Boot 4.1 | 2026-06-30 | 2027-07-31 | **4.1.2** |

R1 时按"不自动升 Boot4"锁定 3.5.16 并把风险写成 ADR。用户随后明确：**如果以后要升，现在直接升也行，确保版本兼容即可**。因此 R2 直接落到 4.1.2——它是有支持的最长线，避免"先升 4.0 再在两个月内二次升级"。

## 决策

把 Spring Boot 父 POM 从 3.5.16 升到 **4.1.2**，并整体重冻结受 BOM 管理的版本：

| 组件 | R1（3.5.16） | R2（4.1.2） |
|---|---|---|
| Spring Framework | 6.2.19 | **7.0.10** |
| Spring Security | 6.5.11 | **7.1.2** |
| Spring AMQP | 3.2.12 | **4.1.2** |
| Flyway | 11.7.2 | **12.4.0** |
| Testcontainers | 1.21.4 | **2.0.5** |
| JUnit | 5.12.2 | **6.0.3** |
| Tomcat | 10.1.55 | **11.0.27** |
| Jackson | 2.21.4 | **3.1.7（另保留 2.21.7 兼容坐标）** |
| MyBatis-Plus | boot3 starter 3.5.18 | **boot4 starter 3.5.18** |
| Connector/J | 9.7.0 | 9.7.0（不变） |
| AWS SDK v2 | 2.57.10 | 2.57.10（不变，独立 BOM） |

## 升级时必须知道的四个差异（本次实测，不是推测）

1. **自动配置被拆成模块**：Boot 4 的 `spring-boot-autoconfigure` 不再是万能包。只加 `flyway-core` 时上下文能启动、但**没有 Flyway bean、迁移根本不执行**；第一次跑兼容测试就报 `No qualifying bean of type 'org.flywaydb.core.Flyway'`。必须用 **`spring-boot-starter-flyway`** 拉起 `spring-boot-flyway`，再显式加 `flyway-mysql`。同理存在 `spring-boot-jdbc`、`spring-boot-security`、`spring-boot-amqp`、`spring-boot-webmvc` 等模块包。
2. **MyBatis-Plus 必须换 starter**：`mybatis-plus-spring-boot3-starter` 引 Boot 3 的 autoconfigure API；Boot 4 用 **`mybatis-plus-spring-boot4-starter`**（3.5.13 起提供，3.5.18 为最新）。
3. **JUnit 6 / Jackson 3**：测试代码的 `org.junit.jupiter.api.*` 不变，但 `@SpringBootTest` 的切片、Mockito 版本随 BOM 升级；Jackson 3 的包名是 `tools.jackson.*`，任何手写 `com.fasterxml.jackson` 的序列化配置都要重写（本项目 V01 阶段没有手写 Jackson 配置）。
4. **Testcontainers 从 1.21 跳到 2.0**：Boot 4.1 直接管理 2.0.5，容器 API 有变化，V03 的集成测试要按 2.x 写。

## 验证（可证伪）

`reports/.../compat-check-backend`（Boot 4.1.2 脚手架，非应用工程）：

- `mvn dependency:list` 退出码 0；
- `mvn test` → **Tests run: 3, Failures: 0, Errors: 0, Skipped: 0**：
  - 上下文真实启动；
  - `SpringBootVersion == 4.1.2`、`spring-context-7.0.10.jar`、`flyway-core-12.4.0.jar`、`mybatis-plus-core-3.5.18.jar`、`s3-2.57.10.jar` 运行时断言；
  - Flyway 在 H2 上执行 `V1__compat_init.sql`；
  - MyBatis-Plus `BaseMapper.selectCount/selectList` 真实查询；
  - AWS SDK v2 对本地 MinIO 完成建桶/写/读/HEAD（`logs/mvn-boot4-test-s3.log`）。

## 残余风险

- MySQL/Redis/RabbitMQ/Meili 的真实行为仍未验证（Docker 守护进程未就绪），升级后 BOM 里的 Connector/J、AMQP、Testcontainers 组合只过了"装配"这一关。
- Boot 4.1 才发布一年多，生态（MyBatis-Plus boot4 starter）成熟度低于 3.x；但 boot4 starter 自 2025-08 起持续发版，且本次实测通过。
- 若 V02 出现 Boot 4 特有回归，回退成本是"父 POM + 四个模块依赖"级别的，不算全栈重写；回退必须新开 ADR，不能偷偷改回 3.5。

## 后果

- `docs/06`、`docs/05`、`docs/02`、`docs/07` 里的 "Boot3.5" 字样已同步改为 4.1（见交付清单）。
- `versions.lock.json` 的 `spring-boot` 条目为 4.1.2，R1 的 3.5.16 数字作废。
