package cn.nexus.infrastructure.adapter.auth.repository;

import cn.nexus.domain.auth.adapter.port.IPasswordHasher;
import cn.nexus.domain.auth.adapter.repository.IAuthSmsCodeRepository;
import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao;
import cn.nexus.infrastructure.dao.auth.po.AuthSmsCodePO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 短信验证码仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class AuthSmsCodeRepository implements IAuthSmsCodeRepository {

    private static final int MAX_VERIFY_FAIL_COUNT = 5;
    private static final String SEND_STATUS_FAILED = "FAILED";

    private final IAuthSmsCodeDao authSmsCodeDao;
    private final IPasswordHasher passwordHasher;
    private final ISocialIdPort socialIdPort;

    @Override
    public void requireLatestValid(String phone, AuthSmsBizTypeVO bizType, String smsCode) {
        AuthSmsCodePO latest = authSmsCodeDao.selectLatestActive(phone, bizType.name());
        if (latest == null || latest.getId() == null) {
            throw invalidCode("验证码不存在");
        }
        long now = now();
        if (latest.getExpireAt() == null || latest.getExpireAt().getTime() < now) {
            authSmsCodeDao.markUsed(latest.getId(), new Date(now));
            throw invalidCode("验证码已过期");
        }
        if (!passwordHasher.matches(smsCode, latest.getCodeHash())) {
            authSmsCodeDao.incrementVerifyFail(latest.getId());
            int currentFailCount = latest.getVerifyFailCount() == null ? 1 : latest.getVerifyFailCount() + 1;
            if (currentFailCount >= MAX_VERIFY_FAIL_COUNT) {
                authSmsCodeDao.markUsed(latest.getId(), new Date(now));
            }
            throw invalidCode("验证码不正确");
        }
    }

    @Override
    public void invalidateLatest(String phone, AuthSmsBizTypeVO bizType) {
        authSmsCodeDao.invalidateLatest(phone, bizType.name());
    }

    @Override
    public void saveLatest(String phone, AuthSmsBizTypeVO bizType, String codeHash, Long expireAt, String requestIp, String sendStatus) {
        authSmsCodeDao.insert(buildCodePO(phone, bizType, codeHash, expireAt, requestIp, sendStatus, 1));
    }

    @Override
    public void saveFailedAttempt(String phone, AuthSmsBizTypeVO bizType, String codeHash, Long expireAt, String requestIp) {
        authSmsCodeDao.insert(buildCodePO(phone, bizType, codeHash, expireAt, requestIp, SEND_STATUS_FAILED, 0));
    }

    @Override
    public void markUsed(String phone, AuthSmsBizTypeVO bizType, String smsCode) {
        AuthSmsCodePO latest = authSmsCodeDao.selectLatestActive(phone, bizType.name());
        if (latest == null || latest.getId() == null) {
            return;
        }
        if (!passwordHasher.matches(smsCode, latest.getCodeHash())) {
            return;
        }
        authSmsCodeDao.markUsed(latest.getId(), new Date(now()));
    }

    private AuthSmsCodePO buildCodePO(String phone,
                                      AuthSmsBizTypeVO bizType,
                                      String codeHash,
                                      Long expireAt,
                                      String requestIp,
                                      String sendStatus,
                                      int latestFlag) {
        AuthSmsCodePO po = new AuthSmsCodePO();
        po.setId(socialIdPort.nextId());
        po.setBizType(bizType.name());
        po.setPhone(phone);
        po.setCodeHash(codeHash);
        po.setExpireAt(expireAt == null ? null : new Date(expireAt));
        po.setUsedAt(null);
        po.setVerifyFailCount(0);
        po.setSendStatus(sendStatus);
        po.setRequestIp(requestIp == null ? "" : requestIp);
        po.setLatestFlag(latestFlag);
        return po;
    }

    private AppException invalidCode(String info) {
        return new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), info);
    }

    private long now() {
        Long current = socialIdPort.now();
        return current != null ? current : System.currentTimeMillis();
    }
}
