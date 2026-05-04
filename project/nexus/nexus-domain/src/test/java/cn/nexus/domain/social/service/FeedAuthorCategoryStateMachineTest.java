package cn.nexus.domain.social.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedAuthorCategoryStateMachineTest {

    private IRelationRepository relationRepository;
    private IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private FeedAuthorCategoryStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(IRelationRepository.class);
        feedAuthorCategoryRepository = Mockito.mock(IFeedAuthorCategoryRepository.class);
        stateMachine = new FeedAuthorCategoryStateMachine(relationRepository, feedAuthorCategoryRepository);
        ReflectionTestUtils.setField(stateMachine, "bigvFollowerThreshold", 500000);
    }

    @Test
    void onFollowerCountChanged_shouldUpdateCategoryWithoutRebuildCoupling() {
        when(relationRepository.countFollowerIds(100L)).thenReturn(500000);
        when(feedAuthorCategoryRepository.getCategory(100L)).thenReturn(FeedAuthorCategoryEnumVO.NORMAL.getCode());

        stateMachine.onFollowerCountChanged(100L);

        verify(feedAuthorCategoryRepository).setCategory(100L, FeedAuthorCategoryEnumVO.BIGV.getCode());
    }

    @Test
    void onFollowerCountChanged_shouldKeepThresholdAndNoopWhenCategoryUnchanged() {
        when(relationRepository.countFollowerIds(100L)).thenReturn(499999);
        when(feedAuthorCategoryRepository.getCategory(100L)).thenReturn(FeedAuthorCategoryEnumVO.NORMAL.getCode());

        stateMachine.onFollowerCountChanged(100L);

        verify(feedAuthorCategoryRepository, never()).setCategory(Mockito.anyLong(), Mockito.anyInt());
    }
}
