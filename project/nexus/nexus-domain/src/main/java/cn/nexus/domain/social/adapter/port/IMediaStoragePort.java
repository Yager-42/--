package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;

import java.io.InputStream;

/**
 * 媒体存储端口：生成直传上传会话（预签名 URL 等凭证）。
 */
public interface IMediaStoragePort {

    /**
     * 基于对象存储生成上传会话。
     *
     * @param sessionId  业务侧生成的会话标识，用于构造对象键
     * @param fileType   文件类型（MIME），用于设置 Content-Type
     * @param fileSize   文件大小（字节），用于限流/校验
     * @param crc32      客户端传入的校验值，可用于后续校验占位
     * @return 上传会话凭证
     */
    UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32);

    /**
     * 生成媒体读取 URL（用于内容展示/异步风控扫描等场景）。
     *
     * <p>约束：返回的 URL 应该是“可被外部拉取”的地址（例如预签名 GET URL）。</p>
     *
     * @param sessionId 上传会话的业务标识（同 generateUploadSession 入参）
     * @return 可读取的 URL
     */
    String generateReadUrl(String sessionId);

    /**
     * 服务端直传：用于 /file/upload 这类 multipart 入口。
     *
     * <p>注意：该方法是 playbook 兼容入口；业务主链路仍建议走预签名直传。</p>
     *
     * @param originalFilename 原始文件名（用于保留后缀，可为空）
     * @param fileType         MIME（可为空）
     * @param fileSize         文件大小（字节，可为空）
     * @param inputStream      文件流（由调用方负责关闭）
     * @return 可读 URL（例如预签名 GET URL）
     */
    String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream);
}
