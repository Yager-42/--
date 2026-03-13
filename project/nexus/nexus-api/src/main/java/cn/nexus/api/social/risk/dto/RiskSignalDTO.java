package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 风控信号（对外 DTO）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskSignalDTO {
    private String source;
    private String name;
    private Double score;
    private List<String> tags;
}

