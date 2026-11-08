package com.larder.pipeline.model;

/** 任务状态，与 job.state CHECK 一致。 */
public enum JobState {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    NEEDS_DECISION,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED
}
