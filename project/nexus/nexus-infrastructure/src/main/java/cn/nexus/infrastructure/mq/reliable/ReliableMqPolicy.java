package cn.nexus.infrastructure.mq.reliable;

import java.util.Date;

/**
 * RabbitMQ 可靠消息统一策略：
 * 1. 发送端 outbox 与消费端 replay 共用同一套时间策略。
 * 2. 不允许各条链路自己发明一套数字。
 */
public final class ReliableMqPolicy {

    public static final int CONSUMER_MAX_ATTEMPTS = 5;
    public static final long CONSUMER_INITIAL_INTERVAL_MS = 200L;
    public static final double CONSUMER_MULTIPLIER = 2.0d;
    public static final long CONSUMER_MAX_INTERVAL_MS = 5000L;

    public static final int MAX_REPLAY_ATTEMPTS = 6;

    private static final long[] REPLAY_DELAYS_MS = new long[]{
            60_000L,
            5L * 60_000L,
            15L * 60_000L,
            60L * 60_000L,
            6L * 60L * 60_000L,
            24L * 60L * 60_000L
    };

    private ReliableMqPolicy() {
    }

    public static Date nextReplayTime(int attempt) {
        int safeAttempt = Math.max(0, attempt);
        int index = Math.min(safeAttempt, REPLAY_DELAYS_MS.length - 1);
        return new Date(System.currentTimeMillis() + REPLAY_DELAYS_MS[index]);
    }
}
