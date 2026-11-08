package com.larder.runner.artifacts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 产物清单结构校验：坏清单必须被拒绝，而不是"先收下再说"。 */
class ArtifactManifestValidatorTest {

    private static final String SHA = "a".repeat(64);

    @Test
    @DisplayName("合法清单通过")
    void acceptsValidManifest() {
        assertThat(ArtifactManifestValidator.validate(List.of(
                new ClaimedArtifact("art_1", SHA, 10L, "text/plain")))).isEmpty();
    }

    @Test
    @DisplayName("sha256 非 64 位小写十六进制：拒绝")
    void rejectsBadSha() {
        assertThat(ArtifactManifestValidator.validate(List.of(
                new ClaimedArtifact("art_1", "ABC", 10L, "text/plain"))))
                .anyMatch(e -> e.contains("sha256"));
    }

    @Test
    @DisplayName("负数 bytes / 不支持 mime：拒绝")
    void rejectsBadBytesAndMime() {
        List<String> errors = ArtifactManifestValidator.validate(List.of(
                new ClaimedArtifact("art_1", SHA, -1L, "application/zip")));
        assertThat(errors).anyMatch(e -> e.contains("bytes"))
                .anyMatch(e -> e.contains("mime"));
    }

    @Test
    @DisplayName("artifactId 重复：拒绝")
    void rejectsDuplicateId() {
        assertThat(ArtifactManifestValidator.validate(List.of(
                new ClaimedArtifact("art_1", SHA, 1L, "text/plain"),
                new ClaimedArtifact("art_1", SHA, 1L, "text/plain"))))
                .anyMatch(e -> e.contains("重复"));
    }

    @Test
    @DisplayName("超过 200 条：拒绝")
    void rejectsTooMany() {
        List<ClaimedArtifact> many = new ArrayList<>();
        for (int i = 0; i < ArtifactManifestValidator.MAX_ARTIFACTS + 1; i++) {
            many.add(new ClaimedArtifact("art_" + i, SHA, 1L, "text/plain"));
        }
        assertThat(ArtifactManifestValidator.validate(many)).anyMatch(e -> e.contains("超过上限"));
    }
}
