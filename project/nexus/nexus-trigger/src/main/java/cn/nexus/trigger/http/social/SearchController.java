package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.ISearchApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.search.dto.*;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.domain.social.model.valobj.SearchTrendingVO;
import cn.nexus.domain.social.service.ISearchService;
import cn.nexus.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * 搜索接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/search")
public class SearchController implements ISearchApi {

    @Resource
    private ISearchService searchService;

    @GetMapping("/general")
    @Override
    public Response<SearchGeneralResponseDTO> search(SearchGeneralRequestDTO requestDTO) {
        SearchResultVO vo = searchService.search(requestDTO.getKeyword(), requestDTO.getType(), requestDTO.getSort(), requestDTO.getFilters());
        SearchGeneralResponseDTO dto = SearchGeneralResponseDTO.builder()
                .items(vo.getItems().stream().map(item -> SearchGeneralResponseDTO.SearchItemDTO.builder()
                        .id(item.getId())
                        .type(item.getType())
                        .title(item.getTitle())
                        .summary(item.getSummary())
                        .build()).collect(Collectors.toList()))
                .facets(vo.getFacets())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }

    @GetMapping("/suggest")
    @Override
    public Response<SearchSuggestResponseDTO> suggest(SearchSuggestRequestDTO requestDTO) {
        SearchSuggestVO vo = searchService.suggest(requestDTO.getKeyword());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                SearchSuggestResponseDTO.builder().suggestions(vo.getSuggestions()).build());
    }

    @GetMapping("/trending")
    @Override
    public Response<SearchTrendingResponseDTO> trending(SearchTrendingRequestDTO requestDTO) {
        SearchTrendingVO vo = searchService.trending(requestDTO.getCategory());
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                SearchTrendingResponseDTO.builder().keywords(vo.getKeywords()).build());
    }

    @DeleteMapping("/history")
    @Override
    public Response<OperationResultDTO> clearHistory(@RequestBody SearchHistoryDeleteRequestDTO requestDTO) {
        OperationResultVO vo = searchService.clearHistory(requestDTO.getUserId());
        OperationResultDTO dto = OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
