package com.larder.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL 规范化与 JSON 转义的边界测试。
 *
 * <p>规范化直接决定去重结果：改错一条规则，同一页面会被当成两条内容，
 * 或者两个不同页面被合并——两种都是数据事故，所以每条规则单独断言。
 */
class UrlNormalizerTest {

    @Test
    @DisplayName("host 小写、默认端口、fragment 被去掉，query 顺序保留")
    void normalizesCommonVariants() {
        assertThat(UrlNormalizer.normalize("HTTPS://Example.COM:443/a/b?x=1&y=2#frag"))
                .isEqualTo("https://example.com/a/b?x=1&y=2");
        assertThat(UrlNormalizer.normalize("http://example.com:80"))
                .isEqualTo("http://example.com/");
        assertThat(UrlNormalizer.normalize("https://example.com"))
                .isEqualTo("https://example.com/");
    }

    @Test
    @DisplayName("非默认端口保留，query 不重排（重排可能改变语义）")
    void keepsNonDefaultPortAndQueryOrder() {
        assertThat(UrlNormalizer.normalize("https://example.com:8443/p?b=2&a=1"))
                .isEqualTo("https://example.com:8443/p?b=2&a=1");
    }

    @Test
    @DisplayName("非法协议/缺 host/带 userinfo 一律拒绝")
    void rejectsUnsafeOrInvalid() {
        assertThatThrownBy(() -> UrlNormalizer.normalize("ftp://example.com/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize("https:///nohost"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize("https://user:pass@example.com/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("同一页面的不同写法得到同一 hash，不同页面得到不同 hash")
    void hashesAreStable() {
        String a = UrlNormalizer.hash("https://Example.com/a?x=1#one");
        String b = UrlNormalizer.hash("https://example.com:443/a?x=1#two");
        String c = UrlNormalizer.hash("https://example.com/a?x=2");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(c).isNotEqualTo(a);
    }

    @Test
    @DisplayName("JSON 转义处理引号、反斜杠、换行与控制字符")
    void jsonEscapesUserContent() {
        String escaped = Json.escape("a\"b\\c\nd\te\u0001");
        assertThat(escaped).isEqualTo("a\\\"b\\\\c\\nd\\te\\u0001");
        assertThat(Json.canonical("k", "v\"1")).isEqualTo("{\"k\":\"v\\\"1\"}");
    }
}
