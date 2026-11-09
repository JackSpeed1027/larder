package com.larder.pipeline.lease;

import java.time.LocalDateTime;

/** 租约视图：只含 fencing 提交与 SSE 需要的列。 */
public class JobLeaseRow {

    private String jobId;
    private String ownerId;
    private String state;
    private Integer attempt;
    private String runId;
    private Long processingEpoch;
    private String leaseOwner;
    private Long leaseToken;
    private LocalDateTime leaseUntil;
    private Long lastEventSeq;
    private String errorCode;
    private LocalDateTime updatedAt;

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getProcessingEpoch() {
        return processingEpoch;
    }

    public void setProcessingEpoch(Long processingEpoch) {
        this.processingEpoch = processingEpoch;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Long getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(Long leaseToken) {
        this.leaseToken = leaseToken;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Long getLastEventSeq() {
        return lastEventSeq;
    }

    public void setLastEventSeq(Long lastEventSeq) {
        this.lastEventSeq = lastEventSeq;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
