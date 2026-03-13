package cn.nexus.infrastructure.adapter.id;

import cn.nexus.infrastructure.dao.id.ILeafAllocDao;
import cn.nexus.infrastructure.dao.id.po.LeafAllocPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf segment ID service (MySQL).
 */
@Component
@RequiredArgsConstructor
public class LeafSegmentIdService {

    private static final int DEFAULT_STEP = 1000;

    private final ILeafAllocDao leafAllocDao;
    private final PlatformTransactionManager transactionManager;

    private final ConcurrentHashMap<String, Segment> segmentByTag = new ConcurrentHashMap<>();

    public long nextId(String bizTag) {
        String tag = normalizeBizTag(bizTag);
        if (tag == null) {
            throw new IllegalArgumentException("bizTag is blank");
        }

        Segment seg = segmentByTag.get(tag);
        if (seg == null) {
            Segment allocated = allocateSegment(tag);
            Segment prev = segmentByTag.putIfAbsent(tag, allocated);
            seg = prev == null ? allocated : prev;
        }

        while (true) {
            long id = seg.next.getAndIncrement();
            if (id <= seg.max) {
                return id;
            }
            Segment refreshed = allocateSegment(tag);
            segmentByTag.put(tag, refreshed);
            seg = refreshed;
        }
    }

    protected Segment allocateSegment(String bizTag) {
        TransactionTemplate tpl = new TransactionTemplate(transactionManager);
        Segment seg = tpl.execute(status -> allocateSegmentTx(bizTag));
        if (seg == null) {
            throw new IllegalStateException("allocate segment failed, bizTag=" + bizTag);
        }
        return seg;
    }

    private Segment allocateSegmentTx(String bizTag) {
        LeafAllocPO po = leafAllocDao.selectByBizTagForUpdate(bizTag);
        if (po == null) {
            LeafAllocPO init = new LeafAllocPO();
            init.setBizTag(bizTag);
            init.setMaxId(0L);
            init.setStep(DEFAULT_STEP);
            init.setDescription("init");
            try {
                leafAllocDao.insert(init);
            } catch (Exception ignored) {
                // concurrent insert
            }
            po = leafAllocDao.selectByBizTagForUpdate(bizTag);
        }
        if (po == null) {
            throw new IllegalStateException("leaf_alloc not found for bizTag=" + bizTag);
        }

        long currentMax = po.getMaxId() == null ? 0L : Math.max(0L, po.getMaxId());
        int step = po.getStep() == null || po.getStep() <= 0 ? DEFAULT_STEP : po.getStep();
        long newMax = currentMax + step;

        leafAllocDao.updateMaxId(bizTag, newMax);
        return new Segment(new AtomicLong(currentMax + 1), newMax);
    }

    private String normalizeBizTag(String bizTag) {
        if (bizTag == null) {
            return null;
        }
        String t = bizTag.trim();
        return t.isEmpty() ? null : t;
    }

    protected static class Segment {
        private final AtomicLong next;
        private final long max;

        protected Segment(AtomicLong next, long max) {
            this.next = next;
            this.max = max;
        }
    }
}
