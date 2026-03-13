package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Repository;

import java.util.concurrent.TimeUnit;

/**
 * 用户基础信息仓储 MyBatis 实现。
 *
 * @author rr
 * @author codex
 * @since 2026-01-21
 */
@Repository
public class UserBaseRepository implements IUserBaseRepository {

    /**
     * 用户基础信息 DAO。
     */
    private final IUserBaseDao userBaseDao;

    /**
     * Redis 字符串值操作。
     */
    private final ValueOperations<String, String> valueOps;

    /**
     * JSON 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 用户基础信息缓存 Key 前缀。
     */
    private static final String KEY_USER_BASE = "social:userbase:";

    /**
     * 用户名到用户 ID 的映射缓存 Key 前缀。
     */
    private static final String KEY_USER_ID_BY_USERNAME = "social:userbase:uid:";

    /**
     * 缓存过期时间，单位：秒。
     */
    private static final long TTL_SECONDS = 3600;

    /**
     * 创建用户基础信息仓储。
     *
     * @param userBaseDao 用户基础表 DAO，类型：{@link IUserBaseDao}
     * @param redisTemplate Redis 模板，类型：{@link StringRedisTemplate}
     * @param objectMapper JSON 序列化工具，类型：{@link ObjectMapper}
     */
    @Autowired
    public UserBaseRepository(IUserBaseDao userBaseDao, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(userBaseDao, redisTemplate.opsForValue(), objectMapper);
    }

    UserBaseRepository(IUserBaseDao userBaseDao, ValueOperations<String, String> valueOps, ObjectMapper objectMapper) {
        this.userBaseDao = userBaseDao;
        this.valueOps = valueOps;
        this.objectMapper = objectMapper;
    }

    private static UserBriefVO toVo(UserBasePO po) {
        if (po == null || po.getUserId() == null) {
            return null;
        }
        String nickname = po.getNickname();
        // 迁移期兼容：仅允许在仓储这一处 fallback，避免补丁扩散到调用方。
        if (nickname == null || nickname.isBlank()) {
            nickname = po.getUsername();
        }
        return UserBriefVO.builder()
                .userId(po.getUserId())
                .nickname(nickname)
                .avatarUrl(po.getAvatarUrl())
                .build();
    }

    private String userBaseKey(Long userId) {
        return KEY_USER_BASE + userId;
    }

    private String userIdByUsernameKey(String username) {
        return KEY_USER_ID_BY_USERNAME + username;
    }

    private void cacheUserBrief(UserBriefVO vo) {
        if (vo == null || vo.getUserId() == null) {
            return;
        }
        try {
            UserBriefCacheValue cacheValue = new UserBriefCacheValue();
            cacheValue.setUserId(vo.getUserId());
            cacheValue.setNickname(vo.getNickname());
            cacheValue.setAvatarUrl(vo.getAvatarUrl());
            String json = objectMapper.writeValueAsString(cacheValue);
            valueOps.set(userBaseKey(vo.getUserId()), json, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 缓存失败视为 miss，不影响主流程。
        }
    }

    private UserBriefVO parseUserBrief(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            UserBriefCacheValue cacheValue = objectMapper.readValue(json, UserBriefCacheValue.class);
            if (cacheValue == null || cacheValue.getUserId() == null) {
                return null;
            }
            return UserBriefVO.builder()
                    .userId(cacheValue.getUserId())
                    .nickname(cacheValue.getNickname())
                    .avatarUrl(cacheValue.getAvatarUrl())
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 批量按用户 ID 查询基础信息。
     *
     * @param userIds 用户 ID 列表，类型：{@link List}&lt;{@link Long}&gt;
     * @return 用户基础信息列表，类型：{@link List}&lt;{@link UserBriefVO}&gt;
     */
    @Override
    public List<UserBriefVO> listByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> uniq = new LinkedHashSet<>();
        for (Long id : userIds) {
            if (id != null) {
                uniq.add(id);
            }
        }
        if (uniq.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(uniq);
        List<String> keys = new ArrayList<>(ids.size());
        for (Long id : ids) {
            keys.add(userBaseKey(id));
        }

        // 先批量读缓存，保持接口天然是批量语义，避免上层写成 N + 1。
        Map<Long, UserBriefVO> hit = new HashMap<>();
        List<Long> missIds = new ArrayList<>();
        try {
            List<String> cached = valueOps.multiGet(keys);
            if (cached != null && cached.size() == keys.size()) {
                for (int i = 0; i < ids.size(); i++) {
                    Long id = ids.get(i);
                    UserBriefVO vo = parseUserBrief(cached.get(i));
                    if (vo != null) {
                        hit.put(id, vo);
                    } else {
                        missIds.add(id);
                    }
                }
            } else {
                missIds.addAll(ids);
            }
        } catch (Exception ignored) {
            // Redis 异常视为 miss，仍回源 DB。
            missIds.addAll(ids);
        }

        if (!missIds.isEmpty()) {
            // 只回源 miss 的用户，命中缓存的数据不重复查库。
            List<UserBasePO> list = userBaseDao.selectByUserIds(missIds);
            if (list != null && !list.isEmpty()) {
                for (UserBasePO po : list) {
                    UserBriefVO vo = toVo(po);
                    if (vo != null) {
                        hit.put(vo.getUserId(), vo);
                        cacheUserBrief(vo);
                    }
                }
            }
        }

        List<UserBriefVO> res = new ArrayList<>(ids.size());
        for (Long id : ids) {
            UserBriefVO vo = hit.get(id);
            if (vo != null) {
                res.add(vo);
            }
        }
        return res;
    }

    /**
     * 批量按用户名查询基础信息。
     *
     * @param usernames 用户名列表，类型：{@link List}&lt;{@link String}&gt;
     * @return 用户基础信息列表，类型：{@link List}&lt;{@link UserBriefVO}&gt;
     */
    @Override
    public List<UserBriefVO> listByUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String u : usernames) {
            if (u != null && !u.isBlank()) {
                uniq.add(u);
            }
        }
        if (uniq.isEmpty()) {
            return List.of();
        }
        List<String> nameList = new ArrayList<>(uniq);
        List<String> keys = new ArrayList<>(nameList.size());
        for (String name : nameList) {
            keys.add(userIdByUsernameKey(name));
        }

        // 第一段只做 `username -> userId` 解析，第二段再复用 `listByUserIds` 补完整名片。
        Map<String, Long> nameToUserId = new HashMap<>();
        List<String> missNames = new ArrayList<>();
        try {
            List<String> cached = valueOps.multiGet(keys);
            if (cached != null && cached.size() == keys.size()) {
                for (int i = 0; i < nameList.size(); i++) {
                    String name = nameList.get(i);
                    String raw = cached.get(i);
                    if (raw == null || raw.isBlank()) {
                        missNames.add(name);
                        continue;
                    }
                    try {
                        nameToUserId.put(name, Long.parseLong(raw));
                    } catch (NumberFormatException ignored) {
                        missNames.add(name);
                    }
                }
            } else {
                missNames.addAll(nameList);
            }
        } catch (Exception ignored) {
            missNames.addAll(nameList);
        }

        Map<String, UserBriefVO> nameToVo = new HashMap<>();
        if (!nameToUserId.isEmpty()) {
            List<Long> ids = new ArrayList<>(new LinkedHashSet<>(nameToUserId.values()));
            List<UserBriefVO> briefs = listByUserIds(ids);
            Map<Long, UserBriefVO> idToVo = new HashMap<>();
            for (UserBriefVO vo : briefs) {
                if (vo != null && vo.getUserId() != null) {
                    idToVo.put(vo.getUserId(), vo);
                }
            }
            for (Map.Entry<String, Long> e : nameToUserId.entrySet()) {
                UserBriefVO vo = idToVo.get(e.getValue());
                if (vo != null) {
                    nameToVo.put(e.getKey(), vo);
                }
            }
        }

        if (!missNames.isEmpty()) {
            // 缓存没命中的用户名统一回表，顺手把 `username -> userId` 和名片缓存一起补齐。
            List<UserBasePO> list = userBaseDao.selectByUsernames(missNames);
            if (list != null && !list.isEmpty()) {
                for (UserBasePO po : list) {
                    UserBriefVO vo = toVo(po);
                    if (vo == null) {
                        continue;
                    }
                    String username = po.getUsername();
                    if (username != null && !username.isBlank()) {
                        nameToVo.put(username, vo);
                        try {
                            valueOps.set(userIdByUsernameKey(username), String.valueOf(vo.getUserId()), TTL_SECONDS, TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                        }
                    }
                    cacheUserBrief(vo);
                }
            }
        }

        List<UserBriefVO> res = new ArrayList<>(nameList.size());
        for (String name : nameList) {
            UserBriefVO vo = nameToVo.get(name);
            if (vo != null) {
                res.add(vo);
            }
        }
        return res;
    }

    /**
     * Redis 缓存值（只存 user_base 读侧最小字段）。
     */
    @lombok.Data
    private static class UserBriefCacheValue {
        private Long userId;
        private String nickname;
        private String avatarUrl;
    }
}
