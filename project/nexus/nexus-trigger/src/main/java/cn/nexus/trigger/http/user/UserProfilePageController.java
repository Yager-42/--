package cn.nexus.trigger.http.user;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.risk.dto.UserRiskStatusResponseDTO;
import cn.nexus.api.user.IUserProfilePageApi;
import cn.nexus.api.user.dto.UserProfilePageResponseDTO;
import cn.nexus.api.user.dto.UserProfileQueryRequestDTO;
import cn.nexus.api.user.dto.UserProfileResponseDTO;
import cn.nexus.api.user.dto.UserRelationStatsDTO;
import cn.nexus.domain.social.model.valobj.UserRiskStatusVO;
import cn.nexus.domain.user.model.valobj.UserProfilePageVO;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.domain.user.model.valobj.UserRelationStatsVO;
import cn.nexus.domain.user.service.UserProfilePageQueryService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人主页聚合接口：Profile + 关系统计 + 风控能力。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class UserProfilePageController implements IUserProfilePageApi {

    @Resource
    private UserProfilePageQueryService userProfilePageQueryService;

    @GetMapping("/user/profile/page")
    @Override
    public Response<UserProfilePageResponseDTO> profilePage(UserProfileQueryRequestDTO requestDTO) {
        try {
            Long viewerId = UserContext.requireUserId();
            Long targetUserId = requestDTO == null ? null : requestDTO.getTargetUserId();
            if (targetUserId == null) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "targetUserId 不能为空");
            }
            UserProfilePageVO vo = userProfilePageQueryService.query(viewerId, targetUserId);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toDto(vo));
        } catch (AppException e) {
            return Response.<UserProfilePageResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("user profile page api failed, req={}", requestDTO, e);
            return Response.<UserProfilePageResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private UserProfilePageResponseDTO toDto(UserProfilePageVO vo) {
        if (vo == null) {
            return null;
        }
        UserProfileVO profile = vo.getProfile();
        UserRelationStatsVO relation = vo.getRelation();
        UserRiskStatusVO risk = vo.getRisk();

        UserProfileResponseDTO profileDto = profile == null ? null : UserProfileResponseDTO.builder()
                .userId(profile.getUserId())
                .username(profile.getUsername())
                .nickname(profile.getNickname())
                .avatarUrl(profile.getAvatarUrl())
                .status(vo.getStatus())
                .build();

        UserRelationStatsDTO relationDto = relation == null ? null : UserRelationStatsDTO.builder()
                .followCount(relation.getFollowCount())
                .followerCount(relation.getFollowerCount())
                .friendCount(relation.getFriendCount())
                .isFollow(relation.isFollow())
                .build();

        UserRiskStatusResponseDTO riskDto = risk == null ? null : UserRiskStatusResponseDTO.builder()
                .status(risk.getStatus())
                .capabilities(risk.getCapabilities())
                .build();

        return UserProfilePageResponseDTO.builder()
                .profile(profileDto)
                .relation(relationDto)
                .risk(riskDto)
                .build();
    }
}

