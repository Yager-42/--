package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.LikeUnlikePostEvent;

public interface IReactionLikeUnlikeMqPort {

    void publishLike(LikeUnlikePostEvent event);

    void publishUnlike(LikeUnlikePostEvent event);
}
