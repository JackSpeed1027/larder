package com.larder.content.storage;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物接收的状态机测试（用内存 ObjectStore，不连 MinIO）。
 *
 * <p>重点覆盖可证伪的边界：hash 不符、超限、mime 不支持、幂等复用、
 * 写入失败不落库、落库失败登记孤立对象。
 */
class ArtifactServiceTest {

    /** 内存实现：模拟"同 key 不同内容必须拒绝"的不可变语义。 */
    static class FakeStore implements ObjectStore {
        final Map<String, byte[]> data = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        final List<String> putKeys = new ArrayList<>();
        RuntimeException failOnPut;

        @Override
        public StoredObject putImmutable(String key, byte[] content, String sha256, String mime) {
            if (failOnPut != null) {
                throw failOnPut;
            }
            putKeys.add(key);
            if (data.containsKey(key)) {
                String existing = hashes.get(key);
                if (!existing.equals(sha256)) {
                    throw new ArtifactConflictException(key, existing, sha256);
                }
                return new StoredObject(key, null, existing);
            }
            data.put(key, content);
            hashes.put(key, sha256);
            return new StoredObject(key, null, sha256);
        }

        @Override
        public boolean exists(String key) {
            return data.containsKey(key);
        }

        @Override
        public byte[] get(String key) {
            return data.get(key);
        }
    }

    private ArtifactMapper mapper;
    private FakeStore store;
    private Path orphanFile;
    private ArtifactService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(ArtifactMapper.class);
        store = new FakeStore();
        orphanFile = Files.createTempFile("orphans", ".jsonl");
        service = new ArtifactService(mapper, store, new OrphanRecorder(orphanFile.toString()), 64, "bucket-test");
    }

    @Test
    @DisplayName("正常产物：写对象一次，DB 记 VERIFIED，key 由 hash 派生")
    void storesVerifiedArtifact() {
        byte[] content = "hello artifact".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactKeys.sha256(content);

        ArtifactView view = service.store("own_1", content, sha, "text/plain");

        assertThat(view.sha256()).isEqualTo(sha);
        assertThat(view.state()).isEqualTo("VERIFIED");
        assertThat(view.objectKey()).isEqualTo("artifacts/own_1/" + sha.substring(0, 2) + "/" + sha);
        assertThat(store.putKeys).hasSize(1);

        ArgumentCaptor<Artifact> captor = ArgumentCaptor.forClass(Artifact.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSha256()).isEqualTo(sha);
        assertThat(captor.getValue().getBucket()).isEqualTo("bucket-test");
        assertThat(captor.getValue().getBytes()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("声明 hash 与服务端计算不一致：拒绝，且不写对象、不落库")
    void rejectsHashMismatch() {
        byte[] content = "tampered".getBytes(StandardCharsets.UTF_8);
        String wrong = "0".repeat(64);

        assertThatThrownBy(() -> service.store("own_1", content, wrong, "text/plain"))
                .isInstanceOf(ArtifactStorageException.class)
                .satisfies(ex -> assertThat(((ArtifactStorageException) ex).code()).isEqualTo("CORRUPT_ARTIFACT"));

        assertThat(store.putKeys).isEmpty();
        verify(mapper, never()).insert(any(Artifact.class));
    }

    @Test
    @DisplayName("超过大小上限：拒绝，错误码 PAYLOAD_TOO_LARGE")
    void rejectsOversize() {
        byte[] tooBig = new byte[65];
        assertThatThrownBy(() -> service.store("own_1", tooBig, null, "text/plain"))
                .isInstanceOf(ArtifactStorageException.class)
                .satisfies(ex -> assertThat(((ArtifactStorageException) ex).code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        assertThat(store.putKeys).isEmpty();
    }

    @Test
    @DisplayName("不支持的 mime：拒绝")
    void rejectsUnsupportedMime() {
        assertThatThrownBy(() -> service.store("own_1", "x".getBytes(StandardCharsets.UTF_8), null, "application/zip"))
                .isInstanceOf(ArtifactStorageException.class)
                .satisfies(ex -> assertThat(((ArtifactStorageException) ex).code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("同内容重复接收：直接返回已有记录，不重复写对象")
    void dedupesByHash() {
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactKeys.sha256(content);
        Artifact existing = new Artifact();
        existing.setId("art_existing");
        existing.setOwnerId("own_1");
        existing.setSha256(sha);
        existing.setBytes((long) content.length);
        existing.setMime("text/plain");
        existing.setObjectKey("artifacts/own_1/" + sha.substring(0, 2) + "/" + sha);
        existing.setState("VERIFIED");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ArtifactView view = service.store("own_1", content, sha, "text/plain");

        assertThat(view.id()).isEqualTo("art_existing");
        assertThat(store.putKeys).isEmpty();
        verify(mapper, never()).insert(any(Artifact.class));
    }

    @Test
    @DisplayName("对象写入失败：不落库（不会出现指向不存在对象的引用）")
    void doesNotPersistWhenObjectWriteFails() {
        store.failOnPut = new ArtifactStorageException("DEPENDENCY_UNAVAILABLE", "minio down");
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.store("own_1", content, null, "text/plain"))
                .isInstanceOf(ArtifactStorageException.class);
        verify(mapper, never()).insert(any(Artifact.class));
    }

    @Test
    @DisplayName("对象写成功但落库失败：异常 + 孤立对象清单有记录")
    void recordsOrphanWhenDbFails() throws Exception {
        when(mapper.insert(any(Artifact.class))).thenThrow(new RuntimeException("db down"));
        byte[] content = "orphan-me".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactKeys.sha256(content);

        assertThatThrownBy(() -> service.store("own_1", content, null, "text/plain"))
                .isInstanceOf(ArtifactStorageException.class)
                .satisfies(ex -> assertThat(((ArtifactStorageException) ex).code()).isEqualTo("INTERNAL_ERROR"));

        String recorded = Files.readString(orphanFile);
        assertThat(recorded).contains("\"sha256\":\"" + sha + "\"")
                .contains("artifacts/own_1/" + sha.substring(0, 2) + "/" + sha);
    }
}
