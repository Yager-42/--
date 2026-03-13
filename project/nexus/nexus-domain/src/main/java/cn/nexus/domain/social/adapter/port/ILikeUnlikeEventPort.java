package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.LikeUnlikePostEvent;

/**
 * Like/Unlike MQ publish port.
 */
public interface ILikeUnlikeEventPort {

    void publishLike(LikeUnlikePostEvent event);

    void publishUnlike(LikeUnlikePostEvent event);
}
