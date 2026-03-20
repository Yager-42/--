# Nexus Frontend Auth & Network Layer Design Doc

## 1. 目标 (Goal)
构建一个高度解耦、强类型且符合 `project/nexus/docs/frontend-api.md` 规范的鉴权与网络请求层。

## 2. 网络请求架构 (Network Layer)
### 2.1 技术选型
- **库**: Axios (强大的拦截器支持)
- **类型**: TypeScript (严格定义请求/响应 DTO)

### 2.2 响应处理规范 (Response Handling)
严格对齐后端的 `Response<T>` 壳：
```typescript
interface ApiResponse<T = any> {
  code: string;
  info: string;
  data: T;
}
```
- **成功判定**: 只有 `code === "0000"` 才会解析并返回 `data`。
- **全局错误拦截**:
  - `0410` (用户停用) / `0401` (鉴权失败): 自动重定向至登录页并清除 Token。
  - `其他错误码`: 弹出苹果样式的 UI 提示 (Toast/Alert)。

### 2.3 鉴权注入 (Auth Injection)
- **拦截器**: 每次请求自动从 `localStorage` 或 `Pinia Store` 获取 `token`。
- **Header**: `Authorization: Bearer <token>`。

## 3. 账号体系设计 (Auth Module)
### 3.1 状态管理 (Pinia - useAuthStore)
- **State**: `token: string`, `userInfo: UserDTO | null`, `isLoggedIn: boolean`。
- **Actions**: `login(dto)`, `logout()`, `refreshProfile()`。

### 3.2 登录界面 (iCloud Minimalist Style)
- **布局**: 居中容器，大留白。
- **输入框**: 带苹果风格 Focus 态（蓝色外边框渐变）的输入组件。
- **按钮**: 带有 `loading` 状态和 `active` 缩放动效。

## 4. 接口映射方案 (API Mapping)
按照后端的模块进行文件划分：
- `src/api/auth.ts`: 对应 `AuthController`
- `src/api/feed.ts`: 对应 `FeedController`
- `src/api/interact.ts`: 对应 `InteractionController`

## 5. 验收标准 (Success Criteria)
- [ ] 调用登录接口成功后，Token 正确保存至 `localStorage`。
- [ ] 后续请求自动携带 `Authorization` 请求头。
- [ ] 后端返回非 `0000` 错误码时，前端有统一的视觉提示。
- [ ] 页面在无 Token 访问受限路径时自动跳转至登录页。
