package cn.nexus.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 聚合查询（读时拼装）专用线程池。
 *
 * 设计目标：
 * 1) 有界队列，避免流量峰值时“越堆越多”把内存打爆
 * 2) CallerRunsPolicy：队列满了就让当前线程跑，用“变慢”替代“直接失败”
 * 3) 关停可控：应用退出时不会挂线程或卡死
 * 4) 最小上下文复制：只复制 MDC（traceId 等日志上下文）
 */
@Configuration
@EnableConfigurationProperties(AggregationExecutorProperties.class)
public class AggregationExecutorConfig {

    @Bean(name = "aggregationExecutor")
    public Executor aggregationExecutor(AggregationExecutorProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agg-");

        int core = Math.max(1, props.getCorePoolSize());
        int max = Math.max(core, props.getMaxPoolSize());
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setKeepAliveSeconds(Math.max(0, props.getKeepAliveSeconds()));
        executor.setQueueCapacity(Math.max(0, props.getQueueCapacity()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, props.getAwaitTerminationSeconds()));

        executor.setTaskDecorator(mdcCopyingTaskDecorator());
        executor.initialize();
        return executor;
    }

    private static TaskDecorator mdcCopyingTaskDecorator() {
        return runnable -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (captured == null || captured.isEmpty()) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(captured);
                    }
                    runnable.run();
                } finally {
                    if (previous == null || previous.isEmpty()) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}

