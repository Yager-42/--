package cn.nexus.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
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
}
