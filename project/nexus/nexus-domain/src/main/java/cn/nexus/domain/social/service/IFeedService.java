package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.FeedTimelineVO;

/**
 * 分发/Feed 服务。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
public interface IFeedService {

    FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType);

    FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit);
}
