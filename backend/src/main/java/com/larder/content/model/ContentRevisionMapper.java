package com.larder.content.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** content_revision 表 Mapper（V02-T03 的流水线写入，本任务不调用）。 */
@Mapper
public interface ContentRevisionMapper extends BaseMapper<ContentRevision> {
}
