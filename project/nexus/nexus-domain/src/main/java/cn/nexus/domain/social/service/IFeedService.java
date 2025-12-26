package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;

/**
 * 分发/Feed 服务。
 */
public interface IFeedService {

    FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType);

    FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit);

    OperationResultVO negativeFeedback(Long userId, Long targetId, String type, String reasonCode, java.util.List<String> extraTags);

    OperationResultVO cancelNegativeFeedback(Long userId, Long targetId);
}
