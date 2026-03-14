package cn.nexus.infrastructure.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 进程内单飞控制：同一个 key 的并发 miss 只允许一个线程真正回源。
 *
 * @author {$authorName}
 * @since 2026-03-10
 */
public class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * 执行单飞逻辑：同一 key 的并发调用只让“领头线程”执行 supplier，其它线程等待结果。
     *
     * @param key 单飞 key {@link String}
     * @param supplier 回源执行器 {@link Supplier}
     * @return supplier 执行结果 {@code T}
     */
    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> supplier) {
        CompletableFuture<Object> leader = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, leader);
        if (existing != null) {
            return (T) waitFor(existing);
        }

        try {
            T result = supplier.get();
            leader.complete(result);
            return result;
        } catch (Throwable throwable) {
            leader.completeExceptionally(throwable);
            throw propagate(throwable);
        } finally {
            inflight.remove(key, leader);
        }
    }

    private Object waitFor(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw propagate(cause);
        }
    }

    private RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable);
    }
}
