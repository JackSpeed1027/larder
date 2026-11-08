package com.larder.runner.artifacts;

/**
 * Runner 上报的产物声明（Java 侧接收时的入参）。
 *
 * @param artifactId 产物 ID（由 Runner 生成，形如 art_xxx）
 * @param sha256     内容哈希；Java 会重新计算并比对，绝不盲信
 * @param bytes      字节数
 * @param mime       媒体类型
 */
public record ClaimedArtifact(String artifactId, String sha256, Long bytes, String mime) {
}
