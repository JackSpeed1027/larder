package com.larder.organize;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 阅读/标签/笔记/集合的公开接口。
 *
 * <p>owner 一律来自认证主体；阅读接口必须带 revisionId（固定版本），
 * 不提供"读最新"的隐式行为，避免正文在阅读过程中被悄悄替换。
 */
@RestController
@RequestMapping("/api")
public class OrganizeController {

    private final OrganizeService service;

    public OrganizeController(OrganizeService service) {
        this.service = service;
    }

    public record CreateTagRequest(String name) {
    }

    public record AttachTagRequest(String tagId) {
    }

    public record NoteRequest(String revisionId, String markdown) {
    }

    public record ReadStatusRequest(String readStatus) {
    }

    public record ReadingPositionRequest(String revisionId, double progress, String anchorJson) {
    }

    public record CollectionRequest(String name, String description, String itemId) {
    }

    // ---------- 库/阅读 ----------

    @GetMapping("/items")
    public Map<String, Object> list(@RequestParam(required = false) String query,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    Authentication auth) {
        return service.list(auth.getName(), query, tag, status, limit, offset);
    }

    @GetMapping("/items/{itemId}")
    public Map<String, Object> detail(@PathVariable String itemId, Authentication auth) {
        return service.detail(auth.getName(), itemId);
    }

    @GetMapping("/items/{itemId}/revisions/{revisionId}")
    public Map<String, Object> readRevision(@PathVariable String itemId, @PathVariable String revisionId,
                                            Authentication auth) {
        return service.readRevision(auth.getName(), itemId, revisionId);
    }

    @PatchMapping("/items/{itemId}")
    public Map<String, Object> updateItem(@PathVariable String itemId, @RequestBody ReadStatusRequest body,
                                          Authentication auth) {
        return service.markRead(auth.getName(), itemId, body.readStatus());
    }

    @GetMapping("/items/{itemId}/reading-position")
    public Map<String, Object> readingPosition(@PathVariable String itemId,
                                               @RequestParam String revisionId, Authentication auth) {
        return service.readingPosition(auth.getName(), itemId, revisionId);
    }

    @PutMapping("/items/{itemId}/reading-position")
    public Map<String, Object> saveReadingPosition(@PathVariable String itemId,
                                                   @RequestBody ReadingPositionRequest body,
                                                   Authentication auth) {
        return service.saveReadingPosition(auth.getName(), itemId, body.revisionId(),
                body.progress(), body.anchorJson());
    }

    // ---------- 标签 ----------

    @GetMapping("/tags")
    public List<Map<String, Object>> tags(Authentication auth) {
        return service.tags(auth.getName());
    }

    @PostMapping("/tags")
    public Map<String, Object> createTag(@RequestBody CreateTagRequest body, Authentication auth) {
        return service.createTag(auth.getName(), body.name());
    }

    @PostMapping("/items/{itemId}/tags")
    public ResponseEntity<Void> attachTag(@PathVariable String itemId, @RequestBody AttachTagRequest body,
                                          Authentication auth) {
        service.attachTag(auth.getName(), itemId, body.tagId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/items/{itemId}/tags/{tagId}")
    public ResponseEntity<Void> detachTag(@PathVariable String itemId, @PathVariable String tagId,
                                          Authentication auth) {
        service.detachTag(auth.getName(), itemId, tagId);
        return ResponseEntity.noContent().build();
    }

    // ---------- 笔记 ----------

    @GetMapping("/items/{itemId}/notes")
    public List<Map<String, Object>> notes(@PathVariable String itemId, Authentication auth) {
        return service.notes(auth.getName(), itemId);
    }

    @PostMapping("/items/{itemId}/notes")
    public Map<String, Object> createNote(@PathVariable String itemId, @RequestBody NoteRequest body,
                                          Authentication auth) {
        return service.createNote(auth.getName(), itemId, body.revisionId(), body.markdown());
    }

    @PatchMapping("/notes/{noteId}")
    public Map<String, Object> updateNote(@PathVariable String noteId, @RequestBody NoteRequest body,
                                          Authentication auth) {
        return service.updateNote(auth.getName(), noteId, body.markdown());
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable String noteId, Authentication auth) {
        service.deleteNote(auth.getName(), noteId);
        return ResponseEntity.noContent().build();
    }

    // ---------- 集合 ----------

    @GetMapping("/collections")
    public List<Map<String, Object>> collections(Authentication auth) {
        return service.collections(auth.getName());
    }

    @PostMapping("/collections")
    public Map<String, Object> createCollection(@RequestBody CollectionRequest body, Authentication auth) {
        return service.createCollection(auth.getName(), body.name(), body.description());
    }

    @PostMapping("/collections/{collectionId}/items")
    public ResponseEntity<Void> addToCollection(@PathVariable String collectionId, @RequestBody CollectionRequest body,
                                                Authentication auth) {
        service.addToCollection(auth.getName(), collectionId, body.itemId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/collections/{collectionId}/items")
    public List<Map<String, Object>> collectionItems(@PathVariable String collectionId, Authentication auth) {
        return service.collectionItems(auth.getName(), collectionId);
    }

    @ExceptionHandler(OrganizeService.OrganizeException.class)
    public ResponseEntity<Map<String, String>> onError(OrganizeService.OrganizeException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(Map.of(
                "code", ex.code(),
                "message", ex.getMessage() == null ? "" : ex.getMessage(),
                "traceId", traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String incoming = request.getHeader("X-Request-Id");
        return incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
    }
}
