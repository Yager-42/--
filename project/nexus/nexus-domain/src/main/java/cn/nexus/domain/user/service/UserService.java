package cn.nexus.domain.user.service;

import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import cn.nexus.domain.user.adapter.repository.IUserPrivacyRepository;
import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.domain.user.model.valobj.UserInternalUpsertRequestVO;
import cn.nexus.domain.user.model.valobj.UserProfilePatchVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户域服务：收口 `Profile / Settings / Status` 这几条最小写链路。
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /**
     * 正常可写状态。
     */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 已停用状态。
     */
    public static final String STATUS_DEACTIVATED = "DEACTIVATED";

    private final IUserProfileRepository userProfileRepository;
    private final IUserPrivacyRepository userPrivacyRepository;
    private final IUserStatusRepository userStatusRepository;
    private final IUserEventOutboxPort userEventOutboxPort;

    /**
     * 用户自助更新 `Profile`。
     *
     * <p>只允许更新 `nickname / avatarUrl`；`null` 表示不改，昵称空白字符串一律非法。</p>
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param patch Profile Patch 参数，类型：{@link UserProfilePatchVO}
     * @return 操作结果，类型：{@link OperationResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO updateMyProfile(Long userId, UserProfilePatchVO patch) {
        if (userId == null || patch == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId/patch 不能为空");
        }
        ensureActiveForUserWrite(userId);

        // 先拿当前真值，再决定这次请求到底是“值没变”还是“真的要更新”
        UserProfileVO before = requireProfile(userId);
        validateNicknamePatch(patch.getNickname());

        // 先把“是否要写库”和“是否要发昵称变更事件”拆开算清楚，避免混在一起写出补丁逻辑
        boolean needUpdate = patch.getNickname() != null || patch.getAvatarUrl() != null;
        boolean nicknameChanged = patch.getNickname() != null && !eq(patch.getNickname(), before.getNickname());
        Long tsMs = nicknameChanged ? System.currentTimeMillis() : null;

        if (needUpdate) {
            // 仓储层会处理“值相同导致 affectedRows = 0”的情况，这里只认用户是否真实存在
            boolean ok = userProfileRepository.updatePatch(userId, patch.getNickname(), patch.getAvatarUrl());
            if (!ok) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
        }

        if (nicknameChanged) {
            // 先落 outbox，再等事务提交后尝试投递，避免消息比数据库更早被消费者看到
            userEventOutboxPort.saveNicknameChanged(userId, tsMs);
            afterCommit(userEventOutboxPort::tryPublishPending);
        }

        return OperationResultVO.builder().success(true).status("OK").message("success").build();
    }

    /**
     * 用户自助更新隐私设置。
     *
     * <p>当前只支持 `needApproval`；规则和 `Profile` 一样，用户不存在就直接报错，不在这里偷偷补数据。</p>
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param needApproval 是否需要关注审批，类型：{@link Boolean}
     * @return 操作结果，类型：{@link OperationResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO updateMyPrivacy(Long userId, Boolean needApproval) {
        if (userId == null || needApproval == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId/needApproval 不能为空");
        }
        ensureActiveForUserWrite(userId);
        // 与 Profile 写链路保持同一条边界：user_base 不存在就报错，不做自动创建
        requireProfile(userId);

        userPrivacyRepository.upsertNeedApproval(userId, needApproval);
        return OperationResultVO.builder().success(true).status("OK").message("success").build();
    }

    /**
     * 网关内部同步更新。
     *
     * <p>这是一个 `update-only` 入口：不负责创建用户；同时内部同步允许改停用用户，所以这里不走普通写链路的停用拦截。</p>
     *
     * @param req 内部同步请求，类型：{@link UserInternalUpsertRequestVO}
     * @return 操作结果，类型：{@link OperationResultVO}
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO internalUpsert(UserInternalUpsertRequestVO req) {
        if (req == null || req.getUserId() == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId 不能为空");
        }
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "username 不能为空");
        }
        validateNicknamePatch(req.getNickname());
        validateStatusPatch(req.getStatus());

        // 先确认目标用户真的存在，再做 username 一致性校验，避免把错误请求写成脏数据
        Long userId = req.getUserId();
        UserProfileVO before = userProfileRepository.get(userId);
        if (before == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        if (!eq(req.getUsername(), before.getUsername())) {
            throw new AppException(ResponseCode.CONFLICT.getCode(), ResponseCode.CONFLICT.getInfo());
        }

        boolean needUpdateProfile = req.getNickname() != null || req.getAvatarUrl() != null;
        boolean nicknameChanged = req.getNickname() != null && !eq(req.getNickname(), before.getNickname());
        Long tsMs = nicknameChanged ? System.currentTimeMillis() : null;

        if (needUpdateProfile) {
            // Profile 真值和隐私/状态拆开写，避免把所有字段硬塞进一个大 Patch
            boolean ok = userProfileRepository.updatePatch(userId, req.getNickname(), req.getAvatarUrl());
            if (!ok) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
        }
        if (req.getNeedApproval() != null) {
            userPrivacyRepository.upsertNeedApproval(userId, req.getNeedApproval());
        }
        if (req.getStatus() != null) {
            Long deactivatedTimeMs = STATUS_DEACTIVATED.equals(req.getStatus()) ? System.currentTimeMillis() : null;
            userStatusRepository.upsertStatus(userId, req.getStatus(), deactivatedTimeMs);
        }

        if (nicknameChanged) {
            // 内部同步改昵称也要走同一套 outbox 链路，保证搜索和读侧最终一致
            userEventOutboxPort.saveNicknameChanged(userId, tsMs);
            afterCommit(userEventOutboxPort::tryPublishPending);
        }
        return OperationResultVO.builder().success(true).status("OK").message("success").build();
    }

    private void ensureActiveForUserWrite(Long userId) {
        String status = userStatusRepository.getStatus(userId);
        if (STATUS_DEACTIVATED.equals(status)) {
            throw new AppException(ResponseCode.USER_DEACTIVATED.getCode(), ResponseCode.USER_DEACTIVATED.getInfo());
        }
    }

    private UserProfileVO requireProfile(Long userId) {
        UserProfileVO profile = userProfileRepository.get(userId);
        if (profile == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        return profile;
    }

    private void validateNicknamePatch(String nickname) {
        if (nickname != null && nickname.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "nickname 不能为空");
        }
    }

    private void validateStatusPatch(String status) {
        if (status == null) {
            return;
        }
        if (status.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "status 不能为空");
        }
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DEACTIVATED.equals(status)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "status 仅支持 ACTIVE/DEACTIVATED");
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("transaction synchronization is not active");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
