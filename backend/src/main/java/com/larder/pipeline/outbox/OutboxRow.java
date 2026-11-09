package com.larder.pipeline.outbox;

import java.time.LocalDateTime;

/**
 * Outbox 投递视图：只包含 relay 需要的列。
 *
 * <p>为什么不直接改 {@code pipeline/model/OutboxEvent}：本任务的 allowlist 只有
 * {@code pipeline/outbox}，而实体是共享模型。用独立的行视图 + 专用 Mapper，
 * 把"投递器需要 relay_token/租约"这个局部需求留在自己的包里。
 */
public class OutboxRow {

    private String eventId;
    private String ownerId;
    private String jobId;
    private String eventType;
    private String payloadJson;
    private String payloadHash;
    private String state;
    private Integer attempt;
    private LocalDateTime availableAt;
    private String relayOwner;
    private Long relayToken;
    private LocalDateTime relayLeaseUntil;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
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

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime availableAt) {
        this.availableAt = availableAt;
    }

    public String getRelayOwner() {
        return relayOwner;
    }

    public void setRelayOwner(String relayOwner) {
        this.relayOwner = relayOwner;
    }

    public Long getRelayToken() {
        return relayToken;
    }

    public void setRelayToken(Long relayToken) {
        this.relayToken = relayToken;
    }

    public LocalDateTime getRelayLeaseUntil() {
        return relayLeaseUntil;
    }

    public void setRelayLeaseUntil(LocalDateTime relayLeaseUntil) {
        this.relayLeaseUntil = relayLeaseUntil;
    }
}
