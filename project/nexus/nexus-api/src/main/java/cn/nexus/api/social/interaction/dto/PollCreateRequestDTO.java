package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建投票请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollCreateRequestDTO {
    private String question;
    private List<String> options;
    private Boolean allowMulti;
    private Integer expireSeconds;
}
