package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRiskTaskPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskCaseRepository;
import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.adapter.repository.IRiskRuleVersionRepository;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskEventVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;

class RiskServiceTest {

    private ISocialIdPort socialIdPort;
    private IRiskDecisionLogRepository decisionLogRepository;
    private IRiskCaseRepository caseRepository;
    private IRiskRuleVersionRepository ruleVersionRepository;
    private IRiskPunishmentRepository punishmentRepository;
    private IRiskTaskPort riskTaskPort;
    private RiskService riskService;

    @BeforeEach
    void setUp() {
        socialIdPort = Mockito.mock(ISocialIdPort.class);
        decisionLogRepository = Mockito.mock(IRiskDecisionLogRepository.class);
        caseRepository = Mockito.mock(IRiskCaseRepository.class);
        ruleVersionRepository = Mockito.mock(IRiskRuleVersionRepository.class);
        punishmentRepository = Mockito.mock(IRiskPunishmentRepository.class);
        riskTaskPort = Mockito.mock(IRiskTaskPort.class);
        riskService = new RiskService(
                socialIdPort,
                decisionLogRepository,
                caseRepository,
                ruleVersionRepository,
                punishmentRepository,
                riskTaskPort,
                Mockito.mock(RedissonClient.class),
                new ObjectMapper()
        );
    }

    @Test
    void decision_shouldReplayExistingDecision() {
        when(decisionLogRepository.findByUserEvent(1L, "evt-1")).thenReturn(RiskDecisionLogEntity.builder()
                .decisionId(9L)
                .result("PASS")
                .reasonCode("PASS")
                .build());

        RiskDecisionVO result = riskService.decision(RiskEventVO.builder()
                .eventId("evt-1")
                .userId(1L)
                .actionType("TEXT_SCAN")
                .scenario("text.scan")
                .contentText("hello")
                .build());

        assertEquals(9L, result.getDecisionId());
        assertEquals("PASS", result.getResult());
    }

    @Test
    void decision_shouldBlockWhenPunishmentActive() {
        when(socialIdPort.now()).thenReturn(1000L);
        when(socialIdPort.nextId()).thenReturn(88L);
        when(decisionLogRepository.findByUserEvent(1L, "evt-2")).thenReturn(null);
        when(punishmentRepository.listActiveByUser(1L, 1000L)).thenReturn(List.of(
                RiskPunishmentEntity.builder().type("POST_BAN").build()
        ));
        when(decisionLogRepository.insert(any())).thenReturn(true);

        RiskDecisionVO result = riskService.decision(RiskEventVO.builder()
                .eventId("evt-2")
                .userId(1L)
                .actionType("PUBLISH_POST")
                .scenario("post.publish")
                .contentText("hello")
                .build());

        assertEquals("BLOCK", result.getResult());
        verify(riskTaskPort, never()).dispatchLlmScan(any());
    }

    @Test
    void imageScan_shouldPersistAndDispatchAsyncTask() {
        when(socialIdPort.nextId()).thenReturn(1L, 2L);
        when(socialIdPort.now()).thenReturn(1000L, 1000L, 1000L, 1000L);
        when(decisionLogRepository.insert(any())).thenReturn(true);

        ImageScanResultVO result = riskService.imageScan("https://img.test/a.png", 1L);

        assertEquals("task-1", result.getTaskId());
        verify(riskTaskPort).dispatchImageScan(any());
    }

    @Test
    void userStatus_shouldFreezeWhenCapabilitiesRemoved() {
        when(socialIdPort.now()).thenReturn(1000L);
        when(punishmentRepository.listActiveByUser(1L, 1000L)).thenReturn(List.of(
                RiskPunishmentEntity.builder().type("POST_BAN").build(),
                RiskPunishmentEntity.builder().type("COMMENT_BAN").build()
        ));

        UserRiskStatusVO result = riskService.userStatus(1L);

        assertEquals("FROZEN", result.getStatus());
        assertEquals(0, result.getCapabilities().size());
    }

    @Test
    void textScan_shouldBlockLinkAndReturnRuleTags() {
        when(socialIdPort.nextId()).thenReturn(1L, 2L);
        when(socialIdPort.now()).thenReturn(1000L, 1000L);
        when(decisionLogRepository.findByUserEvent(1L, "scan_text_1")).thenReturn(null);
        when(punishmentRepository.listActiveByUser(1L, 1000L)).thenReturn(List.of());
        when(decisionLogRepository.insert(any())).thenReturn(true);

        TextScanResultVO result = riskService.textScan("visit https://bad.site", 1L, null);

        assertEquals("BLOCK", result.getResult());
        assertEquals(List.of("spam/link"), result.getTags());
    }
}
