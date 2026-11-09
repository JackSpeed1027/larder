package com.larder.pipeline.inbox;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * inbox 表：消息消费去重与"持久接管"凭据。
 *
 * <p>语义边界（契约注释原文）：**不代表业务已完成**。它只证明"这条事件被这个消费者接收过，
 * 且当时完成了一次 job 接管"。job 是否真的执行完，看 job.state，不看 inbox。
 */
@TableName("inbox")
public class InboxRecord {

    private String consumer;
    private String eventId;
    private String jobId;
    private String payloadHash;
    private LocalDateTime receivedAt;
    private LocalDateTime acceptedAt;

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }
}
