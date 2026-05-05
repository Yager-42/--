package cn.nexus.domain.social.model.valobj;

/**
 * 关系计数投影 RabbitMQ 路由常量。
 */
public final class RelationCounterRouting {

    public static final String EXCHANGE = "social.relation.counter";
    public static final String RK_FOLLOW = "relation.counter.follow";
    public static final String RK_BLOCK = "relation.counter.block";
    public static final String Q_FOLLOW = "relation.counter.follow.queue";
    public static final String Q_BLOCK = "relation.counter.block.queue";
    public static final String Q_FOLLOW_FEED_COMPENSATE = "relation.counter.follow.feed.compensate.queue";
    public static final String DLX_EXCHANGE = "social.relation.counter.dlx";
    public static final String DLQ_FOLLOW = "relation.counter.follow.dlq.queue";
    public static final String DLQ_BLOCK = "relation.counter.block.dlq.queue";
    public static final String DLQ_FOLLOW_FEED_COMPENSATE = "relation.counter.follow.feed.compensate.dlq.queue";
    public static final String RK_FOLLOW_DLX = "relation.counter.follow.dlx";
    public static final String RK_BLOCK_DLX = "relation.counter.block.dlx";
    public static final String RK_FOLLOW_FEED_COMPENSATE_DLX = "relation.counter.follow.feed.compensate.dlx";

    private RelationCounterRouting() {
    }
}
