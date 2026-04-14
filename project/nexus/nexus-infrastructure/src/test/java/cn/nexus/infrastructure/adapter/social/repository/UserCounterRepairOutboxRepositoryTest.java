package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.UserCounterRepairOutboxVO;
import cn.nexus.infrastructure.dao.social.IUserCounterRepairOutboxDao;
import cn.nexus.infrastructure.dao.social.po.UserCounterRepairOutboxPO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserCounterRepairOutboxRepositoryTest {

    @Test
    void save_shouldInsertNewRepairRecord() {
        IUserCounterRepairOutboxDao dao = Mockito.mock(IUserCounterRepairOutboxDao.class);
        UserCounterRepairOutboxRepository repository = new UserCounterRepairOutboxRepository(dao);

        repository.save(101L, 201L, "FOLLOW", "COUNT_REDIS_WRITE_FAILED", "evt-1");

        ArgumentCaptor<UserCounterRepairOutboxPO> captor = ArgumentCaptor.forClass(UserCounterRepairOutboxPO.class);
        verify(dao).insertIgnore(captor.capture());
        UserCounterRepairOutboxPO po = captor.getValue();
        assertEquals(101L, po.getSourceUserId());
        assertEquals(201L, po.getTargetUserId());
        assertEquals("FOLLOW", po.getOperation());
        assertEquals("COUNT_REDIS_WRITE_FAILED", po.getReason());
        assertEquals("evt-1", po.getCorrelationId());
        assertEquals("NEW", po.getStatus());
        assertEquals(0, po.getRetryCount());
        assertNotNull(po.getNextRetryTime());
    }

    @Test
    void fetchPending_shouldMapPersistedRows() {
        IUserCounterRepairOutboxDao dao = Mockito.mock(IUserCounterRepairOutboxDao.class);
        UserCounterRepairOutboxRepository repository = new UserCounterRepairOutboxRepository(dao);
        Date now = new Date(1234L);
        Date retryAt = new Date(5678L);
        Date createTime = new Date(9012L);
        Date updateTime = new Date(3456L);
        when(dao.selectByStatus("FAIL", now, 20)).thenReturn(List.of(row(1L, "FAIL", 2, retryAt, createTime, updateTime)));

        List<UserCounterRepairOutboxVO> result = repository.fetchPending("FAIL", now, 20);

        assertEquals(1, result.size());
        UserCounterRepairOutboxVO item = result.get(0);
        assertEquals(1L, item.getId());
        assertEquals(101L, item.getSourceUserId());
        assertEquals(201L, item.getTargetUserId());
        assertEquals("FOLLOW", item.getOperation());
        assertEquals("COUNT_REDIS_WRITE_FAILED", item.getReason());
        assertEquals("evt-1", item.getCorrelationId());
        assertEquals("FAIL", item.getStatus());
        assertEquals(2, item.getRetryCount());
        assertEquals(retryAt, item.getNextRetryTime());
        assertEquals(createTime, item.getCreateTime());
        assertEquals(updateTime, item.getUpdateTime());
    }

    @Test
    void markDone_shouldDelegateToDao() {
        IUserCounterRepairOutboxDao dao = Mockito.mock(IUserCounterRepairOutboxDao.class);
        UserCounterRepairOutboxRepository repository = new UserCounterRepairOutboxRepository(dao);

        repository.markDone(3L);

        verify(dao).markDone(3L);
    }

    @Test
    void markFail_shouldDelegateToDao() {
        IUserCounterRepairOutboxDao dao = Mockito.mock(IUserCounterRepairOutboxDao.class);
        UserCounterRepairOutboxRepository repository = new UserCounterRepairOutboxRepository(dao);
        Date retryTime = new Date(9999L);

        repository.markFail(4L, retryTime);

        verify(dao).markFail(4L, retryTime);
    }

    @Test
    void schemaFiles_shouldDeclareUserCounterRepairOutbox() throws Exception {
        String socialSchema = Files.readString(Path.of("C:/Users/Administrator/Desktop/文档/project/nexus/docs/social_schema.sql"));
        String finalSchema = Files.readString(Path.of("C:/Users/Administrator/Desktop/文档/project/nexus/docs/nexus_final_mysql_schema.sql"));
        String migration = Files.readString(Path.of("C:/Users/Administrator/Desktop/文档/project/nexus/docs/migrations/20260410_01_user_counter_repair_outbox.sql"));

        assertSchemaContains(socialSchema);
        assertSchemaContains(finalSchema);
        assertSchemaContains(migration);
    }

    private void assertSchemaContains(String schema) {
        String normalized = schema.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        assertTrue(normalized.contains("create table if not exists `user_counter_repair_outbox`"));
        assertTrue(normalized.contains("`id` bigint"));
        assertTrue(normalized.contains("`source_user_id` bigint not null"));
        assertTrue(normalized.contains("`target_user_id` bigint not null"));
        assertTrue(normalized.contains("`operation` varchar(16) not null"));
        assertTrue(normalized.contains("`reason` varchar(64) not null"));
        assertTrue(normalized.contains("`correlation_id` varchar(128) null"));
        assertTrue(normalized.contains("`status` varchar(16) not null default 'new'"));
        assertTrue(normalized.contains("`retry_count` int not null default 0"));
        assertTrue(normalized.contains("key `idx_user_counter_repair_status_retry` (`status`, `next_retry_time`)"));
    }

    private UserCounterRepairOutboxPO row(Long id,
                                          String status,
                                          Integer retryCount,
                                          Date nextRetryTime,
                                          Date createTime,
                                          Date updateTime) {
        UserCounterRepairOutboxPO po = new UserCounterRepairOutboxPO();
        po.setId(id);
        po.setSourceUserId(101L);
        po.setTargetUserId(201L);
        po.setOperation("FOLLOW");
        po.setReason("COUNT_REDIS_WRITE_FAILED");
        po.setCorrelationId("evt-1");
        po.setStatus(status);
        po.setRetryCount(retryCount);
        po.setNextRetryTime(nextRetryTime);
        po.setCreateTime(createTime);
        po.setUpdateTime(updateTime);
        return po;
    }
}
