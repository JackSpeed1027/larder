package com.larder.pipeline.lease;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** S05：job_event 持久事件日志；SSE 用 (owner, job, seq) 作为可恢复游标。 */
@Mapper
public interface JobEventMapper {

    @Insert("INSERT INTO job_event (owner_id, job_id, seq, event_type, payload_json, created_at) "
            + "VALUES (#{ownerId}, #{jobId}, #{seq}, #{eventType}, #{payloadJson}, #{createdAt})")
    int insert(@Param("ownerId") String ownerId, @Param("jobId") String jobId, @Param("seq") long seq,
               @Param("eventType") String eventType, @Param("payloadJson") String payloadJson,
               @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT seq, event_type AS eventType, payload_json AS payloadJson, created_at AS createdAt "
            + "FROM job_event WHERE owner_id = #{ownerId} AND job_id = #{jobId} AND seq > #{afterSeq} "
            + "ORDER BY seq ASC LIMIT #{limit}")
    List<Map<String, Object>> listAfter(@Param("ownerId") String ownerId, @Param("jobId") String jobId,
                                        @Param("afterSeq") long afterSeq, @Param("limit") int limit);

    @Select("SELECT last_event_seq FROM job WHERE id = #{jobId} AND owner_id = #{ownerId}")
    Long lastEventSeq(@Param("ownerId") String ownerId, @Param("jobId") String jobId);
}
