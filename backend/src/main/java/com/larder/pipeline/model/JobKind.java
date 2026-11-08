package com.larder.pipeline.model;

/** 任务类型，与 job.kind CHECK 一致（V02-T01 只用 INGEST）。 */
public enum JobKind {
    INGEST,
    ENRICH,
    INDEX,
    ASSIST,
    EMBED,
    EXPORT
}
