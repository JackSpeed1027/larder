package com.larder.common.contract;

import java.util.Map;

/**
 * Java 公开 API 错误响应（对应 contracts/public-api-common.schema.json 的 ApiErrorResponse）。
 *
 * <p>错误码必须来自 contracts/error-codes.json 注册表；details 只允许按错误码登记的白名单键，
 * 不允许把异常堆栈或秘密塞进来。
 *
 * @param code      稳定错误码，形如 NEED_LOGIN
 * @param message   安全具体描述
 * @param traceId   链路标识
 * @param retryable 是否允许在幂等预算边界内重试
 * @param details   白名单详情（最多 16 个键）
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        boolean retryable,
        Map<String, Object> details
) {
}
