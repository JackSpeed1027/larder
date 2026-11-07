package com.larder.health;

import com.larder.meta.AppMeta;
import com.larder.meta.AppMetaMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 骨架探针：证明 API 能连上数据库并读到 Flyway 迁移写入的行。
 *
 * <p>它不代替 actuator：liveness/readiness 走 /actuator/health（含 db 检查），
 * 这个接口额外暴露"当前 schema 版本"，方便 compose 起来后一眼看出迁移是否真的执行了。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private final AppMetaMapper appMetaMapper;
    private final String version;

    public PingController(AppMetaMapper appMetaMapper,
                          @Value("${larder.version:0.1.0-SNAPSHOT}") String version) {
        this.appMetaMapper = appMetaMapper;
        this.version = version;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        // 读不到就是数据库/迁移有问题，让它失败暴露，而不是返回假的 ok
        AppMeta meta = appMetaMapper.selectById("schema_version");
        String schemaVersion = meta == null ? "MISSING" : meta.getMetaValue();
        return new PingResponse("larder-api", version, schemaVersion, Instant.now().toString());
    }
}
