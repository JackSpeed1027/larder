package com.larder.runner.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runner 客户端：路径/幂等头正确，非 2xx 必须当失败（不能假装启动成功）。 */
class RunnerClientTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedIdempotencyKey = new AtomicReference<>();
    private volatile int responseStatus = 202;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("POST /v1/runs，带 Idempotency-Key=runId")
    void postsRunWithIdempotencyKey() throws IOException {
        startServer();
        RunnerClient client = new RunnerClient("http://127.0.0.1:" + server.getAddress().getPort(), 3000);

        String runId = client.startRun("run_abc", "job_1", "own_1", "itm_1", 0L, "{\"url\":\"https://example.com\"}");

        assertThat(runId).isEqualTo("run_abc");
        assertThat(capturedIdempotencyKey.get()).isEqualTo("run_abc");
        assertThat(capturedBody.get()).contains("\"runId\":\"run_abc\"").contains("\"jobId\":\"job_1\"").contains("\"protocolVersion\":\"v1\"").contains("\"scope\"");
    }

    @Test
    @DisplayName("Runner 返回 503：抛 RunnerUnavailableException")
    void failsOnNon2xx() throws IOException {
        responseStatus = 503;
        startServer();
        RunnerClient client = new RunnerClient("http://127.0.0.1:" + server.getAddress().getPort(), 3000);

        assertThatThrownBy(() -> client.startRun("run_x", "job_1", "own_1", "itm_1", 0L, "{}"))
                .isInstanceOf(RunnerUnavailableException.class);
    }

    @Test
    @DisplayName("Runner 不可达：抛 RunnerUnavailableException 而不是静默成功")
    void failsWhenUnreachable() {
        RunnerClient client = new RunnerClient("http://127.0.0.1:1", 500);
        assertThatThrownBy(() -> client.startRun("run_x", "job_1", "own_1", "itm_1", 0L, "{}"))
                .isInstanceOf(RunnerUnavailableException.class);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response = "{\"runId\":\"run_abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }
}
