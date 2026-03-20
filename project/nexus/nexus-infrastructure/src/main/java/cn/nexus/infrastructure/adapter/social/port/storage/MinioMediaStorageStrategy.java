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

/**
 * MinIO 媒体存储策略：生成预签名 PUT/GET URL，支持客户端直传与后端中转上传。
 *
 * @author {$authorName}
 * @since 2026-01-06
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioMediaStorageStrategy implements MediaStorageStrategy {

    private final MinioMediaProperties properties;
    private MinioClient client;

    /**
     * 初始化 MinIO 客户端。
     */
    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * 策略类型标识。
     *
     * @return 类型（固定为 {@code "minio"}） {@link String}
     */
    @Override
    public String type() {
        return "minio";
    }

    /**
     * 生成上传会话：返回预签名上传 URL，客户端可直接 PUT 到对象存储。
     *
     * @param sessionId 会话 ID {@link String}
     * @param fileType 文件类型（MIME） {@link String}
     * @param fileSize 文件大小（字节，可为空） {@link Long}
     * @param crc32 内容校验值（当前未使用，透传保留） {@link String}
     * @return 上传会话信息 {@link UploadSessionVO}
     */
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

    /**
     * 生成读取 URL（预签名 GET）。
     *
     * @param sessionId 会话 ID {@link String}
     * @return 读取 URL（生成失败时返回 {@code null}） {@link String}
     */
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

    /**
     * 服务端中转上传文件到对象存储。
     *
     * @param originalFilename 原始文件名（可为空） {@link String}
     * @param fileType 文件类型（MIME，可为空） {@link String}
     * @param fileSize 文件大小（字节，可为空） {@link Long}
     * @param inputStream 文件输入流 {@link InputStream}
     * @return 读取 URL（上传成功后返回） {@link String}
     */
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
                // 防御性处理：限制后缀长度与字符集，避免写入奇怪路径或过长对象名。
                if (ext.length() <= 16 && ext.indexOf('/') < 0 && ext.indexOf('\\') < 0) {
                    suffix = ext;
                }
            }
        }
        return UUID.randomUUID().toString() + suffix;
    }
}
