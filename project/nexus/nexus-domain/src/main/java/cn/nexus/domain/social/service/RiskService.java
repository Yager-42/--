package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.ImageScanResultVO;
import cn.nexus.domain.social.model.valobj.TextScanResultVO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 风控服务实现。
 */
@Service
@RequiredArgsConstructor
public class RiskService implements IRiskService {

    private final ISocialIdPort socialIdPort;

    @Override
    public TextScanResultVO textScan(String content, Long userId, String scenario) {
        return TextScanResultVO.builder()
                .result("PASS")
                .tags(List.of("clean"))
                .build();
    }

    @Override
    public ImageScanResultVO imageScan(String imageUrl, Long userId) {
        return ImageScanResultVO.builder()
                .taskId("task-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public UserRiskStatusVO userStatus(Long userId) {
        return UserRiskStatusVO.builder()
                .status("NORMAL")
                .capabilities(List.of("POST", "COMMENT"))
                .build();
    }
}
