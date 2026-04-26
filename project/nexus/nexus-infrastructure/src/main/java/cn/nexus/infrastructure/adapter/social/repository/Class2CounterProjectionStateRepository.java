package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IClass2CounterProjectionStateRepository;
import cn.nexus.domain.social.model.valobj.Class2ProjectionAdvanceResult;
import cn.nexus.infrastructure.dao.social.IClass2CounterProjectionStateDao;
import cn.nexus.infrastructure.dao.social.po.Class2CounterProjectionStatePO;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class Class2CounterProjectionStateRepository implements IClass2CounterProjectionStateRepository {

    private final IClass2CounterProjectionStateDao projectionStateDao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Class2ProjectionAdvanceResult advanceIfNewer(String projectionKey, String projectionType, long projectionVersion) {
        if (projectionKey == null || projectionKey.isBlank() || projectionType == null || projectionType.isBlank()) {
            throw new IllegalArgumentException("projectionKey/projectionType must not be blank");
        }
        Date now = new Date();
        Class2CounterProjectionStatePO state = projectionStateDao.selectForUpdate(projectionKey);
        if (state == null) {
            int inserted = projectionStateDao.insertIgnore(projectionKey, projectionType, projectionVersion, now);
            if (inserted > 0) {
                return Class2ProjectionAdvanceResult.ADVANCED;
            }
            state = projectionStateDao.selectOne(projectionKey);
            if (state == null) {
                throw new IllegalStateException("projection state missing after insertIgnore, key=" + projectionKey);
            }
        }
        if (state.getProjectionType() != null && !state.getProjectionType().equals(projectionType)) {
            throw new IllegalStateException("projection key type mismatch, key=" + projectionKey
                    + ", existingType=" + state.getProjectionType()
                    + ", requestType=" + projectionType);
        }
        long lastVersion = state.getLastVersion() == null ? Long.MIN_VALUE : state.getLastVersion();
        if (projectionVersion <= lastVersion) {
            return Class2ProjectionAdvanceResult.STALE;
        }
        projectionStateDao.updateVersion(projectionKey, projectionType, projectionVersion, now);
        return Class2ProjectionAdvanceResult.ADVANCED;
    }
}
