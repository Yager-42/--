# Nexus 复现《小红书项目复现手册》执行文档（给 Codex agent）

> 读者：Codex agent（不是人）。  
> 目标：在 `project/nexus` 里，按步骤实现 `xiaohashu/xiaohashu_project_implementation_playbook.md` 的能力。  
> 要求：每一步都要“能跑、能验收、可回滚”。不要把现有 `project/nexus/nexus-app` 搞挂。

---

## 重要更新（2026-03-02）

本执行文档早期版本假设通过 `nexus-xhs-*` 隔离子工程来复现手册（包括“隔离 Spring Boot 版本”“独立微服务+网关”“强制 RocketMQ/Cassandra/Leaf/Nacos/Zookeeper”等）。

当前策略已改为：把 `docs/xiaohashu_project_implementation_playbook.md` 当作“改进清单”，直接融入现有 Nexus 工程（DDD 分层：`nexus-app/nexus-trigger/nexus-domain/nexus-infrastructure`），不再做隔离子工程。

因此，下文中与 `nexus-xhs-*`、隔离版本、强制组件替换相关的内容，统一视为“历史草案”。以以下内容为准：

- `project/nexus/.codex/context-scan.json`：现状扫描与章节映射（事实）
- `project/nexus/operations-log.md`：关键取舍与决策
- Shrimp 任务列表：分阶段执行与验收标准

---

## 0. 固定输入（用户已确认，写死不要再问）

用户最新确认（以本段为准）：

1) 范围：只硬替换三条链路：缓存一致性 / 点赞 / 计数  
2) 目标：把 `docs/xiaohashu_project_implementation_playbook.md` 作为“改进方案”融入现有 `project/nexus`，不做隔离子工程  
3) 技术栈对齐：仅对齐三链路相关栈：**RocketMQ / RedisBloom / BufferTrigger**  
4) 数据库：以 nexus 现有 SQL 表为基准演进（`docs/social_schema.sql` 等），无真实数据，可重建/迁移  
5) 兼容性：对外 HTTP API 不变（路径/DTO/返回语义尽量不破坏）

说明：playbook 里的其它关键词（SaToken/Leaf/Cassandra/Nacos 等）不在本轮范围，后续需要时再拆任务。

## 0.1 执行硬规则（缺失就停，不许猜）

这份文档的目的：把实现变成“照抄 + 填空”，避免 Codex agent 自己拍脑袋决定细节。

硬规则（违反就会跑偏）：
1) **范围硬限制**：只允许修改三条链路（缓存一致性/点赞/计数）涉及的代码与 SQL，禁止扩散到 SaToken/Leaf/Cassandra/Nacos/网关/微服务拆分等其它章节。  
2) **名字不许乱**：三条链路涉及的 DDL / Topic / Tag / Redis Key / 配置键名必须以 playbook 为准（例如 Topic：`DeleteNoteLocalCacheTopic` / `DelayDeleteNoteRedisCacheTopic` / `LikeUnlikeTopic` / `CountNoteLike2DBTopic`）。  
3) **避免双写**：旧的点赞延迟同步链路（`ReactionSync*` / `IReactionDelayPort` / `syncTarget` 触发器）必须被禁用或移除，避免与新链路并行导致双写与计数翻倍。  
4) **不破坏 userspace**：HTTP 路由与 DTO 语义保持不变；只替换内部实现与数据结构。  
5) **验证优先**：每一步都要可编译、可验证；中间件环境缺失时，至少保证 `mvn test` 通过，并提供可复现实操步骤文档。  

---

## 0.2 你现在没有“落地环境”怎么办（默认：本地 docker-compose）

用户反馈：项目仍在编码期，没有现成可用的中间件环境；且本机当前无法使用 `docker` 命令。

因此本轮统一决策（写死，不再问）：
1) 以“代码可编译 + 单测/伪集成验证”为主（`mvn test`）  
2) 同时补齐 `project/nexus/.tools/xhs/docker-compose.yml` 作为未来一键环境，但不阻塞本轮交付  
3) RocketMQ/RedisBloom 等依赖的真实联调，留到环境具备后按文档复验

## 0.3 三链路契约（Topic / Redis Key / SQL 映射，写死别发明）

### 0.3.1 缓存一致性（playbook 6）

- Redis key（详情缓存）：`interact:content:post:<postId>`
- Topic（广播删本地缓存）：`DeleteNoteLocalCacheTopic`（广播模式，payload：`postId`）
- Topic（延迟再删 Redis）：`DelayDeleteNoteRedisCacheTopic`（延时约 1s，payload：`postId`）

### 0.3.2 点赞/取消点赞（playbook 7）

- Redis key（按 userId 拆分，避免热 key）：
  - Bloom（Post）：`bloom:note:likes:<userId>`
  - ZSet（Post，最近 100 条）：`user:note:likes:<userId>`
  - Bloom（Comment）：`bloom:comment:likes:<userId>`（如果实现 comment like）
  - ZSet（Comment，最近 100 条）：`user:comment:likes:<userId>`（如果实现 comment like）
- MQ：
  - Topic：`LikeUnlikeTopic`
  - Tag：`Like` / `Unlike`
  - hashKey：`userId`（保证同一用户消息有序）
- MySQL：
  - 事实表：`interaction_reaction`（在现有表上新增 `status(0/1)`，主键不变）

### 0.3.3 计数（playbook 8）

- Redis key（Hash）：
  - `count:note:<noteId>`（field：`likeTotal`）
  - `count:user:<userId>`（field：`likeTotal`）
- MQ：
  - Topic：`CountNoteLike2DBTopic`（payload：`[{noteId, creatorId, count}]`）
- MySQL：
  - 计数表（note）：复用 `interaction_reaction_count`，用 `(target_type=POST,target_id=noteId,reaction_type=LIKE)` 承载 `likeTotal`
  - 计数表（user）：新增 `interaction_user_count(user_id, like_total, update_time)`

---

## 1. 你要交付什么（Definition of Done）

最终以“三链路硬替换可验证”为准。最低交付包括：

1) 缓存一致性（playbook 6）：延时双删 Redis + RocketMQ 广播删本地缓存  
2) 点赞（playbook 7）：RedisBloom + ZSet + RocketMQ LikeUnlikeTopic 异步落库 + 消费端 RateLimiter  
3) 计数（playbook 8）：BufferTrigger(1000条/1s) 聚合 LikeUnlikeTopic → CountNoteLike2DBTopic → 增量落库  
4) SQL 资产：基于 nexus 现有表的增量迁移（migrations）+ 重建用 `00_init.sql`  
5) 验证产物：`.codex/testing.md`、`verification.md`、`.codex/review-report.md`

---

## 2. 总体策略（避免把仓库写烂）

### 2.1 不破坏旧系统

`project/nexus` 现有代码已经有一套“社交骨架”。本轮目标不是“全量复现手册项目”，而是把手册里最关键且已确认范围的三条链路，硬替换进现有实现。

硬规则：
- 不新增隔离子工程/微服务模块；直接在现有 DDD 分层内实现（`nexus-trigger/nexus-domain/nexus-infrastructure`）。  
- 其它链路（RabbitMQ/Kafka/Outbox/搜索等）不强制改动，避免牵一发动全身。  

### 2.2 只做两件事：对齐 + 补齐

- 对齐：三条链路涉及的 Topic/Key/表结构/限流/聚合策略按 playbook 照抄（只在与 Nexus 现有契约冲突时做最小改动）。  
- 补齐：缺的能力用最短路径补上，先跑通链路，再优化。  

### 2.3 版本与模块隔离策略（保证不搞挂 nexus-app）

本轮不做版本隔离子工程。

注意：引入 RocketMQ starter 时必须选择与 Spring Boot 3.2/Jakarta 兼容的版本，以保证现有 `nexus-app` 不被依赖冲突搞挂。

---

## 3. 目标微服务清单（建议模块名 + 本地端口）

本章节及后续“微服务拆分/网关/SaToken/Leaf/Cassandra/Nacos”等内容已作废，保留仅作历史参考。
> 端口只是建议，方便本地同时跑起来。

1) `nexus-xhs-gateway`（8080）  
   - Spring Cloud Gateway（WebFlux）  
   - SaToken Reactor 鉴权  
   - 统一注入 Header：`userId: <数字>`

2) `nexus-xhs-user`（8081）  
   - 登录接口：`/auth/login`（SaToken 发 token）  
   - （可选）验证码发送：`/auth/verification/code/send`

3) `nexus-xhs-id-generator`（8082）  
   - Leaf Controller：  
     - `GET /id/segment/get/{bizTag}`  
     - `GET /id/snowflake/get/{key}`

4) `nexus-xhs-kv`（8083）  
   - Cassandra KV：  
     - `POST /kv/note/content/add|find|delete`  
     - `POST /kv/comment/content/batchAdd|batchFind|delete`

5) `nexus-xhs-oss`（8084）  
   - 文件上传：`POST /file/upload`（multipart/form-data）  
   - 策略模式：MinIO / Aliyun OSS  
   - Nacos 动态切换：`storage.type=minio|aliyun`

6) `nexus-xhs-note`（8085）  
   - 业务接口（照手册）：  
     - `/note/publish` `/note/detail` `/note/update` `/note/delete`  
     - `/note/like` `/note/unlike`  
   - 二级缓存：Caffeine + Redis  
   - RocketMQ：缓存一致性 + 点赞 MQ

7) `nexus-xhs-count`（8086）  
   - 消费 LikeUnlikeTopic，做 BufferTrigger 聚合  
   - 更新 Redis：`count:note:<noteId>`、`count:user:<userId>`  
   - 再发 MQ 到落库 topic（例如 `CountNoteLike2DBTopic`）

---

## 4. 全局统一约定（必须一致，不一致就会“跑不通”）

### 4.1 Header 与身份（最重要）

客户端 → Gateway：
- `Authorization: Bearer <token>`

Gateway → 下游所有服务：
- `userId: <Long>`（注意：是普通 Header，不要 `X-User-Id`）

下游服务必须遵守：
- **禁止**信任客户端直接传 `userId`  
- userId 只能来自 Gateway 注入  

### 4.2 下游服务的“用户上下文”实现（照手册做）

你需要做 3 件事（建议做成一个可复用 starter）：

1) Filter：读取 Header `userId` → 写入 ThreadLocal  
2) finally 清理 ThreadLocal（防止线程复用串号）  
3) Feign 拦截器：服务 A 调服务 B 时，自动把 `userId` 透传

建议新增模块：
- `nexus-xhs-framework-biz-context`（给所有 xhs 服务依赖）

### 4.3 响应格式

为了统一，你可以复用现有的 `project/nexus/nexus-api` 的 `Response` 壳：
- `{ code, info, data }`

但注意：Gateway 401/未登录要统一输出（别一会儿 401 一会儿 200）。

---

## 5. Redis Key / RocketMQ Topic（照抄手册名字，别发明新名字）

> 完整契约（Key/Topic/Tag/TTL/消息体/消费者组）见：**附录A**。这里仅放“最常用的几条”方便你记忆。

### 5.1 Note 详情缓存

- Redis：`note:detail:<noteId>`  
  - 命中返回正文（JSON）  
  - 查不到写 `"null"`（短 TTL 60~120s）防穿透  
  - 正常数据 TTL = 1 天 + 随机秒数（打散过期时间）

### 5.2 缓存一致性（第 6 节）

两个 Topic（照抄）：
- `DeleteNoteLocalCacheTopic`：广播删本地缓存（RocketMQ BROADCASTING）
- `DelayDeleteNoteRedisCacheTopic`：延迟再删一次 Redis（集群消费即可）

### 5.3 点赞（第 7 节）

Redis Key（照抄）：
- Bloom：`bloom:note:likes:<userId>`
- ZSet：`user:note:likes:<userId>`（最多保留 100 条）

MQ Topic（照抄）：
- `LikeUnlikeTopic`
  - Tag：`Like` / `Unlike`
  - hashKey：`userId`（保证同一 userId 有序）

DB 表（照抄语义）：
- `t_note_like`：`UNIQUE(user_id, note_id)` + upsert

### 5.4 计数（第 8 节）

Redis Key（照抄）：
- `count:note:<noteId>`（Hash：likeTotal/collectTotal/commentTotal）
- `count:user:<userId>`（Hash：fansTotal/followingTotal/noteTotal/likeTotal/collectTotal）

MQ Topic（示例，照手册）：
- 聚合消费者：消费 `LikeUnlikeTopic`
- 落库 topic：`CountNoteLike2DBTopic`（只承载“聚合后的增量”）

---

## 6. 分阶段实施（必须按顺序；每步完成才进入下一步）

> 规则：每个阶段都要留下“可运行产物”，不要一次写完所有功能。

### 阶段 0：工程骨架与一键启动（先把地基打好）

目标：
- 新增 `nexus-xhs` 父模块（隔离版本），并在其下新增：7 个可运行微服务 + 1 个共享 starter；全部都能启动到 health。  
- 一键拉起本地中间件环境（docker-compose）。

必须做的事：
1) 修改 `project/nexus/pom.xml`：只新增 1 个 module：`nexus-xhs`（避免污染旧模块依赖）  
2) 新增 `project/nexus/nexus-xhs/pom.xml`：声明 xhs 子模块（`nexus-xhs-*`）  
3) 新增共享 starter：`nexus-xhs-framework-biz-context`（ThreadLocal + Feign 透传 userId）  
4) 为 7 个服务模块新增 `...Application.java` 与本地 `application-dev.yml`（先能启动，再逐步迁移到 Nacos 配置）  
5) 增加本地启动文档：`project/nexus/docs/xhs-local-dev.md`  
6) 增加本地中间件：`project/nexus/.tools/xhs/docker-compose.yml`（见附录B 默认值）

验收：
- `docker compose -f project/nexus/.tools/xhs/docker-compose.yml up -d` 后，本地中间件全部可用  
- 7 个服务都能启动，分别访问 `/actuator/health` 返回 UP（或你自定义 `/health`）

### 阶段 1：Gateway 鉴权 + userId 透传（手册第 1 节）

目标：
- 用户登录拿 token  
- 带 token 访问 Note 服务，Note 能拿到 ThreadLocal 里的 userId

实现要点（照手册）：  
1) User 服务：`StpUtil.login(userId)` 返回 token  
2) Gateway：SaToken `checkLogin()`  
3) Gateway：鉴权通过后注入 Header `userId`（注意 exchange 要传 newExchange）  
4) 下游：Filter 把 Header `userId` 放入 ThreadLocal（请求结束清理）  
5) Feign：A 调 B 自动透传 `userId`

验收：
1) `/auth/login` 拿 token  
2) `Authorization: Bearer <token>` 调 Note 任意接口  
3) Note 打印/断点看到 userId 正确

### 阶段 2：Leaf ID 服务（手册第 2 节）

目标：
- `/id/segment/get/{bizTag}` 可用  
- `/id/snowflake/get/{key}` 可用（含 Zookeeper）

实现要点（照手册）：  
1) MySQL 建库 `leaf` + 表 `leaf_alloc`（照抄手册）  
2) `leaf.properties` 配置 segment/snowflake  
3) 服务启动 init Segment/Snowflake  
4) Note/User/Comment 等业务用 Feign 调 ID 服务

验收：
- 连续请求 1 万次 ID 不重复  
- 多实例并发也不重复

### 阶段 3：Cassandra KV 服务（手册第 4 节）

目标：
- Note 正文写 Cassandra，MySQL 只存 `content_uuid`

实现要点（照手册）：  
1) Cassandra keyspace + 两张表：`note_content` / `comment_content`  
2) KV HTTP 接口：add/find/delete + 评论 batchAdd/batchFind  
3) Note 发布：先写 KV，再写 MySQL；MySQL 失败要补偿删 KV

验收：
- MySQL 主表只有 `content_uuid`  
- KV 能按 uuid 查到正文  
- 删除帖子时 KV 也删除

### 阶段 4：Note 主流程 + 二级缓存 + 并发聚合（手册第 5 节）

目标：
- 跑通 `/note/publish` + `/note/detail`  
- detail 走：本地缓存 → Redis → DB + 并发 RPC（KV/User）

实现要点（照手册）：  
1) MySQL 元数据表：`t_note`（字段含：note_id/creator_id/visible/content_uuid...）  
2) detail：用 `CompletableFuture` 并发拿：
   - 用户信息（User 服务）
   - 正文（KV 服务）
3) 缓存策略：
   - 本地缓存（Caffeine）：TTL=1 小时（与手册仓库一致，依赖 MQ 广播删本地缓存）  
   - Redis：长 TTL（1 天 + 随机秒）  
   - NOT_FOUND 写 `"null"`（短 TTL）

验收：
- 第一次 detail：走 DB + RPC  
- 第二次：命中 Redis  
- 第三次：命中本地缓存

### 阶段 5：缓存一致性（手册第 6 节）

目标：
- 更新/删除后，几乎不会再读到旧详情

实现要点（照手册）：  
1) 立刻删 Redis：`note:detail:<id>`  
2) 广播删本地缓存：`DeleteNoteLocalCacheTopic`（BROADCASTING）  
3) 延迟 1s 再删 Redis：`DelayDeleteNoteRedisCacheTopic`

验收：
- 压测：并发读 detail + 并发 update 同一条 note  
- 旧数据比例接近 0

### 阶段 6：点赞高并发（手册第 7 节）

目标：
- `/note/like` `/note/unlike` 高并发下能扛住，DB 不被打爆

实现要点（照手册）：  
1) RedisBloom：Bloom check/add（Lua 调用 `BF.EXISTS/BF.ADD`）  
2) Redis ZSet：最近 100 条点赞（Lua 原子更新）  
3) 发 RocketMQ：`LikeUnlikeTopic`（asyncSendOrderly，hashKey=userId）  
4) 消费者落库：RateLimiter + 合并去抖 + upsert `t_note_like`
5) RateLimiter 配置键（Nacos，可热更新）：`mq-consumer.like-unlike.rate-limit`（本地默认：5000）

验收：
- 重复点赞返回“已点赞”  
- 点赞后立刻查“是否点赞”能立刻反映  
- 高并发时 DB 写入被 RateLimiter 控住

### 阶段 7：计数服务 BufferTrigger（手册第 8 节）

目标：
- 点赞计数能实时更新 Redis，DB 异步最终一致

实现要点（照手册）：  
1) 消费 `LikeUnlikeTopic`（用独立 consumerGroup，别和落库共用）  
2) BufferTrigger：`batchSize=1000` + `linger=1s`  
3) 聚合后：
   - `HINCRBY count:note:<noteId> likeTotal`  
   - `HINCRBY count:user:<userId> likeTotal`  
   - 再发 MQ 到 `CountNoteLike2DBTopic`
4) 落库消费者：RateLimiter + 事务更新 `t_note_count` / `t_user_count`
5) RateLimiter 配置键（Nacos，可热更新）：`mq-consumer.count-fans-following.rate-limit`（本地默认：5000；手册仓库 Count 服务复用这个 key）

验收：
- Redis 计数实时变化  
- DB 计数延迟几秒后对齐

### 阶段 8：OSS 策略 + Nacos 动态切换（手册第 3 节）

目标：
- `storage.type=minio` 能上传  
- 改成 `aliyun` 后不重启也能上传

实现要点（照手册）：  
1) `FileStrategy` 接口  
2) `MinioFileStrategy` / `AliyunOSSFileStrategy`  
3) `FileStrategyFactory` + `@RefreshScope`  
4) Nacos 配置：`storage.type` / `storage.minio.*` / `storage.aliyun-oss.*`

验收：
- 动态切换后，上传走新存储

---

## 7. 允许的“最少提问”（只有卡住才问，最多 1~3 个）

当前阶段（本地 docker-compose）：你不需要向用户要任何环境信息。附录B 已经给出“本地默认值”，直接照做。

只有当出现以下情况之一，才允许提问（每次最多 1~3 个）：  
1) 用户明确要求接入“公司/线上/已有环境”（不再使用本地 docker-compose）  
2) 你发现当前环境不满足 **附录A 契约**（比如：Redis 不支持 RedisBloom、Snowflake 没有 Zookeeper、RocketMQ 禁止创建 Topic）

提问必须满足：
- 每次最多问 1~3 个问题  
- 问题要“二选一”或“让用户填表”，不要开放式发散  
- 没拿到回答前：**禁止继续实现**（包括“先用别的替代”）

推荐提问模板（只在情况 1 发生时使用；把示例值替换成真实值）：
1) 你的 Nacos：`server-addr=127.0.0.1:8848`，`namespace=""`（public），`group=DEFAULT_GROUP`。我是否有写配置权限？（有/没有）  
2) 你的 RocketMQ NameServer：`127.0.0.1:9876`。是否允许创建 Topic？（允许/不允许；若不允许请给我“已创建的 Topic 列表”）  
3) 你的 Redis：`127.0.0.1:6379`（password 空，db=0）。是否支持 RedisBloom（能执行 `BF.EXISTS`）？（支持/不支持）

---

## 附录A：契约附录（DDL / Topic / Key / 配置 / 版本）【缺失就停，不许猜】

> 这部分是“写死的契约”。实现时必须照抄：**表名/字段名/Key/Topic/Tag/配置键名**。  
> 如果你发现契约和现实环境冲突：停下来，按“第 7 节提问规则”问用户，不许自己换方案。

来源文件（可核对，不许“凭感觉”抄错）：
- 版本：`xiaohashu/pom.xml`
- 时间/JSON：`xiaohashu/xiaoha-framework/xiaoha-spring-boot-starter-jackson/.../JacksonAutoConfiguration.java`、`xiaohashu/xiaoha-framework/xiaoha-common/.../DateConstants.java`
- MySQL DDL：`xiaohashu/sql/xiaohashu_init.sql`（注意：`leaf_alloc.step` 那行原文件有重复 COMMENT，见下方“已修正版本”）
- Cassandra DDL：`xiaohashu/cassandra/cassandra.txt` + 手册第 4 节（`note_content`）
- Redis Key：
  - Note：`xiaohashu/xiaohashu-note/.../constant/RedisKeyConstants.java`
  - Count：`xiaohashu/xiaohashu-count/.../constant/RedisKeyConstants.java`
- MQ Topic/Tag：
  - Note：`xiaohashu/xiaohashu-note/.../constant/MQConstants.java`
  - Count：`xiaohashu/xiaohashu-count/.../constant/MQConstants.java`
- 网关 SaToken + 路由示例：`xiaohashu/xiaohashu-gateway/src/main/resources/application.yml`
- Leaf 配置示例：`xiaohashu/xiaohashu-distributed-id-generator/.../leaf.properties`
- Nacos bootstrap 约定示例：`xiaohashu/*/bootstrap.yml`

### A1. 版本锁定（来自 xiaohashu/pom.xml）

这张表是“手册仓库的真实版本”。你在 Nexus 里如果版本不一致，**必须先停下来问用户**：要不要对齐到下面这些版本。

| 类别 | 名称 | 版本 |
| --- | --- | --- |
| Runtime | Java | 17 |
| Framework | Spring Boot | 3.0.2 |
| Framework | Spring Cloud | 2022.0.0 |
| Framework | Spring Cloud Alibaba | 2022.0.0.0 |
| Auth | Sa-Token | 1.38.0 |
| MQ | RocketMQ Spring | 2.2.3 |
| MQ | rocketmq-client | 4.9.4 |
| KV | Cassandra Driver（Spring） | 跟随 Spring Boot 依赖管理 |
| Cache | Caffeine | 3.1.8 |
| Aggregation | BufferTrigger | 0.2.21 |
| OSS | MinIO SDK | 8.2.1 |
| OSS | aliyun-sdk-oss | 3.17.4 |
| Thread Context | TransmittableThreadLocal | 2.14.2 |
| DB | mysql-connector-java | 8.0.29 |
| Pool | Druid | 1.2.23 |
| JSON | Jackson | 2.16.1 |

### A2. 时间 / JSON / 时区契约（非常容易被“默认值”坑）

- 时区：`Asia/Shanghai`
- HTTP 响应壳：统一 `{ code, info, data }`（复用 `project/nexus/nexus-api` 的 Response）
- `LocalDateTime` 序列化格式：`yyyy-MM-dd HH:mm:ss`
- MQ 消息体里 `createTime` 同样用 `yyyy-MM-dd HH:mm:ss`
- ZSet score 使用“毫秒时间戳”：
  - 代码来源：`DateUtils.localDateTime2Timestamp()`（`toEpochMilli()`）

### A3. Header / 鉴权 / Gateway 路由契约

**Header 契约（必须统一）：**
- 客户端 -> Gateway：`Authorization: Bearer <token>`
- Gateway -> 下游：`userId: <Long>`
- 下游服务：禁止信任客户端直接传 `userId`；只能用 Gateway 注入的 `userId`

**SaToken 配置契约（来自 xiaohashu-gateway/application.yml）：**
- `sa-token.token-name=Authorization`
- `sa-token.token-prefix=Bearer`
- `sa-token.timeout=2592000`（30 天）
- `sa-token.active-timeout=-1`
- `sa-token.is-concurrent=true`
- `sa-token.is-share=true`
- `sa-token.token-style=random-128`

**Gateway 路由契约（关键：避免 /auth/login 的“内外路径不一致”）：**
- 对外暴露：`POST /auth/login`
- 对内转发：`POST /login`（依赖 Gateway `StripPrefix=1`）

示例（你在 Nexus 里照这个结构配，但服务名换成 `nexus-xhs-*`）：  
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth
          uri: lb://nexus-xhs-user
          predicates:
            - Path=/auth/**
          filters:
            - StripPrefix=1
        - id: user
          uri: lb://nexus-xhs-user
          predicates:
            - Path=/user/**
          filters:
            - StripPrefix=1
        - id: note
          uri: lb://nexus-xhs-note
          predicates:
            - Path=/note/**
          filters:
            - StripPrefix=1
        - id: kv
          uri: lb://nexus-xhs-kv
          predicates:
            - Path=/kv/**
          filters:
            - StripPrefix=1
        - id: oss
          uri: lb://nexus-xhs-oss
          predicates:
            - Path=/file/**
          filters:
            - StripPrefix=1
        - id: id-generator
          uri: lb://nexus-xhs-id-generator
          predicates:
            - Path=/id/**
          filters:
            - StripPrefix=1
```

### A4. API 契约（路径 / 方法 / 请求字段）

说明：除 ID 服务外，所有 API 都使用统一 Response 壳 `{code, info, data}`。

#### A4.1 登录（对外：/auth/login；对内：/login）

- `POST /login`（Gateway 对外为 `/auth/login`）
- Request（来自 `UserLoginReqVO`）：
  - `phone`：String，必填
  - `code`：String，可选
  - `password`：String，可选
  - `type`：Integer，必填（登录类型）
- Response：
  - `data`：String（token）

#### A4.2 用户信息（给 Note 服务并发聚合用）

- `POST /user/findById`
- Request（来自 `FindUserByIdReqDTO`）：`id: Long`
- Response（来自 `FindUserByIdRspDTO`）：
  - `id: Long`
  - `nickName: String`
  - `avatar: String`
  - `introduction: String`

#### A4.3 ID 生成（Leaf）

注意：这个服务为了最少特殊情况，**直接返回纯文本数字字符串**（与手册仓库一致）。

- `GET /id/segment/get/{key}` -> Response body: `<数字字符串>`
- `GET /id/snowflake/get/{key}` -> Response body: `<数字字符串>`

#### A4.4 KV（Cassandra）

接口来源：`KeyValueFeignApi`。

- `POST /kv/note/content/add`：`{ uuid: String, content: String }`
- `POST /kv/note/content/find`：`{ uuid: String }` -> `data: { uuid: UUID, content: String }`
- `POST /kv/note/content/delete`：`{ uuid: String }`
- `POST /kv/comment/content/batchAdd`：`{ comments: [ { noteId, yearMonth, contentId, content }, ... ] }`
- `POST /kv/comment/content/batchFind`：`{ noteId: Long, commentContentKeys: [ { yearMonth, contentId }, ... ] }`
- `POST /kv/comment/content/delete`：`{ noteId: Long, yearMonth: String, contentId: String }`

#### A4.5 Note（核心业务）

接口来源：`NoteController`、`*ReqVO/*RspVO`。

- `POST /note/publish`（建议：返回 `data=noteId`，方便验收）
  - Request：`{ type, imgUris?, videoUri?, title?, content?, topicId? }`
- `POST /note/detail`
  - Request：`{ id: Long }`
  - Response.data（来自 `FindNoteDetailRspVO`）：
    - `id, type, title, content, imgUris, topicId, topicName, creatorId, creatorName, avatar, videoUri, updateTime, visible`
- `POST /note/update`：`{ id, type, imgUris?, videoUri?, title?, content?, topicId? }`
- `POST /note/delete`：`{ id }`
- `POST /note/like`：`{ id }`
- `POST /note/unlike`：`{ id }`
- （可选）`POST /note/collect`：`{ id }`
- （可选）`POST /note/uncollect`：`{ id }`
- （可选）`POST /note/top`：`{ id, isTop }`
- （可选）`POST /note/visible/onlyme`：`{ id }`

#### A4.6 OSS

- `POST /file/upload`（multipart/form-data）
  - form field：`file`
  - Response.data：String（url）

### A5. MySQL DDL（已修正可执行版）

说明：下面 DDL 来自 `xiaohashu/sql/xiaohashu_init.sql`。  
已修正点：`leaf_alloc.step` 原文件写成了重复 `COMMENT`，这里改成正确注释（不改会 SQL 语法错误）。

#### A5.1 Leaf（Segment）数据库：leaf_alloc

```sql
create database if not exists leaf
CHARACTER set utf8mb4 collate utf8mb4_unicode_ci;

use leaf;

CREATE TABLE `leaf_alloc` (
  `biz_tag` varchar(128)  NOT NULL DEFAULT '' COMMENT '区分业务，例如生成用户ID、生成笔记ID',
  `max_id` bigint(20) NOT NULL DEFAULT '1' COMMENT '该biz_tag目前所被分配的ID号段的最大值',
  `step` int(11) NOT null COMMENT '每次分配的号段长度',
  `description` varchar(256)  DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='美团Leaf-segment数据库方案分布式ID生成器';

insert into leaf_alloc(biz_tag, max_id, step, description)
values('leaf-segment-test', 1, 2000, 'Test leaf Segment Mode Get Id');

INSERT INTO `leaf`.`leaf_alloc` (`biz_tag`, `max_id`, `step`, `description`, `update_time`)
VALUES ('leaf-segment-xiaohashu-id', 10100, 2000, '小哈书 ID', now());

INSERT INTO `leaf`.`leaf_alloc` (`biz_tag`, `max_id`, `step`, `description`, `update_time`)
VALUES ('leaf-segment-user-id', 100, 2000, '用户 ID', now());

INSERT INTO `leaf`.`leaf_alloc` (`biz_tag`, `max_id`, `step`, `description`, `update_time`)
VALUES ('leaf-segment-comment-id', 1, 2000, '评论 ID', NOW());
```

#### A5.2 业务数据库：核心表（最小必需）

```sql
create database if not exists xiaohashu
CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
use xiaohashu;

CREATE TABLE `t_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `xiaohashu_id` varchar(15) NOT NULL COMMENT '小哈书号(唯一凭证)',
  `password` varchar(64) DEFAULT NULL COMMENT '密码',
  `nickname` varchar(24) NOT NULL COMMENT '昵称',
  `avatar` varchar(120) DEFAULT NULL COMMENT '头像',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `background_img` varchar(120) DEFAULT NULL COMMENT '背景图',
  `phone` varchar(11) NOT NULL COMMENT '手机号',
  `sex` tinyint DEFAULT '0' COMMENT '性别(0：女 1：男)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态(0：启用 1：禁用)',
  `introduction` varchar(100) DEFAULT NULL COMMENT '个人简介',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '逻辑删除(0：未删除 1：已删除)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_xiaohashu_id` (`xiaohashu_id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE `t_note` (
  `id` bigint(11) unsigned NOT NULL COMMENT '主键ID',
  `title` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `is_content_empty` bit(1) NOT NULL DEFAULT b'0' COMMENT '内容是否为空(0：不为空 1：空)',
  `creator_id` bigint(11) unsigned NOT NULL COMMENT '发布者ID',
  `topic_id` bigint(11) unsigned DEFAULT NULL COMMENT '话题ID',
  `topic_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '话题名称',
  `is_top` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否置顶(0：未置顶 1：置顶)',
  `type` tinyint(2) DEFAULT '0' COMMENT '类型(0：图文 1：视频)',
  `img_uris` varchar(660) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '笔记图片链接(逗号隔开)',
  `video_uri` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '视频链接',
  `visible` tinyint(2) DEFAULT '0' COMMENT '可见范围(0：公开,所有人可见 1：仅对自己可见)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `status` tinyint(2) NOT NULL DEFAULT '0' COMMENT '状态(0：待审核 1：正常展示 2：被删除(逻辑删除) 3：被下架)',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_creator_id` (`creator_id`),
  KEY `idx_topic_id` (`topic_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记表';

ALTER table t_note add column `content_uuid` varchar(36) CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '笔记内容UUID';

CREATE TABLE `t_note_like` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(11) NOT NULL COMMENT '用户ID',
  `note_id` bigint(11) NOT NULL COMMENT '笔记ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `status` tinyint(2) NOT NULL DEFAULT '0' COMMENT '点赞状态(0：取消点赞 1：点赞)',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id_note_id` (`user_id`,`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记点赞表';

CREATE TABLE `t_note_count` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `note_id` bigint unsigned NOT NULL COMMENT '笔记ID',
  `like_total` bigint DEFAULT '0' COMMENT '获得点赞总数',
  `collect_total` bigint DEFAULT '0' COMMENT '获得收藏总数',
  `comment_total` bigint DEFAULT '0' COMMENT '被评论总数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_note_id` (`note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记计数表';

CREATE TABLE `t_user_count` (
  `id` bigint(11) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint(11) unsigned NOT NULL COMMENT '用户ID',
  `fans_total` bigint(11) DEFAULT '0' COMMENT '粉丝总数',
  `following_total` bigint(11) DEFAULT '0' COMMENT '关注总数',
  `note_total` bigint(11) DEFAULT '0' COMMENT '发布笔记总数',
  `like_total` bigint(11) DEFAULT '0' COMMENT '获得点赞总数',
  `collect_total` bigint(11) DEFAULT '0' COMMENT '获得收藏总数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户计数表';
```

### A6. Cassandra DDL（KV）

Keyspace 名称：默认用 `xiaohashu`（也可以改，但要全项目统一）。

```sql
-- 笔记正文：单条查/写
CREATE TABLE note_content (
  id uuid PRIMARY KEY,
  content text
);

-- 评论正文：复合主键，支持批量查
CREATE TABLE comment_content (
  note_id bigint,
  year_month text,
  content_id uuid,
  content text,
  PRIMARY KEY ((note_id, year_month), content_id)
);
```

### A7. Redis Key 契约（Key / 类型 / TTL / 值）

#### A7.1 Note 详情缓存

- Key：`note:detail:<noteId>`（String）
- 值：`FindNoteDetailRspVO` 的 JSON（字段见 A4.5）
- TTL：
  - 正常数据：`86400 + random(0..86400)` 秒（1 天 + 随机 0~1 天）
  - NOT_FOUND：写入字符串 `"null"`，TTL 随机 `60~120` 秒
- 本地缓存（Caffeine）：
  - Key：noteId（Long）
  - Value：详情 JSON（String）
  - TTL：写入后 1 小时过期

#### A7.2 点赞 Bloom + ZSet

来源：`xiaohashu-note/.../RedisKeyConstants.java` + Lua 脚本。

- Bloom（RedisBloom 模块，必须支持 `BF.EXISTS/BF.ADD`）：
  - Key：`bloom:note:likes:<userId>`
  - TTL：`86400 + random(0..86400)` 秒
- ZSet（最近点赞的 100 条）：
  - Key：`user:note:likes:<userId>`
  - Member：`noteId`
  - Score：毫秒时间戳（epoch milli）
  - 规则：如果 size >= 100，先 `ZPOPMIN` 移除最早的一条，再 `ZADD`
  - TTL：`86400 + random(0..86400)` 秒（由 Lua 设置 `EXPIRE`）

#### A7.3 计数 Hash

来源：`xiaohashu-count/.../RedisKeyConstants.java`。

- Key：`count:note:<noteId>`（Hash）
  - Field：`likeTotal` / `collectTotal` / `commentTotal`
- Key：`count:user:<userId>`（Hash）
  - Field：`fansTotal` / `followingTotal` / `noteTotal` / `likeTotal` / `collectTotal`

### A8. RocketMQ 契约（Topic / Tag / 消费模型 / 消费者组 / 消息体）

**关键规则（来自手册第 7.3.1.5）：同一个 Topic 如果要被多条链路同时消费，必须用不同 consumerGroup（或用广播）。否则消息会被“分摊”，某条链路收不到。**

必需 Topic（本计划最小闭环必须具备）：
| Topic | Tag | Producer | Consumer（建议 consumerGroup） | 消费模型 | 消息体 |
| --- | --- | --- | --- | --- | --- |
| DeleteNoteLocalCacheTopic | 无 | Note | Note（`xhs_note_del_local_cache`） | BROADCASTING | `noteId`（字符串） |
| DelayDeleteNoteRedisCacheTopic | 无 | Note | Note（`xhs_note_delay_del_redis`） | CLUSTERING | `noteId`（字符串） |
| LikeUnlikeTopic | Like / Unlike | Note | A) 点赞关系落库（`xhs_note_like_to_db`）B) 点赞计数聚合（`xhs_count_like_agg`） | CLUSTERING | JSON：`{userId,noteId,type,noteCreatorId,createTime}` |
| CountNoteLike2DBTopic | 无 | Count | Count（`xhs_count_like_to_db`） | CLUSTERING | JSON：`[{creatorId,noteId,count}, ...]` |

补充（延时双删）：发送延时消息时使用 `delayLevel=1`（表示约 1 秒，依赖 RocketMQ broker 的 delay 配置）。

### A9. Nacos / 配置键名契约（不许改键名）

#### A9.1 Nacos bootstrap（约定）

每个服务都应该有类似的 bootstrap（示例来自 `xiaohashu-note/bootstrap.yml`）：
```yaml
spring:
  application:
    name: <服务名>
  profiles:
    active: dev
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        prefix: ${spring.application.name}
        group: DEFAULT_GROUP
        namespace: ""
        file-extension: yaml
        refresh-enabled: true
      discovery:
        enabled: true
        group: DEFAULT_GROUP
        namespace: ""
        server-addr: 127.0.0.1:8848
```

DataId 命名（Spring Cloud Alibaba 默认规则）：  
- `\<prefix>-\<profile>.\<file-extension>`（例如：`nexus-xhs-oss-dev.yaml`）

#### A9.2 Leaf（leaf.properties 键名，必须照抄）

```properties
leaf.segment.enable=true
leaf.jdbc.url=jdbc:mysql://127.0.0.1:3306/leaf?useUnicode=true&characterEncoding=utf-8&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai
leaf.jdbc.username=root
leaf.jdbc.password=root

leaf.snowflake.enable=true
leaf.snowflake.zk.address=127.0.0.1:2181
leaf.snowflake.port=2222
```

#### A9.3 OSS（Nacos 动态切换必须进 Nacos）

- `storage.type=minio|aliyun`（**必须**在 Nacos，才能热更新）
- `storage.minio.endpoint/accessKey/secretKey`
- `storage.aliyun-oss.endpoint/accessKey/secretKey`

---

## 附录B：本地默认环境表（docker-compose）

> 你现在没有线上/公司环境，因此这里给出“本地 docker-compose 默认值”。Codex agent 直接按这份表写配置即可。  
> 当你将来要接入真实环境：把本表替换成真实值（并按第 7 节“最少提问”确认 1~3 个关键点）。

### B1. Nacos（配置中心 + 注册发现）

| 项 | 值 |
| --- | --- |
| Nacos server-addr | `127.0.0.1:8848` |
| Nacos namespace | `""`（public） |
| Nacos group | `DEFAULT_GROUP` |
| 我是否有写配置权限 | `有` |

### B2. MySQL（leaf + 业务库）

| 项 | 值 |
| --- | --- |
| MySQL host | `127.0.0.1` |
| MySQL port | `3306` |
| MySQL username | `root` |
| MySQL password | `root` |
| 业务数据库名 | `xiaohashu`（建议不改；若要改，必须全局一致） |
| Leaf 数据库名 | `leaf`（建议不改） |

### B3. Redis（含 RedisBloom）

| 项 | 值 |
| --- | --- |
| Redis host | `127.0.0.1` |
| Redis port | `6379` |
| Redis password | `（空）` |
| Redis database | `0` |
| 是否支持 RedisBloom（BF.EXISTS） | `支持` |

### B4. Cassandra

| 项 | 值 |
| --- | --- |
| Cassandra contact-points | `127.0.0.1` |
| Cassandra port | `9042` |
| Cassandra keyspace | `xiaohashu`（建议不改；若要改，必须全局一致） |
| 是否允许建表 | `允许` |

### B5. RocketMQ

| 项 | 值 |
| --- | --- |
| RocketMQ NameServer | `127.0.0.1:9876` |
| 是否允许创建 Topic | `允许` |
| 若不允许创建 Topic：已存在 Topic 列表 | `N/A（本地允许创建）` |
| 需要的最小 Topic 列表（本计划） | `DeleteNoteLocalCacheTopic, DelayDeleteNoteRedisCacheTopic, LikeUnlikeTopic, CountNoteLike2DBTopic` |

### B6. Zookeeper（Leaf Snowflake 必需）

| 项 | 值 |
| --- | --- |
| Zookeeper 地址 | `127.0.0.1:2181` |

### B7. OSS（MinIO + Aliyun OSS）

| 项 | 值 |
| --- | --- |
| storage.type 初始值 | `minio` |
| MinIO endpoint | `http://127.0.0.1:9000` |
| MinIO accessKey | `minioadmin` |
| MinIO secretKey | `minioadmin` |
| MinIO bucketName | `xiaohashu`（建议别硬编码；放 Nacos 配置里） |
| Aliyun OSS endpoint | `oss-cn-shanghai.aliyuncs.com`（本地不测） |
| Aliyun OSS accessKey | `dummy-access-key`（本地不测） |
| Aliyun OSS secretKey | `dummy-secret-key`（本地不测） |
| Aliyun OSS bucketName | `dummy-bucket`（本地不测） |
