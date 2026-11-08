package com.larder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Larder 后端入口。
 *
 * <p>Mapper 只靠各接口上的 {@code @Mapper} 注解被自动扫描；不再在启动类上写 @MapperScan——
 * 那会让 @WebMvcTest 切片也去创建 mapper（没有 sqlSessionFactory 直接失败），
 * 属于 V01-T04 认证测试暴露出的真实问题。
 *
 * <p>V01-T02 阶段只提供骨架：真实 MySQL 迁移 + 健康检查 + 一次真实的表读取。
 * 业务包（content/search/agent/...）按 docs/02 在 V02 起的任务中逐步加入，
 * 这里不预建空模块，避免"目录齐全但没有实现"的假进度。
 */
@SpringBootApplication
public class LarderApplication {

    public static void main(String[] args) {
        SpringApplication.run(LarderApplication.class, args);
    }
}
