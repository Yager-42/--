package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.RiskPromptVersionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface IRiskPromptVersionDao {

    int insert(RiskPromptVersionPO po);

    RiskPromptVersionPO selectByVersion(@Param("version") Long version);

    RiskPromptVersionPO selectActive(@Param("contentType") String contentType);

    List<RiskPromptVersionPO> selectAll(@Param("contentType") String contentType);

    Long selectMaxVersion();

    int updatePrompt(@Param("version") Long version,
                     @Param("promptText") String promptText,
                     @Param("model") String model,
                     @Param("expectedStatus") String expectedStatus);

    int markAllPublishedRolledBack(@Param("contentType") String contentType,
                                   @Param("toStatus") String toStatus);

    int publish(@Param("version") Long version,
                @Param("status") String status,
                @Param("publishBy") Long publishBy,
                @Param("publishTime") Date publishTime);
}

