package com.larder.content.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.larder.ingest.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 受控产物接收：校验 → 写不可变对象 → 再发布 DB 引用。
 *
 * <p>顺序不可颠倒（docs/02/16）：如果先写 DB 再写对象，DB 里的引用会指向不存在的对象，
 * 用户点开就是 500；反过来"对象先写、DB 失败"只会留下可复用的孤立对象，由
 * {@link OrphanRecorder} 登记，不产生坏引用。
 *
 * <p>hash 只认服务端计算结果：调用方声明的 sha256 只用于"比对"，不作为真相；
 * multipart 的 ETag 更不是内容 hash（多段上传时是 "md5-N" 形式），所以从不使用。
 */
@Service
public class ArtifactService {

    /** V02 允许的产物类型；新增类型要同时改契约与这里。 */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "text/plain", "text/markdown", "text/html", "application/json", "application/pdf");

    static final String STATE_VERIFIED = "VERIFIED";

    private final ArtifactMapper artifactMapper;
    private final ObjectStore objectStore;
    private final OrphanRecorder orphanRecorder;
    private final long maxBytes;
    private final String bucket;

    public ArtifactService(ArtifactMapper artifactMapper,
                           ObjectStore objectStore,
                           OrphanRecorder orphanRecorder,
                           @Value("${larder.storage.max-bytes:20971520}") long maxBytes,
                           @Value("${larder.storage.bucket:larder-artifacts}") String bucket) {
        this.artifactMapper = artifactMapper;
        this.objectStore = objectStore;
        this.orphanRecorder = orphanRecorder;
        this.maxBytes = maxBytes;
        this.bucket = bucket;
    }

    /**
     * 接收一个产物。
     *
     * @param ownerId        归属（来自认证，不接受客户端传入）
     * @param content        内容字节
     * @param declaredSha256 调用方声明的 hash；可为 null（不比对），非 null 时必须等于服务端计算值
     * @param mime           媒体类型
     */
    public ArtifactView store(String ownerId, byte[] content, String declaredSha256, String mime) {
        if (content == null || content.length == 0) {
            throw new ArtifactStorageException("VALIDATION_FAILED", "产物内容为空");
        }
        if (content.length > maxBytes) {
            throw ArtifactStorageException.tooLarge(content.length, maxBytes);
        }
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            throw ArtifactStorageException.unsupportedMime(String.valueOf(mime));
        }
        String actualSha = ArtifactKeys.sha256(content);
        if (declaredSha256 != null && !declaredSha256.equalsIgnoreCase(actualSha)) {
            throw ArtifactStorageException.hashMismatch(declaredSha256, actualSha);
        }

        Artifact existing = findByHash(ownerId, actualSha, content.length, mime);
        if (existing != null) {
            return toView(existing);
        }

        String key = ArtifactKeys.forHash(ownerId, actualSha);
        ObjectStore.StoredObject stored = objectStore.putImmutable(key, content, actualSha, mime);

        Artifact row = new Artifact();
        row.setId(Ids.next("art"));
        row.setOwnerId(ownerId);
        row.setBucket(bucket);
        row.setObjectKey(stored.key());
        row.setObjectVersion(stored.versionId());
        row.setSha256(actualSha);
        row.setBytes((long) content.length);
        row.setMime(mime);
        row.setState(STATE_VERIFIED);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            artifactMapper.insert(row);
        } catch (RuntimeException dbFailure) {
            // 对象已在存储里：不删除（可能被并发复用），登记为孤立对象供对账
            orphanRecorder.record(stored.key(), actualSha, content.length, mime);
            throw new ArtifactStorageException("INTERNAL_ERROR",
                    "对象已写入但元数据落库失败，已登记孤立对象: " + stored.key(), dbFailure);
        }
        return toView(row);
    }

    private Artifact findByHash(String ownerId, String sha256, long bytes, String mime) {
        return artifactMapper.selectOne(Wrappers.<Artifact>lambdaQuery()
                .eq(Artifact::getOwnerId, ownerId)
                .eq(Artifact::getSha256, sha256)
                .eq(Artifact::getBytes, bytes)
                .eq(Artifact::getMime, mime)
                .last("limit 1"));
    }

    private ArtifactView toView(Artifact row) {
        return new ArtifactView(row.getId(), row.getSha256(), row.getBytes(), row.getMime(),
                row.getObjectKey(), row.getState());
    }
}
