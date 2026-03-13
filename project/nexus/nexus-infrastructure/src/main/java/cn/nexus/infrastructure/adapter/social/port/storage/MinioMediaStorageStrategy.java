package cn.nexus.infrastructure.adapter.social.port.storage;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import cn.nexus.infrastructure.config.MinioMediaProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioMediaStorageStrategy implements MediaStorageStrategy {

    private final MinioMediaProperties properties;
    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Override
    public String type() {
        return "minio";
    }

    @Override
    public UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32) {
        String objectName = buildObjectName(sessionId);
        try {
            ensureBucket();
            int expiry = (int) Math.max(properties.getExpirySeconds(), 60);
            Map<String, String> headers = Map.of("Content-Type", fileType);
            String uploadUrl = client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .extraHeaders(headers)
                            .expiry(expiry)
                            .build()
            );
            return UploadSessionVO.builder()
                    .uploadUrl(uploadUrl)
                    .token(uploadUrl)
                    .sessionId(sessionId)
                    .build();
        } catch (Exception e) {
            log.error("generate minio upload session failed, sessionId={}, object={}, err={}", sessionId, objectName, e.getMessage(), e);
            throw new RuntimeException("生成上传凭证失败", e);
        }
    }

    @Override
    public String generateReadUrl(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String objectName = buildObjectName(sessionId);
        try {
            ensureBucket();
            int expiry = (int) Math.max(properties.getExpirySeconds(), 60);
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(properties.getBucket())
                            .object(objectName)
                            .expiry(expiry)
                            .build()
            );
        } catch (Exception e) {
            log.warn("generate minio read url failed, sessionId={}, object={}, err={}", sessionId, objectName, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream is null");
        }
        String sessionId = buildSessionId(originalFilename);
        String objectName = buildObjectName(sessionId);
        try {
            ensureBucket();
            long size = fileSize == null ? -1L : Math.max(-1L, fileSize);
            long partSize = 10L * 1024 * 1024;

            PutObjectArgs.Builder b = PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(inputStream, size, partSize);
            if (fileType != null && !fileType.isBlank()) {
                b.contentType(fileType.trim());
            }
            client.putObject(b.build());
            return generateReadUrl(sessionId);
        } catch (Exception e) {
            log.error("minio upload failed, sessionId={}, object={}, err={}", sessionId, objectName, e.getMessage(), e);
            throw new RuntimeException("上传失败", e);
        }
    }

    private void ensureBucket() throws Exception {
        if (client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
            return;
        }
        client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
    }

    private String buildObjectName(String sessionId) {
        String prefix = properties.getBasePath();
        if (prefix == null || prefix.isBlank()) {
            return sessionId;
        }
        if (prefix.endsWith("/")) {
            return prefix + sessionId;
        }
        return prefix + "/" + sessionId;
    }

    private String buildSessionId(String originalFilename) {
        String suffix = "";
        if (originalFilename != null) {
            String name = originalFilename.trim();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot);
                // Defensive: keep suffix short and ASCII-ish.
                if (ext.length() <= 16 && ext.indexOf('/') < 0 && ext.indexOf('\\') < 0) {
                    suffix = ext;
                }
            }
        }
        return UUID.randomUUID().toString() + suffix;
    }
}
