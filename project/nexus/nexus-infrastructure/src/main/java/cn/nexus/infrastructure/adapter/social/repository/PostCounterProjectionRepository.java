package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IPostCounterProjectionRepository;
import cn.nexus.infrastructure.dao.social.IPostCounterProjectionDao;
import cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PostCounterProjectionRepository implements IPostCounterProjectionRepository {

    // Concurrency assumption: events for the same postId are consumed sequentially
    // by a single RabbitMQ consumer thread (manual ack, ordered queue processing).
    // The two-step read-then-write (SELECT + INSERT IGNORE/UPDATE) is safe under
    // this model. If this assumption changes, the INSERT IGNORE path needs re-SELECT
    // after insertion to determine the true edge result.

    private final IPostCounterProjectionDao dao;

    @Override
    public EdgeResult compareAndWrite(Long postId, Long authorId,
                                      boolean targetPublished, Long relationEventId) {
        if (postId == null || authorId == null || relationEventId == null) {
            return EdgeResult.SAME_STATE;
        }

        PostCounterProjectionPO existing = dao.selectByPostId(postId);

        if (existing != null) {
            if (relationEventId <= existing.getLastEventId()) {
                return EdgeResult.STALE_EVENT;
            }
            boolean currentPublished = existing.getProjectedPublished() != null
                    && existing.getProjectedPublished() == 1;
            if (currentPublished == targetPublished) {
                int updated = dao.updateState(postId, targetPublished ? 1 : 0, relationEventId);
                if (updated == 0) {
                    return EdgeResult.STALE_EVENT;
                }
                return EdgeResult.SAME_STATE;
            }
            int updated = dao.updateState(postId, targetPublished ? 1 : 0, relationEventId);
            if (updated == 0) {
                return EdgeResult.STALE_EVENT;
            }
            return EdgeResult.EDGE_TRANSITION;
        }

        PostCounterProjectionPO po = new PostCounterProjectionPO();
        po.setPostId(postId);
        po.setAuthorId(authorId);
        po.setProjectedPublished(targetPublished ? 1 : 0);
        po.setLastEventId(relationEventId);
        dao.insert(po);

        if (targetPublished) {
            return EdgeResult.EDGE_TRANSITION;
        }
        return EdgeResult.SAME_STATE;
    }
}
