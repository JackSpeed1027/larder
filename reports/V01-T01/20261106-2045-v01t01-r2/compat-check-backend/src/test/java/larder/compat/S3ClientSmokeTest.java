package larder.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 客户端（AWS SDK v2）对 MinIO 的真实往返测试。
 *
 * <p>需要本地已启动 MinIO 并通过环境变量给出端点：LARDER_S3_ENDPOINT / LARDER_S3_USER / LARDER_S3_PASSWORD。
 * 未提供端点时用 JUnit assumption 跳过（记为 skipped），因此"通过"必须看到 skipped=0。
 */
class S3ClientSmokeTest {

    @Test
    @DisplayName("AWS SDK v2 对 MinIO 端点完成建桶/写入/读取/HEAD")
    void s3RoundTrip() {
        String endpoint = System.getenv().getOrDefault("LARDER_S3_ENDPOINT", "");
        assumeTrue(!endpoint.isBlank(), "LARDER_S3_ENDPOINT 未设置：本用例需要真实 MinIO 端点");

        String user = System.getenv().getOrDefault("LARDER_S3_USER", "larder_test");
        String password = System.getenv().getOrDefault("LARDER_S3_PASSWORD", "larder_test_secret");
        String bucket = "larder-sdk-smoke";
        byte[] payload = "java sdk to minio".getBytes(StandardCharsets.UTF_8);

        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, password)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {

            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
                // 已存在不算失败；其它错误要暴露出来
                if (e.statusCode() != 409) {
                    throw e;
                }
            }

            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("probe/hello.txt").build(),
                    RequestBody.fromBytes(payload));

            String body = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key("probe/hello.txt").build())
                    .asUtf8String();
            assertThat(body).isEqualTo("java sdk to minio");

            long size = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key("probe/hello.txt").build())
                    .contentLength();
            assertThat(size).isEqualTo(payload.length);

            System.out.println("[COMPAT] s3 roundtrip ok endpoint=" + endpoint + " bytes=" + size);
        }
    }
}
