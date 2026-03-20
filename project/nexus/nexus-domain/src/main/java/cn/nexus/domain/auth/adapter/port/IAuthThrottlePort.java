package cn.nexus.domain.auth.adapter.port;

/**
 * 认证防刷端口。
 */
public interface IAuthThrottlePort {

    /**
     * 校验短信发送频率。
     *
     * @param phone 手机号
     * @param ip 请求 IP
     */
    void checkSmsSendLimit(String phone, String ip);

    /**
     * 记录短信发送成功。
     *
     * @param phone 手机号
     * @param ip 请求 IP
     */
    void onSmsSend(String phone, String ip);

    /**
     * 校验登录是否已被锁定。
     *
     * @param loginType 登录类型，例如 password / sms
     * @param phone 手机号
     */
    void checkLoginLock(String loginType, String phone);

    /**
     * 登录成功后清理失败计数和锁。
     *
     * @param loginType 登录类型
     * @param phone 手机号
     */
    void onLoginSuccess(String loginType, String phone);

    /**
     * 登录失败后累计失败次数。
     *
     * @param loginType 登录类型
     * @param phone 手机号
     */
    void onLoginFailure(String loginType, String phone);
}
