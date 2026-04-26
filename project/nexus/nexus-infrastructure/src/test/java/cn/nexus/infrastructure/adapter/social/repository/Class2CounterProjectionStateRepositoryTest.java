package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.Class2ProjectionAdvanceResult;
import cn.nexus.infrastructure.dao.social.IClass2CounterProjectionStateDao;
import cn.nexus.infrastructure.dao.social.po.Class2CounterProjectionStatePO;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class Class2CounterProjectionStateRepositoryTest {

    @Test
    void advanceIfNewerShouldReturnAdvancedAndStaleAsExpected() {
        IClass2CounterProjectionStateDao dao = Mockito.mock(IClass2CounterProjectionStateDao.class);
        Class2CounterProjectionStateRepository repository = new Class2CounterProjectionStateRepository(dao);

        when(dao.selectForUpdate("follow:1:2"))
                .thenReturn(null,
                        state("follow:1:2", "FOLLOW", 3L),
                        state("follow:1:2", "FOLLOW", 3L),
                        state("follow:1:2", "FOLLOW", 3L));
        when(dao.insertIgnore(eq("follow:1:2"), eq("FOLLOW"), eq(3L), any(Date.class))).thenReturn(1);

        assertEquals(Class2ProjectionAdvanceResult.ADVANCED,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 3L));
        assertEquals(Class2ProjectionAdvanceResult.STALE,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 3L));
        assertEquals(Class2ProjectionAdvanceResult.STALE,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 2L));
        assertEquals(Class2ProjectionAdvanceResult.ADVANCED,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 4L));
    }

    @Test
    void advanceIfNewer_firstInsertConflictShouldReloadStateAndReturnStaleOrAdvanced() {
        IClass2CounterProjectionStateDao dao = Mockito.mock(IClass2CounterProjectionStateDao.class);
        Class2CounterProjectionStateRepository repository = new Class2CounterProjectionStateRepository(dao);

        when(dao.selectForUpdate("follow:1:2")).thenReturn(null, null);
        when(dao.insertIgnore(eq("follow:1:2"), eq("FOLLOW"), eq(3L), any(Date.class)))
                .thenReturn(0);
        when(dao.insertIgnore(eq("follow:1:2"), eq("FOLLOW"), eq(6L), any(Date.class)))
                .thenReturn(0);
        when(dao.selectOne("follow:1:2"))
                .thenReturn(state("follow:1:2", "FOLLOW", 5L), state("follow:1:2", "FOLLOW", 5L));

        assertEquals(Class2ProjectionAdvanceResult.STALE,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 3L));
        assertEquals(Class2ProjectionAdvanceResult.ADVANCED,
                repository.advanceIfNewer("follow:1:2", "FOLLOW", 6L));
    }

    @Test
    void advanceIfNewer_blankOrderingIdentityShouldThrow() {
        IClass2CounterProjectionStateDao dao = Mockito.mock(IClass2CounterProjectionStateDao.class);
        Class2CounterProjectionStateRepository repository = new Class2CounterProjectionStateRepository(dao);

        assertThrows(IllegalArgumentException.class,
                () -> repository.advanceIfNewer(null, "FOLLOW", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> repository.advanceIfNewer("follow:1:2", " ", 1L));
    }

    private Class2CounterProjectionStatePO state(String key, String type, long version) {
        Class2CounterProjectionStatePO po = new Class2CounterProjectionStatePO();
        po.setProjectionKey(key);
        po.setProjectionType(type);
        po.setLastVersion(version);
        po.setUpdateTime(new Date());
        return po;
    }
}
