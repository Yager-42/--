# zhiguang 与 xiaohashu 计数系统差异对比

## 1. 一句话结论

- `zhiguang`：偏“基础设施型计数系统”，核心是 `位图事实 + 事件聚合 + SDS 快照 + 读时重建`
- `xiaohashu`：偏“业务服务型计数系统”，核心是 `业务事实表 + Redis Hash 缓存 + 计数表快照 + data-align 对账`

两者都追求最终一致，但设计重心完全不同。

## 2. 架构层次差异

### 2.1 zhiguang

层次分得很清楚：

1. 事实层：位图
2. 增量层：Kafka 事件 + Redis 聚合桶
3. 汇总层：SDS 固定结构快照
4. 纠偏层：位图回扫重建、采样校验、灾难回放

它更像“计数引擎”。

### 2.2 xiaohashu

层次更偏业务工程化：

1. 业务事实表：`t_note_like/t_following/...`
2. 实时缓存：Redis Hash
3. 快照层：`t_note_count/t_user_count`
4. 补偿层：`xiaohashu-data-align`

它更像“计数服务编排”。

## 3. 事实来源差异

### 3.1 zhiguang 的事实来源

`zhiguang` 认为真正可信的事实是位图。

例如点赞：

- 某用户是否点赞过某帖子，不是看数据库行
- 而是看 `bm:like:{etype}:{eid}:{chunk}` 对应 bit 是否为 1

所以：

- 汇总层坏了可以重建
- 聚合桶丢了也能重建
- 只要位图还在，计数可以恢复

### 3.2 xiaohashu 的事实来源

`xiaohashu` 的事实来源是业务表：

- `t_note_like`
- `t_note_collection`
- `t_following`
- `t_fans`
- `t_comment`

Redis 不是事实源，只是热点缓存。

所以它的重建方式是：

- 对事实表重新 `count(*)`
- 覆盖 `t_note_count/t_user_count`
- 再同步 Redis

## 4. 聚合方式差异

### 4.1 zhiguang

聚合分两段：

1. Kafka 消费后写 Redis Hash 聚合桶
2. 定时任务每秒把桶折叠进 SDS

这是“事件聚合后再快照化”。

### 4.2 xiaohashu

聚合是按业务主题局部进行：

- 粉丝数聚合
- 点赞数聚合
- 收藏数聚合
- 评论数聚合

通常直接在消费者里：

- 聚合 1 秒
- 更新 Redis
- 再发 2DB Topic 或直接落库

没有统一的中间聚合快照层。

## 5. 幂等与去重方式差异

### 5.1 zhiguang

幂等核心在 Redis Lua 位图切换：

- 重复点赞：位图已经是 1，不再发事件
- 重复取消：位图已经是 0，不再发事件

这是数据结构级幂等。

关系链路再加一层去重键：

- `dedup:rel:*`

### 5.2 xiaohashu

幂等更多依赖业务链路：

- 联合唯一索引
- 顺序消费
- 同一用户同一笔记的内存折叠
- 只保留最后一次状态

这是“消费逻辑级幂等”，而不是统一事实结构幂等。

## 6. 存储结构差异

### 6.1 zhiguang

内容计数快照使用固定长度 SDS：

- 空间紧凑
- 读取偏移固定
- 适合批量读取

用户计数也使用 SDS：

- `ucnt:{userId}`

这意味着：

- Redis 是主计数快照存储
- 不是关系型表主导

### 6.2 xiaohashu

Redis 使用 Hash：

- `count:user:{userId}`
- `count:note:{noteId}`
- `count:comment:{commentId}`

DB 使用结构化计数表：

- `t_user_count`
- `t_note_count`

它的长期稳定快照更多依赖 MySQL。

## 7. 读链路差异

### 7.1 zhiguang

默认读取 SDS。

如果 SDS 异常：

- 直接读位图
- `BITCOUNT` 求和
- 现场重建

所以读链路自带修复能力。

### 7.2 xiaohashu

默认读 Redis Hash 或计数表。

如果 Redis 缺失：

- 从 `t_note_count/t_user_count/t_comment` 回源
- 再异步回填 Redis

如果 DB 快照本身错了：

- 靠 data-align 次日修正

所以它的读链路只负责“缓存回填”，不负责“事实重算”。

## 8. 补偿机制差异

### 8.1 zhiguang

补偿偏实时：

- SDS 读时异常立即重建
- 用户计数抽样校验
- earliest 历史事件回放

纠偏触发点离线上业务请求比较近。

### 8.2 xiaohashu

补偿偏离线：

- 记录今日变化对象
- 次日分片任务跑 `count(*)`
- 回刷计数表和 Redis
- 重建搜索索引

这是一种“日终修正”思路。

## 9. 与业务模块的耦合差异

### 9.1 zhiguang

计数系统更独立。

业务模块主要做两件事：

- 发起动作
- 读取计数

而计数系统自己解决：

- 幂等
- 聚合
- 汇总
- 重建

只有少量旁路耦合，比如：

- Feed 缓存失效
- 作者收到的点赞/收藏数同步

### 9.2 xiaohashu

计数逻辑深度散落在多个业务模块：

- note 维护点赞/收藏事实
- relation 维护关注/粉丝事实
- comment 维护评论事实与部分计数
- count 服务维护 Redis/DB
- data-align 维护补偿
- search 依赖计数表建索引

这是明显更强的业务耦合。

## 10. 性能与成本取舍差异

### 10.1 zhiguang 的取舍

优点：

- 写幂等强
- 汇总读很快
- 批量读取友好
- 故障后可基于事实层重建

代价：

- 设计复杂
- Redis/Lua/Kafka/锁/调度都要维护
- 研发门槛高

### 10.2 xiaohashu 的取舍

优点：

- 结构直观
- 容易落地
- MySQL 可直接做统计/索引构建
- 单模块易理解

代价：

- Topic 多，链路分散
- 实时层与补偿层割裂
- DB 快照出错时不能像 zhiguang 那样即时按事实重建
- 更依赖离线对账

## 11. 从具体指标看差异

### 11.1 点赞/收藏

`zhiguang`

- 位图记录用户态
- Kafka 聚合到 SDS
- 作者收到的点赞/收藏由本地监听器旁路更新

`xiaohashu`

- 先落 `t_note_like/t_note_collection`
- count 服务更新笔记和作者两个维度
- DB 快照与 Redis 一起维护

差异本质：

- `zhiguang` 先维护内容实体，再派生作者维度
- `xiaohashu` 在同一链路里同时维护内容维度和作者维度

### 11.2 关注/粉丝

`zhiguang`

- Outbox/Canal 驱动
- 直接更新用户 SDS
- 有抽样校验和全量重建

`xiaohashu`

- 关系消费者写事实表
- 分别发 following/fans 计数 Topic
- Redis 和 DB 双写
- 次日对账

差异本质：

- `zhiguang` 把关系计数当成用户总计数系统的一部分
- `xiaohashu` 把它当成两个独立的计数主题

### 11.3 评论

`zhiguang`

- 当前重点在内容点赞/收藏与用户关系计数
- 评论计数不是整个方案的核心

`xiaohashu`

- 评论数、评论点赞数、子评论数都纳入统一计数体系
- 并和评论热点排序、列表缓存直接联动

这说明 `xiaohashu` 的计数覆盖面更广，但统一性更弱。

## 12. 哪个更适合什么场景

### 12.1 更适合高并发强纠偏场景的是 zhiguang

当你关心：

- 高频互动
- 重复操作很多
- 计数错误不能长期存在
- 批量读压力大

`zhiguang` 方案更稳，因为它有独立事实层。

### 12.2 更适合业务快速扩展的是 xiaohashu

当你关心：

- 业务模块快速上线
- 团队更熟悉 MySQL + Redis Hash + MQ
- 可以接受离线对账
- 计数要和搜索/评论/用户资料一起快速集成

`xiaohashu` 更容易推广。

## 13. 最终判断

如果只看工程成熟度和计数基础设施能力：

- `zhiguang` 的设计更先进，也更“重”

如果只看业务推进效率和模块化落地：

- `xiaohashu` 更直接，也更“散”

可以把二者理解成两种不同哲学：

- `zhiguang`：先做统一计数底座，再让业务接入
- `xiaohashu`：先让业务链路跑起来，再用 count 服务和对账任务把结果拉齐
