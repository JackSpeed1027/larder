package com.larder.pipeline.control;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 任务中心 SQL：取消、重试、列表/详情。
 *
 * <p>取消的语义分两种状态：
 * <ul>
 *   <li>QUEUED/RETRY_WAIT：还没开始 → 直接 CANCELLED，清租约；</li>
 *   <li>RUNNING：执行者正在跑 → 只写 cancel_requested_at，由执行者在检查点退出；
 *       即使它不退出，发布事务也会因 cancel_requested_at 非空而拒绝（A07）。</li>
 * </ul>
 */
@Mapper
public interface JobControlMapper {

    @Select("""
            SELECT id, owner_id AS ownerId, item_id AS itemId, kind, state, attempt, run_id AS runId,
                   processing_epoch AS processingEpoch, cancel_requested_at AS cancelRequestedAt,
                   error_code AS errorCode, error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt
              FROM job WHERE id = #{jobId}
            """)
    Map<String, Object> findJob(@Param("jobId") String jobId);

    @Update("""
            UPDATE job
               SET cancel_requested_at = COALESCE(cancel_requested_at, UTC_TIMESTAMP(6)),
                   state = CASE WHEN state IN ('QUEUED','RETRY_WAIT') THEN 'CANCELLED' ELSE state END,
                   lease_owner = CASE WHEN state = 'QUEUED' THEN NULL ELSE lease_owner END,
                   lease_until = CASE WHEN state = 'QUEUED' THEN NULL ELSE lease_until END,
                   updated_at = UTC_TIMESTAMP(6)
             WHERE id = #{jobId} AND owner_id = #{ownerId}
               AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','SUPERSEDED')
            """)
    int requestCancel(@Param("jobId") String jobId, @Param("ownerId") String ownerId);

    @Update("""
            UPDATE job
               SET state = 'QUEUED', attempt = attempt + 1, error_code = NULL, error_message = NULL,
                   cancel_requested_at = NULL, lease_owner = NULL, lease_until = NULL,
                   next_attempt_at = NULL, updated_at = UTC_TIMESTAMP(6)
             WHERE id = #{jobId} AND owner_id = #{ownerId} AND state IN ('FAILED','CANCELLED')
            """)
    int requeue(@Param("jobId") String jobId, @Param("ownerId") String ownerId);

    @Select("""
            <script>
            SELECT id, item_id AS itemId, kind, state, attempt, error_code AS errorCode,
                   created_at AS createdAt, updated_at AS updatedAt
              FROM job WHERE owner_id = #{ownerId}
             <if test="state != null">AND state = #{state}</if>
             <if test="itemId != null">AND item_id = #{itemId}</if>
             ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> listJobs(@Param("ownerId") String ownerId, @Param("state") String state,
                                       @Param("itemId") String itemId, @Param("limit") int limit,
                                       @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM job WHERE owner_id = #{ownerId}
             <if test="state != null">AND state = #{state}</if>
             <if test="itemId != null">AND item_id = #{itemId}</if>
            </script>
            """)
    long countJobs(@Param("ownerId") String ownerId, @Param("state") String state,
                   @Param("itemId") String itemId);
}
