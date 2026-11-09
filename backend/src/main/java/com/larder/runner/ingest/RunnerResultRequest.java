package com.larder.runner.ingest;

/**
 * Runner 回传的抓取结果。
 *
 * @param jobId           目标 job（必须处于 RUNNING）
 * @param runId           Runner 的稳定运行 ID（必须与 job.run_id 一致）
 * @param processingEpoch 处理代次（旧代次不能发布）
 * @param text            抽取后的正文
 * @param contentSha256   正文 sha256；Java 会重新计算比对
 * @param extractorVersion / chunkerVersion 抽取/分段实现版本，便于复现
 * @param manifestJson    分段 manifest（JSON 字符串）
 */
public record RunnerResultRequest(
        String jobId,
        String runId,
        Long processingEpoch,
        String text,
        String contentSha256,
        String extractorVersion,
        String chunkerVersion,
        String manifestJson
) {
}
