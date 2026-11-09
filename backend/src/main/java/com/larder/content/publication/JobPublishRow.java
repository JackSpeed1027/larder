package com.larder.content.publication;

import java.time.LocalDateTime;

/** 发布时锁定的 job 快照（FOR UPDATE）。 */
public class JobPublishRow {

    private String jobId;
    private String ownerId;
    private String itemId;
    private String state;
    private Long processingEpoch;
    private String runId;
    private java.time.LocalDateTime cancelRequestedAt;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getProcessingEpoch() {
        return processingEpoch;
    }

    public void setProcessingEpoch(Long processingEpoch) {
        this.processingEpoch = processingEpoch;
    }

    public java.time.LocalDateTime getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(java.time.LocalDateTime cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }
}
