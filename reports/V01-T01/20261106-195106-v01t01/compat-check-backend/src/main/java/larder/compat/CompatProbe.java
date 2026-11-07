package larder.compat;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 验证用实体：映射 Flyway 建出的 compat_probe 表。
 */
@TableName("compat_probe")
public class CompatProbe {

    private Long id;
    private String note;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
