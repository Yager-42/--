package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.search.dto.*;

/**
 * 搜索发现接口定义。
 */
public interface ISearchApi {

    Response<SearchGeneralResponseDTO> search(SearchGeneralRequestDTO requestDTO);

    Response<SearchSuggestResponseDTO> suggest(SearchSuggestRequestDTO requestDTO);

    Response<SearchTrendingResponseDTO> trending(SearchTrendingRequestDTO requestDTO);

    Response<OperationResultDTO> clearHistory(SearchHistoryDeleteRequestDTO requestDTO);
}
