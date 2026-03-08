package cn.nexus.api.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.search.dto.SearchResponseDTO;
import cn.nexus.api.social.search.dto.SuggestResponseDTO;

public interface ISearchApi {

    Response<SearchResponseDTO> search(String q, Integer size, String tags, String after);

    Response<SuggestResponseDTO> suggest(String prefix, Integer size);
}
