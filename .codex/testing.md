# .codex/testing.md

日期：2026-01-16  
执行者：Codex（Linus-mode）

这次交付是“方案文档”，没有代码可运行测试。这里给出最小可执行的本地验证清单（你后续实现代码时照着跑）。

## 最小验证用例（必须）

1) 幂等：同一个 `eventId` 重放 3 次，最终 `LikeState` 与 `LikeCount` 不变。  
2) 状态覆盖：同一窗口内 `like -> unlike -> like`，最终 state=like，count 只 +1。  
3) 并发：1000 个用户同时点赞同一 target，窗口 flush 后 count=1000。  
4) 回源：清空 Redis 后读 count/state，应从 L3 回源并回填缓存。  

