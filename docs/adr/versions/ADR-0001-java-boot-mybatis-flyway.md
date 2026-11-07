# ADR-0001 Java / Spring Boot / MyBatis-Plus / Flyway 版本冻结与兼容验证

- 状态：ACCEPTED（R2 已修订，见文末“R2 修订”）
- 日期：2026-11-06
- 任务：V01-T01（R1 初版 → R2 修订）
- 证据：`reports/V01-T01/20261106-195106-v01t01/`（R1）、`reports/V01-T01/20261106-2045-v01t01-r2/`（R2）

> 本文件正文里的 3.5.16 / 6.2.19 / 11.7.2 等数字是 R1 在 Boot 3.5 线上的冻结结果，**已被 R2 的 Boot 4.1.2 取代**。保留原记录是为了说明升级前后的差异；当前生效数字只看文末“R2 修订”与 `docs/versions.lock.json`。

## 背景

`docs/06` 与 `README` 已固定技术方向：Java 21、Spring Boot 3.5、MyBatis-Plus、Flyway、MySQL 8.4、Testcontainers，并要求"精确补丁由 V01-T01 验证"。本机环境为 Windows 11 + Temurin JDK 21.0.12.1 + Maven 3.9.11，**没有 Docker、没有 WSL 发行版**，因此真实 MySQL/RabbitMQ/Redis/Meili/S3 无法启动。需要在不伪造结果的前提下，把"能验证的"与"不能验证的"分清楚。

## 决策

1. 以 Spring Boot **3.5.16** 的 BOM 作为后端唯一版本权威；除 MyBatis-Plus 与 AWS SDK 外，不再手工指定 Spring 生态版本。
2. 固定组合如下（全部来自本 run 实际拉取的注册源数据）：

| 组件 | 版本 | 决定来源 |
|---|---|---|
| Spring Boot (starter-parent) | 3.5.16 | Maven Central `maven-metadata.xml`，3.5 线最高补丁 |
| Spring Framework | 6.2.19 | Boot 3.5.16 BOM 属性 `spring-framework.version` |
| Spring Security | 6.5.11 | Boot 3.5.16 BOM 属性 `spring-security.version` |
| Spring AMQP | 3.2.12 | Boot 3.5.16 BOM 属性 `spring-amqp.version` |
| MyBatis-Plus | 3.5.18 | `mybatis-plus-spring-boot3-starter` 元数据最新发布 |
| Flyway (core + mysql) | 11.7.2 | Boot BOM 属性 `flyway.version` |
| MySQL Connector/J | 9.7.0 | Boot BOM 属性 `mysql.version` |
| Testcontainers | 1.21.4 | Boot BOM 属性 `testcontainers.version` |
| AWS SDK v2 S3 | 2.57.10 | AWS SDK BOM（见 ADR-0004） |

3. 不手工混配：Flyway 已有 13.12.1、Testcontainers 已有 2.0.5，但 Boot 3.5 的自动配置是对着 11.x / 1.21.x 写的，单独升级属于"手工混配 Framework/Security 旧版本"的同类风险，`docs/06` 明确禁止。
4. `flyway-mysql` 作为独立依赖显式引入。Flyway 10+ 把 MySQL 方言拆出核心包，缺它时"能启动但真实迁移失败"，属于最容易漏掉的坑。

## 验证方式（可证伪）

在 `reports/.../compat-check-backend/` 建了一个一次性的 Maven 验证工程（**不是应用工程**，应用在 V01-T02 才建）：

- `mvn dependency:list` 解析全图，退出码 0；
- `mvn test` 启动真实 Spring 上下文（`@SpringBootTest`），用 H2 `MODE=MySQL` 代替 MySQL：
  - 断言 `SpringBootVersion.getVersion() == 3.5.16`；
  - 断言 `Flyway` 在 classpath 上的 jar 名为 `flyway-core-11.7.2.jar`；
  - 断言 `org.flywaydb.database.mysql.MySQLDatabaseType` 可加载（证明 `flyway-mysql` 存在）；
  - 用 `V1__compat_init.sql` 建表插种子，再用 MyBatis-Plus `BaseMapper.selectList` 读回，证明 Mapper 与 Flyway 都真的工作；
  - 断言 `S3Client.builder()` 可用。
- 结果：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，日志见 `logs/mvn-test.log`。

## 后果与边界

- **已验证**：这套 Java 组合能编译、能启动、MyBatis-Plus 能映射 SQL、Flyway 能执行迁移、MySQL 方言模块在位、S3 客户端类可用。
- **未验证（BLOCKED）**：真实 MySQL 8.4 的连接/时区/utf8mb4/索引长度/事务语义；RabbitMQ 的 confirm/ack/DLQ；Testcontainers 的真实容器语义。原因是本机无 Docker（`environment.txt` 记录 `docker CLI: NOT FOUND`、`dockerDesktopInstalled=False`、WSL 无发行版）。
- 因此 `versions.lock.json` 中这些条目的 `verification.runtime.status` 全部为 `BLOCKED`，并写明原因；**V01-T02 必须在具备 Docker 的机器上重跑这些检查后才能标 DONE**。
- H2 冒烟不能替代 MySQL 兼容结论，只能证明"框架层组合没问题"。这一点在交付里必须保持区分。

## R2 修订（2026-11-06）

用户决定"如果以后要升 Boot，现在直接升也行，只要版本兼容"，因此 R2 把父 POM 升到 **Spring Boot 4.1.2**，并整体重冻结：

| 组件 | R1（Boot 3.5.16） | R2（Boot 4.1.2，当前生效） |
|---|---|---|
| Spring Framework | 6.2.19 | **7.0.10** |
| Spring Security | 6.5.11 | **7.1.2** |
| Spring AMQP | 3.2.12 | **4.1.2** |
| Flyway | 11.7.2 | **12.4.0** |
| Testcontainers | 1.21.4 | **2.0.5** |
| JUnit | 5.12.2 | **6.0.3** |
| MyBatis-Plus | spring-boot3-starter 3.5.18 | **spring-boot4-starter 3.5.18** |
| Connector/J | 9.7.0 | 9.7.0（不变） |
| AWS SDK v2 | 2.57.10 | 2.57.10（不变，独立 BOM） |

R2 的兼容验证（`reports/V01-T01/20261106-2045-v01t01-r2/`）：`mvn test` **3/3 通过，0 skip**——Boot 4.1.2 上下文启动、Flyway 12.4.0 执行 H2 迁移、MyBatis-Plus boot4 starter 真实查询、AWS SDK v2 对本地 MinIO 完成 S3 往返。

R2 发现的两个 Boot 4 硬性差异（R1 的经验在 Boot 4 不成立）：

1. **必须用 `spring-boot-starter-flyway`**：只加 `flyway-core` 时上下文能起、但 Flyway bean 不存在、迁移根本不跑；
2. **必须用 `mybatis-plus-spring-boot4-starter`**：boot3 starter 依赖 Boot 3 的 autoconfigure API。

因此正文 §"验证方式"与 §"后果与边界"中关于"只需 flyway-mysql"的结论，应按 ADR-0002 的"四个差异"更新理解；其余方法（真实启动 + 真实迁移 + 真实查询 + 版本自证）继续适用。
