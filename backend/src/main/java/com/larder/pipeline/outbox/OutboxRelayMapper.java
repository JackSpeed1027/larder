package com.larder.pipeline.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投递器专用的 Outbox SQL。
 *
 * <p>所有状态更新都带 {@code relay_token} 与 {@code state='PUBLISHING'} 条件：
 * 这正是 fencing——租约过期被接管后，旧投递者的迟到 confirm 更新 0 行，不得改状态。
 */
@Mapper
public interface OutboxRelayMapper {

    String COLUMNS = "event_id AS eventId, owner_id AS ownerId, job_id AS jobId, event_type AS eventType, "
            + "payload_json AS payloadJson, payload_hash AS payloadHash, state AS state, attempt AS attempt, "
            + "available_at AS availableAt, relay_owner AS relayOwner, relay_token AS relayToken, "
            + "relay_lease_until AS relayLeaseUntil";

    /** 领取到期事件：跳过被其他 relay 锁住的行（SKIP LOCKED 避免互相等待）。 */
    @Select("SELECT " + COLUMNS + " FROM outbox "
            + "WHERE state = 'PENDING' AND available_at <= UTC_TIMESTAMP(6) "
            + "ORDER BY available_at LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<OutboxRow> selectClaimable(@Param("limit") int limit);

    @Update("UPDATE outbox SET state = 'PUBLISHING', relay_owner = #{owner}, relay_token = #{nextToken}, "
            + "relay_lease_until = #{leaseUntil} WHERE event_id = #{eventId} AND state = 'PENDING'")
    int claim(@Param("eventId") String eventId, @Param("owner") String owner,
              @Param("nextToken") long nextToken, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("SELECT " + COLUMNS + " FROM outbox WHERE event_id = #{eventId}")
    OutboxRow selectByEventId(@Param("eventId") String eventId);

    @Update("UPDATE outbox SET state = 'SENT', confirmed_at = UTC_TIMESTAMP(6), relay_owner = NULL, "
            + "relay_lease_until = NULL WHERE event_id = #{eventId} AND relay_token = #{token} "
            + "AND state = 'PUBLISHING'")
    int markSent(@Param("eventId") String eventId, @Param("token") long token);

    @Update("UPDATE outbox SET attempt = #{attempt}, state = #{state}, available_at = #{availableAt}, "
            + "relay_owner = NULL, relay_lease_until = NULL "
            + "WHERE event_id = #{eventId} AND relay_token = #{token} AND state = 'PUBLISHING'")
    int markOutcome(@Param("eventId") String eventId, @Param("token") long token,
                    @Param("attempt") int attempt, @Param("state") String state,
                    @Param("availableAt") LocalDateTime availableAt);

    @Select("SELECT " + COLUMNS + " FROM outbox WHERE state = 'PUBLISHING' "
            + "AND relay_lease_until < UTC_TIMESTAMP(6) LIMIT 100")
    List<OutboxRow> selectExpired();
}
