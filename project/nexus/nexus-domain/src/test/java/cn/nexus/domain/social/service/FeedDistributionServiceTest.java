package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedDistributionServiceTest {

    private IRelationRepository relationRepository;
    private IFeedTimelineRepository feedTimelineRepository;
    private FeedDistributionService service;

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(IRelationRepository.class);
        feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        service = new FeedDistributionService(relationRepository, feedTimelineRepository);
        ReflectionTestUtils.setField(service, "batchSize", 200);
    }

    @Test
    void fanoutSlice_shouldFanoutOnlyToOnlineFollowers() {
        when(relationRepository.pageFollowerIdsForFanout(100L, 0, 3)).thenReturn(Arrays.asList(201L, 202L, 100L, null));
        when(feedTimelineRepository.filterOnlineUsers(List.of(201L, 202L))).thenReturn(Set.of(202L));

        service.fanoutSlice(10L, 100L, 1000L, 0, 3);

        verify(feedTimelineRepository).addToInbox(202L, 10L, 1000L);
        verify(feedTimelineRepository, never()).addToInbox(201L, 10L, 1000L);
        verify(feedTimelineRepository, never()).addToInbox(100L, 10L, 1000L);
    }

    @Test
    void service_shouldNotKeepOutboxOrPoolDependencies() {
        for (Field field : FeedDistributionService.class.getDeclaredFields()) {
            String typeName = field.getType().getSimpleName();
            if ("IFeedOutboxRepository".equals(typeName) || "IFeedBigVPoolRepository".equals(typeName)) {
                fail("FeedDistributionService must not depend on outbox or bigv pool repositories");
            }
        }
    }
}
