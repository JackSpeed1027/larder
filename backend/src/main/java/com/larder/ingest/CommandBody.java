package com.larder.ingest;

import com.larder.content.model.ItemKind;

/**
 * 一次收录命令的规范化请求体。
 *
 * <p>bodyHash 必须覆盖"会影响结果"的全部字段（含 text/uploadId），否则同一个幂等键
 * 换一个 URL 会被当成重放，返回错误的结果快照。
 */
public record CommandBody(ItemKind kind, String title, String url, String text, String uploadId) {

    /** 规范化 JSON（字段顺序固定），用于 body_hash 与调试快照。 */
    public String canonicalJson() {
        return Json.canonical(
                "kind", kind.name(),
                "title", title,
                "url", url,
                "text", text,
                "uploadId", uploadId);
    }

    public String bodyHash() {
        return Json.sha256(canonicalJson());
    }
}
