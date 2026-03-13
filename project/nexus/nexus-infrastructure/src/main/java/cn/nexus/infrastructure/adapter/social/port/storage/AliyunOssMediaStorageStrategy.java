package cn.nexus.infrastructure.adapter.social.port.storage;

import cn.nexus.infrastructure.config.AliyunOssMediaProperties;
import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class AliyunOssMediaStorageStrategy implements MediaStorageStrategy {

    private final AliyunOssMediaProperties properties;
    private OSS client;

    @PostConstruct
    public void init() {
        String endpoint = normalize(properties.getEndpoint());
        String ak = normalize(properties.getAccessKey());
        String sk = normalize(properties.getSecretKey());
        if (endpoint == null || ak == null || sk == null) {
            log.warn("aliyun oss not configured (endpoint/accessKey/secretKey is blank)");
            return;
        }
        client = new OSSClientBuilder().build(endpoint, ak, sk);
    }

    @Override
    public String type() {
        return "aliyun";
    }

    @Override
    public UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32) {
        OSS oss = requireClient();
        String sid = normalize(sessionId);
        if (sid == null) {
            throw new IllegalArgumentException("sessionId is blank");
        }
        String bucket = normalize(properties.getBucket());
        if (bucket == null) {
            throw new IllegalStateException("aliyun.oss.media.bucket is blank");
        }
        String objectName = buildObjectName(sid);
        URL url = presign(oss, bucket, objectName, HttpMethod.PUT);
        String uploadUrl = url.toString();
        return UploadSessionVO.builder()
                .uploadUrl(uploadUrl)
                .token(uploadUrl)
                .sessionId(sid)
                .build();
    }

    @Override
    public String generateReadUrl(String sessionId) {
        OSS oss = requireClient();
        String sid = normalize(sessionId);
        if (sid == null) {
            return null;
        }
        String bucket = normalize(properties.getBucket());
        if (bucket == null) {
            throw new IllegalStateException("aliyun.oss.media.bucket is blank");
        }
        String objectName = buildObjectName(sid);
        return presign(oss, bucket, objectName, HttpMethod.GET).toString();
    }

    @Override
    public String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream) {
        OSS oss = requireClient();
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream is null");
        }
        String bucket = normalize(properties.getBucket());
        if (bucket == null) {
            throw new IllegalStateException("aliyun.oss.media.bucket is blank");
        }

        String sessionId = buildSessionId(originalFilename);
        String objectName = buildObjectName(sessionId);
        try {
            oss.putObject(bucket, objectName, inputStream);
            return generateReadUrl(sessionId);
        } catch (Exception e) {
            log.error("aliyun oss upload failed, object={}, err={}", objectName, e.getMessage(), e);
            throw new RuntimeException("上传失败", e);
        }
    }

    private URL presign(OSS oss, String bucket, String objectName, HttpMethod method) {
        long expirySeconds = Math.max(properties.getExpirySeconds(), 60);
        Date expiration = new Date(System.currentTimeMillis() + Duration.ofSeconds(expirySeconds).toMillis());
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, objectName, method);
        req.setExpiration(expiration);
        return oss.generatePresignedUrl(req);
    }

    private OSS requireClient() {
        OSS oss = client;
        if (oss != null) {
            return oss;
        }
        throw new UnsupportedOperationException("aliyun oss is not configured");
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
                if (ext.length() <= 16 && ext.indexOf('/') < 0 && ext.indexOf('\\') < 0) {
                    suffix = ext;
                }
            }
        }
        return UUID.randomUUID().toString() + suffix;
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}
