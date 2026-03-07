package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import java.util.List;
import java.util.Map;

public interface IFeedCardStatRepository {

    Map<Long, FeedCardStatVO> getBatch(List<Long> postIds);

    void saveBatch(List<FeedCardStatVO> stats);
}
