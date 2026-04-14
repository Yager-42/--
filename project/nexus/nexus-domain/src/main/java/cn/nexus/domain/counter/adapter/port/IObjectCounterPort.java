package cn.nexus.domain.counter.adapter.port;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import java.util.List;
import java.util.Map;

/**
 * 对象聚合计数端口。
 *
 * @author codex
 * @since 2026-04-02
 */
public interface IObjectCounterPort {

    long getCount(ObjectCounterTarget target);

    Map<String, Long> batchGetCount(List<ObjectCounterTarget> targets);

    long increment(ObjectCounterTarget target, long delta);

    void setCount(ObjectCounterTarget target, long count);

    void evict(ObjectCounterTarget target);
}
