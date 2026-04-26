package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.Class2UserCounterRepairTaskVO;
import cn.nexus.infrastructure.dao.social.IClass2UserCounterRepairTaskDao;
import cn.nexus.infrastructure.dao.social.po.Class2UserCounterRepairTaskPO;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class Class2UserCounterRepairTaskRepositoryTest {

    @Test
    void enqueueAndClaimBatchShouldCoalesceAndClaimByOwner() {
        IClass2UserCounterRepairTaskDao dao = Mockito.mock(IClass2UserCounterRepairTaskDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        Class2UserCounterRepairTaskRepository repository = new Class2UserCounterRepairTaskRepository(dao, socialIdPort);
        when(socialIdPort.nextId()).thenReturn(1001L, 1002L);

        repository.enqueue("USER_CLASS2", 11L, "redis increment failed", "USER_CLASS2:11");
        repository.enqueue("USER_CLASS2", 11L, "repeat", "USER_CLASS2:11");

        verify(dao).insertIgnore(eq(1001L), eq("USER_CLASS2"), eq(11L), eq("USER_CLASS2:11"),
                eq("PENDING"), eq(0), any(Date.class), eq("redis increment failed"), any(Date.class), any(Date.class));
        verify(dao).insertIgnore(eq(1002L), eq("USER_CLASS2"), eq(11L), eq("USER_CLASS2:11"),
                eq("PENDING"), eq(0), any(Date.class), eq("repeat"), any(Date.class), any(Date.class));

        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + 60_000L);
        when(dao.selectClaimableTaskIds(now, 10)).thenReturn(List.of(1001L));
        when(dao.selectByTaskIds(List.of(1001L))).thenReturn(List.of(task(1001L, "worker-a", 11L)));

        List<Class2UserCounterRepairTaskVO> claimed = repository.claimBatch("worker-a", 10, now, leaseUntil);

        assertEquals(1, claimed.size());
        assertEquals("worker-a", claimed.get(0).getClaimOwner());
        assertEquals(11L, claimed.get(0).getUserId());
    }

    @Test
    void enqueueShouldOnlyRequeueWhenExistingTaskIsTerminal() {
        IClass2UserCounterRepairTaskDao dao = Mockito.mock(IClass2UserCounterRepairTaskDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        Class2UserCounterRepairTaskRepository repository = new Class2UserCounterRepairTaskRepository(dao, socialIdPort);
        when(socialIdPort.nextId()).thenReturn(2001L, 2002L);

        repository.enqueue("USER_CLASS2", 11L, "pending merge", "USER_CLASS2:11");
        repository.enqueue("USER_CLASS2", 11L, "done requeue", "USER_CLASS2:11");

        verify(dao).insertIgnore(eq(2001L), eq("USER_CLASS2"), eq(11L), eq("USER_CLASS2:11"),
                eq("PENDING"), eq(0), any(Date.class), eq("pending merge"), any(Date.class), any(Date.class));
        verify(dao).insertIgnore(eq(2002L), eq("USER_CLASS2"), eq(11L), eq("USER_CLASS2:11"),
                eq("PENDING"), eq(0), any(Date.class), eq("done requeue"), any(Date.class), any(Date.class));
    }

    @Test
    void claimBatch_secondWorkerShouldNotReceiveAlreadyClaimedTask() {
        IClass2UserCounterRepairTaskDao dao = Mockito.mock(IClass2UserCounterRepairTaskDao.class);
        ISocialIdPort socialIdPort = Mockito.mock(ISocialIdPort.class);
        Class2UserCounterRepairTaskRepository repository = new Class2UserCounterRepairTaskRepository(dao, socialIdPort);
        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + 60_000L);

        when(dao.selectClaimableTaskIds(now, 10))
                .thenReturn(List.of(3001L))
                .thenReturn(List.of());
        when(dao.selectByTaskIds(List.of(3001L)))
                .thenReturn(List.of(task(3001L, "worker-a", 44L)));

        List<Class2UserCounterRepairTaskVO> workerA = repository.claimBatch("worker-a", 10, now, leaseUntil);
        List<Class2UserCounterRepairTaskVO> workerB = repository.claimBatch("worker-b", 10, now, leaseUntil);

        assertEquals(1, workerA.size());
        assertEquals(0, workerB.size());
        verify(dao).claimBatch(List.of(3001L), "worker-a", now, leaseUntil);
        verify(dao, never()).claimBatch(List.of(3001L), "worker-b", now, leaseUntil);
    }

    private Class2UserCounterRepairTaskPO task(Long taskId, String owner, Long userId) {
        Class2UserCounterRepairTaskPO po = new Class2UserCounterRepairTaskPO();
        po.setTaskId(taskId);
        po.setRepairType("USER_CLASS2");
        po.setUserId(userId);
        po.setDedupeKey("USER_CLASS2:" + userId);
        po.setStatus("RUNNING");
        po.setRetryCount(0);
        po.setClaimOwner(owner);
        po.setClaimedAt(new Date());
        po.setLeaseUntil(new Date(System.currentTimeMillis() + 60_000L));
        po.setNextRetryTime(new Date());
        po.setCreateTime(new Date());
        po.setUpdateTime(new Date());
        return po;
    }
}
