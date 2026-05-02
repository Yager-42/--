package cn.nexus.infrastructure.mq.reliable.aop;

import org.springframework.core.Ordered;

final class ReliableMqAopOrder {

    static final int TRANSACTION_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 200;
    static final int RELIABLE_MQ_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private ReliableMqAopOrder() {
    }
}
