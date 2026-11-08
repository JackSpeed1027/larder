package com.larder.runner.artifacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验 Runner 上报的产物清单（manifest）。
 *
 * <p>为什么在 Java 侧再校验一遍：Runner 是独立进程，产物清单可能被截断、被篡改、
 * 或版本不一致。不能在"信任 Runner"与"完全不信"之间二选一——清单先过结构校验，
 * 内容字节再逐个算 sha256（{@code ArtifactService}）。
 */
public final class ArtifactManifestValidator {

    /** 单 run 产物条数上限（与 contracts/run-response.schema.json 的 maxItems 对齐）。 */
    public static final int MAX_ARTIFACTS = 200;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "text/plain", "text/markdown", "text/html", "application/json", "application/pdf");

    private ArtifactManifestValidator() {
    }

    /** 返回错误列表；空表示结构合法。 */
    public static List<String> validate(List<ClaimedArtifact> artifacts) {
        List<String> errors = new ArrayList<>();
        if (artifacts == null) {
            errors.add("manifest 为空");
            return errors;
        }
        if (artifacts.size() > MAX_ARTIFACTS) {
            errors.add("产物条数 " + artifacts.size() + " 超过上限 " + MAX_ARTIFACTS);
        }
        Set<String> seen = new HashSet<>();
        for (ClaimedArtifact artifact : artifacts) {
            if (artifact == null) {
                errors.add("产物条目为 null");
                continue;
            }
            if (artifact.artifactId() == null || !artifact.artifactId().matches("[A-Za-z][A-Za-z0-9_-]{1,63}")) {
                errors.add("artifactId 非法: " + artifact.artifactId());
            } else if (!seen.add(artifact.artifactId())) {
                errors.add("artifactId 重复: " + artifact.artifactId());
            }
            if (artifact.sha256() == null || !artifact.sha256().matches("[0-9a-f]{64}")) {
                errors.add("sha256 非法（必须 64 位小写十六进制）: " + artifact.sha256());
            }
            if (artifact.bytes() == null || artifact.bytes() < 0) {
                errors.add("bytes 非法: " + artifact.bytes());
            }
            if (artifact.mime() == null || !ALLOWED_MIME.contains(artifact.mime())) {
                errors.add("mime 不支持: " + artifact.mime());
            }
        }
        return errors;
    }
}
