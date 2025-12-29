package cn.nexus.domain.social.adapter.port;

/**
 * 关系事件 MQ 收件箱持久化端口。
 */
public interface IRelationEventInboxPort {

    /**
     * 按指纹入库，若已存在则返回 false。
     */
    boolean save(String eventType, String fingerprint, String payload);

    /**
     * 处理成功标记 DONE。
     */
    void markDone(String fingerprint);

    /**
     * 处理失败标记 FAIL。
     */
    void markFail(String fingerprint);

    /**
     * 拉取需要重放/补偿的事件（失败或长期未处理）。
     */
    java.util.List<cn.nexus.domain.social.model.valobj.RelationEventInboxVO> fetchRetry(int limit);

    /**
     * 清理过期已完成记录。
     */
    int cleanBefore(java.util.Date beforeTime);
}
