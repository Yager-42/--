package cn.nexus.infrastructure.mq.reliable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.dao.social.IReliableMqConsumerRecordDao;
import cn.nexus.infrastructure.dao.social.po.ReliableMqConsumerRecordPO;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReliableMqConsumerRecordServiceTest {

    @Test
    void startManual_shouldReturnInProgressForFreshProcessingRecord() {
        IReliableMqConsumerRecordDao dao = Mockito.mock(IReliableMqConsumerRecordDao.class);
        ReliableMqConsumerRecordPO existed = new ReliableMqConsumerRecordPO();
        existed.setStatus(ReliableMqConsumerRecordService.STATUS_PROCESSING);
        existed.setUpdateTime(new Date());
        when(dao.insertIgnore(Mockito.any())).thenReturn(0);
        when(dao.selectOne("relation-counter:1", "RelationCounterProjectConsumer")).thenReturn(existed);
        ReliableMqConsumerRecordService service = new ReliableMqConsumerRecordService(dao);

        assertEquals(ReliableMqConsumerRecordService.StartResult.IN_PROGRESS,
                service.startManual("relation-counter:1", "RelationCounterProjectConsumer", "{}"));
    }

    @Test
    void startManual_shouldAllowRecoveringStaleProcessingRecordInsteadOfTreatingItAsDuplicateDone() {
        IReliableMqConsumerRecordDao dao = Mockito.mock(IReliableMqConsumerRecordDao.class);
        ReliableMqConsumerRecordPO existed = new ReliableMqConsumerRecordPO();
        existed.setStatus(ReliableMqConsumerRecordService.STATUS_PROCESSING);
        existed.setUpdateTime(new Date(System.currentTimeMillis() - 10 * 60 * 1000L));
        when(dao.insertIgnore(Mockito.any())).thenReturn(0);
        when(dao.selectOne("relation-counter:1", "RelationCounterProjectConsumer")).thenReturn(existed);
        ReliableMqConsumerRecordService service = new ReliableMqConsumerRecordService(dao);

        assertEquals(ReliableMqConsumerRecordService.StartResult.STARTED,
                service.startManual("relation-counter:1", "RelationCounterProjectConsumer", "{}"));

        verify(dao).updateStatus("relation-counter:1",
                "RelationCounterProjectConsumer",
                ReliableMqConsumerRecordService.STATUS_PROCESSING,
                null);
    }

    @Test
    void start_shouldStillBlockDoneRecord() {
        IReliableMqConsumerRecordDao dao = Mockito.mock(IReliableMqConsumerRecordDao.class);
        ReliableMqConsumerRecordPO existed = new ReliableMqConsumerRecordPO();
        existed.setStatus(ReliableMqConsumerRecordService.STATUS_DONE);
        when(dao.insertIgnore(Mockito.any())).thenReturn(0);
        when(dao.selectOne("relation-counter:1", "RelationCounterProjectConsumer")).thenReturn(existed);
        ReliableMqConsumerRecordService service = new ReliableMqConsumerRecordService(dao);

        assertFalse(service.start("relation-counter:1", "RelationCounterProjectConsumer", "{}"));
        assertEquals(ReliableMqConsumerRecordService.StartResult.DUPLICATE_DONE,
                service.startManual("relation-counter:1", "RelationCounterProjectConsumer", "{}"));
    }
}
