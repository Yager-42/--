package cn.nexus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聚合查询（读时拼装）专用线程池配置。
 *
 * 目标：有界、可控、可关停。默认值偏保守，后续可按压测调整。
 */
@ConfigurationProperties(prefix = "social.aggregation.executor")
public class AggregationExecutorProperties {

    /**
     * 核心线程数。
     */
    private int corePoolSize = 4;

    /**
     * 最大线程数。
     */
    private int maxPoolSize = 8;

    /**
     * 空闲线程回收时间（秒）。
     */
    private int keepAliveSeconds = 60;

    /**
     * 有界队列容量。
     */
    private int queueCapacity = 256;

    /**
     * 关闭时最多等待任务完成时间（秒）。
     */
    private int awaitTerminationSeconds = 10;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}

