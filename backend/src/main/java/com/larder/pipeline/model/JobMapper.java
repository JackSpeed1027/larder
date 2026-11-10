package com.larder.pipeline.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Job 表 Mapper。 */
@Mapper
public interface JobMapper extends BaseMapper<Job> {

    /** 该 owner 的活跃任务数（QUEUED/RETRY_WAIT/RUNNING），用于背压。 */
    @Select("SELECT COUNT(*) FROM job WHERE owner_id = #{ownerId} AND state IN ('QUEUED','RETRY_WAIT','RUNNING')")
    long countActiveByOwner(@Param("ownerId") String ownerId);
}
