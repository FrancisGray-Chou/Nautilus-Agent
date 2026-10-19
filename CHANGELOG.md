# 版本记录

本文件记录 Nautilus 的功能与发布工程变化。版本是否已公开、安装包是否构建成功，以 GitHub Releases 和对应 Actions 运行结果为准。

## Nautilus Alpha 0.0.2-alpha.2 — 2026-10-19

版本号：`0.0.2-alpha.2` · 目标标签：`v0.0.2-alpha.2`。

### Windows 与安装包

- 前后端统一使用 `%APPDATA%\Nautilus` 保存用户配置，兼容中文及空格路径。
- 密钥存储使用 Windows 原生账户 ACL，限制目录与文件访问，支持轮换、删除及原子替换。
- 安装后的桌面程序从自身目录寻找 Rust 后端，随包提供 Java 运行时及 Nautilus 图标。
- Windows 打包默认生成 x64 MSI，可使用 `--installer-type exe` 输出 EXE；构建中间文件及安装包保存在工程之外。
- 自动寻找完整 x64 JDK 17+，支持 `--jdk-home`，统一使用同一套 `java`、`javac`、`jar`、`jpackage` 和 `jlink`。

### 自动构建与发布修复

- GitHub Actions 在推送至 `main`／`master`、推送 `v*` 标签及提交 Pull Request 时自动构建 MSI，也支持手动触发。
- 构建流程包含 Rust 原生测试、桌面回归、随附后端及 Java 运行时启动检查、MSI 数据库验证和 SHA-256 校验，成功后上传 Actions 产物。
- 发布检查和脚本统一按 UTF-8 读写文本；Windows 工作流及 CMD 入口为 Python 启用 UTF-8，修复中文文档触发 `charmap` 解码错误的问题。
- 使用 Git 换行规则规范 Windows CMD 文件，修复 CRLF 被行尾空白检查拦截的问题。
- 发布时保留远端演示目录和历史发布说明，演示 README 继续位于子目录；演示文件不进入源码包或 Windows 构建包。
- 修复发布回归测试的历史文件名冲突；CI 失败时输出失败步骤日志，分支推送遭遇未说明原因的服务端失败时有限重试。
- 增加 `--push-only`：只提交并推送默认分支，触发新构建，保留已有标签和 Release。

### 桌面体验与联网搜索

- 设置提供明亮、黑暗及随系统主题，保存主题选择并响应系统外观变化。
- 侧栏支持拖动调宽、模型图标模式及模型空间折叠；历史对话默认收起，显示模型类别并支持恢复续聊。
- 本地聊天记录保存草稿、代码修改和批注，单模型与 Mixture 上下文分别恢复。
- 窗口及 macOS Dock 使用 Nautilus 图标；统一圆角、间距、悬停、焦点反馈及共享动画时钟。
- 复用历史条目并缓存 Markdown 布局；修复滚轮反向、嵌套滚动及自动跟随抢回阅读位置的问题，提供“回到最新”操作。
- 输入框新增联网搜索气泡：开启渐变为海蓝色，关闭淡出为透明，读取环境默认配置并处理失败回退。
- Gemini、DeepSeek、OpenAI、Claude 独立模型及 Mixture 均接入各平台搜索路径，保留返回的来源信息。

### 验证与边界

- 最近本地验证记录：65 项 Rust 测试、Rust 格式及 Clippy 检查、14 组桌面回归、17 项发布回归和 12 项 Windows 打包回归通过。
- 编码回归包含 Windows 默认 CP1252 的模拟；发布回归包含带历史文件和演示目录的本地 Git 仓库、标签保护及仅推送修复流程。
- 本地检查不能替代 Windows 原生安装、升级、卸载、图形界面或 GitHub 实际构建验证；未执行真实付费模型 API 验证。

详见[本版发布说明](docs/releases/v0.0.2-alpha.2.md)、[Windows 指南](desktop/WINDOWS.md)及[发布流程](docs/RELEASING.md)。

## Nautilus Alpha 0.0.2-alpha.1 — 开发快照

- 新增 Claude 原生 Messages API、独立密钥管理、子模型菜单及四平台 Mixture 协作。
- Mixture 使用模型与角色两列拖拽布局，支持规划、执行、复核和汇总职责分配、成员排序及替换。
- 增加专家团队及推荐团队预设，按已配置平台填入可编辑草稿。
- Rust SSE 传输阶段、实际模型、节点状态及耗时，桌面同步显示动态进度。
- 统一菜单、齿轮、按钮及卡片的动画与生命周期清理，完善四语界面、Markdown、代码复制、修改和行号批注。

详见[开发快照说明](docs/releases/v0.0.2-alpha.1.md)。

## Nautilus Alpha 0.0.1 — 初始快照

- Rust CLI 与 Java Swing 桌面端接入 DeepSeek、OpenAI、Gemini，支持连续对话及模型切换。
- 提供 Gemini 折叠菜单、Google Search 来源、Markdown 渲染、代码操作和本地追问建议。
- 按系统账号管理用户密钥；加入桌面与桥接离线检查、源码归档、校验文件及 GitHub 发布流程。

详见[初始快照说明](docs/releases/v0.0.1-alpha.1.md)。
