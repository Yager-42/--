package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.CommentLikeChangedEvent;

public interface IReactionCommentLikeChangedMqPort {

    void publish(CommentLikeChangedEvent event);
}
