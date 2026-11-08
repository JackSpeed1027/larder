package com.larder.content.api;

/**
 * 创建条目的请求体（严格字段，未知字段由 Jackson 默认忽略——真正的严格校验在
 * 契约层由 schema 保证；这里只做业务必填与互斥校验）。
 *
 * @param kind     WEB/TEXT（V02-T01 支持范围）
 * @param title    可选标题；为空时由服务端取 URL 或正文前缀
 * @param url      WEB 必填
 * @param text     TEXT 必填
 * @param uploadId 上传文件引用（V02 上传任务实现前拒绝）
 */
public record CreateItemRequest(
        String kind,
        String title,
        String url,
        String text,
        String uploadId
) {
}
