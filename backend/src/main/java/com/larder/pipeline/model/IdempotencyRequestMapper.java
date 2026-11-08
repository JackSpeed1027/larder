package com.larder.pipeline.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** IdempotencyRequest 表 Mapper。 */
@Mapper
public interface IdempotencyRequestMapper extends BaseMapper<IdempotencyRequest> {
}
