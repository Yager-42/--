package cn.nexus.infrastructure.support;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 进程内单飞控制：同一个 key 的并发 miss 只允许一个线程真正回源。
 */
public class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    /**
     * 执行单飞逻辑。
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
