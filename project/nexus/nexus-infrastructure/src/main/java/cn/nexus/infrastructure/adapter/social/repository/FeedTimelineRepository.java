package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;
import cn.nexus.infrastructure.config.FeedInboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Feed InboxTimeline 仓储 Redis 实现（ZSET）。
 *
 * @author rr
 * @author codex
 * @since 2026-01-12
 */
@Repository
@RequiredArgsConstructor
public class FeedTimelineRepository implements IFeedTimelineRepository {

    private static final String KEY_INBOX_PREFIX = "feed:inbox:";
    private static final String KEY_TMP_PREFIX = "feed:inbox:tmp:";
    private static final String KEY_REBUILD_LOCK_PREFIX = "feed:inbox:rebuild:lock:";
    private static final String INBOX_NO_MORE_MEMBER = "__NOMORE__";
    private static final RedisScript<Long> REPLACE_INBOX_SCRIPT = new DefaultRedisScript<>("""
            local realKey = ARGV[1]
            local tmpKey = ARGV[2]
            local ttlSeconds = tonumber(ARGV[3])
            local maxSize = tonumber(ARGV[4])
            local windowSize = tonumber(ARGV[5])
            local noMoreMember = ARGV[6]
            local entryCount = tonumber(ARGV[7])

            redis.call('DEL', tmpKey)
            local argIndex = 8
            for i = 1, entryCount do
                redis.call('ZADD', tmpKey, ARGV[argIndex + 1], ARGV[argIndex])
                argIndex = argIndex + 2
            end

            if entryCount == 0 then
                redis.call('ZADD', tmpKey, 0, noMoreMember)
            end

            if entryCount > 0 and windowSize > 0 then
                local oldMembers = redis.call('ZREVRANGE', realKey, 0, windowSize - 1, 'WITHSCORES')
                for i = 1, #oldMembers, 2 do
                    local member = oldMembers[i]
                    if member ~= noMoreMember and redis.call('ZSCORE', tmpKey, member) == false then
                        redis.call('ZADD', tmpKey, oldMembers[i + 1], member)
                    end
                end
            end

            local size = redis.call('ZCARD', tmpKey)
            if size > maxSize then
                redis.call('ZREMRANGEBYRANK', tmpKey, 0, size - maxSize - 1)
            end
            redis.call('EXPIRE', tmpKey, ttlSeconds)
            redis.call('RENAME', tmpKey, realKey)
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedInboxProperties feedInboxProperties;

    /**
     * inbox 重建互斥锁过期秒数（默认 30）。 {@code int}
     */
    @Value("${feed.rebuild.lockSeconds:30}")
    private int rebuildLockSeconds;

    /**
     * inbox 重建时保留最新端旧成员窗口（默认 256）。 {@code int}
     */
    @Value("${feed.rebuild.mergeWindowSize:256}")
    private int rebuildMergeWindowSize;

    /**
     * 执行 addToInbox 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     * @param publishTimeMs publishTimeMs 参数。类型：{@link Long}
     */
    @Override
    public void addToInbox(Long userId, Long postId, Long publishTimeMs) {
        if (userId == null || postId == null || publishTimeMs == null) {
            return;
        }
        String key = inboxKey(userId);
        stringRedisTemplate.opsForZSet().add(key, postId.toString(), publishTimeMs.doubleValue());
        expireIfNeeded(key);
        trimToMaxSize(key);
    }

    /**
     * 执行 inboxExists 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean inboxExists(Long userId) {
        if (userId == null) {
            return false;
        }
        Boolean exists = stringRedisTemplate.hasKey(inboxKey(userId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 执行 filterOnlineUsers 逻辑。
     *
     * @param userIds userIds 参数。类型：{@link List}
     * @return 处理结果。类型：{@link Set}
     */
    @Override
    public Set<Long> filterOnlineUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        List<Long> candidates = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            candidates.add(userId);
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }

        List<Object> existsList = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long userId : candidates) {
                byte[] key = inboxKey(userId).getBytes(StandardCharsets.UTF_8);
                connection.keyCommands().exists(key);
            }
            return null;
        });
        if (existsList == null || existsList.isEmpty()) {
            return Set.of();
        }

        int size = Math.min(candidates.size(), existsList.size());
        Set<Long> online = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            Object exists = existsList.get(i);
            if (Boolean.TRUE.equals(exists)) {
                online.add(candidates.get(i));
                continue;
            }
            if (exists instanceof Long l && l > 0) {
                online.add(candidates.get(i));
            }
        }
        return online.isEmpty() ? Set.of() : online;
    }

    /**
     * 执行 replaceInbox 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param entries entries 参数。类型：{@link List}
     */
    @Override
    public void replaceInbox(Long userId, List<FeedInboxEntryVO> entries) {
        if (userId == null) {
            return;
        }
        if (!tryAcquireRebuildLock(userId)) {
            return;
        }

        String inboxKey = inboxKey(userId);
        String tmpKey = inboxTmpKey(userId, System.nanoTime());
        List<Object> args = new ArrayList<>();
        args.add(inboxKey);
        args.add(tmpKey);
        args.add(String.valueOf(ttl().getSeconds()));
        args.add(String.valueOf(maxSize()));
        args.add(String.valueOf(rebuildWindowSize()));
        args.add(INBOX_NO_MORE_MEMBER);
        int countIndex = args.size();
        args.add("0");
        int count = 0;
        if (entries != null && !entries.isEmpty()) {
            for (FeedInboxEntryVO entry : entries) {
                if (entry == null || entry.getPostId() == null || entry.getPublishTimeMs() == null) {
                    continue;
                }
                args.add(entry.getPostId().toString());
                args.add(entry.getPublishTimeMs().toString());
                count++;
            }
        }
        args.set(countIndex, String.valueOf(count));
        stringRedisTemplate.execute(REPLACE_INBOX_SCRIPT, List.of(inboxKey), args.toArray());
    }

    /**
     * 执行 pageInbox 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param cursor 分页游标。类型：{@link String}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link FeedIdPageVO}
     */
    @Override
    public FeedIdPageVO pageInbox(Long userId, String cursor, int limit) {
        if (userId == null) {
            return FeedIdPageVO.builder().postIds(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = inboxKey(userId);
        stringRedisTemplate.expire(key, ttl());

        List<String> ids;
        if (cursor == null || cursor.isBlank()) {
            ids = toList(stringRedisTemplate.opsForZSet().reverseRange(key, 0, normalizedLimit - 1));
        } else {
            Long rank = stringRedisTemplate.opsForZSet().reverseRank(key, cursor);
            if (rank == null) {
                ids = List.of();
            } else {
                ids = toList(stringRedisTemplate.opsForZSet().reverseRange(key, rank + 1, rank + normalizedLimit));
            }
        }

        List<String> filteredIds = filterNoMore(ids);
        List<Long> postIds = toLongList(filteredIds);
        String nextCursor = filteredIds.isEmpty() ? null : filteredIds.get(filteredIds.size() - 1);
        return FeedIdPageVO.builder()
                .postIds(postIds)
                .nextCursor(nextCursor)
                .build();
    }

    /**
     * 执行 pageInboxEntries 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param cursorTimeMs cursorTimeMs 参数。类型：{@link Long}
     * @param cursorPostId cursorPostId 参数。类型：{@link Long}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<FeedInboxEntryVO> pageInboxEntries(Long userId, Long cursorTimeMs, Long cursorPostId, int limit) {
        if (userId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, limit);
        String key = inboxKey(userId);
        stringRedisTemplate.expire(key, ttl());

        long safeCursorTime = cursorTimeMs == null ? Long.MAX_VALUE : cursorTimeMs;
        long safeCursorPostId = cursorPostId == null ? Long.MAX_VALUE : cursorPostId;
        int fetchCount = normalizedLimit + 20;

        // Redis ZSET 只能按 score 做粗分页，所以这里多抓一点，再用 (publishTimeMs, postId) 做二次裁剪，保证同毫秒下翻页稳定。
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0D, safeCursorTime, 0, fetchCount);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }

        List<FeedInboxEntryVO> result = new ArrayList<>(normalizedLimit);
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple == null || tuple.getValue() == null || tuple.getValue().isBlank() || tuple.getScore() == null) {
                continue;
            }
            if (INBOX_NO_MORE_MEMBER.equals(tuple.getValue())) {
                continue;
            }
            Long postId = parseLong(tuple.getValue());
            if (postId == null) {
                continue;
            }
            long publishTimeMs = tuple.getScore().longValue();
            if (!passCursor(publishTimeMs, postId, safeCursorTime, safeCursorPostId)) {
                continue;
            }
            result.add(FeedInboxEntryVO.builder().postId(postId).publishTimeMs(publishTimeMs).build());
            if (result.size() >= normalizedLimit) {
                break;
            }
        }
        return result;
    }

    /**
     * 执行 removeFromInbox 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param postId 帖子 ID。类型：{@link Long}
     */
    @Override
    public void removeFromInbox(Long userId, Long postId) {
        if (userId == null || postId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(inboxKey(userId), postId.toString());
    }

    private boolean passCursor(long publishTimeMs, long postId, long cursorTimeMs, long cursorPostId) {
        if (cursorTimeMs == Long.MAX_VALUE && cursorPostId == Long.MAX_VALUE) {
            return true;
        }
        if (publishTimeMs < cursorTimeMs) {
            return true;
        }
        return publishTimeMs == cursorTimeMs && postId < cursorPostId;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String inboxKey(Long userId) {
        return KEY_INBOX_PREFIX + userId;
    }

    private String rebuildLockKey(Long userId) {
        return KEY_REBUILD_LOCK_PREFIX + userId;
    }

    private String inboxTmpKey(Long userId, long epochMs) {
        return KEY_TMP_PREFIX + userId + ":" + epochMs;
    }

    private boolean tryAcquireRebuildLock(Long userId) {
        int seconds = Math.max(1, rebuildLockSeconds);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(rebuildLockKey(userId), "1", Duration.ofSeconds(seconds));
        return Boolean.TRUE.equals(locked);
    }

    private Duration ttl() {
        int ttlDays = Math.max(1, feedInboxProperties.getTtlDays());
        return Duration.ofDays(ttlDays);
    }

    private int maxSize() {
        return Math.max(1, feedInboxProperties.getMaxSize());
    }

    private int rebuildWindowSize() {
        return Math.min(Math.max(0, rebuildMergeWindowSize), maxSize());
    }

    private void expireIfNeeded(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        Long ttlSeconds = stringRedisTemplate.getExpire(key);
        if (ttlSeconds == null || ttlSeconds < 0) {
            stringRedisTemplate.expire(key, ttl());
        }
    }

    private void trimToMaxSize(String key) {
        int maxSize = maxSize();
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size == null || size <= maxSize) {
            return;
        }
        long removeCount = size - maxSize;
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
    }

    private List<String> filterNoMore(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (INBOX_NO_MORE_MEMBER.equals(id)) {
                continue;
            }
            filtered.add(id);
        }
        return filtered;
    }

    private List<String> toList(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(set);
    }

    private List<Long> toLongList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> list = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                list.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // 跳过异常数据，避免一次坏数据导致整页失败
            }
        }
        return list;
    }
}
