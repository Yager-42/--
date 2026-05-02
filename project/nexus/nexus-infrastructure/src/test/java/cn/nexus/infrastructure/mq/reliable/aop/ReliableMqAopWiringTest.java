package cn.nexus.infrastructure.mq.reliable.aop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringJUnitConfig(ReliableMqAopWiringTest.TestConfig.class)
class ReliableMqAopWiringTest {

    @Autowired
    private ReliableMqOutboxService outboxService;

    @Autowired
    private PublisherBean publisherBean;

    @Autowired
    private TransactionalPublisherBean transactionalPublisherBean;

    @Autowired
    private AtomicBoolean transactionActiveAtSave;

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
    @EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE)
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ReliableMqExpressionEvaluator reliableMqExpressionEvaluator(ObjectMapper objectMapper) {
            return new ReliableMqExpressionEvaluator(objectMapper);
        }

        @Bean
        ReliableMqPublishAspect reliableMqPublishAspect(ReliableMqOutboxService outboxService,
                                                        ReliableMqExpressionEvaluator evaluator) {
            return new ReliableMqPublishAspect(outboxService, evaluator);
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
        AtomicBoolean transactionActiveAtSave() {
            return new AtomicBoolean(false);
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactionManager() {
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
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
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
}
