package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskCaseRepository;
import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPromptVersionRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.adapter.repository.IRiskRuleVersionRepository;
import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

class RiskAdminServiceTest {

    private ISocialIdPort socialIdPort;
    private IRiskRuleVersionRepository ruleVersionRepository;
    private IRiskPromptVersionRepository promptVersionRepository;
    private IRiskCaseRepository caseRepository;
    private IRiskPunishmentRepository punishmentRepository;
    private IRiskDecisionLogRepository decisionLogRepository;
    private IContentService contentService;
    private IInteractionService interactionService;
    private RedissonClient redissonClient;
    private RiskAdminService riskAdminService;

    @BeforeEach
    void setUp() {
        socialIdPort = Mockito.mock(ISocialIdPort.class);
        ruleVersionRepository = Mockito.mock(IRiskRuleVersionRepository.class);
        promptVersionRepository = Mockito.mock(IRiskPromptVersionRepository.class);
        caseRepository = Mockito.mock(IRiskCaseRepository.class);
        punishmentRepository = Mockito.mock(IRiskPunishmentRepository.class);
        decisionLogRepository = Mockito.mock(IRiskDecisionLogRepository.class);
        contentService = Mockito.mock(IContentService.class);
        interactionService = Mockito.mock(IInteractionService.class);
        redissonClient = Mockito.mock(RedissonClient.class);
        riskAdminService = new RiskAdminService(
                socialIdPort,
                ruleVersionRepository,
                promptVersionRepository,
                caseRepository,
                punishmentRepository,
                decisionLogRepository,
                contentService,
                interactionService,
                redissonClient,
                new ObjectMapper()
        );
    }

    @Test
    void publishRuleVersion_shouldPatchRulesJsonBeforePublish() {
        when(ruleVersionRepository.findByVersion(3L)).thenReturn(RiskRuleVersionEntity.builder()
                .version(3L)
                .status("DRAFT")
                .rulesJson("{\"version\":1,\"shadow\":false,\"rules\":[]}")
                .build());
        when(ruleVersionRepository.updateRulesJson(Mockito.eq(3L), anyString(), Mockito.eq("DRAFT")))
                .thenReturn(true);
        when(ruleVersionRepository.publish(3L, 9L)).thenReturn(true);

        OperationResultVO result = riskAdminService.publishRuleVersion(9L, 3L, true, null, null);

        assertEquals("PUBLISHED", result.getStatus());
        verify(ruleVersionRepository).updateRulesJson(Mockito.eq(3L), anyString(), Mockito.eq("DRAFT"));
    }

    @Test
    void applyPunishment_shouldReturnDuplicateWhenDecisionAlreadyHandled() {
        RBucket<Object> bucket = Mockito.mock(RBucket.class);
        when(socialIdPort.now()).thenReturn(1000L, 1000L);
        when(socialIdPort.nextId()).thenReturn(2000L);
        when(punishmentRepository.insertIgnore(Mockito.any())).thenReturn(false);
        when(punishmentRepository.findByDecisionAndType(7L, "POST_BAN")).thenReturn(RiskPunishmentEntity.builder().punishId(3000L).build());
        when(redissonClient.getBucket("risk:status:1")).thenReturn(bucket);

        OperationResultVO result = riskAdminService.applyPunishment(9L, 1L, "POST_BAN", 7L, "RISK", null, 2000L, null);

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals(3000L, result.getId());
        verify(bucket).delete();
    }

    @Test
    void decideCase_shouldApplyToCommentAndCreatePunishment() {
        when(caseRepository.findByCaseId(10L)).thenReturn(RiskCaseEntity.builder().caseId(10L).decisionId(99L).build());
        when(caseRepository.finish(10L, "BLOCK", "evidence", "ASSIGNED")).thenReturn(true);
        when(decisionLogRepository.findByDecisionId(99L)).thenReturn(RiskDecisionLogEntity.builder()
                .decisionId(99L)
                .userId(1L)
                .actionType("COMMENT_CREATE")
                .reasonCode("OLD")
                .extJson("{\"commentId\":123}")
                .signalsJson("[]")
                .actionsJson("[]")
                .build());
        when(socialIdPort.now()).thenReturn(1000L, 1000L);
        when(socialIdPort.nextId()).thenReturn(2000L);

        OperationResultVO result = riskAdminService.decideCase(9L, 10L, "BLOCK", "MANUAL", "evidence", null, "COMMENT_BAN", 60L);

        assertEquals("DONE", result.getStatus());
        verify(interactionService).applyCommentRiskReviewResult(123L, "BLOCK", "MANUAL");
        verify(punishmentRepository).insertIgnore(Mockito.any());
    }
}
