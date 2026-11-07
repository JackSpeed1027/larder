package larder.compat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 验证用 Mapper：只证明 MyBatis-Plus 的 BaseMapper 能在该版本组合下装配并执行 SQL。
 */
@Mapper
public interface CompatProbeMapper extends BaseMapper<CompatProbe> {
}
