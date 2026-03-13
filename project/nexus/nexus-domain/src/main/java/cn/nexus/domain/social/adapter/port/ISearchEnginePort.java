package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import java.util.List;

public interface ISearchEnginePort {

    SearchEngineResultVO search(SearchEngineQueryVO query);

    List<String> suggest(String prefix, int limit);

    void upsert(SearchDocumentVO doc);

    void softDelete(Long contentId);

    long updateAuthorNickname(Long authorId, String authorNickname);
}
