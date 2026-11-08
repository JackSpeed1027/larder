package com.larder.ingest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL 规范化：用于 owner 内去重（url_hash 唯一键），不是"能访问"的证明。
 *
 * <p>规则刻意保守（docs/16 item 注释："按保守规则规范化，hash 碰撞时比较原值"）：
 * <ul>
 *   <li>只接受 http/https；</li>
 *   <li>host 小写、去掉默认端口（80/443）；</li>
 *   <li>去掉 fragment（同一页面的锚点不是不同内容）；</li>
 *   <li>保留 query 原顺序（重排可能改变语义）；</li>
 *   <li>拒绝带 userinfo 的 URL（凭据不应进入系统）。</li>
 * </ul>
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** 规范化；非法 URL 抛 {@link IllegalArgumentException}（由控制器转成 400）。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url 语法不合法: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("只支持 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("url 缺少 host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url 不允许携带 userinfo");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String path = (uri.getPath() == null || uri.getPath().isEmpty()) ? "/" : uri.getPath();
        String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();

        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path + query;
    }

    /** 规范化后的 SHA-256，作为 item.url_hash。 */
    public static String hash(String raw) {
        return Json.sha256(normalize(raw));
    }
}
