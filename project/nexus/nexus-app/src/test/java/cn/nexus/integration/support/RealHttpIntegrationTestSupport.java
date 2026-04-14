package cn.nexus.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@Isolated
@SpringBootTest(classes = TestNoSchedulingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "wsl", "real-it"})
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "auth.sms.phone-send-limit-1m=1000",
        "auth.sms.phone-send-limit-1h=1000",
        "auth.sms.phone-send-limit-1d=1000",
        "auth.sms.ip-send-limit-1m=1000",
        "auth.sms.ip-send-limit-1d=1000",
        "auth.login.fail-threshold=1000"
})
public abstract class RealHttpIntegrationTestSupport extends RealBusinessIntegrationTestSupport {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    protected int port;

    protected JsonNode getJson(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Accept", "application/json");
        withToken(builder, token);
        return send(builder.build());
    }

    protected String getText(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .header("Accept", "text/plain");
        withToken(builder, token);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    protected JsonNode postJson(String path, Object body, String token) throws Exception {
        return sendWithBody("POST", path, body, token);
    }

    protected JsonNode putJson(String path, Object body, String token) throws Exception {
        return sendWithBody("PUT", path, body, token);
    }

    protected JsonNode patchJson(String path, Object body, String token) throws Exception {
        return sendWithBody("PATCH", path, body, token);
    }

    protected JsonNode deleteJson(String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        withToken(builder, token);
        String json = body == null ? "" : objectMapper.writeValueAsString(body);
        return send(builder.method("DELETE", HttpRequest.BodyPublishers.ofString(json)).build());
    }

    protected JsonNode assertSuccess(JsonNode response) {
        assertThat(response.path("code").asText()).isEqualTo("0000");
        return response.path("data");
    }

    protected String bearerToken(JsonNode response) {
        return assertSuccess(response).path("token").asText();
    }

    protected void sendSms(String phone, String bizType) throws Exception {
        clearAuthThrottleKeys(phone);
        JsonNode response = postJson("/api/v1/auth/sms/send", Map.of(
                "phone", phone,
                "bizType", bizType
        ), null);
        assertSuccess(response);
    }

    protected long registerUser(String phone, String password, String nickname) throws Exception {
        sendSms(phone, "REGISTER");
        JsonNode response = postJson("/api/v1/auth/register", Map.of(
                "phone", phone,
                "smsCode", "123456",
                "password", password,
                "nickname", nickname,
                "avatarUrl", "https://avatar.example/" + uniqueUuid() + ".png"
        ), null);
        return assertSuccess(response).path("userId").asLong();
    }

    protected String passwordLogin(String phone, String password) throws Exception {
        JsonNode response = postJson("/api/v1/auth/login/password", Map.of(
                "phone", phone,
                "password", password
        ), null);
        return bearerToken(response);
    }

    protected String registerAndLogin(String phone, String password, String nickname) throws Exception {
        registerUser(phone, password, nickname);
        return passwordLogin(phone, password);
    }

    protected ConcurrentRunResult runConcurrentRequests(int totalRequests,
                                                        int concurrentWorkers,
                                                        long eachTaskTimeoutSec,
                                                        ConcurrentRequestTask task) throws Exception {
        if (totalRequests <= 0) {
            throw new IllegalArgumentException("totalRequests must be > 0");
        }
        if (concurrentWorkers <= 0) {
            throw new IllegalArgumentException("concurrentWorkers must be > 0");
        }
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        int workers = Math.min(totalRequests, concurrentWorkers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<SingleRunResult>> futures = new ArrayList<>(totalRequests);
        for (int i = 0; i < totalRequests; i++) {
            futures.add(pool.submit(() -> {
                startGate.await(10, TimeUnit.SECONDS);
                long begin = System.nanoTime();
                try {
                    task.run();
                    long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                    return SingleRunResult.success(costMs);
                } catch (Throwable e) {
                    long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
                    String type = e.getClass().getSimpleName();
                    return SingleRunResult.failure(costMs, type == null || type.isBlank() ? "ERROR" : type);
                }
            }));
        }

        startGate.countDown();

        List<Long> latencies = new ArrayList<>(totalRequests);
        List<String> errorTypes = new ArrayList<>();
        int success = 0;
        int failure = 0;

        try {
            for (Future<SingleRunResult> future : futures) {
                SingleRunResult result = future.get(eachTaskTimeoutSec, TimeUnit.SECONDS);
                latencies.add(result.costMs());
                if (result.ok()) {
                    success++;
                } else {
                    failure++;
                    errorTypes.add(result.errorType());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        latencies.sort(Long::compareTo);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);

        return new ConcurrentRunResult(totalRequests, workers, success, failure, p95, p99, errorTypes);
    }

    protected void printLoadSmoke(String label, ConcurrentRunResult result) {
        if (result == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[LOAD-SMOKE][").append(label).append("]")
                .append(" total=").append(result.totalRequests())
                .append(", concurrent=").append(result.concurrentWorkers())
                .append(", success=").append(result.success())
                .append(", failure=").append(result.failure())
                .append(", p95Ms=").append(result.p95Ms())
                .append(", p99Ms=").append(result.p99Ms());
        if (result.failure() > 0 && result.errorTypes() != null && !result.errorTypes().isEmpty()) {
            sb.append(", errors=").append(result.errorTypes());
        }
        System.out.println(sb);
    }

    private long percentile(List<Long> sortedMs, double ratio) {
        if (sortedMs == null || sortedMs.isEmpty()) {
            return 0L;
        }
        int idx = Math.max(0, (int) Math.ceil(sortedMs.size() * ratio) - 1);
        return sortedMs.get(idx);
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body();
        assertThat(status).as("http status=%s, body=%s", status, body).isEqualTo(200);
        return objectMapper.readTree(body);
    }

    private JsonNode sendWithBody(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(path)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        withToken(builder, token);
        String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
        return send(builder.method(method, HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private void withToken(HttpRequest.Builder builder, String token) {
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
    }

    @FunctionalInterface
    protected interface ConcurrentRequestTask {
        void run() throws Exception;
    }

    protected record ConcurrentRunResult(int totalRequests,
                                         int concurrentWorkers,
                                         int success,
                                         int failure,
                                         long p95Ms,
                                         long p99Ms,
                                         List<String> errorTypes) {
    }

    private record SingleRunResult(boolean ok, long costMs, String errorType) {
        static SingleRunResult success(long costMs) {
            return new SingleRunResult(true, costMs, null);
        }

        static SingleRunResult failure(long costMs, String errorType) {
            return new SingleRunResult(false, costMs, errorType);
        }
    }
}
