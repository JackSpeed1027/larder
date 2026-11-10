package com.larder.pipeline.lease;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租约与 fencing 提交 SQL。
 *
 * <p>所有提交/续租都带 {@code lease_token}（以及提交时的 {@code processing_epoch} 和 {@code run_id}）：
 * 数据库条件更新是最终的仲裁者，应用层只根据"影响行数是否为 1"判断自己是否还有资格。
 */
@Mapper
public interface JobLeaseMapper {

    String COLUMNS = "id AS jobId, owner_id AS ownerId, state AS state, attempt AS attempt, run_id AS runId, "
            + "processing_epoch AS processingEpoch, lease_owner AS leaseOwner, lease_token AS leaseToken, "
            + "lease_until AS leaseUntil, last_event_seq AS lastEventSeq, error_code AS errorCode, "
            + "updated_at AS updatedAt";

    @Select("SELECT " + COLUMNS + " FROM job WHERE id = #{jobId}")
    JobLeaseRow findJob(@Param("jobId") String jobId);

    /** 续租：只有仍持有同一 token、仍在 RUNNING、且租约未过期才生效。 */
    @Update("UPDATE job SET lease_until = #{leaseUntil}, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_owner = #{owner} "
            + "AND lease_token = #{token} AND lease_until > UTC_TIMESTAMP(6)")
    int renew(@Param("jobId") String jobId, @Param("owner") String owner,
              @Param("token") long token, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 分配稳定 runId：同一次执行只分配一次，接管与对账复用。 */
    @Update("UPDATE job SET run_id = #{runId}, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_owner = #{owner} "
            + "AND lease_token = #{token} AND run_id IS NULL")
    int assignRunId(@Param("jobId") String jobId, @Param("owner") String owner,
                    @Param("token") long token, @Param("runId") String runId);

    /** 成功提交：token + epoch + runId 三者都对上才允许落终态。 */
    @Update("UPDATE job SET state = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL, "
            + "row_version = row_version + 1, last_event_seq = last_event_seq + 1, "
            + "updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_owner = #{owner} "
            + "AND lease_token = #{token} AND processing_epoch = #{epoch} "
            + "AND run_id = #{runId} AND lease_until > UTC_TIMESTAMP(6)")
    int commitSuccess(@Param("jobId") String jobId, @Param("owner") String owner,
                      @Param("token") long token, @Param("epoch") long epoch,
                      @Param("runId") String runId);

    /** 失败提交：同样的 fencing 条件。 */
    @Update("UPDATE job SET state = #{state}, error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "lease_owner = NULL, lease_until = NULL, row_version = row_version + 1, "
            + "last_event_seq = last_event_seq + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_owner = #{owner} "
            + "AND lease_token = #{token} AND processing_epoch = #{epoch} AND run_id = #{runId} "
            + "AND lease_until > UTC_TIMESTAMP(6)")
    int commitFailure(@Param("jobId") String jobId, @Param("owner") String owner,
                      @Param("token") long token, @Param("epoch") long epoch,
                      @Param("runId") String runId, @Param("state") String state,
                      @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    /** 过期租约：RUNNING 且 lease_until 已过（或从未设置）。 */
    @Select("SELECT " + COLUMNS + " FROM job WHERE state = 'RUNNING' "
            + "AND (lease_until IS NULL OR lease_until < UTC_TIMESTAMP(6)) LIMIT #{limit}")
    List<JobLeaseRow> selectExpired(@Param("limit") int limit);

    /** QUEUED 但已到 next_attempt_at、且未被补偿扫描处理过的任务（S04）。 */
    @Select("SELECT " + COLUMNS + " FROM job WHERE state = 'QUEUED' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= UTC_TIMESTAMP(6)) "
            + "AND (next_reconcile_at IS NULL OR next_reconcile_at <= UTC_TIMESTAMP(6)) LIMIT #{limit}")
    List<JobLeaseRow> selectQueuedDue(@Param("limit") int limit);

    /** 补偿扫描防抖：短时间内不重复补发同一 job。 */
    @Update("UPDATE job SET next_reconcile_at = #{until}, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'QUEUED'")
    int markReconcile(@Param("jobId") String jobId, @Param("until") LocalDateTime until);

    /** 是否已有未投递事件：有则不重复补发（relay 会负责投递）。 */
    @Select("SELECT COUNT(*) FROM outbox WHERE job_id = #{jobId} AND state = 'PENDING'")
    int countPendingOutbox(@Param("jobId") String jobId);

    /** 对账收回：条件更新保证只收回未被别人抢先续租/提交的任务。 */
    @Update("UPDATE job SET state = #{state}, attempt = #{attempt}, lease_owner = NULL, lease_until = NULL, "
            + "next_attempt_at = #{nextAttemptAt}, next_reconcile_at = NULL, error_code = #{errorCode}, "
            + "row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_token = #{token} "
            + "AND (lease_until IS NULL OR lease_until < UTC_TIMESTAMP(6))")
    int reclaim(@Param("jobId") String jobId, @Param("token") long token, @Param("state") String state,
                @Param("attempt") int attempt, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                @Param("errorCode") String errorCode);
}
