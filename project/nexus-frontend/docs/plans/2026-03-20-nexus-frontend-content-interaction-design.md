# Nexus Frontend Content & Interaction Layer Design Doc

## 1. 目标 (Goal)
将静态视觉原型连接至真实后端接口（`FeedController` & `InteractionController`），并实现具备“苹果呼吸感”的高频互动组件（点赞/评论）。

## 2. 接口封装设计 (API Architecture)
基于前期搭建的 Axios 封装，实现以下业务接口：

### 2.1 Feed 模块 (`src/api/feed.ts`)
- **API**: `GET /api/v1/feed/timeline`
- **DTO**: 
  - 请求: `{ cursor, limit, feedType }`
  - 响应: `FeedTimelineResponseDTO { items, nextCursor }`
- **功能**: 支持基于 Cursor 的无限滚动。

### 2.2 互动模块 (`src/api/interact.ts`)
- **API**: `POST /api/v1/interact/reaction` (点赞)
- **API**: `POST /api/v1/interact/comment` (发布评论)
- **API**: `GET /api/v1/comment/list` (获取评论列表)
- **策略**: 采用“乐观更新”(Optimistic UI) 保证客户端操作的即时响应性，若接口报错则回滚状态。

## 3. 状态管理 (Pinia - useFeedStore)
- **职责**: 管理当前 Timeline 的数据流，避免每次进出详情页重新请求。
- **State**:
  - `posts: FeedItemDTO[]` (当前流的帖子列表)
  - `nextCursor: string | null` (分页游标)
  - `isLoading: boolean` (加载状态)
- **Actions**:
  - `fetchNextPage()`: 加载下一页。
  - `optimisticLike(postId: string)`: 乐观更新点赞状态。

## 4. UI 动效设计 (Apple Breathing Feedback)
### 4.1 点赞按钮 (Reaction Button)
- **交互**: 
  - 未点赞态: 灰色轮廓，`opacity: 0.6`。
  - 点击态: 
    - 颜色过渡: 0.3s 平滑变为实心苹果红/蓝。
    - 形变: `scale(0.95)` 到 `scale(1.05)` 的微量回弹 (Spring)。
    - 光晕: 产生极其短暂的弥散阴影，模拟“呼吸”发光。

### 4.2 评论浮层 (Comment Bottom Sheet)
- **材质**: 背景采用 `rgba(255, 255, 255, 0.8)` 配合 `backdrop-filter: blur(24px)`。
- **动效**: 列表项采用 `stagger` 渐次进入的动画，保持视觉的流畅连贯。

## 5. 验收标准 (Success Criteria)
- [ ] 页面滚动到底部时，自动触发 `fetchNextPage` 获取新数据并追加。
- [ ] 点赞操作能够立即改变前端 UI 状态，无需等待接口返回。
- [ ] 在详情页点开评论，评论浮层带毛玻璃效果并能正常展示和发布评论。
