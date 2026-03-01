package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentPublishAttemptPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentPublishAttemptDao {

    int insert(ContentPublishAttemptPO po);

    ContentPublishAttemptPO selectById(@Param("attemptId") Long attemptId);

    ContentPublishAttemptPO selectByToken(@Param("idempotentToken") String idempotentToken);

    ContentPublishAttemptPO selectLatestActiveAttempt(@Param("postId") Long postId,
                                                      @Param("userId") Long userId,
                                                      @Param("statusCreated") Integer statusCreated,
                                                      @Param("statusTranscoding") Integer statusTranscoding,
                                                      @Param("statusPendingReview") Integer statusPendingReview);

    int updateStatus(@Param("attemptId") Long attemptId,
                     @Param("attemptStatus") Integer attemptStatus,
                     @Param("riskStatus") Integer riskStatus,
                     @Param("transcodeStatus") Integer transcodeStatus,
                     @Param("transcodeJobId") String transcodeJobId,
                     @Param("publishedVersionNum") Integer publishedVersionNum,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("expectedAttemptStatus") Integer expectedAttemptStatus);
}

