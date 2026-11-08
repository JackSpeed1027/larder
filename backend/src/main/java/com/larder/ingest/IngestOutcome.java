package com.larder.ingest;

/**
 * 收录结果。
 *
 * @param itemId    条目 ID（去重时是已存在的条目）
 * @param jobId     本次（或已存在的最近一次）INGEST 任务 ID
 * @param dedupe    CREATED / EXISTING / REPLAYED，仅用于可读性，不参与授权
 * @param httpStatus 201/202 新建，200 已存在，与 docs/04 的接口语义一致
 */
public record IngestOutcome(String itemId, String jobId, String dedupe, int httpStatus) {
}
