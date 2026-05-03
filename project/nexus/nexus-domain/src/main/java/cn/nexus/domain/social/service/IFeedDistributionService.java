package cn.nexus.domain.social.service;

/**
 * Feed 分发服务：处理内容发布后的写扩散（fanout）。
 *
 * <p>注意：该服务属于领域层编排，不应直接依赖 MQ/Redis/DAO 客户端实现。</p>
 *
 * @author codex
 * @since 2026-01-12
 */
public interface IFeedDistributionService {

    /**
     * 执行 fanout 的一个切片：只处理 authorId 的粉丝列表中 {@code [offset, offset+limit)} 这一段。
     *
     * <p>用于“fanout 大任务切片”：失败重试只重试这一片，避免整条 fanout 从 0 重跑。</p>
     *
     * @param postId        内容 ID
     * @param authorId      作者用户 ID
     * @param publishTimeMs 发布时间毫秒时间戳
     * @param offset        粉丝分页 offset（从 0 开始）
     * @param limit         粉丝分页 limit（单片大小）
     */
    void fanoutSlice(Long postId, Long authorId, Long publishTimeMs, Integer offset, Integer limit);
}
