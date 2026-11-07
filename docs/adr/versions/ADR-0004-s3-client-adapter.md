# ADR-0004 S3 客户端选 AWS SDK v2（适配器隔离）

- 状态：ACCEPTED
- 日期：2026-11-06
- 任务：V01-T01
- 证据：`reports/V01-T01/20261106-195106-v01t01/raw/maven/awssdk-s3.maven-metadata.xml`、`aws-sdk-java-pom.pom`、`minio-8.5.17.pom`

## 背景

`docs/06` 要求"保留 S3 对象存储主线，API 依赖 S3 适配接口而非 MinIO 专有行为"。原方案默认使用 MinIO 服务端，因此客户端自然可以是 `io.minio:minio`。但 ADR-0003 已确认 MinIO 服务端镜像下线，V12 很可能换服务端。

## 决策

Java 侧统一使用 **AWS SDK for Java v2 (`software.amazon.awssdk:s3` 2.57.10)**，并通过 `ObjectStore` 端口隔离：

- port 定义在 domain/application 层（`put`/`get`/`presign`/`delete`/`head`/分片上传），不出现 `S3Client` 类型；
- adapter 在 infrastructure 层实现，端点、区域、path-style、签名全部由配置注入；
- 业务代码不得调用 MinIO 专有 API（bucket notification、admin API 等）。

## 理由

1. **端点可替换**：AWS SDK v2 对任意 S3 兼容端点（MinIO/SeaweedFS/Garage/RustFS/AWS）都可用，换服务端只改配置。
2. **维护面**：MinIO SDK 的 GitHub 仓库已归档（服务端），AWS SDK v2 由 AWS 持续维护且今天仍在发布（2.57.10）。
3. **许可**：AWS SDK v2 为 Apache-2.0（父 POM `aws-sdk-java-pom` 声明），与 MinIO SDK 的 Apache-2.0 相同，无额外许可负担。
4. **已知代价**：AWS SDK v2 依赖体量比 MinIO SDK 大（`s3` 会带 `apache-client`/`netty` 等），启动稍重；对一个单用户自托管应用可以接受。

## 验证

- 依赖解析通过（`logs/dependency-list.txt` 含 `software.amazon.awssdk:s3:2.57.10`）；
- `S3Client.builder()` 在兼容测试中可加载（`logs/mvn-test.log`）；
- 真实读写/分片/校验/预签名 **未验证（BLOCKED）**：没有可用的本地 S3 端点（ADR-0003），V02-T01 在确定对象存储后必须补测。

## 后果

- V02 的 `ObjectStore` 实现只允许出现一处 `S3Client` 依赖（adapter），出现第二处即视为架构违规。
- 预签名 URL、分片上传、校验和、版本引用都要有对应测试；不能只测 `put` 成功。
