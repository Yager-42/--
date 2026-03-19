package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Feed 作者类别状态机仓储 Redis 实现（HASH）。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-01
 */
@Repository
@RequiredArgsConstructor
public class FeedAuthorCategoryRepository implements IFeedAuthorCategoryRepository {

    private static final String KEY_AUTHOR_CATEGORY = "feed:author:category";
    private static final int MULTI_GET_BATCH = 200;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 读取指定作者当前的类别。
     *
     * @param userId 作者 ID。 {@link Long}
     * @return 作者类别编码。 {@link Integer}
     */
    @Override
    public Integer getCategory(Long userId) {
        if (userId == null) {
            return null;
        }
        Object value = stringRedisTemplate.opsForHash().get(KEY_AUTHOR_CATEGORY, userId.toString());
        return parseInt(value);
    }

    /**
     * 批量读取作者类别。
     *
     * @param userIds 作者 ID 列表。 {@link List}
     * @return 作者类别映射。 {@link Map}
     */
    @Override
    public Map<Long, Integer> batchGetCategory(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<Long> uniq = new LinkedHashSet<>();
        for (Long id : userIds) {
            if (id != null) {
                uniq.add(id);
            }
        }
        if (uniq.isEmpty()) {
            return Map.of();
        }

        List<Long> ids = new ArrayList<>(uniq);
        Map<Long, Integer> result = new HashMap<>(ids.size());
        for (int from = 0; from < ids.size(); from += MULTI_GET_BATCH) {
            int to = Math.min(ids.size(), from + MULTI_GET_BATCH);
            List<String> fields = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                fields.add(ids.get(i).toString());
            }
            List<Object> values;
            try {
                values = stringRedisTemplate.opsForHash().multiGet(KEY_AUTHOR_CATEGORY, new ArrayList<>(fields));
            } catch (Exception ignored) {
                continue;
            }
            if (values == null || values.size() != fields.size()) {
                continue;
            }
            for (int i = 0; i < fields.size(); i++) {
                Integer category = parseInt(values.get(i));
                if (category == null) {
                    continue;
                }
                try {
                    result.put(Long.parseLong(fields.get(i)), category);
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return result;
    }

    /**
     * 写入作者类别。
     *
     * @param userId 作者 ID。 {@link Long}
     * @param category 类别编码。 {@link Integer}
     */
    @Override
    public void setCategory(Long userId, Integer category) {
        if (userId == null || category == null) {
            return;
        }
        stringRedisTemplate.opsForHash().put(KEY_AUTHOR_CATEGORY, userId.toString(), category.toString());
    }

    private Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

