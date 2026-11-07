package larder.compat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 兼容性验证用的最小启动类。
 *
 * <p>刻意不包含任何业务包：V01-T01 只验证"版本组合能否真实启动"，
 * 业务骨架由 V01-T02 创建。放在 reports 目录下也表明它不是应用工程的一部分。
 */
@SpringBootApplication
public class CompatApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompatApplication.class, args);
    }
}
