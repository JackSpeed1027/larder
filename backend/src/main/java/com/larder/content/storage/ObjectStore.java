package com.larder.content.storage;

/**
 * 受控对象存储端口（S3 兼容）。
 *
 * <p>业务只依赖本接口：换 MinIO/其它 S3 实现时只改 adapter（ADR-0004）。
 * 关键语义：<b>key 由内容 hash 派生</b>，同 key 同内容幂等复用，同 key 不同内容必须拒绝而不是覆盖。
 */
public interface ObjectStore {

    /**
     * 写入不可变对象；若 key 已存在：
     * <ul>
     *   <li>已存在对象的 sha256 与本次一致 → 视为幂等复用，返回已有对象；</li>
     *   <li>不一致 → 抛 {@link ArtifactConflictException}，绝不覆盖。</li>
     * </ul>
     */
    StoredObject putImmutable(String key, byte[] content, String sha256, String mime);

    /** 对象是否存在（用于孤儿对象对账与测试）。 */
    boolean exists(String key);

    /** 读取对象内容（对账/校验用，业务下载走短期限定 URL）。 */
    byte[] get(String key);

    /** 写入结果：key、对象版本（未启用版本时为空）、服务端确认的 sha256。 */
    record StoredObject(String key, String versionId, String sha256) {
    }
}
