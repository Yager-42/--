package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 文本补丁（压缩后的 diff）。
 */
@Data
public class ContentPatchPO {
    private String patchHash;
    private byte[] patchData;
    private Long size;
    private String compressAlgo;
    private Date createTime;
}
