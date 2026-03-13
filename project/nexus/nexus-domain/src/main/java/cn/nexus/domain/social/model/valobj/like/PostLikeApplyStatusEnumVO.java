package cn.nexus.domain.social.model.valobj.like;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PostLikeApplyStatusEnumVO {
    ALREADY(0),
    APPLIED(1),
    NEED_DB_CHECK(2);

    private final int code;
}
