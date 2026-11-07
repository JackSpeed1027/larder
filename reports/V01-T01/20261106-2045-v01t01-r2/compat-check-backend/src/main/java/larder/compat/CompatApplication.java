package larder.compat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot 4 兼容性验证用的最小启动类。
 *
 * <p>不含业务包：本工程只回答"这套版本能不能启动并跑通一次迁移+查询"，
 * 业务骨架由 V01-T02 创建。放在 reports 证据目录下也表明它不是产品代码。
 */
@SpringBootApplication
public class CompatApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompatApplication.class, args);
    }
}
