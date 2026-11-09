package com.larder.pipeline.lease;

/**
 * 任务进度快照（SSE 的权威状态源）。
 *
 * @param seq 事件序号；SSE 用 Last-Event-ID 恢复时以它为准
 */
public record JobProgress(String jobId, String ownerId, String state, int attempt, String runId,
                          long epoch, String errorCode, String updatedAt, long seq) {

    public boolean terminal() {
        return switch (state) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "SUPERSEDED" -> true;
            default -> false;
        };
    }
}
