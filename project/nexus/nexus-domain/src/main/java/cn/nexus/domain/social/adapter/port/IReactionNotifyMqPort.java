package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.InteractionNotifyEvent;

public interface IReactionNotifyMqPort {

    void publish(InteractionNotifyEvent event);
}
