package com.larder.content.api;

/**
 * 创建条目的响应数据。
 *
 * @param itemId 条目 ID（去重时是已存在的条目）
 * @param jobId  本次或已存在的 INGEST 任务 ID
 * @param dedupe CREATED / EXISTING / REPLAYED，仅用于前端提示，不参与授权
 */
public record CreateItemResponse(String itemId, String jobId, String dedupe) {
}
