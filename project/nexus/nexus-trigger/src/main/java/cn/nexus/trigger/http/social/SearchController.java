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
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
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
        try {
            Long userId = UserContext.getUserId();
            SearchResultVO vo = searchService.search(userId, requestDTO.getKeyword(), requestDTO.getType(), requestDTO.getSort(), requestDTO.getFilters());
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
        } catch (AppException e) {
            return Response.<SearchGeneralResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search general api failed, req={}", requestDTO, e);
            return Response.<SearchGeneralResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/suggest")
    @Override
    public Response<SearchSuggestResponseDTO> suggest(SearchSuggestRequestDTO requestDTO) {
        try {
            Long userId = UserContext.getUserId();
            SearchSuggestVO vo = searchService.suggest(userId, requestDTO.getKeyword());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    SearchSuggestResponseDTO.builder().suggestions(vo.getSuggestions()).build());
        } catch (AppException e) {
            return Response.<SearchSuggestResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search suggest api failed, req={}", requestDTO, e);
            return Response.<SearchSuggestResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/trending")
    @Override
    public Response<SearchTrendingResponseDTO> trending(SearchTrendingRequestDTO requestDTO) {
        try {
            SearchTrendingVO vo = searchService.trending(requestDTO.getCategory());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    SearchTrendingResponseDTO.builder().keywords(vo.getKeywords()).build());
        } catch (AppException e) {
            return Response.<SearchTrendingResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search trending api failed, req={}", requestDTO, e);
            return Response.<SearchTrendingResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/history")
    @Override
    public Response<OperationResultDTO> clearHistory(@RequestBody SearchHistoryDeleteRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = searchService.clearHistory(userId);
            OperationResultDTO dto = OperationResultDTO.builder()
                    .success(vo.isSuccess())
                    .id(vo.getId())
                    .status(vo.getStatus())
                    .message(vo.getMessage())
                    .build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search clear history api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
