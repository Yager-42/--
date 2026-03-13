package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.ISearchApi;
import cn.nexus.api.social.search.dto.SearchItemDTO;
import cn.nexus.api.social.search.dto.SearchResponseDTO;
import cn.nexus.api.social.search.dto.SuggestResponseDTO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.domain.social.service.ISearchService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/search")
public class SearchController implements ISearchApi {

    @Resource
    private ISearchService searchService;

    @GetMapping
    @Override
    public Response<SearchResponseDTO> search(@RequestParam("q") String q,
                                              @RequestParam(value = "size", required = false) Integer size,
                                              @RequestParam(value = "tags", required = false) String tags,
                                              @RequestParam(value = "after", required = false) String after) {
        try {
            Long userId = UserContext.getUserId();
            SearchResultVO vo = searchService.search(userId, q, size, tags, after);
            List<SearchItemDTO> items = vo.getItems() == null ? List.of() : vo.getItems().stream()
                    .map(item -> SearchItemDTO.builder()
                            .id(item.getId())
                            .title(item.getTitle())
                            .description(item.getDescription())
                            .coverImage(item.getCoverImage())
                            .tags(item.getTags())
                            .authorAvatar(item.getAuthorAvatar())
                            .authorNickname(item.getAuthorNickname())
                            .tagJson(item.getTagJson())
                            .likeCount(item.getLikeCount())
                            .favoriteCount(item.getFavoriteCount())
                            .liked(item.getLiked())
                            .faved(item.getFaved())
                            .isTop(item.getIsTop())
                            .build())
                    .toList();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    SearchResponseDTO.builder()
                            .items(items)
                            .nextAfter(vo.getNextAfter())
                            .hasMore(vo.isHasMore())
                            .build());
        } catch (AppException e) {
            return Response.<SearchResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search api failed, q={}, size={}, tags={}, after={}", q, size, tags, after, e);
            return Response.<SearchResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/suggest")
    @Override
    public Response<SuggestResponseDTO> suggest(@RequestParam("prefix") String prefix,
                                                @RequestParam(value = "size", required = false) Integer size) {
        try {
            SearchSuggestVO vo = searchService.suggest(prefix, size);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    SuggestResponseDTO.builder()
                            .items(vo.getItems() == null ? List.of() : vo.getItems())
                            .build());
        } catch (AppException e) {
            return Response.<SuggestResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("search suggest api failed, prefix={}, size={}", prefix, size, e);
            return Response.<SuggestResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
