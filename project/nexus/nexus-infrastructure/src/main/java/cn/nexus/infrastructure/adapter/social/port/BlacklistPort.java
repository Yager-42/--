package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IBlacklistPort;
import cn.nexus.infrastructure.dao.social.IRelationDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 黑名单 MyBatis 实现：落库查询，而非内存占位。
 */
@Component
@RequiredArgsConstructor
public class BlacklistPort implements IBlacklistPort {

    private static final int RELATION_BLOCK = 3;

    private final IRelationDao relationDao;

    @Override
    public boolean isBlocked(Long sourceId, Long targetId) {
        if (sourceId == null || targetId == null) {
            return true;
        }
        // 目标是否屏蔽来源：使用关系表中 relation_type=3 的记录进行判断
        return relationDao.selectOne(targetId, sourceId, RELATION_BLOCK) != null;
    }
}
