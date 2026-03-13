package cn.nexus.infrastructure.dao.kv.po;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentContentKeyPO {
    private String yearMonth;
    private String contentId;
}
