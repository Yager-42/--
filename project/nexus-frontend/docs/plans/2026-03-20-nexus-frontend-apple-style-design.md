# Nexus Frontend Design Doc - Apple Editorial Style

## 1. 项目愿景 (Project Vision)
为 Nexus 项目打造一个极具“苹果味”的前端原型。结合苹果官网的极简美学（亮色系、呼吸感、毛玻璃）与主流社交 App（抖音、小红书）的沉浸式交互逻辑（吸附式滚动、卡片式布局）。

## 2. 技术栈 (Tech Stack)
- **核心框架**: Vue 3 (Composition API) + TypeScript
- **构建工具**: Vite
- **样式处理**: Vanilla CSS (CSS Variables + Flexbox/Grid)
- **动画库**: Motion One (轻量级，支持 Spring 动画)
- **状态管理**: Pinia (用于 Mock 数据流转)
- **路由**: Vue Router

## 3. 视觉规范 (UI Specification)
### 3.1 色彩与材质 (Color & Materials)
- **背景**: `#FFFFFF` (纯白)
- **卡片背景**: `#F5F5F7` (苹果标准浅灰)
- **文字主色**: `#1D1D1F` (深炭黑)
- **文字副色**: `#86868B` (灰色)
- **毛玻璃效果**: `background: rgba(255, 255, 255, 0.72); backdrop-filter: blur(20px);`

### 3.2 字体与排版 (Typography)
- **首选字体**: `PingFang SC`, `-apple-system`, `SF Pro Text`, `Inter`
- **标题级别**:
  - `Large Title`: 34pt / Semibold
  - `Headline`: 17pt / Semibold
  - `Body`: 17pt / Regular
- **对齐**: 严格的左对齐，配合大面积留白。

### 3.3 圆角与投影 (Corners & Shadows)
- **大圆角**: `border-radius: 28px` (用于内容卡片)
- **小圆角**: `border-radius: 12px` (用于按钮和头像)
- **投影**: 极淡的弥散投影 `0 4px 24px rgba(0,0,0,0.04)`。

## 4. 核心交互逻辑 (Core Interactions)
### 4.1 垂直吸附流 (Scroll Snapping)
- 容器配置 `scroll-snap-type: y mandatory`。
- 每一个帖子（Post Card）高度为 `100vh` 或 `calc(100vh - 80px)`，确保滑动时自动对齐。

### 4.2 展开式动画 (Expand Animation)
- 点击信息流中的卡片时，封面图会平滑地扩充至全屏，并弹出详情浮层，而非传统的页面跳转。
- 关闭详情时，卡片收缩回原位。

### 4.3 底部浮动 Dock (Floating Dock)
- 仿照 iOS 底部操作条，包含“首页”、“搜索”、“通知”、“我”。
- 始终固定在底部，带毛玻璃效果。

## 5. 页面结构 (Page Structure)
1. **Feed 页 (`/`)**: 核心交互区，垂直滑动浏览。
2. **详情页 (Overlay)**: 帖子正文、评论列表（底部弹出）。
3. **个人主页 (`/profile`)**: 网格视图，展示用户发布的帖子，视觉类似“照片” App。
4. **通知中心 (`/notifications`)**: 列表展示互动信息。

## 6. Mock 数据架构 (Data Mapping)
严格遵循 `project/nexus/docs/frontend-api.md` 定义的字段：
- `postId`, `authorName`, `avatarUrl`, `contentTitle`, `contentBody`, `mediaUrls`, `reactionCount`, `commentCount`。

## 7. 验收标准 (Success Criteria)
- [ ] 页面在移动端浏览器下能够顺畅地进行垂直吸附滚动。
- [ ] 所有交互按钮带有符合苹果审美的微动效。
- [ ] 毛玻璃导航栏和底部 Dock 效果在各种背景色下清晰美观。
- [ ] 代码结构清晰，易于后续接入真实 API。
