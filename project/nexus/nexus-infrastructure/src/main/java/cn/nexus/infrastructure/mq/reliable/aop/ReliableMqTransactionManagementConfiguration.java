package cn.nexus.infrastructure.mq.reliable.aop;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = ReliableMqAopOrder.TRANSACTION_ADVISOR_ORDER)
public class ReliableMqTransactionManagementConfiguration {
}
