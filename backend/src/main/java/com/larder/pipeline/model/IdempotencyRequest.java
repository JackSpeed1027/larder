package com.larder.pipeline.model;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 公开命令幂等键与响应快照
 *
 * <p>列名与 contracts/mysql-schema.sql 一致；只映射本任务用到/写入的列，
 * 其余列交给数据库默认值，不在这里预填假数据。
 */
@TableName("idempotency_request")
public class IdempotencyRequest {

    private String ownerId;
    private String routeKey;
    private String requestKey;
    private String bodyHash;
    private String state;
    private Integer responseStatus;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public void setRouteKey(String routeKey) {
        this.routeKey = routeKey;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getBodyHash() {
        return bodyHash;
    }

    public void setBodyHash(String bodyHash) {
        this.bodyHash = bodyHash;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }}
