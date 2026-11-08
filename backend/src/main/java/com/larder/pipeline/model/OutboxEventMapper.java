package com.larder.pipeline.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** OutboxEvent 表 Mapper。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
}
