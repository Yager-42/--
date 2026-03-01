package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.FeedTimelineVO;

/**
 * 分发/Feed 服务。
 */
public interface IFeedService {

    FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType);

    FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit);
}
