package cn.nexus.domain.auth.adapter.port;

/**
 * 密码与验证码统一哈希端口。
 */
public interface IPasswordHasher {

    /**
     * 计算哈希。
     *
     * @param raw 原始内容
     * @return 哈希值
     */
    String hash(String raw);

    /**
     * 校验原始内容与哈希是否匹配。
     *
     * @param raw 原始内容
     * @param hash 哈希值
     * @return 是否匹配
     */
    boolean matches(String raw, String hash);
}
