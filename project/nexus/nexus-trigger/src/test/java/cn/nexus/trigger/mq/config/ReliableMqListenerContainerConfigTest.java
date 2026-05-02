package cn.nexus.trigger.mq.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.aop.ProxyMethodInvocation;

class ReliableMqListenerContainerConfigTest {

    @Test
    void reliableRetryAdvice_shouldPassThroughImmediateRequeueWithoutRetryOrRecoverer() throws Throwable {
        ReliableMqListenerContainerConfig config = new ReliableMqListenerContainerConfig();
        SimpleRabbitListenerContainerFactory factory = config.reliableMqListenerContainerFactory(
                mock(ConnectionFactory.class),
                mock(MessageConverter.class));
        Advice[] adviceChain = factory.getAdviceChain();
        MethodInterceptor interceptor = (MethodInterceptor) adviceChain[0];
        ProxyMethodInvocation invocation = mock(ProxyMethodInvocation.class);
        MethodInvocation clonedInvocation = mock(MethodInvocation.class);
        ImmediateRequeueAmqpException failure = new ImmediateRequeueAmqpException("in progress");
        when(invocation.getMethod()).thenReturn(ReliableMqListenerContainerConfigTest.class.getDeclaredMethod("listener"));
        when(invocation.getArguments()).thenReturn(new Object[0]);
        when(invocation.invocableClone()).thenReturn(clonedInvocation);
        when(clonedInvocation.proceed()).thenThrow(failure);

        ImmediateRequeueAmqpException thrown = assertThrows(ImmediateRequeueAmqpException.class,
                () -> interceptor.invoke(invocation));

        assertSame(failure, thrown);
        verify(clonedInvocation, times(1)).proceed();
    }

    @Test
    void reliableRetryAdvice_shouldStillRetryBusinessFailuresBeforeDlqRecoverer() throws Throwable {
        ReliableMqListenerContainerConfig config = new ReliableMqListenerContainerConfig();
        SimpleRabbitListenerContainerFactory factory = config.reliableMqListenerContainerFactory(
                mock(ConnectionFactory.class),
                mock(MessageConverter.class));
        MethodInterceptor interceptor = (MethodInterceptor) factory.getAdviceChain()[0];
        ProxyMethodInvocation invocation = mock(ProxyMethodInvocation.class);
        MethodInvocation clonedInvocation = mock(MethodInvocation.class);
        Message message = new Message(new byte[0], new MessageProperties());
        RuntimeException failure = new RuntimeException("business failed");
        when(invocation.getMethod()).thenReturn(ReliableMqListenerContainerConfigTest.class.getDeclaredMethod("listener"));
        when(invocation.getArguments()).thenReturn(new Object[] {null, message});
        when(invocation.invocableClone()).thenReturn(clonedInvocation);
        when(clonedInvocation.proceed()).thenThrow(failure);

        ListenerExecutionFailedException thrown = assertThrows(ListenerExecutionFailedException.class,
                () -> interceptor.invoke(invocation));

        assertTrue(thrown.getCause() instanceof AmqpRejectAndDontRequeueException);
        assertSame(failure, thrown.getCause().getCause());
        assertTrue(thrown.getMessage().contains("Retry Policy Exhausted"));
        verify(clonedInvocation, times(5)).proceed();
    }

    private void listener() {
    }
}
