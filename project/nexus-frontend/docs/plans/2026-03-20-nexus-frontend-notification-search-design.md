# Nexus Frontend Notification & Search Layer Design Doc

## 1. 目标 (Goal)
构建一个具备“Spotlight”交互感的顶部常驻搜索框，以及一个符合 iOS 风格的通知中心，实现与后端 `SearchController` 和 `InteractionController` 的全量对接。

## 2. 接口封装设计 (API Architecture)
对接 `project/nexus/docs/frontend-api.md` 中定义的以下模块：

### 2.1 搜索模块 (`src/api/search.ts`)
- **API**: `GET /api/v1/search` (执行搜索)
- **API**: `GET /api/v1/search/suggest` (搜索建议)
- **交互**: 输入时防抖 (Debounce) 调用 `suggest`，点击结果跳转至详情或个人主页。

### 2.2 通知模块 (`src/api/notification.ts`)
- **API**: `GET /api/v1/notification/list` (获取列表)
- **API**: `POST /api/v1/notification/read` (标记单条已读)
- **API**: `POST /api/v1/notification/read/all` (全部标记已读)
- **展示**: 分为“点赞”、“评论”、“关注”等类型，显示在统一的时间线中。

## 3. UI 布局与动效设计 (Interaction Design)
### 3.1 顶部展开搜索 (Persistent Expandable Search)
- **初始态**: 在 `TheNavBar` 右侧显示图标。
- **展开动效**: 点击图标，导航栏 Logo 向左平滑移出屏幕，搜索框从右侧平滑拉伸，背景为 `rgba(242, 242, 247, 0.8)` 且带毛玻璃。
- **联想列表**: 下拉菜单样式，背景模糊，点击词条即刻搜索。

### 3.2 通知中心 (Notification Center)
- **列表项**: `12px` 圆角白色背景，左侧 `40px` 头像，右侧显示动作图标（心形、气泡、人像）。
- **未读标记**: 左侧带有 `6px` 的苹果蓝点 (`#007aff`)。
- **已读逻辑**: 进入通知中心 2s 后或点击后，通过 `markAsRead` 接口同步后端并清除蓝点。

## 4. 路由与状态管理
- **`/notifications`**: 独立页面，展示所有通知。
- **`/search/results`**: 搜索结果展示页。

## 5. 验收标准 (Success Criteria)
- [ ] 搜索框能够平滑展开和收起，且不影响导航栏布局。
- [ ] 输入关键词时，能够正确显示来自后端的联想词。
- [ ] 通知列表能够正确展示来源头像、内容类型和时间。
- [ ] 点击“全部标记已读”后，界面上的所有蓝点消失，且后端状态同步成功。
