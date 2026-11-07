package com.larder.meta;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * app_meta 的 Mapper。
 *
 * <p>用 BaseMapper 而不是再包一层 IService：这里只有"按 key 取一条"的需求，
 * 不为了对称去复制一遍 CRUD（docs/18 对空接口/万能基类的约束）。
 */
@Mapper
public interface AppMetaMapper extends BaseMapper<AppMeta> {
}
