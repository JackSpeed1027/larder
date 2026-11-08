package com.larder.content.storage;

/**
 * 已落库产物的对外视图。
 *
 * @param id         artifact 记录 ID
 * @param sha256     服务端计算的真实内容哈希
 * @param bytes      字节数
 * @param mime       校验后的媒体类型
 * @param objectKey  不可变对象 key（由 owner + hash 派生）
 * @param state      STAGED/VERIFIED/QUARANTINED
 */
public record ArtifactView(String id, String sha256, long bytes, String mime, String objectKey, String state) {
}
