package com.larder.pipeline.model;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 与业务同事务的待投递通知（至少一次）
 *
 * <p>列名与 contracts/mysql-schema.sql 一致；只映射本任务用到/写入的列，
 * 其余列交给数据库默认值，不在这里预填假数据。
 */
@TableName("outbox")
public class OutboxEvent {

    private String eventId;
    private String ownerId;
    private String jobId;
    private String eventType;
    private Long wakeGeneration;
    private String payloadJson;
    private String payloadHash;
    private String state;
    private LocalDateTime availableAt;
    private Integer attempt;
    private LocalDateTime createdAt;

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

    public Long getWakeGeneration() {
        return wakeGeneration;
    }

    public void setWakeGeneration(Long wakeGeneration) {
        this.wakeGeneration = wakeGeneration;
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

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(LocalDateTime availableAt) {
        this.availableAt = availableAt;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }}
