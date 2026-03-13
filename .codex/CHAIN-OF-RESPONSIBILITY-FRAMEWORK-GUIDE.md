# 责任链模式框架使用指南

> 本文档总结了项目中基于 `xfg-wrench-starter-design-framework` 框架的责任链模式实现方式，可用于指导 Claude Agent 在其他项目中实现类似的责任链完成业务逻辑。

## 目录

1. [框架概述](#1-框架概述)
2. [框架依赖](#2-框架依赖)
3. [责任链模式一：线性链（Model2 - 推荐）](#3-责任链模式一线性链model2---推荐)
4. [责任链模式二：单实例链（Model1）](#4-责任链模式二单实例链model1)
5. [责任链模式三：树形策略路由（Tree Strategy Router）](#5-责任链模式三树形策略路由tree-strategy-router)
6. [最佳实践总结](#6-最佳实践总结)
7. [实现清单](#7-实现清单)

---

## 1. 框架概述

本项目使用的责任链框架提供了三种模式：

| 模式 | 适用场景 | 核心类 | 特点 |
|------|----------|--------|------|
| **Model2 线性链** | 规则过滤、流程校验 | `ILogicHandler` + `LinkArmory` + `BusinessLinkedList` | Spring Bean 托管、线程安全、推荐使用 |
| **Model1 单实例链** | 简单链式调用 | `AbstractLogicLink` + `ILogicLink` | 手动组装、适合简单场景 |
| **树形策略路由** | 复杂决策树、多分支流程 | `AbstractMultiThreadStrategyRouter` + `StrategyHandler` | 支持动态路由、多线程预加载 |

---

## 2. 框架依赖

在 `pom.xml` 中添加依赖：

```xml
<!-- 扳手工程；设计模式框架 -->
<dependency>
    <groupId>cn.bugstack.wrench</groupId>
    <artifactId>xfg-wrench-starter-design-framework</artifactId>
    <version>3.0.0</version>
</dependency>
```

**依赖来源**：https://gitcode.net/KnowledgePlanet/ai-agent-station

---

## 3. 责任链模式一：线性链（Model2 - 推荐）

### 3.1 核心概念

- **`ILogicHandler<T, D, R>`**：责任链节点接口，泛型参数：
  - `T`：请求参数类型
  - `D`：动态上下文类型（用于节点间数据传递）
  - `R`：返回结果类型

- **`LinkArmory<T, D, R>`**：链装配器，用于将多个处理器组装成链

- **`BusinessLinkedList<T, D, R>`**：业务链对象，可直接调用 `apply()` 执行

### 3.2 实现步骤

#### 步骤1：定义请求参数实体

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeLockRuleCommandEntity {
    private Long activityId;
    private String userId;
    private String teamId;
}
```

#### 步骤2：定义返回结果实体

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeLockRuleFilterBackEntity {
    private Integer userTakeOrderCount;
    private String recoveryTeamStockKey;
}
```

#### 步骤3：定义动态上下文（用于节点间数据传递）

```java
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public static class DynamicContext {
    // 在责任链执行过程中需要传递的数据
    private GroupBuyActivityEntity groupBuyActivity;
    private Integer userTakeOrderCount;
}
```

#### 步骤4：实现责任链节点（Filter）

**节点1：活动可用性校验**

```java
@Slf4j
@Service
public class ActivityUsabilityRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-活动的可用性校验{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        // 1. 执行业务逻辑
        GroupBuyActivityEntity groupBuyActivity = repository.queryGroupBuyActivityEntityByActivityId(requestParameter.getActivityId());

        // 2. 校验不通过则抛出异常，中断链执行
        if (!ActivityStatusEnumVO.EFFECTIVE.equals(groupBuyActivity.getStatus())) {
            throw new AppException(ResponseCode.E0101);
        }

        // 3. 将数据写入动态上下文，供后续节点使用
        dynamicContext.setGroupBuyActivity(groupBuyActivity);

        // 4. 调用 next() 继续执行下一个节点
        return next(requestParameter, dynamicContext);
    }
}
```

**节点2：用户参与限制校验**

```java
@Slf4j
@Service
public class UserTakeLimitRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-用户参与次数校验{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        // 从上下文获取前置节点设置的数据
        GroupBuyActivityEntity groupBuyActivity = dynamicContext.getGroupBuyActivity();

        Integer count = repository.queryOrderCountByActivityId(requestParameter.getActivityId(), requestParameter.getUserId());

        if (null != groupBuyActivity.getTakeLimitCount() && count >= groupBuyActivity.getTakeLimitCount()) {
            throw new AppException(ResponseCode.E0103);
        }

        dynamicContext.setUserTakeOrderCount(count);

        return next(requestParameter, dynamicContext);
    }
}
```

**节点3：终止节点（返回最终结果）**

```java
@Slf4j
@Service
public class TeamStockOccupyRuleFilter implements ILogicHandler<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> {

    @Resource
    private ITradeRepository repository;

    @Override
    public TradeLockRuleFilterBackEntity apply(TradeLockRuleCommandEntity requestParameter, TradeLockRuleFilterFactory.DynamicContext dynamicContext) throws Exception {
        log.info("交易规则过滤-组队库存校验{} activityId:{}", requestParameter.getUserId(), requestParameter.getActivityId());

        String teamId = requestParameter.getTeamId();
        if (StringUtils.isBlank(teamId)) {
            // 直接返回结果，不再调用 next()
            return TradeLockRuleFilterBackEntity.builder()
                    .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                    .build();
        }

        // 业务逻辑处理...
        boolean status = repository.occupyTeamStock(teamStockKey, recoveryTeamStockKey, target, validTime);

        if (!status) {
            throw new AppException(ResponseCode.E0008);
        }

        // 最后一个节点直接返回结果
        return TradeLockRuleFilterBackEntity.builder()
                .userTakeOrderCount(dynamicContext.getUserTakeOrderCount())
                .recoveryTeamStockKey(recoveryTeamStockKey)
                .build();
    }
}
```

#### 步骤5：创建工厂类组装责任链

```java
@Slf4j
@Service
public class TradeLockRuleFilterFactory {

    /**
     * 使用 @Bean 注册责任链，Spring 容器托管
     */
    @Bean("tradeRuleFilter")
    public BusinessLinkedList<TradeLockRuleCommandEntity, DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter(
            ActivityUsabilityRuleFilter activityUsabilityRuleFilter,
            UserTakeLimitRuleFilter userTakeLimitRuleFilter,
            TeamStockOccupyRuleFilter teamStockOccupyRuleFilter) {

        // 使用 LinkArmory 组装责任链，按参数顺序执行
        LinkArmory<TradeLockRuleCommandEntity, DynamicContext, TradeLockRuleFilterBackEntity> linkArmory =
                new LinkArmory<>("交易规则过滤链",
                        activityUsabilityRuleFilter,    // 第1个执行
                        userTakeLimitRuleFilter,        // 第2个执行
                        teamStockOccupyRuleFilter);     // 第3个执行

        return linkArmory.getLogicLink();
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {
        private GroupBuyActivityEntity groupBuyActivity;
        private Integer userTakeOrderCount;
    }
}
```

#### 步骤6：在服务中使用责任链

```java
@Slf4j
@Service
public class TradeLockOrderService implements ITradeLockOrderService {

    @Resource
    private BusinessLinkedList<TradeLockRuleCommandEntity, TradeLockRuleFilterFactory.DynamicContext, TradeLockRuleFilterBackEntity> tradeRuleFilter;

    @Override
    public MarketPayOrderEntity lockMarketPayOrder(UserEntity userEntity, PayActivityEntity payActivityEntity, PayDiscountEntity payDiscountEntity) throws Exception {

        // 构建请求参数
        TradeLockRuleCommandEntity commandEntity = TradeLockRuleCommandEntity.builder()
                .activityId(payActivityEntity.getActivityId())
                .userId(userEntity.getUserId())
                .teamId(payActivityEntity.getTeamId())
                .build();

        // 执行责任链，传入请求参数和空的动态上下文
        TradeLockRuleFilterBackEntity result = tradeRuleFilter.apply(
                commandEntity,
                new TradeLockRuleFilterFactory.DynamicContext()
        );

        // 使用责任链返回的结果
        Integer userTakeOrderCount = result.getUserTakeOrderCount();
        // ... 后续业务处理
    }
}
```

---

## 4. 责任链模式二：单实例链（Model1）

### 4.1 核心概念

- **`AbstractLogicLink<T, D, R>`**：抽象链节点，通过继承实现
- **`ILogicLink<T, D, R>`**：链接口
- **手动组装**：通过 `appendNext()` 方法串联节点

### 4.2 实现步骤

#### 步骤1：实现链节点

```java
@Slf4j
@Service
public class RuleLogic101 extends AbstractLogicLink<String, DynamicContext, String> {

    @Override
    public String apply(String requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("link model01 RuleLogic101");
        // 继续下一个节点
        return next(requestParameter, dynamicContext);
    }
}
```

```java
@Slf4j
@Service
public class RuleLogic102 extends AbstractLogicLink<String, DynamicContext, String> {

    @Override
    public String apply(String requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("link model01 RuleLogic102");
        // 最后一个节点，返回结果
        return "处理完成";
    }
}
```

#### 步骤2：创建工厂组装链

```java
@Service
public class Rule01TradeRuleFactory {

    @Resource
    private RuleLogic101 ruleLogic101;
    @Resource
    private RuleLogic102 ruleLogic102;

    public ILogicLink<String, DynamicContext, String> openLogicLink() {
        // 手动组装链：101 -> 102
        ruleLogic101.appendNext(ruleLogic102);
        return ruleLogic101;
    }
}
```

#### 步骤3：使用链

```java
@Test
public void test_model01() throws Exception {
    ILogicLink<String, DynamicContext, String> logicLink = factory.openLogicLink();
    String result = logicLink.apply("请求参数", new DynamicContext());
    log.info("结果: {}", result);
}
```

### 4.3 Model1 vs Model2 对比

| 特性 | Model1 (AbstractLogicLink) | Model2 (ILogicHandler) |
|------|---------------------------|------------------------|
| 组装方式 | 手动 `appendNext()` | `LinkArmory` 自动组装 |
| Spring 集成 | 需要手动管理 | `@Bean` 自动托管 |
| 线程安全 | 需注意单例问题 | 天然线程安全 |
| 推荐程度 | 简单场景 | **推荐用于生产** |

---

## 5. 责任链模式三：树形策略路由（Tree Strategy Router）

### 5.1 核心概念

适用于复杂的多分支决策场景，如：
- 根据条件动态选择下一个处理节点
- 支持多线程预加载数据
- 可以构建复杂的处理树

核心类：
- **`AbstractMultiThreadStrategyRouter<T, D, R>`**：抽象策略路由器
- **`StrategyHandler<T, D, R>`**：策略处理器接口

### 5.2 实现步骤

#### 步骤1：创建抽象支撑类

```java
public abstract class AbstractGroupBuyMarketSupport<MarketProductEntity, DynamicContext, TrialBalanceEntity>
        extends AbstractMultiThreadStrategyRouter<MarketProductEntity, DynamicContext, TrialBalanceEntity> {

    protected long timeout = 5000;

    @Resource
    protected IActivityRepository repository;

    @Override
    protected void multiThread(MarketProductEntity requestParameter, DynamicContext dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException {
        // 可选：多线程预加载数据
    }
}
```

#### 步骤2：实现根节点

```java
@Slf4j
@Service
public class RootNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DynamicContext, TrialBalanceEntity> {

    @Resource
    private SwitchNode switchNode;

    @Override
    protected TrialBalanceEntity doApply(MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("RootNode 执行参数校验");

        // 参数校验
        if (StringUtils.isBlank(requestParameter.getUserId())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }

        // 路由到下一个节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DynamicContext, TrialBalanceEntity> get(
            MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 返回下一个要执行的节点
        return switchNode;
    }
}
```

#### 步骤3：实现分支节点（动态路由）

```java
@Slf4j
@Service
public class MarketNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DynamicContext, TrialBalanceEntity> {

    @Resource
    private ErrorNode errorNode;
    @Resource
    private TagNode tagNode;

    @Override
    protected void multiThread(MarketProductEntity requestParameter, DynamicContext dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException {
        // 多线程预加载数据
        FutureTask<GroupBuyActivityDiscountVO> task1 = new FutureTask<>(() -> repository.queryActivity());
        FutureTask<SkuVO> task2 = new FutureTask<>(() -> repository.querySku());

        threadPoolExecutor.execute(task1);
        threadPoolExecutor.execute(task2);

        dynamicContext.setGroupBuyActivityDiscountVO(task1.get(timeout, TimeUnit.MILLISECONDS));
        dynamicContext.setSkuVO(task2.get(timeout, TimeUnit.MILLISECONDS));
    }

    @Override
    public TrialBalanceEntity doApply(MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 执行业务逻辑
        GroupBuyActivityDiscountVO config = dynamicContext.getGroupBuyActivityDiscountVO();
        if (null == config) {
            return router(requestParameter, dynamicContext);
        }
        // ... 业务处理
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<MarketProductEntity, DynamicContext, TrialBalanceEntity> get(
            MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 根据条件动态选择下一个节点
        if (null == dynamicContext.getGroupBuyActivityDiscountVO()) {
            return errorNode;  // 无营销配置，走错误节点
        }
        return tagNode;  // 正常流程，走标签节点
    }
}
```

#### 步骤4：实现终止节点

```java
@Slf4j
@Service
public class EndNode extends AbstractGroupBuyMarketSupport<MarketProductEntity, DynamicContext, TrialBalanceEntity> {

    @Override
    public TrialBalanceEntity doApply(MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 组装最终返回结果
        return TrialBalanceEntity.builder()
                .goodsId(dynamicContext.getSkuVO().getGoodsId())
                .payPrice(dynamicContext.getPayPrice())
                .build();
    }

    @Override
    public StrategyHandler<MarketProductEntity, DynamicContext, TrialBalanceEntity> get(
            MarketProductEntity requestParameter, DynamicContext dynamicContext) throws Exception {
        // 返回默认处理器，表示链结束
        return defaultStrategyHandler;
    }
}
```

#### 步骤5：创建工厂类

```java
@Service
public class DefaultActivityStrategyFactory {

    private final RootNode rootNode;

    public DefaultActivityStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<MarketProductEntity, DynamicContext, TrialBalanceEntity> strategyHandler() {
        return rootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {
        private GroupBuyActivityDiscountVO groupBuyActivityDiscountVO;
        private SkuVO skuVO;
        private BigDecimal payPrice;
    }
}
```

#### 步骤6：使用树形策略

```java
@Service
public class IndexGroupBuyMarketServiceImpl implements IIndexGroupBuyMarketService {

    @Resource
    private DefaultActivityStrategyFactory defaultActivityStrategyFactory;

    @Override
    public TrialBalanceEntity indexMarketTrial(MarketProductEntity marketProductEntity) throws Exception {
        // 获取策略处理器（根节点）
        StrategyHandler<MarketProductEntity, DynamicContext, TrialBalanceEntity> strategyHandler =
                defaultActivityStrategyFactory.strategyHandler();

        // 执行策略树
        return strategyHandler.apply(marketProductEntity, new DynamicContext());
    }
}
```

### 5.3 树形结构示意

```
RootNode (参数校验)
    │
    ▼
SwitchNode (开关/降级判断)
    │
    ▼
MarketNode (营销计算 + 多线程预加载)
    │
    ├── [无配置] ──▶ ErrorNode (异常处理)
    │
    └── [有配置] ──▶ TagNode (人群标签判断)
                        │
                        ▼
                    EndNode (返回结果)
```

---

## 6. 最佳实践总结

### 6.1 模式选择指南

| 场景 | 推荐模式 |
|------|----------|
| 线性规则过滤（如参数校验、权限校验） | Model2 线性链 |
| 简单的链式调用 | Model1 单实例链 |
| 复杂决策树、多分支流程 | 树形策略路由 |
| 需要多线程预加载数据 | 树形策略路由 |

### 6.2 设计原则

1. **职责单一**：每个节点只处理一个校验/业务逻辑
2. **上下文传递**：使用 `DynamicContext` 在节点间传递数据，避免重复查询
3. **快速失败**：校验不通过时抛出异常，中断链执行
4. **日志记录**：每个节点入口记录关键参数，便于问题排查
5. **终止节点**：最后一个节点直接返回结果，不再调用 `next()`

### 6.3 命名规范

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 过滤器/处理器 | `Xxx[Rule]Filter` | `ActivityUsabilityRuleFilter` |
| 工厂类 | `XxxRuleFilterFactory` | `TradeLockRuleFilterFactory` |
| 动态上下文 | `DynamicContext`（内部类） | `Factory.DynamicContext` |
| 请求实体 | `XxxCommandEntity` | `TradeLockRuleCommandEntity` |
| 返回实体 | `XxxBackEntity` 或 `XxxResponse` | `TradeLockRuleFilterBackEntity` |
| 树形节点 | `XxxNode` | `RootNode`, `SwitchNode`, `EndNode` |

---

## 7. 实现清单

当需要实现责任链时，按以下清单检查：

### Model2 线性链清单

- [ ] 定义请求参数实体 (`XxxCommandEntity`)
- [ ] 定义返回结果实体 (`XxxBackEntity`)
- [ ] 在工厂类中定义 `DynamicContext` 内部类
- [ ] 实现各个过滤器节点 (`implements ILogicHandler<T, D, R>`)
- [ ] 每个节点使用 `@Service` 注解
- [ ] 节点中注入所需的 Repository/Service
- [ ] 创建工厂类，使用 `@Bean` 方法组装链
- [ ] 在服务类中注入 `BusinessLinkedList` 并调用 `apply()`

### 树形策略路由清单

- [ ] 创建抽象支撑类继承 `AbstractMultiThreadStrategyRouter`
- [ ] 定义 `DynamicContext` 动态上下文
- [ ] 实现各个节点类，重写 `doApply()` 和 `get()` 方法
- [ ] 节点中使用 `router()` 路由到下一节点
- [ ] 使用 `get()` 方法根据条件返回下一个节点
- [ ] 终止节点返回 `defaultStrategyHandler`
- [ ] 创建工厂类返回根节点
- [ ] 服务类中调用 `strategyHandler.apply()` 执行

---

## 附录：Import 语句参考

```java
// Model2 线性链
import cn.bugstack.wrench.design.framework.link.model2.LinkArmory;
import cn.bugstack.wrench.design.framework.link.model2.chain.BusinessLinkedList;
import cn.bugstack.wrench.design.framework.link.model2.handler.ILogicHandler;

// Model1 单实例链
import cn.bugstack.wrench.design.framework.link.model1.AbstractLogicLink;
import cn.bugstack.wrench.design.framework.link.model1.ILogicLink;

// 树形策略路由
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
```

---

*文档生成日期：2026-01-05*
*基于项目：group-buy-market*
*框架版本：xfg-wrench-starter-design-framework 3.0.0*
