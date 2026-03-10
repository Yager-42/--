package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface IFeedCardStatRepository {

    Map<Long, FeedCardStatVO> getBatch(List<Long> postIds);

    Map<Long, FeedCardStatVO> getOrLoadBatch(List<Long> postIds,
                                             Function<List<Long>, Map<Long, FeedCardStatVO>> loader);

    void saveBatch(List<FeedCardStatVO> stats);
}
