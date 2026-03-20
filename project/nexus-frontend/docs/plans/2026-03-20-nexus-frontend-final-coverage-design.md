# Nexus Frontend Content & Risk Coverage Layer Design Doc

## 1. 目标 (Goal)
完成对 Nexus API 的全量覆盖（除钱包模块），重点实现苹果风格的内容发布编辑器、媒体上传系统以及账号风险感知与申诉模块。

## 2. 接口封装设计 (API Architecture)
补全 `project/nexus/docs/frontend-api.md` 中缺失的模块：

### 2.1 内容发布模块 (`src/api/content.ts`)
- **API**: `POST /api/v1/content/publish` (正式发布)
- **API**: `PUT /api/v1/content/draft` (保存草稿)
- **API**: `POST /api/v1/media/upload/session` (媒体上传会话)
- **API**: `POST /file/upload` (备用文件上传)

### 2.2 风控模块 (`src/api/risk.ts`)
- **API**: `GET /api/v1/risk/user/status` (账号风险状态)
- **API**: `POST /api/v1/risk/appeals` (提交申诉)

## 3. UI 布局与动效设计 (Interaction Design)
### 3.1 底部 Dock 发布入口 (Integrated Publish Icon)
- **设计**: 在 Dock 容器中心增加一个 `+` 图标，保持与其他 Tab 的视觉权重一致。
- **动效**: 点击时，页面以 `apple-fade` 效果过渡到发布视图。

### 3.2 沉浸式编辑器 (Immersive Editor)
- **视觉**: 仿照 iOS 备忘录，白色背景，极简导航栏（取消/发布）。
- **交互**: 
  - 图片上传时，底部显示细长的进度条。
  - 自动保存逻辑：每隔 30s 或输入停止时自动调用 `saveDraft`。

### 3.3 风控状态感知 (Risk Awareness)
- **视觉**: 在 Profile 顶部显示“安全中心”，若 `riskStatus` 异常，则显示浅黄色警告条。
- **申诉**: 采用 iOS 典型的设置列表风格，引导用户输入理由。

## 4. 路由与收尾
- **`/publish`**: 发布编辑页。
- **`/settings/risk`**: 安全与申诉页。

## 5. 验收标准 (Success Criteria)
- [ ] 点击发布按钮，能够成功选择图片并显示上传进度。
- [ ] 帖子发布成功后，自动返回 Feed 首页并刷新列表。
- [ ] 若账号处于处罚状态，Profile 页面能够显式呈现该状态。
- [ ] 全站 API 文档中约 95% 的业务接口已在前端有对应的调用逻辑。
