package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.feed.dto.*;

/**
 * Feed/分发接口定义。
 */
public interface IFeedApi {

    Response<FeedTimelineResponseDTO> timeline(FeedTimelineRequestDTO requestDTO);

    Response<FeedTimelineResponseDTO> profile(Long targetId, ProfileFeedRequestDTO requestDTO);
}
