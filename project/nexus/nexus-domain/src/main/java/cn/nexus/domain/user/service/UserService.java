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
 * 用户域服务：Profile/Settings/Status 的最小写入实现。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEACTIVATED = "DEACTIVATED";

    private final IUserProfileRepository userProfileRepository;
    private final IUserPrivacyRepository userPrivacyRepository;
    private final IUserStatusRepository userStatusRepository;
    private final IUserEventOutboxPort userEventOutboxPort;

    /**
     * 用户自助更新 Profile（只允许更新 nickname/avatarUrl）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO updateMyProfile(Long userId, UserProfilePatchVO patch) {
        if (userId == null || patch == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId/patch 不能为空");
        }
        ensureActiveForUserWrite(userId);

        UserProfileVO before = requireProfile(userId);
        validateNicknamePatch(patch.getNickname());

        boolean needUpdate = patch.getNickname() != null || patch.getAvatarUrl() != null;
        boolean nicknameChanged = patch.getNickname() != null && !eq(patch.getNickname(), before.getNickname());
        Long tsMs = nicknameChanged ? System.currentTimeMillis() : null;

        if (needUpdate) {
            boolean ok = userProfileRepository.updatePatch(userId, patch.getNickname(), patch.getAvatarUrl());
            if (!ok) {
                throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
            }
        }

        if (nicknameChanged) {
            userEventOutboxPort.saveNicknameChanged(userId, tsMs);
            afterCommit(userEventOutboxPort::tryPublishPending);
        }

        return OperationResultVO.builder().success(true).status("OK").message("success").build();
    }

    /**
     * 用户自助更新隐私设置（当前仅 needApproval）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OperationResultVO updateMyPrivacy(Long userId, Boolean needApproval) {
        if (userId == null || needApproval == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId/needApproval 不能为空");
        }
        ensureActiveForUserWrite(userId);
        // 与 Profile 一致：user_base 不存在视为用户不存在，禁止在这里打补丁自动创建
        requireProfile(userId);

        userPrivacyRepository.upsertNeedApproval(userId, needApproval);
        return OperationResultVO.builder().success(true).status("OK").message("success").build();
    }

    /**
     * 网关同步更新（update-only）：不负责创建用户；不拦 DEACTIVATED（internal 例外允许写）。
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

