package com.larder.organize;

import com.larder.content.storage.ArtifactKeys;
import com.larder.content.storage.ObjectStore;
import com.larder.ingest.OwnerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 阅读/标签/笔记的边界：固定 revision、哈希校验、归属校验、参数校验。 */
class OrganizeServiceTest {

    private OrganizeMapper mapper;
    private ObjectStore objectStore;
    private OwnerService ownerService;
    private OrganizeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OrganizeMapper.class);
        objectStore = mock(ObjectStore.class);
        ownerService = mock(OwnerService.class);
        service = new OrganizeService(mapper, objectStore, ownerService);
        when(ownerService.ownerIdFor("admin")).thenReturn("own_1");
    }

    @Test
    @DisplayName("阅读固定 revision：从对象存储读正文，哈希一致才返回")
    void readsPinnedRevision() {
        byte[] text = "正文内容".getBytes(StandardCharsets.UTF_8);
        String sha = ArtifactKeys.sha256(text);
        when(mapper.findRevision("own_1", "itm_1", "rev_1"))
                .thenReturn(Map.of("revisionNo", 1, "sourceHash", sha));
        when(mapper.findBodyArtifact("own_1", "rev_1"))
                .thenReturn(Map.of("objectKey", "artifacts/own_1/aa/" + sha, "sha256", sha));
        when(objectStore.get("artifacts/own_1/aa/" + sha)).thenReturn(text);

        Map<String, Object> result = service.readRevision("admin", "itm_1", "rev_1");

        assertThat(result.get("text")).isEqualTo("正文内容");
        assertThat(result.get("revisionId")).isEqualTo("rev_1");
    }

    @Test
    @DisplayName("正文哈希不符：拒绝展示，不返回伪造正文")
    void rejectsCorruptedBody() {
        when(mapper.findRevision("own_1", "itm_1", "rev_1"))
                .thenReturn(Map.of("revisionNo", 1, "sourceHash", "a".repeat(64)));
        when(mapper.findBodyArtifact("own_1", "rev_1"))
                .thenReturn(Map.of("objectKey", "k", "sha256", "b".repeat(64)));
        when(objectStore.get("k")).thenReturn("tampered".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.readRevision("admin", "itm_1", "rev_1"))
                .isInstanceOf(OrganizeService.OrganizeException.class)
                .satisfies(ex -> assertThat(((OrganizeService.OrganizeException) ex).code())
                        .isEqualTo("CORRUPT_ARTIFACT"));
    }

    @Test
    @DisplayName("条目不存在：404")
    void detailMissingItem() {
        when(mapper.findItem("own_1", "itm_x")).thenReturn(null);
        assertThatThrownBy(() -> service.detail("admin", "itm_x"))
                .isInstanceOf(OrganizeService.OrganizeException.class)
                .satisfies(ex -> assertThat(((OrganizeService.OrganizeException) ex).status()).isEqualTo(404));
    }

    @Test
    @DisplayName("笔记：revision 不属于该条目 → 400")
    void rejectsForeignRevision() {
        when(mapper.findItem("own_1", "itm_1")).thenReturn(Map.of("id", "itm_1"));
        when(mapper.findRevision("own_1", "itm_1", "rev_x")).thenReturn(null);

        assertThatThrownBy(() -> service.createNote("admin", "itm_1", "rev_x", "note"))
                .isInstanceOf(OrganizeService.OrganizeException.class)
                .satisfies(ex -> assertThat(((OrganizeService.OrganizeException) ex).status()).isEqualTo(400));
        verify(mapper, never()).insertNote(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("笔记：空内容 → 400")
    void rejectsEmptyNote() {
        when(mapper.findItem("own_1", "itm_1")).thenReturn(Map.of("id", "itm_1"));
        assertThatThrownBy(() -> service.createNote("admin", "itm_1", null, "   "))
                .isInstanceOf(OrganizeService.OrganizeException.class);
    }

    @Test
    @DisplayName("同一标签名重复创建：返回已有，不重复插入")
    void createTagIsIdempotent() {
        when(mapper.findTagByNormalizedName("own_1", "java")).thenReturn(Map.of("id", "tag_1", "name", "Java"));

        Map<String, Object> tag = service.createTag("admin", "  Java  ");

        assertThat(tag.get("id")).isEqualTo("tag_1");
        verify(mapper, never()).insertTag(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("打标签：标签不存在 → 404")
    void attachUnknownTag() {
        when(mapper.findItem("own_1", "itm_1")).thenReturn(Map.of("id", "itm_1"));
        when(mapper.listTags("own_1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.attachTag("admin", "itm_1", "tag_x"))
                .isInstanceOf(OrganizeService.OrganizeException.class)
                .satisfies(ex -> assertThat(((OrganizeService.OrganizeException) ex).status()).isEqualTo(404));
    }

    @Test
    @DisplayName("阅读位置：progress 越界 → 400，且不写库")
    void rejectsInvalidProgress() {
        assertThatThrownBy(() -> service.saveReadingPosition("admin", "itm_1", "rev_1", 1.5, "{}"))
                .isInstanceOf(OrganizeService.OrganizeException.class);
        verify(mapper, never()).upsertReadingPosition(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("阅读位置：revision 不属于条目 → 400")
    void rejectsPositionForForeignRevision() {
        when(mapper.findRevision("own_1", "itm_1", "rev_x")).thenReturn(null);
        assertThatThrownBy(() -> service.saveReadingPosition("admin", "itm_1", "rev_x", 0.5, "{}"))
                .isInstanceOf(OrganizeService.OrganizeException.class);
    }

    @Test
    @DisplayName("列表：limit 上限被收敛到 100")
    void clampsLimit() {
        when(mapper.listItems(eq("own_1"), any(), any(), any(), eq(100), eq(0))).thenReturn(List.of());
        when(mapper.countItems(eq("own_1"), any(), any(), any())).thenReturn(0L);

        Map<String, Object> page = service.list("admin", null, null, null, 5000, 0);

        assertThat(page.get("limit")).isEqualTo(100);
    }
}
