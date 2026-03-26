package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MediaStorageRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void minioStorage_shouldUploadAndReadBackUsingPresignedUrls() throws Exception {
        String sessionId = "it-" + uniqueUuid() + ".txt";
        byte[] content = ("minio-real-it-" + uniqueUuid()).getBytes(StandardCharsets.UTF_8);

        UploadSessionVO uploadSession = mediaStoragePort.generateUploadSession(sessionId, "text/plain", (long) content.length, null);

        HttpRequest putRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadSession.getUploadUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();

        HttpResponse<String> putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(putResponse.statusCode()).isBetween(200, 299);

        String readUrl = mediaStoragePort.generateReadUrl(sessionId);
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(readUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<byte[]> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(new String(getResponse.body(), StandardCharsets.UTF_8))
                .isEqualTo(new String(content, StandardCharsets.UTF_8));
    }
}
