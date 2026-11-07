package com.larder.common.contract;

/**
 * Java 公开 API 成功响应外壳（对应 contracts/public-api-common.schema.json 的 ApiResponse）。
 *
 * <p>字段名必须与 schema 完全一致，scripts/contracts/check_contracts.py 会做字段级 parity 校验；
 * 改这里必须同时改 schema 并重跑生成与检查。
 *
 * <p>只有面向 Web 的普通 JSON 接口使用本壳；Runner 协议、SSE、文件下载不套壳（docs/04）。
 *
 * @param code    固定为 OK 的业务码（不是 HTTP 状态）
 * @param message 安全可读说明
 * @param data    端点声明的业务结果
 * @param traceId 链路标识
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String traceId
) {
}
