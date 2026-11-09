package com.larder.runner.ingest;

import com.larder.content.publication.PublicationException;
import com.larder.content.publication.PublicationResult;
import com.larder.content.publication.RevisionPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Runner → Java 的内部回传接口。
 *
 * <p>鉴权用共享内部令牌（`X-Internal-Token`），不是用户 session：
 * Runner 是服务，不应伪造用户身份；令牌未配置时直接 503，绝不"默认放行"。
 */
@RestController
@RequestMapping("/internal/runs")
public class RunnerResultController {

    private final RevisionPublisher publisher;
    private final String internalToken;

    public RunnerResultController(RevisionPublisher publisher,
                                  @Value("${larder.internal.token:}") String internalToken) {
        this.publisher = publisher;
        this.internalToken = internalToken;
    }

    @PostMapping("/{runId}/result")
    public ResponseEntity<Map<String, String>> publish(@PathVariable String runId,
                                                       @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                       @RequestBody RunnerResultRequest request) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new PublicationException("INTERNAL_AUTH_DISABLED", "内部令牌未配置", 503);
        }
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new PublicationException("NEED_LOGIN", "内部令牌无效", 401);
        }
        if (request.jobId() == null || request.text() == null || request.contentSha256() == null) {
            throw new PublicationException("VALIDATION_FAILED", "缺少 jobId/text/contentSha256", 400);
        }
        if (!runId.equals(request.runId())) {
            throw new PublicationException("VALIDATION_FAILED", "路径 runId 与 body 不一致", 400);
        }
        PublicationResult result = publisher.publish(request);
        return ResponseEntity.ok(Map.of("revisionId", result.revisionId(), "dedupe", result.dedupe()));
    }

    @ExceptionHandler(PublicationException.class)
    public ResponseEntity<Map<String, String>> onRejected(PublicationException ex) {
        return ResponseEntity.status(ex.httpStatus())
                .body(Map.of("code", ex.code(), "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
