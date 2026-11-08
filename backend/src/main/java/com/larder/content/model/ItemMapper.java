package com.larder.content.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * item 表 Mapper。
 *
 * <p>URL 去重依赖数据库唯一键 uk_item_url(owner_id, url_hash)：
 * 先查再插只是优化，真正的并发唯一由约束保证，插入冲突时回读已存在行。
 */
@Mapper
public interface ItemMapper extends BaseMapper<Item> {
}
