package com.larder.content.api;

import com.larder.common.contract.ApiErrorResponse;
import com.larder.common.contract.ApiResponse;
import com.larder.content.model.ItemKind;
import com.larder.ingest.CommandBody;
import com.larder.ingest.IngestOutcome;
import com.larder.ingest.IngestRejectedException;
import com.larder.ingest.ItemIngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 内容收录公开接口（V02-T01）。
 *
 * <p>POST /api/items：
 * <ul>
 *   <li>必须带 Idempotency-Key（同 key 同 body 返回首次结果，同 key 不同 body 409）；</li>
 *   <li>新建返回 202 + itemId/jobId；同 URL 已存在返回 200；</li>
 *   <li>owner 来自认证主体，绝不接受客户端传入的 ownerId。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ItemController {

    private static final int MIN_KEY = 8;
    private static final int MAX_KEY = 128;

    private final ItemIngestService ingestService;

    public ItemController(ItemIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CreateItemResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateItemRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw IngestRejectedException.validation("缺少 Idempotency-Key 请求头");
        }
        if (idempotencyKey.length() < MIN_KEY || idempotencyKey.length() > MAX_KEY) {
            throw IngestRejectedException.validation("Idempotency-Key 长度必须在 8..128 之间");
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IngestRejectedException("NEED_LOGIN", "未登录", 401, false);
        }

        CommandBody body = new CommandBody(
                parseKind(request.kind()), request.title(), request.url(), request.text(), request.uploadId());

        IngestOutcome outcome = ingestService.ingest(authentication.getName(), idempotencyKey, body);
        CreateItemResponse data = new CreateItemResponse(outcome.itemId(), outcome.jobId(), outcome.dedupe());
        String message = outcome.httpStatus() == 202 ? "已受理" : "已存在";
        return ResponseEntity.status(outcome.httpStatus())
                .body(new ApiResponse<>("OK", message, data, traceId(httpRequest)));
    }

    @ExceptionHandler(IngestRejectedException.class)
    public ResponseEntity<ApiErrorResponse> onRejected(IngestRejectedException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.httpStatus()).body(new ApiErrorResponse(
                ex.code(), ex.getMessage(), traceId(request), ex.retryable(), Map.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(
                "VALIDATION_FAILED", ex.getMessage(), traceId(request), false, Map.of()));
    }

    private ItemKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw IngestRejectedException.validation("kind 不能为空");
        }
        try {
            return ItemKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw IngestRejectedException.validation("未知的 kind: " + raw);
        }
    }

    private String traceId(HttpServletRequest request) {
        String incoming = request.getHeader("X-Request-Id");
        return (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
    }
}
