package com.larder.pipeline.model;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Java 业务任务权威（V02-T01 只创建 INGEST 任务）
 *
 * <p>列名与 contracts/mysql-schema.sql 一致；只映射本任务用到/写入的列，
 * 其余列交给数据库默认值，不在这里预填假数据。
 */
@TableName("job")
public class Job {

    @TableId("id")
    private String id;
    private String ownerId;
    private String itemId;
    private String kind;
    private String state;
    private Long processingEpoch;
    private Integer attempt;
    private String payloadHash;
    private String inputJson;
    private Long lastEventSeq;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
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

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public Long getLastEventSeq() {
        return lastEventSeq;
    }

    public void setLastEventSeq(Long lastEventSeq) {
        this.lastEventSeq = lastEventSeq;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }}
