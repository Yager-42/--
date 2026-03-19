# AGENTS.md

## Superpowers / Codex 规则

1. 本项目默认启用 superpowers 体系，`using-superpowers` 视为基础 skill。
2. 在 Codex 环境中，skills 是原生可用的；即使没有独立名为 `Skill` 的工具按钮，也不得解释为“skills 不可用”。
3. 当用户显式写出 `$using-superpowers`，或请求明显命中某个 superpowers skill 时，必须立即按该 skill 流程执行，再进行后续回答或操作。
4. 若 skill 文档中使用的是 Claude Code 工具名，必须按 Codex 平台等价映射理解和执行，而不是以“工具名不一致”为理由跳过。
5. 不允许出现以下错误判断：
   - “当前环境没有独立 Skill 工具，所以不能用 superpowers”
   - “必须由用户重复粘贴 `<skill>...</skill>` 才算启用”
6. 若用户已在 Codex 设置中启用相关 skills，应直接视为该 skill 已可用；用户显式点名 `$using-superpowers` 时，按更严格标准执行。
7. 若确实存在平台差异，应明确说明“这是工具形态差异，不是 skill 不可用”，然后继续按 skill 规则完成任务。

## 路线2：聚合查询重构（仅代理用）

- 规范文档：`docs/superpowers/specs/2026-03-19-aggregation-query-route2-agent-design.md`
