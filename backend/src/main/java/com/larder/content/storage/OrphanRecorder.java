package com.larder.content.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 孤立对象清单：对象已写入 S3，但元数据落库失败时登记下来，供后台对账/清理。
 *
 * <p>为什么不静默删除对象：它可能已被并发请求复用（key 是内容 hash），
 * 删除会破坏别人刚建立的引用。宁可留清单，让人工/后台任务确认后再处理。
 *
 * <p>为什么用文件而不是表：这是运维侧的低频证据，不参与业务事务；
 * 用 JSONL 落盘（容器内挂卷）比加一张跨任务表更轻，也避免污染契约。
 */
@Component
public class OrphanRecorder {

    private final Path file;

    public OrphanRecorder(@Value("${larder.storage.orphan-file:/var/lib/larder/orphans.jsonl}") String filePath) {
        this.file = Path.of(filePath);
    }

    /** 记录一条孤立对象；记录失败只告警，不能掩盖原始入库失败。 */
    public synchronized void record(String key, String sha256, long bytes, String mime) {
        String line = "{\"objectKey\":\"" + key + "\",\"sha256\":\"" + sha256
                + "\",\"bytes\":" + bytes + ",\"mime\":\"" + mime
                + "\",\"detectedAt\":\"" + Instant.now() + "\"}\n";
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[orphan] 无法写入孤立对象清单 " + file + ": " + e.getMessage() + " line=" + line);
        }
    }

    public Path file() {
        return file;
    }
}
