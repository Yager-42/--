package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户昵称变更事件：用于更新搜索索引中冗余的 authorNickname 字段等旁路能力。
 *
 * <p>注意：时间字段统一使用毫秒时间戳（Long），禁止 Date。</p>
 *
 * @author codex
 * @since 2026-02-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserNicknameChangedEvent extends BaseEvent {

    /** 用户 ID。 */
    private Long userId;

    /** 事件时间戳（毫秒）。 */
    private Long tsMs;
}

