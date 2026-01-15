package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 点赞延迟落库服务实现：对接 Redis + MySQL flush。
 */
@Service
@RequiredArgsConstructor
public class LikeSyncService implements ILikeSyncService {

    private final IReactionRepository reactionRepository;

    /**
     * like:win / like:touch TTL（秒）。
     */
    @Value("${interaction.like.syncTtlSeconds:180}")
    private long syncTtlSeconds;

    /**
     * flush 分布式锁 TTL（秒）。
     */
    @Value("${interaction.like.flushLockSeconds:30}")
    private long flushLockSeconds;

    @Override
    public boolean flush(String targetType, Long targetId) {
        if (targetId == null || targetId <= 0) {
            return false;
        }
        String normalizedTargetType = normalizeUpper(targetType);
        if (!isSupportedTargetType(normalizedTargetType)) {
            return false;
        }
        return reactionRepository.flush(
                normalizedTargetType,
                targetId,
                Math.max(1L, syncTtlSeconds),
                Math.max(1L, flushLockSeconds)
        );
    }

    private static boolean isSupportedTargetType(String targetType) {
        return "POST".equals(targetType) || "COMMENT".equals(targetType);
    }

    private static String normalizeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}

