package com.larder.content.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 可变内容入口（item 表）。
 *
 * <p>只映射本任务会写/读的列：其余列（current_revision_id、active_generation_id 等）
 * 由后续任务写入，依赖数据库默认值，不在这里预填假数据。
 *
 * <p>不可变正文在 content_revision；索引发布指针与正文指针分离（docs/16）。
 */
@TableName("item")
public class Item {

    @TableId("id")
    private String id;
    private String ownerId;
    private String kind;
    private String title;
    private String originalUrl;
    private String normalizedUrl;
    private String urlHash;
    private String currentRevisionId;
    private String activeGenerationId;
    private Long processingEpoch;
    private Long metadataVersion;
    private Long rowVersion;
    private String readStatus;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public String getNormalizedUrl() {
        return normalizedUrl;
    }

    public void setNormalizedUrl(String normalizedUrl) {
        this.normalizedUrl = normalizedUrl;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public String getCurrentRevisionId() {
        return currentRevisionId;
    }

    public void setCurrentRevisionId(String currentRevisionId) {
        this.currentRevisionId = currentRevisionId;
    }

    public String getActiveGenerationId() {
        return activeGenerationId;
    }

    public void setActiveGenerationId(String activeGenerationId) {
        this.activeGenerationId = activeGenerationId;
    }

    public Long getProcessingEpoch() {
        return processingEpoch;
    }

    public void setProcessingEpoch(Long processingEpoch) {
        this.processingEpoch = processingEpoch;
    }

    public Long getMetadataVersion() {
        return metadataVersion;
    }

    public void setMetadataVersion(Long metadataVersion) {
        this.metadataVersion = metadataVersion;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }

    public String getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(String readStatus) {
        this.readStatus = readStatus;
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
    }
}
