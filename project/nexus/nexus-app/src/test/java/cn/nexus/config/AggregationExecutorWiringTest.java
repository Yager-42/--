package cn.nexus.config;

import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只验证“aggregationExecutor 这个 Bean 能被创建并按名称注入”，不启动全量应用上下文。
 */
class AggregationExecutorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AggregationExecutorConfig.class);

    @Test
    void shouldExposeAggregationExecutorBean() {
        contextRunner.run(context -> {
            assertTrue(context.containsBean("aggregationExecutor"));
            Executor executor = context.getBean("aggregationExecutor", Executor.class);
            assertNotNull(executor);
        });
    }
}

