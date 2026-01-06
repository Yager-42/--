package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 媒体上传配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio.media")
public class MinioMediaProperties {
    /** MinIO 服务地址，如 http://localhost:9000 */
    private String endpoint;
    /** 访问凭证 */
    private String accessKey;
    private String secretKey;
    /** 上传目标桶 */
    private String bucket;
    /** 对象存储路径前缀 */
    private String basePath = "content";
    /** 预签名 URL 过期时间（秒） */
    private long expirySeconds = 900;
}
