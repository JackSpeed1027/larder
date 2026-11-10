package com.larder.runner.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 最小 Runner 客户端（V03-T03 为 V05 预留）。
 *
 * <p>runId 由 Java 生成并作为幂等键：ack 之后、HTTP 之前崩溃时，对账重派用同一个 runId，
 * Runner 必须按 runId 幂等，避免产生两个 run。
 */
@Component
public class RunnerClient {

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    public RunnerClient(@Value("${larder.runner.base-url:http://runner:8000}") String baseUrl,
                        @Value("${larder.runner.timeout-ms:10000}") long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 启动一次 run；返回 runId。请求体必须带冻结的 protocolVersion/scope（S02）。 */
    public String startRun(String runId, String jobId, String ownerId, String itemId, long processingEpoch, String inputJson) {
        String scope = "{\"ownerId\":\"" + ownerId + "\"" + (itemId == null ? "" : ",\"itemId\":\"" + itemId + "\"") + "}";
        String body = "{\"protocolVersion\":\"v1\",\"runId\":\"" + runId + "\",\"jobId\":\"" + jobId + "\",\"mode\":\"INGEST\",\"scope\":" + scope + ",\"traceId\":\"" + runId + "\",\"processingEpoch\":" + processingEpoch + ",\"input\":" + inputJson + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/runs"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", runId)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RunnerUnavailableException("Runner 返回 " + response.statusCode());
            }
            return runId;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RunnerUnavailableException("Runner 不可用: " + e.getMessage());
        }
    }
}
