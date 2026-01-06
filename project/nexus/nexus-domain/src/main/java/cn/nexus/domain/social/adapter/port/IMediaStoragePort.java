package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;

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
}
