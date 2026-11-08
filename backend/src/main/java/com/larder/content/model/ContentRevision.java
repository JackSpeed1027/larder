package com.larder.content.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 不可变正文版本（content_revision）。
 *
 * <p>本任务只定义模型，不写入：正文由 V02-T03 的抓取/抽取流水线产生。
 * 列保持与 contracts/mysql-schema.sql 一致，避免后续任务重复改表。
 */
@TableName("content_revision")
public class ContentRevision {

    @TableId("id")
    private String id;
    private String ownerId;
    private String itemId;
    private Long revisionNo;
    private String sourceHash;
    private String extractorVersion;
    private String chunkerVersion;
    private String manifestHash;
    private String manifestJson;
    private LocalDateTime createdAt;

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

    public Long getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(Long revisionNo) {
        this.revisionNo = revisionNo;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public void setExtractorVersion(String extractorVersion) {
        this.extractorVersion = extractorVersion;
    }

    public String getChunkerVersion() {
        return chunkerVersion;
    }

    public void setChunkerVersion(String chunkerVersion) {
        this.chunkerVersion = chunkerVersion;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void setManifestHash(String manifestHash) {
        this.manifestHash = manifestHash;
    }

    public String getManifestJson() {
        return manifestJson;
    }

    public void setManifestJson(String manifestJson) {
        this.manifestJson = manifestJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
