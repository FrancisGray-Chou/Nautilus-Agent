# 版本记录

## 0.0.1-alpha.2 — Alpha 0.0.1（待发布）

首个早期测试版本。GitHub 标签计划为 `v0.0.1-alpha.2`，Release 类型为 **Pre-release**。

### 对话与模型

- Rust CLI 连续多轮输入，数字菜单切换 DeepSeek、OpenAI、Gemini。
- `/model` 切换，`/exit` 与 `/quit` 退出；切换保留历史。
- Gemini Flash、Argon 与本地自动选择预设，高强度思考及 Google Search 来源显示。
- Java Swing 桌面端，通过本地 JSON Lines v2 子进程协议调用 Rust。

### 桌面体验

- 深色界面、统一模型卡片、Gemini 动画展开及键盘导航。
- Markdown、表格、代码、链接和原始 Markdown 一键复制。
- 代码块独立复制、修改、行引用批注，以及相关问题的可选上下文增强。
- 点赞后的本地追问建议，支持编辑，保留草稿，不自动发送。
- 后台请求、错误恢复和新对话清理。

### 凭据与发布工程

- 当前系统用户独立的明文 `.env`，私有权限、更新和删除控制。
- 桌面端不回退使用工程/继承的开发者 API key。
- 离线 Rust 测试、Swing 测试和真实 Java↔Rust 协议集成测试。
- 重写三个 README；补充发布说明、CI、手动预发布草稿工作流与源码归档校验。

已知限制与验证边界见[发布说明](docs/releases/v0.0.1-alpha.2.md)。
