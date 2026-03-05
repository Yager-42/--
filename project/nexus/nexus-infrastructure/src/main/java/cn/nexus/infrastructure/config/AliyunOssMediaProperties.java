package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 媒体上传配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss.media")
public class AliyunOssMediaProperties {
    /** OSS endpoint，如 oss-cn-shanghai.aliyuncs.com */
    private String endpoint;
    /** AccessKey */
    private String accessKey;
    private String secretKey;
    /** 上传目标桶 */
    private String bucket;
    /** 对象存储路径前缀 */
    private String basePath = "content";
    /** 预签名 URL 过期时间（秒） */
    private long expirySeconds = 900;
}

