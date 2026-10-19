# 版本记录

## 0.0.2-alpha.2 — Nautilus Alpha 0.0.2-alpha.2（待发布）

计划 Git 标签为 `v0.0.2-alpha.2`，发布说明见[Nautilus Alpha 0.0.2-alpha.2](docs/releases/v0.0.2-alpha.2.md)。

- GitHub Actions 在推送主分支、版本标签及 Pull Request 时自动构建 Windows x64 MSI，支持手动运行，上传安装器及 SHA-256。
- Windows 打包默认生成 MSI，保留 EXE 选项；自动检测完整 x64 JDK 17+，支持手动指定 JDK 目录。
- 同步 Cargo、发布草稿默认标签、安装包名称及当前版本文档；保留此前预发布记录。
- 本地打包和发布检查已通过；Windows 原生构建及安装验证以实际 Actions 结果为准。

## 0.0.2-alpha.1 — Alpha 0.0.2-alpha.1（待发布）

计划 Git 标签为 `v0.0.2-alpha.1`，发布说明见[Alpha 0.0.2-alpha.1](docs/releases/v0.0.2-alpha.1.md)。

当前详细更新见[更新内容](更新内容.md)，文档整理日期为 2026-10-19；版本更新为 `0.0.2-alpha.1`。

- 增加 Windows 配置路径、原生账户 ACL、安装后启动路径和七尺寸 ICO；新增工程外构建与手动 Windows 安装器工作流。
- 输入框新增“联网搜索”气泡：环境默认值、海蓝色渐变、四平台独立模型及 Mixture 的真实搜索开关及失败回退。
- Mixture 新增内联“推荐”：专家团队优先高能力子模型，推荐团队兼顾效果与成本；适配已配置 key，填入可编辑草稿并保留确认流程。
- 设置齿轮改为居中矢量图形，共享动画节奏、可反向缓动、悬停渐变与退出清理，修正按钮按下和进度重显反馈。
- Mixture 改为两列拖拽面板：左列模型卡片，右列规划、执行、复核、汇总；支持拖动分配、替换、移除与排序，移除下拉框及目标表单。
- 保留后端角色调度、跨职责接替开关和阶段进度，桌面端按拖放角色生成工作指令。
- 新增 Claude 原生 Messages API 与 Opus 5.5、Fable 5.1、Sonnet 5.5、Haiku 5.5 四个预设，使用用户自己的 Anthropic key。
- 同步密钥配置、独立删除、侧栏动态折叠菜单、Mixture 子模型选择、滚动排版及四语提示。
- Mixture 扩展为四平台协作：轮流分配子节点，未分配节点的中间成员独立复核，失败节点转交其余已选模型，保留 SSE 阶段事件。
- 增加原生请求/响应、模型映射、密钥隔离、四成员分工、故障接替与桌面交互验证。
- 重整项目 README，新增中文更新说明与 Claude 界面预览，同步发布白名单并修正中文文件名的 Git 清单检查。

## 0.0.1-alpha.1 — Alpha 0.0.1（初始准备快照）

首个早期测试版本。GitHub 标签计划为 `v0.0.1-alpha.1`，Release 类型为 **Pre-release**。

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

已知限制与验证边界见[发布说明](docs/releases/v0.0.1-alpha.1.md)。
