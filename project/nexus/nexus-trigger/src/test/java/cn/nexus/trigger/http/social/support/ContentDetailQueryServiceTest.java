package cn.nexus.trigger.http.social.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.api.social.content.dto.ContentDetailResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ContentDetailQueryServiceTest {

    @Test
    void query_shouldReuseFindPostAndCacheLocalSnapshot() {
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        Executor aggregationExecutor = Runnable::run;
        ContentDetailQueryService service = new ContentDetailQueryService(
                contentRepository,
                userBaseRepository,
                objectCounterService,
                aggregationExecutor
        );

        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .title("t")
                .contentText("body")
                .summary("s")
                .status(2)
                .build());
        when(userBaseRepository.listByUserIds(List.of(11L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(11L).nickname("u").avatarUrl("a").build()));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(101L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(java.util.Map.of("like", 9L));

        ContentDetailResponseDTO first = service.query(101L);
        ContentDetailResponseDTO second = service.query(101L);

        assertEquals("body", first.getContent());
        assertEquals(9L, first.getLikeCount());
        assertEquals("body", second.getContent());
        verify(contentRepository, times(1)).findPost(101L);
    }

    @Test
    void query_shouldNotFailWhenAuthorLoadFailed() {
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        Executor aggregationExecutor = Runnable::run;
        ContentDetailQueryService service = new ContentDetailQueryService(
                contentRepository,
                userBaseRepository,
                objectCounterService,
                aggregationExecutor
        );

        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(11L)
                .title("t")
                .contentText("body")
                .summary("s")
                .status(2)
                .build());
        when(userBaseRepository.listByUserIds(List.of(11L))).thenThrow(new RuntimeException("boom"));
        when(objectCounterService.getCounts(eq(ReactionTargetTypeEnumVO.POST), eq(101L), eq(List.of(ObjectCounterType.LIKE))))
                .thenReturn(java.util.Map.of("like", 9L));

        ContentDetailResponseDTO response = service.query(101L);

        assertEquals("body", response.getContent());
        assertEquals(9L, response.getLikeCount());
        assertEquals("", response.getAuthorNickname());
    }
}
