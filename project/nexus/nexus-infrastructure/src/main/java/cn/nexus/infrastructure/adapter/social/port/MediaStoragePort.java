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
 * Media storage router.
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

    @jakarta.annotation.PostConstruct
    public void init() {
        switchTo(normalizeType(storageType));
    }

    @Override
    public UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32) {
        MediaStorageStrategy s = requireCurrent();
        return s.generateUploadSession(sessionId, fileType, fileSize, crc32);
    }

    @Override
    public String generateReadUrl(String sessionId) {
        MediaStorageStrategy s = requireCurrent();
        return s.generateReadUrl(sessionId);
    }

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
