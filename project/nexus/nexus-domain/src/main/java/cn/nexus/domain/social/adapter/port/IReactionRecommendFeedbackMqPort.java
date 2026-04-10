package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.recommend.RecommendFeedbackEvent;

public interface IReactionRecommendFeedbackMqPort {

    void publish(RecommendFeedbackEvent event);
}
