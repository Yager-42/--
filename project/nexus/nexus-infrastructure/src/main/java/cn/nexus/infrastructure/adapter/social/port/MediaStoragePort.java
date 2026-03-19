package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import cn.nexus.infrastructure.adapter.social.port.storage.MediaStorageStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 媒体存储路由端口：根据配置选择具体对象存储策略（默认 MinIO）。
 *
 * <p>领域层只依赖 {@link IMediaStoragePort}；具体存储实现通过 {@link MediaStorageStrategy} 插件化接入。</p>
 *
 * @author {$authorName}
 * @since 2026-01-06
 */
@Slf4j
@Component
@RefreshScope
@RequiredArgsConstructor
public class MediaStoragePort implements IMediaStoragePort {

    private static final String DEFAULT_TYPE = "minio";

    private final List<MediaStorageStrategy> strategies;

    @Value("${storage.type:minio}")
    private String storageType;

    private final AtomicReference<MediaStorageStrategy> current = new AtomicReference<>();
    private final AtomicReference<String> currentType = new AtomicReference<>(DEFAULT_TYPE);

    /**
     * 初始化当前存储策略。
     *
     * <p>启动时按配置切换一次；运行期配置变更可通过刷新触发再次切换。</p>
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        switchTo(normalizeType(storageType));
    }

    /**
     * 生成上传会话信息，供客户端直传对象存储。
     *
     * @param sessionId 会话 ID {@link String}
     * @param fileType 文件类型（MIME） {@link String}
     * @param fileSize 文件大小（字节，可为空） {@link Long}
     * @param crc32 内容校验值（可为空） {@link String}
     * @return 上传会话信息 {@link UploadSessionVO}
     */
    @Override
    public UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32) {
        MediaStorageStrategy s = requireCurrent();
        return s.generateUploadSession(sessionId, fileType, fileSize, crc32);
    }

    /**
     * 生成读取 URL（通常是预签名 URL）。
     *
     * @param sessionId 会话 ID {@link String}
     * @return 可读取的 URL（生成失败时返回 {@code null}） {@link String}
     */
    @Override
    public String generateReadUrl(String sessionId) {
        MediaStorageStrategy s = requireCurrent();
        return s.generateReadUrl(sessionId);
    }

    /**
     * 通过服务端中转方式上传文件（一般用于后端任务/补偿，不建议走在线大流量）。
     *
     * @param originalFilename 原始文件名（可为空） {@link String}
     * @param fileType 文件类型（MIME，可为空） {@link String}
     * @param fileSize 文件大小（字节，可为空） {@link Long}
     * @param inputStream 文件输入流 {@link InputStream}
     * @return 读取 URL（上传成功后返回） {@link String}
     */
    @Override
    public String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream) {
        MediaStorageStrategy s = requireCurrent();
        return s.uploadFile(originalFilename, fileType, fileSize, inputStream);
    }

    private MediaStorageStrategy requireCurrent() {
        MediaStorageStrategy s = current.get();
        if (s != null) {
            return s;
        }
        switchTo(DEFAULT_TYPE);
        s = current.get();
        if (s == null) {
            throw new IllegalStateException("no media storage strategy available");
        }
        return s;
    }

    private void switchTo(String desiredType) {
        String target = desiredType == null ? DEFAULT_TYPE : desiredType;

        Map<String, MediaStorageStrategy> byType = indexStrategies(strategies);
        MediaStorageStrategy picked = byType.get(target);
        if (picked == null) {
            picked = byType.get(DEFAULT_TYPE);
        }
        if (picked == null && !byType.isEmpty()) {
            picked = byType.values().iterator().next();
        }
        if (picked == null) {
            throw new IllegalStateException("no media storage strategy available");
        }

        current.set(picked);
        currentType.set(picked.type());
        log.info("media storage switched, type={} -> {}", desiredType, picked.type());
    }

    private Map<String, MediaStorageStrategy> indexStrategies(List<MediaStorageStrategy> list) {
        Map<String, MediaStorageStrategy> m = new HashMap<>();
        if (list == null) {
            return m;
        }
        for (MediaStorageStrategy s : list) {
            if (s == null) {
                continue;
            }
            String t = normalizeType(s.type());
            if (t != null) {
                m.put(t, s);
            }
        }
        return m;
    }

    private String normalizeType(String t) {
        if (t == null) {
            return null;
        }
        String v = t.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

}
