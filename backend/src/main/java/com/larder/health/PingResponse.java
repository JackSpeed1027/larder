package com.larder.health;

/**
 * /api/ping 的响应。
 *
 * <p>V01-T02 只做探针，不套 ApiResponse 信封：docs/04 规定 Java 公开 JSON 用
 * ApiResponse<T>，但那套 DTO 由 V01-T03 从契约生成，这里先用最小 record，
 * 避免提前手写一份"以后要改名"的公共封装。
 */
public record PingResponse(
        String service,
        String version,
        String schemaVersion,
        String time
) {
}
