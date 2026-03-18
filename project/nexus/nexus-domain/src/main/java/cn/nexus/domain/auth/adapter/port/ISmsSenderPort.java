package cn.nexus.domain.auth.adapter.port;

import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;

/**
 * 短信发送端口。
 */
public interface ISmsSenderPort {

    /**
     * 发送短信验证码。
     *
     * @param phone 手机号
     * @param code 验证码明文
     * @param bizType 业务类型
     * @return 是否发送成功
     */
    boolean send(String phone, String code, AuthSmsBizTypeVO bizType);
}
