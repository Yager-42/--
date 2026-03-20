package cn.nexus.domain.auth.adapter.port;

/**
 * 首批管理员引导端口。
 */
public interface IAuthAdminBootstrapPort {

    /**
     * 指定手机号是否应该自动获得管理员角色。
     *
     * @param phone 手机号
     * @return true 表示需要自动授予 ADMIN
     */
    boolean shouldGrantAdmin(String phone);
}
