package cn.nexus.domain.social.adapter.port;

public interface IRelationEventPort {

    void onFollow(Long eventId, Long sourceId, Long targetId, String status);

    void onBlock(Long eventId, Long sourceId, Long targetId);
}
