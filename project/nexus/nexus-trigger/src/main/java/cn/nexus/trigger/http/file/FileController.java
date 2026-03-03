package cn.nexus.trigger.http.file;

import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.trigger.http.file.dto.FileUploadResponseDTO;
import cn.nexus.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * Playbook-compatible multipart upload endpoint.
 *
 * <p>Note: main business flow should still prefer presigned direct upload.</p>
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequiredArgsConstructor
public class FileController {

    private final IMediaStoragePort mediaStoragePort;

    @PostMapping("/file/upload")
    public Response<FileUploadResponseDTO> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Response.<FileUploadResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("file 不能为空")
                    .build();
        }

        try (InputStream in = file.getInputStream()) {
            String url = mediaStoragePort.uploadFile(file.getOriginalFilename(), file.getContentType(), file.getSize(), in);
            FileUploadResponseDTO dto = FileUploadResponseDTO.builder().url(url).build();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (Exception e) {
            log.error("file upload failed, name={}, size={}", file.getOriginalFilename(), file.getSize(), e);
            return Response.<FileUploadResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
