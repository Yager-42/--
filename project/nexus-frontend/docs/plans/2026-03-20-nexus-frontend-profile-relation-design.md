# Nexus Frontend Profile & Relation Layer Design Doc

## 1. 目标 (Goal)
构建一个具备“苹果健康” (Apple Health) 风格的个人中心，实现用户资料查看、数据统计磁贴展示以及完整的社交关系（关注/取关/粉丝列表）功能。

## 2. 接口封装设计 (API Architecture)
对接 `project/nexus/docs/frontend-api.md` 中定义的以下模块：

### 2.1 用户资料模块 (`src/api/user.ts`)
- **API**: `GET /api/v1/user/me/profile` (我的资料)
- **API**: `GET /api/v1/user/profile` (他人资料)
- **API**: `POST /api/v1/user/me/profile` (更新资料)
- **数据结构**: `UserProfileResponseDTO { userId, nickname, avatar, bio, stats }`。

### 2.2 社交关系模块 (`src/api/relation.ts`)
- **API**: `POST /api/v1/relation/follow`
- **API**: `POST /api/v1/relation/unfollow`
- **API**: `GET /api/v1/relation/followers` (粉丝列表)
- **API**: `GET /api/v1/relation/following` (关注列表)
- **策略**: 关注状态采用实时响应，点击后立即切换按钮样式并同步后端。

## 3. UI 布局设计 (Apple Health Style Tiles)
### 3.1 个人主页顶部 (Profile Header)
- **头像**: `border-radius: 50%` 或苹果标准的超椭圆圆角，带 2px 的白色边框。
- **数据磁贴 (Data Tiles)**:
  - 布局: 横向 Flex 容器，三个子项。
  - 样式: `background: #f5f5f7; border-radius: 16px; padding: 12px;`。
  - 内容: 上层显示粗体数字，下层显示浅色小字标签。

### 3.2 关注按钮 (Relation Button)
- **未关注**: `background: var(--apple-accent); color: white;` (苹果蓝)。
- **已关注**: `background: #e5e5ea; color: #1d1d1f;` (浅灰色)。
- **动效**: 点击时触发 0.1s 的 `scale(0.96)` 回弹效果。

## 4. 路由逻辑
- **`/profile`**: 指向 `views/Profile.vue` (我的)。
- **`/user/:id`**: 指向 `views/Profile.vue` (他人的)。
- **`/relation/:type`**: 指向关注/粉丝列表页。

## 5. 验收标准 (Success Criteria)
- [ ] 进入个人中心时，能够正确拉取并显示当前登录用户的资料。
- [ ] 在他人的主页点击“关注”，按钮状态即时切换并成功调用后端接口。
- [ ] 磁贴数据区在移动端自适应排列，圆角和留白符合苹果视觉规范。
- [ ] 个人资料更新接口连通，修改昵称或签名后即时刷新 UI。
