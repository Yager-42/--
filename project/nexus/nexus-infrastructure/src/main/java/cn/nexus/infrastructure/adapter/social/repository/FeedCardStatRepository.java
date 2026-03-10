package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import cn.nexus.infrastructure.support.SingleFlight;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FeedCardStatRepository implements IFeedCardStatRepository {
    private final SingleFlight singleFlight = new SingleFlight();

    @Override
    public Map<Long, FeedCardStatVO> getBatch(List<Long> postIds) {
        return Map.of();
    }

    @Override
    public Map<Long, FeedCardStatVO> getOrLoadBatch(List<Long> postIds,
                                                    Function<List<Long>, Map<Long, FeedCardStatVO>> loader) {
        List<Long> missIds = normalizeIds(postIds);
        if (missIds.isEmpty() || loader == null) {
            return Map.of();
        }
        Map<Long, FeedCardStatVO> loaded = singleFlight.execute(
                normalizeInflightKey(missIds),
                () -> loader.apply(missIds)
        );
        return loaded == null || loaded.isEmpty() ? Map.of() : loaded;
    }

    @Override
    public void saveBatch(List<FeedCardStatVO> stats) {
        // stat 默认不做独立缓存，保留接口避免上层改动扩散。
    }

    public void evictLocal(Long postId) {
    }

    public void evictRedis(Long postId) {
    }

    private List<Long> normalizeIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (Long postId : postIds) {
            if (postId == null || !seen.add(postId)) {
                continue;
            }
            ids.add(postId);
        }
        return ids;
    }

    private String normalizeInflightKey(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                normalized.add(id);
            }
        }
        return normalized.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }
}
