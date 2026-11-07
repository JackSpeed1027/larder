package com.larder.meta;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 应用元数据实体，对应 V1__baseline.sql 的 app_meta 表。
 *
 * <p>当前只用于健康检查读取 schema_version；后续需要全局配置/版本标记时可扩展，
 * 不把业务字段塞进这张表。
 */
@TableName("app_meta")
public class AppMeta {

    /** 元数据键，主键（如 schema_version）。 */
    @TableId("meta_key")
    private String metaKey;

    /** 元数据值。 */
    private String metaValue;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    public String getMetaKey() {
        return metaKey;
    }

    public void setMetaKey(String metaKey) {
        this.metaKey = metaKey;
    }

    public String getMetaValue() {
        return metaValue;
    }

    public void setMetaValue(String metaValue) {
        this.metaValue = metaValue;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
