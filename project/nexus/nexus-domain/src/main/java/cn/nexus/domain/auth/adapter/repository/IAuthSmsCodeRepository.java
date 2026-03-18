package cn.nexus.domain.auth.adapter.repository;

import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;

/**
 * 短信验证码仓储。
 */
public interface IAuthSmsCodeRepository {

    /**
     * 校验最新一条验证码有效。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     * @param smsCode 验证码明文
     */
    void requireLatestValid(String phone, AuthSmsBizTypeVO bizType, String smsCode);

    /**
     * 使旧的 latest 验证码失效。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     */
    void invalidateLatest(String phone, AuthSmsBizTypeVO bizType);

    /**
     * 保存最新验证码。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     * @param codeHash 验证码哈希
     * @param expireAt 过期时间
     * @param requestIp 请求 IP
     * @param sendStatus 发送状态
     */
    void saveLatest(String phone, AuthSmsBizTypeVO bizType, String codeHash, Long expireAt, String requestIp, String sendStatus);

    /**
     * 记录发送失败尝试。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     * @param codeHash 验证码哈希
     * @param expireAt 过期时间
     * @param requestIp 请求 IP
     */
    void saveFailedAttempt(String phone, AuthSmsBizTypeVO bizType, String codeHash, Long expireAt, String requestIp);

    /**
     * 标记验证码已使用。
     *
     * @param phone 手机号
     * @param bizType 业务类型
     * @param smsCode 验证码明文
     */
    void markUsed(String phone, AuthSmsBizTypeVO bizType, String smsCode);
}
