package com.larder.organize;

import com.larder.content.storage.ArtifactKeys;
import com.larder.content.storage.ObjectStore;
import com.larder.ingest.Ids;
import com.larder.ingest.OwnerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阅读/标签/笔记/集合的业务实现（V02-T04 后端 slice）。
 *
 * <p>两个不可让步的规则：
 * <ol>
 *   <li>阅读永远引用**固定 revision**，不跟随 current_revision_id 漂移；</li>
 *   <li>正文只从产物存储读，且校验 sha256；哈希不符就报错，绝不返回伪造/截断的正文。</li>
 * </ol>
 */
@Service
public class OrganizeService {

    private final OrganizeMapper mapper;
    private final ObjectStore objectStore;
    private final OwnerService ownerService;

    public OrganizeService(OrganizeMapper mapper, ObjectStore objectStore, OwnerService ownerService) {
        this.mapper = mapper;
        this.objectStore = objectStore;
        this.ownerService = ownerService;
    }

    public static class OrganizeException extends RuntimeException {
        private final String code;
        private final int status;

        public OrganizeException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    private String owner(String username) {
        return ownerService.ownerIdFor(username);
    }

    public Map<String, Object> list(String username, String query, String tagId, String readStatus,
                                    int limit, int offset) {
        String ownerId = owner(username);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        List<Map<String, Object>> items = mapper.listItems(ownerId, blankToNull(query), blankToNull(tagId),
                blankToNull(readStatus), safeLimit, safeOffset);
        long total = mapper.countItems(ownerId, blankToNull(query), blankToNull(tagId), blankToNull(readStatus));
        return Map.of("items", items, "total", total, "limit", safeLimit, "offset", safeOffset);
    }

    public Map<String, Object> detail(String username, String itemId) {
        String ownerId = owner(username);
        Map<String, Object> item = mapper.findItem(ownerId, itemId);
        if (item == null) {
            throw new OrganizeException("NOT_FOUND", "条目不存在", 404);
        }
        item.put("tags", mapper.listItemTags(ownerId, itemId));
        item.put("noteCount", mapper.countNotes(ownerId, itemId));
        return item;
    }

    /** 读取固定 revision 的正文；sha256 不符直接失败。 */
    public Map<String, Object> readRevision(String username, String itemId, String revisionId) {
        String ownerId = owner(username);
        Map<String, Object> revision = mapper.findRevision(ownerId, itemId, revisionId);
        if (revision == null) {
            throw new OrganizeException("NOT_FOUND", "revision 不存在", 404);
        }
        Map<String, Object> body = mapper.findBodyArtifact(ownerId, revisionId);
        if (body == null) {
            throw new OrganizeException("NOT_FOUND", "该 revision 没有正文产物", 404);
        }
        byte[] bytes = objectStore.get(String.valueOf(body.get("objectKey")));
        String actual = ArtifactKeys.sha256(bytes);
        if (!actual.equals(String.valueOf(body.get("sha256")))) {
            throw new OrganizeException("CORRUPT_ARTIFACT", "正文哈希与元数据不一致，拒绝展示", 500);
        }
        return Map.of(
                "revisionId", revisionId,
                "revisionNo", revision.get("revisionNo"),
                "sha256", actual,
                "text", new String(bytes, StandardCharsets.UTF_8));
    }

    public Map<String, Object> markRead(String username, String itemId, String readStatus) {
        String ownerId = owner(username);
        if (!List.of("UNREAD", "READING", "READ").contains(readStatus)) {
            throw new OrganizeException("VALIDATION_FAILED", "readStatus 不合法", 400);
        }
        if (mapper.updateReadStatus(ownerId, itemId, readStatus) == 0) {
            throw new OrganizeException("NOT_FOUND", "条目不存在", 404);
        }
        return detail(username, itemId);
    }

    public List<Map<String, Object>> tags(String username) {
        return mapper.listTags(owner(username));
    }

    public Map<String, Object> createTag(String username, String name) {
        String ownerId = owner(username);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 96) {
            throw new OrganizeException("VALIDATION_FAILED", "标签名长度必须在 1..96", 400);
        }
        String normalized = normalize(trimmed);
        Map<String, Object> existing = mapper.findTagByNormalizedName(ownerId, normalized);
        if (existing != null) {
            return existing;
        }
        String id = Ids.next("tag");
        mapper.insertTag(id, ownerId, trimmed, normalized);
        return Map.of("id", id, "name", trimmed);
    }

    public void attachTag(String username, String itemId, String tagId) {
        String ownerId = owner(username);
        requireItem(ownerId, itemId);
        if (!tagExists(ownerId, tagId)) {
            throw new OrganizeException("NOT_FOUND", "标签不存在", 404);
        }
        mapper.attachTag(ownerId, itemId, tagId);
    }

    public void detachTag(String username, String itemId, String tagId) {
        mapper.detachTag(owner(username), itemId, tagId);
    }

    public List<Map<String, Object>> notes(String username, String itemId) {
        String ownerId = owner(username);
        requireItem(ownerId, itemId);
        return mapper.listNotes(ownerId, itemId);
    }

    public Map<String, Object> createNote(String username, String itemId, String revisionId, String markdown) {
        String ownerId = owner(username);
        requireItem(ownerId, itemId);
        String text = markdown == null ? "" : markdown;
        if (text.isBlank()) {
            throw new OrganizeException("VALIDATION_FAILED", "笔记内容不能为空", 400);
        }
        if (revisionId != null && mapper.findRevision(ownerId, itemId, revisionId) == null) {
            throw new OrganizeException("VALIDATION_FAILED", "revision 不属于该条目", 400);
        }
        String id = Ids.next("note");
        mapper.insertNote(id, ownerId, itemId, revisionId, text);
        return mapper.findNote(ownerId, id);
    }

    public Map<String, Object> updateNote(String username, String noteId, String markdown) {
        String ownerId = owner(username);
        if (markdown == null || markdown.isBlank()) {
            throw new OrganizeException("VALIDATION_FAILED", "笔记内容不能为空", 400);
        }
        if (mapper.findNote(ownerId, noteId) == null) {
            throw new OrganizeException("NOT_FOUND", "笔记不存在", 404);
        }
        mapper.updateNote(ownerId, noteId, markdown);
        return mapper.findNote(ownerId, noteId);
    }

    public void deleteNote(String username, String noteId) {
        if (mapper.deleteNote(owner(username), noteId) == 0) {
            throw new OrganizeException("NOT_FOUND", "笔记不存在", 404);
        }
    }

    public List<Map<String, Object>> collections(String username) {
        return mapper.listCollections(owner(username));
    }

    public Map<String, Object> createCollection(String username, String name, String description) {
        String ownerId = owner(username);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new OrganizeException("VALIDATION_FAILED", "集合名长度必须在 1..128", 400);
        }
        String id = Ids.next("col");
        mapper.insertCollection(id, ownerId, trimmed, normalize(trimmed),
                description == null ? "" : description.substring(0, Math.min(description.length(), 1024)));
        return Map.of("id", id, "name", trimmed);
    }

    public void addToCollection(String username, String collectionId, String itemId) {
        String ownerId = owner(username);
        requireItem(ownerId, itemId);
        mapper.addToCollection(ownerId, collectionId, itemId);
    }

    public List<Map<String, Object>> collectionItems(String username, String collectionId) {
        return mapper.listCollectionItems(owner(username), collectionId);
    }

    public Map<String, Object> readingPosition(String username, String itemId, String revisionId) {
        Map<String, Object> found = mapper.findReadingPosition(owner(username), itemId, revisionId);
        return found == null ? Map.of("revisionId", revisionId, "progress", 0) : found;
    }

    @Transactional
    public Map<String, Object> saveReadingPosition(String username, String itemId, String revisionId,
                                                   double progress, String anchorJson) {
        String ownerId = owner(username);
        if (progress < 0 || progress > 1) {
            throw new OrganizeException("VALIDATION_FAILED", "progress 必须在 0..1", 400);
        }
        if (mapper.findRevision(ownerId, itemId, revisionId) == null) {
            throw new OrganizeException("VALIDATION_FAILED", "revision 不属于该条目", 400);
        }
        mapper.upsertReadingPosition(ownerId, itemId, revisionId,
                anchorJson == null || anchorJson.isBlank() ? "{}" : anchorJson,
                BigDecimal.valueOf(progress).setScale(5, java.math.RoundingMode.HALF_UP));
        return readingPosition(username, itemId, revisionId);
    }

    private void requireItem(String ownerId, String itemId) {
        if (mapper.findItem(ownerId, itemId) == null) {
            throw new OrganizeException("NOT_FOUND", "条目不存在", 404);
        }
    }

    private boolean tagExists(String ownerId, String tagId) {
        return mapper.listTags(ownerId).stream()
                .anyMatch(tag -> tagId.equals(String.valueOf(tag.get("id"))));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
