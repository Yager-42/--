package cn.nexus.infrastructure.adapter.social.port.storage;

import cn.nexus.domain.social.model.valobj.UploadSessionVO;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class AliyunOssMediaStorageStrategy implements MediaStorageStrategy {

    @Override
    public String type() {
        return "aliyun";
    }

    @Override
    public UploadSessionVO generateUploadSession(String sessionId, String fileType, Long fileSize, String crc32) {
        throw new UnsupportedOperationException("aliyun oss is not configured");
    }

    @Override
    public String generateReadUrl(String sessionId) {
        throw new UnsupportedOperationException("aliyun oss is not configured");
    }

    @Override
    public String uploadFile(String originalFilename, String fileType, Long fileSize, InputStream inputStream) {
        throw new UnsupportedOperationException("aliyun oss is not configured");
    }
}
