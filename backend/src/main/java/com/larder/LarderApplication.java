package com.larder;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Larder 后端入口。
 *
 * <p>V01-T02 阶段只提供骨架：真实 MySQL 迁移 + 健康检查 + 一次真实的表读取。
 * 业务包（content/search/agent/...）按 docs/02 在 V02 起的任务中逐步加入，
 * 这里不预建空模块，避免"目录齐全但没有实现"的假进度。
 */
@SpringBootApplication
@MapperScan("com.larder.meta")
public class LarderApplication {

    public static void main(String[] args) {
        SpringApplication.run(LarderApplication.class, args);
    }
}
