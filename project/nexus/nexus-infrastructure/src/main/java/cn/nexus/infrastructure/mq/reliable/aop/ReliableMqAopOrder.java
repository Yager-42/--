package cn.nexus.infrastructure.mq.reliable.aop;

import org.springframework.core.Ordered;

final class ReliableMqAopOrder {

    static final int CONSUME_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 300;
    static final int DLQ_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 300;
    static final int TRANSACTION_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 200;
    static final int PUBLISH_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private ReliableMqAopOrder() {
    }
}
