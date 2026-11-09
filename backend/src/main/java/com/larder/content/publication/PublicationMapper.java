package com.larder.content.publication;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 发布事务 SQL：锁 job → 写不可变 revision → 绑定产物 → 移动 item 指针 → 提交 job。
 *
 * <p>所有写操作都在同一个事务里；最后一步的条件更新（run_id + epoch + RUNNING）
 * 是 fencing：租约被接管或用户重新抓取后，旧 run 的结果不会落库。
 */
@Mapper
public interface PublicationMapper {

    @Select("SELECT id AS jobId, owner_id AS ownerId, item_id AS itemId, state AS state, "
            + "processing_epoch AS processingEpoch, run_id AS runId FROM job WHERE id = #{jobId} FOR UPDATE")
    JobPublishRow lockJob(@Param("jobId") String jobId);

    @Select("SELECT COALESCE(MAX(revision_no), 0) FROM content_revision "
            + "WHERE owner_id = #{ownerId} AND item_id = #{itemId}")
    long maxRevisionNo(@Param("ownerId") String ownerId, @Param("itemId") String itemId);

    @Insert("INSERT INTO content_revision (id, owner_id, item_id, revision_no, source_hash, "
            + "extractor_version, chunker_version, manifest_hash, manifest_json, created_at) "
            + "VALUES (#{id}, #{ownerId}, #{itemId}, #{revisionNo}, #{sourceHash}, "
            + "#{extractorVersion}, #{chunkerVersion}, #{manifestHash}, #{manifestJson}, #{createdAt})")
    int insertRevision(@Param("id") String id, @Param("ownerId") String ownerId,
                       @Param("itemId") String itemId, @Param("revisionNo") long revisionNo,
                       @Param("sourceHash") String sourceHash, @Param("extractorVersion") String extractorVersion,
                       @Param("chunkerVersion") String chunkerVersion, @Param("manifestHash") String manifestHash,
                       @Param("manifestJson") String manifestJson, @Param("createdAt") LocalDateTime createdAt);

    @Insert("INSERT INTO revision_artifact (owner_id, revision_id, artifact_id, role, ordinal) "
            + "VALUES (#{ownerId}, #{revisionId}, #{artifactId}, 'BODY', 0)")
    int bindBodyArtifact(@Param("ownerId") String ownerId, @Param("revisionId") String revisionId,
                         @Param("artifactId") String artifactId);

    @Update("UPDATE item SET current_revision_id = #{revisionId}, row_version = row_version + 1, "
            + "updated_at = UTC_TIMESTAMP(6) WHERE id = #{itemId} AND owner_id = #{ownerId}")
    int pointCurrentRevision(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                             @Param("revisionId") String revisionId);

    @Update("UPDATE job SET state = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL, "
            + "last_event_seq = last_event_seq + 1, row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND run_id = #{runId} "
            + "AND processing_epoch = #{epoch}")
    int commitJob(@Param("jobId") String jobId, @Param("runId") String runId, @Param("epoch") long epoch);

    @Select("SELECT id FROM content_revision WHERE owner_id = #{ownerId} AND item_id = #{itemId} "
            + "AND source_hash = #{sourceHash} ORDER BY revision_no DESC LIMIT 1")
    String findRevisionBySource(@Param("ownerId") String ownerId, @Param("itemId") String itemId,
                                @Param("sourceHash") String sourceHash);

    @Select("SELECT id FROM content_revision WHERE owner_id = #{ownerId} AND item_id = #{itemId} "
            + "ORDER BY revision_no DESC LIMIT 1")
    String latestRevision(@Param("ownerId") String ownerId, @Param("itemId") String itemId);
}
