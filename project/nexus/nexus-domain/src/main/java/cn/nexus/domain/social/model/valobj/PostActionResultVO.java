package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostActionResultVO {

    private boolean changed;
    private boolean liked;
    private boolean faved;
    private long likeCount;
    private long favoriteCount;
}
