package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import java.util.List;
import java.util.Map;

public interface IFeedCardRepository {

    Map<Long, FeedCardBaseVO> getBatch(List<Long> postIds);

    void saveBatch(List<FeedCardBaseVO> cards);
}
