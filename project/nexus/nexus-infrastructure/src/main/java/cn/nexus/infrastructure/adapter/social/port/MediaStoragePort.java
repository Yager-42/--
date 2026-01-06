package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import cn.nexus.infrastructure.config.MinioMediaProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于 MinIO 的媒体上传会话实现：返回预签名 PUT URL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaStoragePort implements IMediaStoragePort {

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
                    .token(uploadUrl) // 预签名 URL 已携带签名参数，可直接作为 token
                    .sessionId(sessionId)
                    .build();
        } catch (Exception e) {
            log.error("生成 MinIO 上传会话失败, sessionId={}, object={}, err={}", sessionId, objectName, e.getMessage(), e);
            throw new RuntimeException("生成上传凭证失败", e);
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
}
