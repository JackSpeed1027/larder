package com.larder.organize;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 阅读/标签/笔记/集合的 SQL。
 *
 * <p>所有查询都带 owner_id：单管理员也做归属校验，避免以后多用户时到处补条件。
 * 返回 Map 而不是 POJO：这些读模型字段少、只服务 API 组装，用 Map 省掉大量样板类。
 */
@Mapper
public interface OrganizeMapper {

    @Select("""
            <script>
            SELECT id, kind, title, current_revision_id AS currentRevisionId, read_status AS readStatus,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM item
             WHERE owner_id = #{ownerId} AND deleted_at IS NULL
             <if test="tagId != null">AND id IN (SELECT item_id FROM item_tag WHERE owner_id = #{ownerId} AND tag_id = #{tagId})</if>
             <if test="readStatus != null">AND read_status = #{readStatus}</if>
             <if test="query != null">AND title LIKE CONCAT('%', #{query}, '%')</if>
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> listItems(@Param("ownerId") String ownerId, @Param("query") String query,
                                        @Param("tagId") String tagId, @Param("readStatus") String readStatus,
                                        @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM item WHERE owner_id = #{ownerId} AND deleted_at IS NULL
             <if test="tagId != null">AND id IN (SELECT item_id FROM item_tag WHERE owner_id = #{ownerId} AND tag_id = #{tagId})</if>
             <if test="readStatus != null">AND read_status = #{readStatus}</if>
             <if test="query != null">AND title LIKE CONCAT('%', #{query}, '%')</if>
            </script>
            """)
    long countItems(@Param("ownerId") String ownerId, @Param("query") String query,
                    @Param("tagId") String tagId, @Param("readStatus") String readStatus);

    @Select("""
            SELECT id, kind, title, original_url AS originalUrl, current_revision_id AS currentRevisionId,
                   read_status AS readStatus, created_at AS createdAt, updated_at AS updatedAt
              FROM item WHERE owner_id = #{ownerId} AND id = #{itemId} AND deleted_at IS NULL
            """)
    Map<String, Object> findItem(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Select("""
            SELECT id, item_id AS itemId, revision_no AS revisionNo, source_hash AS sourceHash, created_at AS createdAt
              FROM content_revision
             WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND id = #{revisionId}
            """)
    Map<String, Object> findRevision(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                                     @Param("revisionId") String revisionId);

    @Select("""
            SELECT a.object_key AS objectKey, a.sha256 AS sha256, a.bytes AS bytes, a.mime AS mime
              FROM revision_artifact ra JOIN artifact a
                ON a.owner_id = ra.owner_id AND a.id = ra.artifact_id
             WHERE ra.owner_id = #{ownerId} AND ra.revision_id = #{revisionId} AND ra.role = 'BODY'
             ORDER BY ra.ordinal LIMIT 1
            """)
    Map<String, Object> findBodyArtifact(@Param("ownerId") String ownerId, @Param("revisionId") String revisionId);

    @Update("UPDATE item SET read_status = #{readStatus}, row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE owner_id = #{ownerId} AND id = #{itemId} AND deleted_at IS NULL")
    int updateReadStatus(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                         @Param("readStatus") String readStatus);

    @Select("SELECT id, name FROM tag WHERE owner_id = #{ownerId} ORDER BY name")
    List<Map<String, Object>> listTags(@Param("ownerId") String ownerId);

    @Select("SELECT id, name FROM tag WHERE owner_id = #{ownerId} AND normalized_name = #{normalizedName}")
    Map<String, Object> findTagByNormalizedName(@Param("ownerId") String ownerId,
                                                @Param("normalizedName") String normalizedName);

    @Insert("INSERT INTO tag (id, owner_id, name, normalized_name, created_at) "
            + "VALUES (#{id}, #{ownerId}, #{name}, #{normalizedName}, UTC_TIMESTAMP(6))")
    int insertTag(@Param("id") String id, @Param("ownerId") String ownerId, @Param("name") String name,
                  @Param("normalizedName") String normalizedName);

    @Insert("INSERT IGNORE INTO item_tag (owner_id, item_id, tag_id) VALUES (#{ownerId}, #{itemId}, #{tagId})")
    int attachTag(@Param("ownerId") String ownerId, @Param("itemId") String itemId, @Param("tagId") String tagId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM item_tag WHERE owner_id = #{ownerId} "
            + "AND item_id = #{itemId} AND tag_id = #{tagId}")
    int detachTag(@Param("ownerId") String ownerId, @Param("itemId") String itemId, @Param("tagId") String tagId);

    @Select("SELECT t.id, t.name FROM item_tag it JOIN tag t ON t.owner_id = it.owner_id AND t.id = it.tag_id "
            + "WHERE it.owner_id = #{ownerId} AND it.item_id = #{itemId} ORDER BY t.name")
    List<Map<String, Object>> listItemTags(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Select("""
            SELECT id, item_id AS itemId, revision_id AS revisionId, markdown, created_at AS createdAt, updated_at AS updatedAt
              FROM note WHERE owner_id = #{ownerId} AND item_id = #{itemId} ORDER BY created_at DESC, id DESC
            """)
    List<Map<String, Object>> listNotes(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Insert("INSERT INTO note (id, owner_id, item_id, revision_id, markdown, created_at, updated_at) "
            + "VALUES (#{id}, #{ownerId}, #{itemId}, #{revisionId}, #{markdown}, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
    int insertNote(@Param("id") String id, @Param("ownerId") String ownerId, @Param("itemId") String itemId,
                   @Param("revisionId") String revisionId, @Param("markdown") String markdown);

    @Select("SELECT id, item_id AS itemId, revision_id AS revisionId, markdown, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM note WHERE owner_id = #{ownerId} AND id = #{noteId}")
    Map<String, Object> findNote(@Param("ownerId") String ownerId, @Param("noteId") String noteId);

    @Update("UPDATE note SET markdown = #{markdown}, row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE owner_id = #{ownerId} AND id = #{noteId}")
    int updateNote(@Param("ownerId") String ownerId, @Param("noteId") String noteId, @Param("markdown") String markdown);

    @org.apache.ibatis.annotations.Delete("DELETE FROM note WHERE owner_id = #{ownerId} AND id = #{noteId}")
    int deleteNote(@Param("ownerId") String ownerId, @Param("noteId") String noteId);

    @Select("SELECT id, name, description FROM collection WHERE owner_id = #{ownerId} AND archived_at IS NULL ORDER BY name")
    List<Map<String, Object>> listCollections(@Param("ownerId") String ownerId);

    @Insert("INSERT INTO collection (id, owner_id, name, normalized_name, description, created_at) "
            + "VALUES (#{id}, #{ownerId}, #{name}, #{normalizedName}, #{description}, UTC_TIMESTAMP(6))")
    int insertCollection(@Param("id") String id, @Param("ownerId") String ownerId, @Param("name") String name,
                         @Param("normalizedName") String normalizedName, @Param("description") String description);

    @Insert("INSERT IGNORE INTO collection_item (owner_id, collection_id, item_id, added_at) "
            + "VALUES (#{ownerId}, #{collectionId}, #{itemId}, UTC_TIMESTAMP(6))")
    int addToCollection(@Param("ownerId") String ownerId, @Param("collectionId") String collectionId,
                        @Param("itemId") String itemId);

    @Select("SELECT i.id, i.title FROM collection_item ci JOIN item i ON i.owner_id = ci.owner_id AND i.id = ci.item_id "
            + "WHERE ci.owner_id = #{ownerId} AND ci.collection_id = #{collectionId} ORDER BY ci.added_at DESC")
    List<Map<String, Object>> listCollectionItems(@Param("ownerId") String ownerId,
                                                  @Param("collectionId") String collectionId);

    @Select("SELECT revision_id AS revisionId, anchor_json AS anchorJson, progress, updated_at AS updatedAt "
            + "FROM reading_position WHERE owner_id = #{ownerId} AND item_id = #{itemId} AND revision_id = #{revisionId}")
    Map<String, Object> findReadingPosition(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                                            @Param("revisionId") String revisionId);

    @Insert("INSERT INTO reading_position (owner_id, item_id, revision_id, anchor_json, progress, updated_at) "
            + "VALUES (#{ownerId}, #{itemId}, #{revisionId}, #{anchorJson}, #{progress}, UTC_TIMESTAMP(6)) "
            + "ON DUPLICATE KEY UPDATE anchor_json = VALUES(anchor_json), progress = VALUES(progress), "
            + "row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)")
    int upsertReadingPosition(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                              @Param("revisionId") String revisionId, @Param("anchorJson") String anchorJson,
                              @Param("progress") BigDecimal progress);

    @Select("SELECT COUNT(*) FROM chunk WHERE owner_id = #{ownerId} AND revision_id = #{revisionId}")
    long countChunks(@Param("ownerId") String ownerId, @Param("revisionId") String revisionId);

    @Select("SELECT COUNT(*) FROM note WHERE owner_id = #{ownerId} AND item_id = #{itemId}")
    long countNotes(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Select("SELECT COUNT(*) FROM item_tag WHERE owner_id = #{ownerId} AND item_id = #{itemId}")
    long countItemTags(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Select("SELECT updated_at AS updatedAt FROM item WHERE owner_id = #{ownerId} AND id = #{itemId}")
    LocalDateTime itemUpdatedAt(@Param("ownerId") String ownerId, @Param("itemId") String itemId);
}
