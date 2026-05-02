package cn.nexus.infrastructure.mq.reliable.aop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig({
        ReliableMqAopWiringTest.TestConfig.class,
        ReliableMqTransactionManagementConfiguration.class
})
class ReliableMqAopWiringTest {

    @Autowired
    private ReliableMqOutboxService outboxService;

    @Autowired
    private ReliableMqConsumerRecordService consumerRecordService;

    @Autowired
    private PublisherBean publisherBean;

    @Autowired
    private TransactionalPublisherBean transactionalPublisherBean;

    @Autowired
    private TransactionalConsumerBean transactionalConsumerBean;

    @Autowired
    private AtomicBoolean transactionActiveAtSave;

    @Autowired
    private List<String> consumeEvents;

    @BeforeEach
    void setUp() {
        consumeEvents.clear();
        transactionActiveAtSave.set(false);
    }

    @Test
    void publicAnnotatedSpringBeanMethod_shouldBeProxiedAndSaveOutbox() {
        PublishEvent event = new PublishEvent("evt-1", "hello");

        publisherBean.publish(event);

        org.junit.jupiter.api.Assertions.assertTrue(AopUtils.isAopProxy(publisherBean));
        verify(outboxService).save("evt-1", "social.interaction", "comment.created", event);
    }

    @Test
    void directSelfInvocation_shouldBeRejectedByArchitectureAssertion() {
        assertThrows(IllegalStateException.class,
                () -> assertNoSelfInvocationOfReliablePublish(SelfInvokingPublisherBean.class));
    }

    @Test
    void transactionalAnnotatedMethod_shouldSaveOutboxWithinActiveTransaction() {
        PublishEvent event = new PublishEvent("evt-2", "hello");

        transactionalPublisherBean.publishInTransaction(event);

        verify(outboxService).save("evt-2", "social.interaction", "comment.created", event);
        org.junit.jupiter.api.Assertions.assertTrue(transactionActiveAtSave.get());
    }

    @Test
    void transactionalConsumeMethod_shouldRunBusinessInTransactionAndMarkDoneAfterReturn() {
        ConsumeEvent event = new ConsumeEvent("evt-3", "hello");

        transactionalConsumerBean.consumeInTransaction(event);

        verify(consumerRecordService).startManual(Mockito.eq("evt-3"), Mockito.eq("comment-consumer"),
                Mockito.anyString());
        verify(consumerRecordService).markDone("evt-3", "comment-consumer");
        InOrder inOrder = Mockito.inOrder(consumerRecordService);
        inOrder.verify(consumerRecordService).startManual(Mockito.eq("evt-3"), Mockito.eq("comment-consumer"),
                Mockito.anyString());
        inOrder.verify(consumerRecordService).markDone("evt-3", "comment-consumer");
        org.junit.jupiter.api.Assertions.assertEquals(List.of("start:false", "body:true", "commit:true", "done:false"),
                consumeEvents);
    }

    @Test
    void transactionalConsumeFailure_shouldRollbackThenMarkFailOutsideActiveTransaction() {
        ConsumeEvent event = new ConsumeEvent("evt-4", "boom");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> transactionalConsumerBean.consumeAndFailInTransaction(event));

        org.junit.jupiter.api.Assertions.assertEquals("consumer failed", thrown.getMessage());
        verify(consumerRecordService).startManual(Mockito.eq("evt-4"), Mockito.eq("comment-consumer"),
                Mockito.anyString());
        verify(consumerRecordService).markFail("evt-4", "comment-consumer", "consumer failed");
        org.junit.jupiter.api.Assertions.assertEquals(List.of("start:false", "body:true", "rollback:true", "fail:false"),
                consumeEvents);
    }

    @Test
    void productionTransactionManagementOrder_shouldWrapReliableMqAspects() {
        EnableTransactionManagement annotation = AnnotationUtils.findAnnotation(
                ReliableMqTransactionManagementConfiguration.class,
                EnableTransactionManagement.class);

        org.junit.jupiter.api.Assertions.assertNotNull(annotation);
        org.junit.jupiter.api.Assertions.assertEquals(ReliableMqAopOrder.TRANSACTION_ADVISOR_ORDER,
                annotation.order());
        org.junit.jupiter.api.Assertions.assertTrue(ReliableMqAopOrder.CONSUME_ASPECT_ORDER
                < ReliableMqAopOrder.TRANSACTION_ADVISOR_ORDER);
        org.junit.jupiter.api.Assertions.assertTrue(ReliableMqAopOrder.TRANSACTION_ADVISOR_ORDER
                < ReliableMqAopOrder.PUBLISH_ASPECT_ORDER);
        org.junit.jupiter.api.Assertions.assertTrue(ReliableMqAopOrder.DLQ_ASPECT_ORDER
                < ReliableMqAopOrder.TRANSACTION_ADVISOR_ORDER);
    }

    private static void assertNoSelfInvocationOfReliablePublish(Class<?> type) throws Exception {
        Set<String> reliableMethods = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ReliableMqPublish.class)) {
                reliableMethods.add(method.getName());
            }
        }
        String classFile = type.getName().replace('.', '/') + ".class";
        try (InputStream inputStream = new ClassPathResource(classFile).getInputStream()) {
            ClassReader reader = new ClassReader(inputStream);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String descriptor, boolean isInterface) {
                            if (owner.equals(reader.getClassName()) && reliableMethods.contains(methodName)) {
                                throw new IllegalStateException("self-invocation of @ReliableMqPublish: " + methodName);
                            }
                        }
                    };
                }
            }, 0);
        }
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ReliableMqExpressionEvaluator reliableMqExpressionEvaluator() {
            return new ReliableMqExpressionEvaluator();
        }

        @Bean
        ReliableMqPublishAspect reliableMqPublishAspect(ReliableMqOutboxService outboxService,
                                                        ReliableMqExpressionEvaluator evaluator) {
            return new ReliableMqPublishAspect(outboxService, evaluator);
        }

        @Bean
        ReliableMqConsumeAspect reliableMqConsumeAspect(ReliableMqConsumerRecordService consumerRecordService,
                                                        ReliableMqExpressionEvaluator evaluator,
                                                        ObjectMapper objectMapper) {
            return new ReliableMqConsumeAspect(consumerRecordService, evaluator, objectMapper);
        }

        @Bean
        ReliableMqOutboxService reliableMqOutboxService(AtomicBoolean transactionActiveAtSave) {
            ReliableMqOutboxService outboxService = Mockito.mock(ReliableMqOutboxService.class);
            Mockito.doAnswer(invocation -> {
                transactionActiveAtSave.set(TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(outboxService).save(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any());
            return outboxService;
        }

        @Bean
        ReliableMqConsumerRecordService reliableMqConsumerRecordService(List<String> consumeEvents) {
            ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
            Mockito.when(consumerRecordService.startManual(Mockito.matches("evt-[34]"), Mockito.eq("comment-consumer"),
                            Mockito.anyString()))
                    .thenAnswer(invocation -> {
                        consumeEvents.add("start:" + TransactionSynchronizationManager.isActualTransactionActive());
                        return StartResult.STARTED;
                    });
            Mockito.doAnswer(invocation -> {
                consumeEvents.add("done:" + TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(consumerRecordService).markDone(Mockito.anyString(), Mockito.eq("comment-consumer"));
            Mockito.doAnswer(invocation -> {
                consumeEvents.add("fail:" + TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(consumerRecordService).markFail(Mockito.anyString(), Mockito.eq("comment-consumer"),
                    Mockito.anyString());
            return consumerRecordService;
        }

        @Bean
        AtomicBoolean transactionActiveAtSave() {
            return new AtomicBoolean(false);
        }

        @Bean
        List<String> consumeEvents() {
            return new ArrayList<>();
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager(List<String> consumeEvents) {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() throws TransactionException {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
                    consumeEvents.add("commit:" + TransactionSynchronizationManager.isActualTransactionActive());
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
                    consumeEvents.add("rollback:" + TransactionSynchronizationManager.isActualTransactionActive());
                }
            };
        }

        @Bean
        PublisherBean publisherBean() {
            return new PublisherBean();
        }

        @Bean
        TransactionalPublisherBean transactionalPublisherBean() {
            return new TransactionalPublisherBean();
        }

        @Bean
        TransactionalConsumerBean transactionalConsumerBean(List<String> consumeEvents) {
            return new TransactionalConsumerBean(consumeEvents);
        }
    }

    static class PublisherBean {
        @ReliableMqPublish(exchange = "social.interaction",
                routingKey = "comment.created",
                eventId = "#event.eventId",
                payload = "#event")
        public void publish(PublishEvent event) {
        }
    }

    static class TransactionalPublisherBean {
        @Transactional
        @ReliableMqPublish(exchange = "social.interaction",
                routingKey = "comment.created",
                eventId = "#event.eventId",
                payload = "#event")
        public void publishInTransaction(PublishEvent event) {
        }
    }

    static class TransactionalConsumerBean {
        private final List<String> consumeEvents;

        TransactionalConsumerBean(List<String> consumeEvents) {
            this.consumeEvents = consumeEvents;
        }

        @Transactional
        @ReliableMqConsume(consumerName = "comment-consumer", eventId = "#event.eventId", payload = "#event")
        public void consumeInTransaction(ConsumeEvent event) {
            consumeEvents.add("body:" + TransactionSynchronizationManager.isActualTransactionActive());
        }

        @Transactional
        @ReliableMqConsume(consumerName = "comment-consumer", eventId = "#event.eventId", payload = "#event")
        public void consumeAndFailInTransaction(ConsumeEvent event) {
            consumeEvents.add("body:" + TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("consumer failed");
        }
    }

    static class SelfInvokingPublisherBean {
        public void publishViaSelfInvocation(PublishEvent event) {
            publish(event);
        }

        @ReliableMqPublish(exchange = "social.interaction",
                routingKey = "comment.created",
                eventId = "#event.eventId",
                payload = "#event")
        public void publish(PublishEvent event) {
        }
    }

    record PublishEvent(String eventId, String body) {
    }

    record ConsumeEvent(String eventId, String body) {
    }
}
