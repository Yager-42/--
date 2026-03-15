package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.model.entity.RiskFeedbackEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RiskAppealServiceTest {

    private ISocialIdPort socialIdPort;
    private IRiskFeedbackRepository feedbackRepository;
    private IRiskPunishmentRepository punishmentRepository;
    private RiskAppealService riskAppealService;

    @BeforeEach
    void setUp() {
        socialIdPort = Mockito.mock(ISocialIdPort.class);
        feedbackRepository = Mockito.mock(IRiskFeedbackRepository.class);
        punishmentRepository = Mockito.mock(IRiskPunishmentRepository.class);
        riskAppealService = new RiskAppealService(socialIdPort, feedbackRepository, punishmentRepository);
    }

    @Test
    void submitAppeal_shouldPersistOpenFeedback() {
        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(2000L);
        when(feedbackRepository.insert(Mockito.any())).thenReturn(true);

        OperationResultVO result = riskAppealService.submitAppeal(1L, 9L, null, "I disagree");

        assertEquals(true, result.isSuccess());
        assertEquals("OPEN", result.getStatus());
        verify(feedbackRepository).insert(Mockito.any(RiskFeedbackEntity.class));
    }

    @Test
    void decideAppeal_shouldRevokePunishmentWhenAccepted() {
        when(feedbackRepository.findById(9L)).thenReturn(RiskFeedbackEntity.builder().feedbackId(9L).punishId(88L).build());
        when(feedbackRepository.updateStatus(9L, "DONE", "ACCEPT", 7L)).thenReturn(true);

        OperationResultVO result = riskAppealService.decideAppeal(7L, 9L, "accept");

        assertEquals("DONE", result.getStatus());
        verify(punishmentRepository).revoke(88L, 7L);
    }

    @Test
    void submitAppeal_shouldRejectWhenReferenceMissing() {
        assertThrows(AppException.class, () -> riskAppealService.submitAppeal(1L, null, null, "no ref"));
    }
}
