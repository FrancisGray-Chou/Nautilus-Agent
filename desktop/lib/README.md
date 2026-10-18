# 随附 Markdown 依赖

Nautilus 桌面端使用 **CommonMark Java 0.24.0** 解析 Markdown。三个 JAR 随源码包分发，启动及测试时不需要 Maven、IntelliJ 或额外的 Java 依赖下载。

[项目首页](../../README.md) · [桌面端](../README.md) · [上游项目](https://github.com/commonmark/commonmark-java)

## 组件

| Maven 坐标 | 文件 | 用途 |
| --- | --- | --- |
| `org.commonmark:commonmark:0.24.0` | `commonmark-0.24.0.jar` | CommonMark 解析和渲染基础 |
| `org.commonmark:commonmark-ext-gfm-tables:0.24.0` | `commonmark-ext-gfm-tables-0.24.0.jar` | GFM 表格 |
| `org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0` | `commonmark-ext-gfm-strikethrough-0.24.0.jar` | GFM 删除线 |

Nautilus 的 Swing 适配位于 `MarkdownRenderer.java` 和 `MarkdownPane.java`。适配代码控制 HTML 转义、链接、图片替代文本、代码缩进和原生操作按钮。

## 当前文件的来源

当前 JAR 从开发环境 IntelliJ 分发包内已有的 CommonMark 内容重新打包而来，只保留 `org/commonmark/**` 类、相关 Maven 元数据和许可材料；不包含 IntelliJ 或 Jewel 的实现类。

**这些文件不是 Maven Central 原始 JAR 的逐字节副本。** 文件名和 Maven 元数据标识版本为 0.24.0；[SHA256SUMS](SHA256SUMS) 记录本仓库实际分发文件的哈希。哈希用于检测内容变化，不构成独立的上游来源认证。

## 校验

在工程根目录运行发布检查，可同时验证 JAR 哈希、包命名空间和内嵌许可：

```bash
python3 scripts/check_release.py
```

仅校验本目录哈希时，macOS 可执行：

```bash
(cd desktop/lib && shasum -a 256 -c SHA256SUMS)
```

Linux 可将 `shasum -a 256` 替换为 `sha256sum`。

## 授权与再分发

这三个组件采用 **BSD 2-Clause**。完整版权声明、再分发条件及免责声明位于 [LICENSE-commonmark.txt](LICENSE-commonmark.txt)，各 JAR 中也保留对应许可。

发布源码包或后续二进制包时，应保留这些材料。第三方组件许可不替代 Nautilus 项目自身的许可证；项目级授权状态见[根 README](../../README.md)。

## 更新原则

1. 通过上游项目或 Maven Central 获取计划使用的三个组件，保持版本一致，记录来源。
2. 阅读该版本许可；如果替换成官方原始 JAR，同时更新上面的重新打包说明。
3. 更新 JAR、`SHA256SUMS` 及本文件，避免只修改文件名或校验值。
4. 运行 `./desktop/test.sh` 与 `python3 scripts/check_release.py`，检查表格、转义、代码换行、长行折行和代码块按钮。

本次 Alpha 准备保留现有依赖字节内容，没有在整理文档时顺带升级解析库。
