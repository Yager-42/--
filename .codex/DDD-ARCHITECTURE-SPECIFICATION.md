# DDD 分层架构编码规范

> 本规范基于六边形架构（Hexagonal Architecture）与领域驱动设计（DDD）原则，适用于 Java Spring Boot 项目。
> 可作为 Claude Agent 编码指导规范使用。

---

## 一、模块总览

项目采用 **Maven 多模块** 结构，共 6 个核心模块：

```
project-name/
├── project-name-api            # API 接口定义层
├── project-name-app            # 应用启动层
├── project-name-domain         # 领域核心层（核心业务逻辑）
├── project-name-infrastructure # 基础设施层（技术实现）
├── project-name-trigger        # 触发器层（HTTP/MQ/Job入口）
├── project-name-types          # 通用类型层（公共枚举、常量、异常）
└── pom.xml                     # 父POM
```

---

## 二、模块依赖关系

```
                    ┌─────────────────┐
                    │   trigger       │  ← HTTP Controller / MQ Listener / Job
                    └────────┬────────┘
                             │ 依赖
                             ▼
                    ┌─────────────────┐
                    │      api        │  ← 接口定义 + DTO
                    └────────┬────────┘
                             │ 依赖
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌───────────────┐   ┌─────────────────┐   ┌───────────────┐
│infrastructure │ → │     domain      │ ← │    types      │
└───────────────┘   └─────────────────┘   └───────────────┘
        │                    ▲
        │                    │
        └────────────────────┘
              实现接口
```

**依赖原则：**
- `domain` 不依赖任何其他业务模块（只依赖 `types`）
- `infrastructure` 实现 `domain` 定义的接口
- `trigger` 调用 `domain` 服务，不直接操作 `infrastructure`
- `api` 只定义接口契约，不包含实现逻辑

---

## 三、各模块详细规范

### 3.1 types 模块 - 通用类型层

**职责：** 存放全局通用的类型定义，被其他所有模块依赖。

**包结构：**
```
cn.{company}.types/
├── common/          # 通用常量
│   └── Constants.java
├── enums/           # 全局枚举
│   ├── ResponseCode.java
│   └── xxxEnumVO.java
├── event/           # 事件基类
│   └── BaseEvent.java
└── exception/       # 自定义异常
    └── AppException.java
```

**规范要求：**
- 枚举命名以 `EnumVO` 结尾，表示值对象语义
- 常量类使用 `public static final` 定义
- 异常类继承 `RuntimeException`，包含 `code` 和 `info` 属性

**示例：**
```java
@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {
    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数");

    private String code;
    private String info;
}
```

---

### 3.2 api 模块 - 接口定义层

**职责：** 定义对外暴露的服务接口契约，供 `trigger` 实现。

**包结构：**
```
cn.{company}.api/
├── dto/             # 数据传输对象
│   ├── xxxRequestDTO.java
│   └── xxxResponseDTO.java
├── response/        # 统一响应包装
│   └── Response.java
├── IxxxService.java # 服务接口定义
└── package-info.java
```

**规范要求：**
- 接口命名以 `I` 开头，`Service` 结尾
- DTO 命名以 `RequestDTO` / `ResponseDTO` 结尾
- 接口方法返回 `Response<T>` 统一包装

**示例：**
```java
public interface IMarketTradeService {
    Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(LockMarketPayOrderRequestDTO requestDTO);
    Response<SettlementMarketPayOrderResponseDTO> settlementMarketPayOrder(SettlementMarketPayOrderRequestDTO requestDTO);
}
```

---

### 3.3 domain 模块 - 领域核心层 ⭐

**职责：** 承载核心业务逻辑，是整个系统的心脏。

**包结构：**
```
cn.{company}.domain/
├── {聚合名称}/                    # 按聚合根划分子包
│   ├── adapter/                  # 适配器接口（依赖倒置）
│   │   ├── port/                 # 外部服务接口（调用外部系统）
│   │   │   └── IxxxPort.java
│   │   └── repository/           # 仓储接口（数据持久化）
│   │       └── IxxxRepository.java
│   ├── model/                    # 领域模型
│   │   ├── aggregate/            # 聚合对象
│   │   │   └── xxxAggregate.java
│   │   ├── entity/               # 实体对象
│   │   │   └── xxxEntity.java
│   │   └── valobj/               # 值对象
│   │       └── xxxVO.java
│   └── service/                  # 领域服务
│       ├── IxxxService.java      # 服务接口
│       ├── xxxServiceImpl.java   # 服务实现
│       └── {子功能}/             # 复杂逻辑拆分
│           ├── factory/          # 工厂类
│           ├── filter/           # 规则过滤器
│           └── node/             # 流程节点
```

**领域对象说明：**

| 类型 | 命名规范 | 说明 |
|------|----------|------|
| 聚合对象 | `xxxAggregate` | 多个实体的组合，封装业务一致性边界 |
| 实体对象 | `xxxEntity` | 具有唯一标识的领域对象 |
| 值对象 | `xxxVO` | 无唯一标识，描述事物特征的不可变对象 |
| 仓储接口 | `IxxxRepository` | 定义数据存取契约，由基础设施层实现 |
| 端口接口 | `IxxxPort` | 定义外部服务调用契约 |

**规范要求：**
1. **领域模型使用 Lombok 注解：** `@Data`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`
2. **值对象中的枚举以 `EnumVO` 结尾**
3. **仓储接口参数/返回值只能是领域对象**，不能暴露 PO（持久化对象）
4. **服务实现类标注 `@Service`**
5. **领域层不依赖 Spring 以外的框架**（如 MyBatis、Redis客户端）

**聚合对象示例：**
```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyOrderAggregate {
    /** 用户实体对象 */
    private UserEntity userEntity;
    /** 支付活动实体对象 */
    private PayActivityEntity payActivityEntity;
    /** 支付优惠实体对象 */
    private PayDiscountEntity payDiscountEntity;
    /** 已参与拼团量 */
    private Integer userTakeOrderCount;
}
```

**仓储接口示例：**
```java
public interface ITradeRepository {
    MarketPayOrderEntity queryMarketPayOrderEntityByOutTradeNo(String userId, String outTradeNo);
    MarketPayOrderEntity lockMarketPayOrder(GroupBuyOrderAggregate groupBuyOrderAggregate);
    GroupBuyProgressVO queryGroupBuyProgress(String teamId);
}
```

---

### 3.4 infrastructure 模块 - 基础设施层

**职责：** 提供技术实现，包括数据库访问、缓存、消息队列、外部服务调用等。

**包结构：**
```
cn.{company}.infrastructure/
├── adapter/                  # 适配器实现
│   ├── port/                 # 外部服务实现
│   │   └── xxxPort.java
│   └── repository/           # 仓储实现
│       ├── AbstractRepository.java
│       └── xxxRepository.java
├── dao/                      # 数据访问对象
│   ├── IxxxDao.java          # MyBatis Mapper接口
│   └── po/                   # 持久化对象
│       └── xxx.java
├── dcc/                      # 动态配置中心
├── event/                    # 事件发布
│   └── EventPublisher.java
├── gateway/                  # 外部网关调用
│   └── xxxService.java
└── redis/                    # Redis服务
    ├── IRedisService.java
    └── RedissonService.java
```

**规范要求：**
1. **仓储实现类标注 `@Repository`**
2. **PO 类只存在于此模块**，不向上层暴露
3. **仓储实现负责 PO ↔ Entity 转换**
4. **DAO 接口标注 `@Mapper`**
5. **事务注解 `@Transactional` 加在仓储实现方法上**

**仓储实现示例：**
```java
@Slf4j
@Repository
public class TradeRepository implements ITradeRepository {

    @Resource
    private IGroupBuyOrderDao groupBuyOrderDao;

    @Override
    public GroupBuyActivityEntity queryGroupBuyActivityEntityByActivityId(Long activityId) {
        // 查询 PO
        GroupBuyActivity groupBuyActivity = groupBuyActivityDao.queryGroupBuyActivityByActivityId(activityId);
        // 转换为 Entity 返回
        return GroupBuyActivityEntity.builder()
                .activityId(groupBuyActivity.getActivityId())
                .activityName(groupBuyActivity.getActivityName())
                .status(ActivityStatusEnumVO.valueOf(groupBuyActivity.getStatus()))
                .build();
    }

    @Transactional(timeout = 500)
    @Override
    public MarketPayOrderEntity lockMarketPayOrder(GroupBuyOrderAggregate aggregate) {
        // 聚合操作：多表写入在事务中完成
    }
}
```

**PO 类示例：**
```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GroupBuyOrder {
    private Long id;
    private String teamId;
    private Long activityId;
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
```

---

### 3.5 trigger 模块 - 触发器层

**职责：** 接收外部请求，转发给领域服务处理。

**包结构：**
```
cn.{company}.trigger/
├── http/                     # HTTP 接口
│   └── xxxController.java
├── job/                      # 定时任务
│   └── xxxJob.java
└── listener/                 # 消息监听
    └── xxxListener.java
```

**规范要求：**
1. **Controller 实现 `api` 模块定义的接口**
2. **Controller 只做参数校验和结果包装**，业务逻辑交给 Domain
3. **异常统一捕获，返回 `Response` 包装**
4. **Controller 标注 `@RestController`，添加 `@CrossOrigin`**

**Controller 示例：**
```java
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/trade/")
public class MarketTradeController implements IMarketTradeService {

    @Resource
    private ITradeLockOrderService tradeOrderService;

    @RequestMapping(value = "lock_order", method = RequestMethod.POST)
    @Override
    public Response<LockMarketPayOrderResponseDTO> lockMarketPayOrder(@RequestBody LockMarketPayOrderRequestDTO requestDTO) {
        try {
            // 参数校验
            if (StringUtils.isBlank(requestDTO.getUserId())) {
                return Response.<LockMarketPayOrderResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                        .build();
            }

            // 调用领域服务
            MarketPayOrderEntity result = tradeOrderService.lockMarketPayOrder(...);

            // 转换并返回
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(convertToDTO(result))
                    .build();
        } catch (AppException e) {
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("服务异常", e);
            return Response.<LockMarketPayOrderResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
```

---

### 3.6 app 模块 - 应用启动层

**职责：** 应用入口，整合所有模块，提供配置。

**目录结构：**
```
cn.{company}/
├── Application.java          # 启动类
├── config/                   # 配置类
│   ├── RedisClientConfig.java
│   ├── ThreadPoolConfig.java
│   └── RabbitMQConfig.java
└── package-info.java

resources/
├── application.yml           # 主配置
├── application-dev.yml       # 开发环境
├── application-test.yml      # 测试环境
├── application-prod.yml      # 生产环境
├── logback-spring.xml        # 日志配置
└── mybatis/
    ├── config/               # MyBatis配置
    └── mapper/               # Mapper XML
```

**规范要求：**
1. **启动类放在根包下**（确保组件扫描覆盖所有子包）
2. **按环境拆分配置文件**
3. **配置类集中管理第三方组件**

---

## 四、命名规范汇总

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 聚合对象 | `Xxx Aggregate` | `GroupBuyOrderAggregate` |
| 实体对象 | `XxxEntity` | `UserEntity`, `PayActivityEntity` |
| 值对象 | `XxxVO` | `GroupBuyProgressVO`, `NotifyConfigVO` |
| 领域枚举 | `XxxEnumVO` | `TradeOrderStatusEnumVO` |
| 全局枚举 | `ResponseCode` / `XxxEnumVO` | `ResponseCode` |
| 仓储接口 | `IXxxRepository` | `ITradeRepository` |
| 仓储实现 | `XxxRepository` | `TradeRepository` |
| 端口接口 | `IXxxPort` | `ITradePort` |
| 端口实现 | `XxxPort` | `TradePort` |
| 领域服务接口 | `IXxxService` | `ITradeLockOrderService` |
| 领域服务实现 | `XxxService` / `XxxServiceImpl` | `TradeLockOrderService` |
| DAO 接口 | `IXxxDao` | `IGroupBuyOrderDao` |
| PO 对象 | 与数据库表对应 | `GroupBuyOrder` |
| DTO 对象 | `XxxRequestDTO` / `XxxResponseDTO` | `LockMarketPayOrderRequestDTO` |
| Controller | `XxxController` | `MarketTradeController` |
| Job | `XxxJob` | `GroupBuyNotifyJob` |
| Listener | `XxxListener` | `TeamSuccessTopicListener` |

---

## 五、分层职责边界

```
┌─────────────────────────────────────────────────────────────────┐
│                        trigger (触发器层)                        │
│  职责：接收请求、参数校验、调用领域服务、包装响应                  │
│  禁止：直接操作数据库、包含业务逻辑                               │
└───────────────────────────────┬─────────────────────────────────┘
                                │ 调用
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        domain (领域层)                           │
│  职责：核心业务逻辑、领域模型、业务规则                           │
│  禁止：依赖具体技术实现、直接使用 DAO/Redis 客户端                │
└───────────────────────────────┬─────────────────────────────────┘
                                │ 通过接口调用
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   infrastructure (基础设施层)                    │
│  职责：技术实现、数据持久化、外部服务调用、PO-Entity 转换          │
│  禁止：包含业务逻辑、向上层暴露 PO 对象                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 六、常见设计模式应用

### 6.1 策略模式 - 多种算法实现

**场景：** 折扣计算有多种方式（满减、直减、折扣）

**结构：**
```
domain/{聚合}/service/discount/
├── IDiscountCalculateService.java    # 策略接口
├── AbstractDiscountCalculateService.java  # 抽象实现
└── impl/
    ├── MJCalculateService.java       # 满减
    ├── ZJCalculateService.java       # 直减
    └── ZKCalculateService.java       # 折扣
```

### 6.2 责任链模式 - 规则过滤

**场景：** 交易锁单需要多重规则校验

**结构：**
```
domain/{聚合}/service/lock/
├── factory/
│   └── TradeLockRuleFilterFactory.java  # 规则链工厂
└── filter/
    ├── ActivityUsabilityRuleFilter.java  # 活动可用性
    ├── UserTakeLimitRuleFilter.java      # 用户参与限制
    └── TeamStockOccupyRuleFilter.java    # 库存占用
```

### 6.3 工厂模式 - 对象创建

**场景：** 根据类型创建不同的服务实例

```java
@Component
public class DefaultActivityStrategyFactory {
    @Resource
    private Map<String, IDiscountCalculateService> discountCalculateServiceMap;

    public IDiscountCalculateService getService(String discountType) {
        return discountCalculateServiceMap.get(discountType);
    }
}
```

---

## 七、测试规范

**测试目录结构：**
```
project-name-app/src/test/java/
└── cn.{company}.test/
    ├── domain/              # 领域服务测试
    │   └── trade/
    │       └── ITradeLockOrderServiceTest.java
    ├── infrastructure/      # 基础设施测试
    │   └── dao/
    │       └── GroupBuyActivityDaoTest.java
    └── trigger/             # 接口测试
        └── MarketTradeControllerTest.java
```

**规范要求：**
1. 测试类命名：`XxxTest.java`
2. 测试方法命名：`test_方法名_场景描述`
3. 使用 `@SpringBootTest` 进行集成测试

---

## 八、Claude Agent 编码指导

### 新增功能时的检查清单：

1. **确定所属聚合：** 功能属于哪个领域聚合？
2. **定义领域模型：** 需要哪些 Entity、VO、Aggregate？
3. **定义仓储接口：** Domain 层定义 `IXxxRepository`
4. **实现仓储：** Infrastructure 层实现，处理 PO 转换
5. **实现领域服务：** Domain 层编写业务逻辑
6. **定义 API：** api 模块定义接口和 DTO
7. **实现 Controller：** trigger 层实现接口

### 禁止事项：

- ❌ 在 Controller 中编写业务逻辑
- ❌ 在 Domain 层直接使用 DAO 或 Redis 客户端
- ❌ 让 PO 类出现在 Domain 层
- ❌ 仓储接口返回 PO 对象
- ❌ 跨聚合直接调用（应通过领域服务）

### 推荐做法：

- ✅ 通过接口定义解耦
- ✅ 使用聚合对象封装事务边界
- ✅ 值对象表达业务概念
- ✅ 仓储负责对象转换
- ✅ 领域服务编排业务流程

---

1. 核心应用框架 (Core Framework)
业务的骨架，基于 Spring 生态构建微服务。

微服务框架: Spring Boot。

服务注册与发现 / 配置中心: Nacos。

RPC 远程调用: Dubbo。

网关层: Spring Cloud Gateway。

作用: 统一鉴权 (OAuth2 + JWT)、限流 (Sentinel)、灰度发布路由。

2. 数据存储层 (Data Persistence) - Polyglot Persistence

关系型数据库 (OLTP): MySQL，使用mybatis交互。

中间件: ShardingSphere-JDBC。

缓存中间件: Redis。

Java 客户端: Redisson。

图数据库: Neo4j。

用途: 用户关系服务域。存储关注、好友关系，处理“二度人脉”、“共同好友”等图查询。

文档/列式数据库: MongoDB。

搜索引擎: Elasticsearch。

对象存储: MinIO。

3. 分布式中间件 (Middleware)

消息队列: Apache RabbitMQ。

分布式任务调度: XXL-JOB。

---
每一个类文件都需要注释，包括作者和创建时间：
作者需要添加 @author {$authorName}，对于后续的修改者，请在原来的作者下面添加一行新的信息；
创建时间为该类的创建日期，格式是 @since yyyy-MM-dd，一旦文件被创建，该时间不可修改。
所有被 public 和 protected 修饰的类、方法、字段等都需要添加注释，注释风格请参考当前项目中已有的文件：
所有 @param 和 @return 的注释，最后都需要根据其类型添加引用，基本类型需要添加 {@code } 标记，其他需要添加 {@link } 标记。
所有半角字符和全角字符之间需要增加一个空格，来使得整体排版规整，方便超长内容的换行。

---
所有的新数据库表都放在docs文件夹下

---
当前的项目路仅为project\nexus

---
要么 Redisson 的分布式锁，要么使用数据库版本号字段做乐观锁，不要使用JVM自带的锁

*文档版本：v1.0*
*生成日期：2025-01-11*
*适用项目：Java Spring Boot DDD 架构*
