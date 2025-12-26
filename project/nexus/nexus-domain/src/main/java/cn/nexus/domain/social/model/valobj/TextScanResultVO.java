package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文本扫描结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextScanResultVO {
    private String result;
    private List<String> tags;
}
