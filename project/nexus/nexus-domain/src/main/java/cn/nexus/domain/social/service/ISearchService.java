package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;

public interface ISearchService {

    SearchResultVO search(Long userId, String keyword, Integer size, String tags, String after);

    SearchSuggestVO suggest(String prefix, Integer size);
}
