package com.larder.pipeline.inbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 消费侧 SQL：inbox 去重 + job 持久接管。
 *
 * <p>接管用条件更新（{@code WHERE state IN ('QUEUED','RETRY_WAIT')}）：
 * 影响行数 1 才算领到；0 行表示别人已在执行或 job 已终结——这就是"持久接管"的原子性。
 */
@Mapper
public interface InboxMapper {

    @org.apache.ibatis.annotations.Insert(
            "INSERT INTO inbox (consumer, event_id, job_id, payload_hash, received_at, accepted_at) "
                    + "VALUES (#{consumer}, #{eventId}, #{jobId}, #{payloadHash}, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
    int insertInbox(@Param("consumer") String consumer, @Param("eventId") String eventId,
                    @Param("jobId") String jobId, @Param("payloadHash") String payloadHash);

    @Select("SELECT consumer, event_id AS eventId, job_id AS jobId, payload_hash AS payloadHash, "
            + "received_at AS receivedAt, accepted_at AS acceptedAt FROM inbox "
            + "WHERE consumer = #{consumer} AND event_id = #{eventId}")
    InboxRecord findInbox(@Param("consumer") String consumer, @Param("eventId") String eventId);

    /** 接管 job：QUEUED/RETRY_WAIT → RUNNING，lease_token+1。 */
    @Update("UPDATE job SET state = 'RUNNING', lease_owner = #{owner}, lease_token = lease_token + 1, "
            + "lease_until = #{leaseUntil}, next_reconcile_at = #{leaseUntil}, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state IN ('QUEUED','RETRY_WAIT')")
    int claimJob(@Param("jobId") String jobId, @Param("owner") String owner,
                 @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 派发所需的最小 job 快照（租约代次、状态、输入）。 */
    @Select("SELECT lease_token AS token, state, input_json AS inputJson, owner_id AS ownerId, item_id AS itemId FROM job WHERE id = #{jobId}")
    Map<String, Object> findDispatch(@Param("jobId") String jobId);

    /** 派发失败：RUNNING → QUEUED，attempt+1，清租约，退避 2 秒后允许重投。 */
    @Update("UPDATE job SET state = 'QUEUED', attempt = attempt + 1, lease_owner = NULL, lease_until = NULL, "
            + "next_attempt_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 SECOND), updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = #{jobId} AND state = 'RUNNING' AND lease_token = #{token}")
    int releaseForRetry(@Param("jobId") String jobId, @Param("token") long token);

    @Select("SELECT state FROM job WHERE id = #{jobId}")
    String jobState(@Param("jobId") String jobId);
}
