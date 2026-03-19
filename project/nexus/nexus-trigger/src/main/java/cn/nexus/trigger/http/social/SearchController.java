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

/**
 * SearchController 实现。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/search")
public class SearchController implements ISearchApi {

    @Resource
    private ISearchService searchService;

    /**
     * 执行搜索。
     *
     * @param q 搜索关键词。类型：{@link String}
     * @param size 单页大小。类型：{@link Integer}
     * @param tags 标签过滤串。类型：{@link String}
     * @param after 分页游标。类型：{@link String}
     * @return 处理结果。类型：{@link Response}
     */
    @GetMapping
    @Override
    public Response<SearchResponseDTO> search(@RequestParam("q") String q,
                                              @RequestParam(value = "size", required = false) Integer size,
                                              @RequestParam(value = "tags", required = false) String tags,
                                              @RequestParam(value = "after", required = false) String after) {
        try {
            // 搜索接口允许匿名访问，所以这里只拿“可能存在”的用户 ID，用它补齐点赞态，不强制登录。
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

    /**
     * 查询联想词。
     *
     * @param prefix 联想前缀。类型：{@link String}
     * @param size 返回数量。类型：{@link Integer}
     * @return 处理结果。类型：{@link Response}
     */
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
