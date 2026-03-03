package cn.nexus.infrastructure.adapter.social.port.storage;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;

import java.io.InputStream;

public interface MediaStorageStrategy {

    String type();

    UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32);

    String generateReadUrl(String sessionId);

    /**
     * Server-side multipart upload.
     */
    String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream);
}
