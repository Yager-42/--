package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface IFeedCardRepository {

    Map<Long, FeedCardBaseVO> getBatch(List<Long> postIds);

    Map<Long, FeedCardBaseVO> getOrLoadBatch(List<Long> postIds,
                                             Function<List<Long>, Map<Long, FeedCardBaseVO>> loader);

    void saveBatch(List<FeedCardBaseVO> cards);
}
