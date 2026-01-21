package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户基础信息仓储 MyBatis 实现。
 *
 * @author codex
 * @since 2026-01-20
 */
@Repository
@RequiredArgsConstructor
public class UserBaseRepository implements IUserBaseRepository {

    private final IUserBaseDao userBaseDao;

    @Override
    public List<UserBriefVO> listByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UserBasePO> list = userBaseDao.selectByUserIds(userIds);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<UserBriefVO> res = new ArrayList<>(list.size());
        for (UserBasePO po : list) {
            if (po == null || po.getUserId() == null) {
                continue;
            }
            res.add(UserBriefVO.builder()
                    .userId(po.getUserId())
                    .nickname(po.getUsername())
                    .avatarUrl(po.getAvatarUrl())
                    .build());
        }
        return res;
    }

    @Override
    public List<UserBriefVO> listByUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        List<UserBasePO> list = userBaseDao.selectByUsernames(usernames);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<UserBriefVO> res = new ArrayList<>(list.size());
        for (UserBasePO po : list) {
            if (po == null || po.getUserId() == null) {
                continue;
            }
            res.add(UserBriefVO.builder()
                    .userId(po.getUserId())
                    .nickname(po.getUsername())
                    .avatarUrl(po.getAvatarUrl())
                    .build());
        }
        return res;
    }
}
