package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.recommend.RecommendFeedbackEvent;

/**
 * 推荐反馈事件发布端口：domain 只依赖端口，不直接依赖 MQ。
 *
 * @author codex
 * @since 2026-01-26
 */
public interface IRecommendFeedbackEventPort {

    /**
     * 发布推荐反馈事件（旁路，不允许影响主链路）。
     *
     * @param event 推荐反馈事件 {@link RecommendFeedbackEvent}
     */
    void publish(RecommendFeedbackEvent event);
}

