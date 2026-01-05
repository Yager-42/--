package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 文本内容块（基准全文或分块）。
 */
@Data
public class ContentChunkPO {
    private String chunkHash;
    private byte[] chunkData;
    private Long size;
    private String compressAlgo;
    private Date createTime;
}
