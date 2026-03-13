package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 风控 Prompt 版本 PO。
 */
@Data
public class RiskPromptVersionPO {
    private Long version;
    private String contentType;
    private String status;
    private String promptText;
    private String model;
    private Long createBy;
    private Long publishBy;
    private Date publishTime;
    private Date createTime;
    private Date updateTime;
}

