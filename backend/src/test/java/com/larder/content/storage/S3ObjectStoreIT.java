package com.larder.content.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MinIO 集成测试（需要本地 S3 端点）。
 *
 * <p>没有配置 {@code LARDER_TEST_S3_ENDPOINT} 时跳过：它属于"需要真实依赖"的用例，
 * 由 scripts/dev/verify-artifacts.ps1 在起好 MinIO 后显式运行。
 */
@EnabledIfEnvironmentVariable(named = "LARDER_TEST_S3_ENDPOINT", matches = ".+")
class S3ObjectStoreIT {

    @Test
    @DisplayName("真实 MinIO：写入可读、重复写幂等、同 key 不同内容被拒绝")
    void putGetAndImmutability() {
        String endpoint = System.getenv("LARDER_TEST_S3_ENDPOINT");
        String access = System.getenv().getOrDefault("LARDER_TEST_S3_ACCESS_KEY", "");
        String secret = System.getenv().getOrDefault("LARDER_TEST_S3_SECRET_KEY", "");
        String bucket = System.getenv().getOrDefault("LARDER_TEST_S3_BUCKET", "larder-it-artifacts");

        S3ObjectStore store = new S3ObjectStore(endpoint, "us-east-1", access, secret, bucket);

        byte[] content = "hello immutable artifact".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactKeys.sha256(content);
        String key = "artifacts/it/" + sha;

        ObjectStore.StoredObject first = store.putImmutable(key, content, sha, "text/plain");
        assertThat(first.sha256()).isEqualTo(sha);
        assertThat(store.exists(key)).isTrue();
        assertThat(store.get(key)).isEqualTo(content);

        // 同内容重复写：幂等复用，不报错
        ObjectStore.StoredObject second = store.putImmutable(key, content, sha, "text/plain");
        assertThat(second.sha256()).isEqualTo(sha);

        // 同 key 不同内容：必须拒绝而不是覆盖
        byte[] other = "different content".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.putImmutable(key, other, ArtifactKeys.sha256(other), "text/plain"))
                .isInstanceOf(ArtifactConflictException.class);

        // 原对象未被覆盖
        assertThat(store.get(key)).isEqualTo(content);
    }
}
