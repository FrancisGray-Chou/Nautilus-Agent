# 测试与验证

[项目首页](../README.md) · [发布流程](RELEASING.md)

## 本地检查

在工程根目录执行：

```bash
cargo fmt --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
./desktop/test.sh
python3 scripts/check_release.py
python3 scripts/test_release.py
python3 scripts/package_release.py
```

发布脚本需要 Python 3.11+，仅依赖标准库；日常运行桌面或 CLI 不需要 Python。首次 Cargo 构建可能下载依赖；缓存准备后，测试不需要模型 API 网络访问。

## 覆盖范围

| 检查 | 验证内容 |
| --- | --- |
| Rust 单元测试 | 菜单输入、模型配置、自动路由、请求构建、响应与来源解析、凭据权限及删除 |
| 严格 Java 编译 | `--release 17 -Xlint:all -Werror` |
| 桌面交互 | 多轮对话、后台线程、失败恢复、切换回退、新对话、草稿和相关上下文发送 |
| Gemini 动画 | 真实 EDT 定时帧、中途反向、Esc/外部关闭、禁用状态和生命周期清理 |
| Markdown | GFM 表格、HTML 转义、链接、代码换行、长行折行和富文本尺寸 |
| 代码操作 | 原始全文与当前代码复制、草稿保存、行号批注、相关性与截断边界 |
| 凭据 UI | 输入遮蔽、明确确认、部分更新与删除控制 |
| Java↔Rust 集成 | 真实子进程协议、临时假 key 持久化、私有权限、轮换、无开发者 key 回退 |
| 发布检查 | 版本一致、文档相对链接、常见凭据格式、JAR 哈希/命名空间/许可 |
| 归档检查 | 源码白名单、排除私有配置和构建产物、ZIP/TAR 回读及 SHA-256 |

测试使用模拟模型回复及不可用的本地代理。凭据测试创建隔离目录和假 key，不会修改用户真实配置或工程 `.env`。代码中的成功 UI 回复来自模拟服务；真实子进程测试主要验证协议、凭据和离线错误路径。

发布扫描只检查已列入白名单的文本文件中的常见密钥格式，不是全面安全审计或第三方依赖漏洞审计。

## Alpha 0.0.1 验证记录

准备日期：2026-10-18。当前本地环境：macOS / arm64，Rust 1.98.1，Cargo 1.98.1，OpenJDK 25.0.1，Python 3.14.0。Java 使用 Java 17 目标编译；这不等于已在 JDK 17 运行时验证。

本轮本地结果：

| 项目 | 结果 |
| --- | --- |
| Rust 格式、编译和 Clippy | 通过，无 Clippy 警告 |
| Rust 单元测试 | 23 项通过（库 19、CLI 4） |
| Java 编译与桌面检查 | 严格编译通过；6 组测试入口全部通过 |
| 发布边界回归 | 7 项通过，包括敏感文件排除、版本/链接错误、JAR 篡改、符号链接及标签检查 |
| 发布文件清单 | 60 个文件通过检查，包括 3 个 README |
| ZIP / TAR 内容回读 | 通过，文件集合与内容一致 |
| 重复打包 | 相同快照重复生成的两个归档均逐字节一致 |
| 全新解压目录验证 | 使用本机已有 Cargo 依赖缓存，从源码重新构建 Rust bridge 并通过全部桌面测试 |
| GitHub 工作流 | YAML 语法检查通过；尚未上传执行 |

GitHub CI 尚未执行，不能把本地通过写成 GitHub Actions 已通过；Linux、其他架构、实际图形会话及三平台真实 API 调用仍需补充验证。

## 界面预览

桌面测试将组件绘制到 `desktop/build/`：

- `preview-welcome.png`、`preview-chat.png`、`preview-compact.png`
- `preview-gemini-expanded.png`、`preview-gemini-compact.png`
- `preview-markdown.png`、`preview-markdown-compact.png`、`preview-markdown-chat.png`
- `preview-actions.png`、`preview-actions-edited.png`、`preview-context.png`
- `preview-credentials.png`

这些图片使用测试数据。`docs/assets/` 中的两张截图是供 README 使用的选定快照；不包含真实 API key 或用户对话。

## 可选真实 API 冒烟测试

只有在测试者愿意使用自己的 key 并承担平台费用时执行：

1. 使用单独的测试配置目录启动桌面端，填写所需平台 key。
2. 每个平台各发送一个简单问题，再发送关联追问，检查上下文和模型标识。
3. 切换平台，确认历史是否按预期沿用。
4. Gemini 请求搜索一个需要实时信息的问题，检查实际来源；不把普通无来源回答记为搜索成功。
5. 测试替换/删除 key 后旧会话被清空，再删除隔离目录中的测试凭据。

记录系统、日期、模型和结果，不记录 key。真实 API 验证不属于当前离线测试结果。
