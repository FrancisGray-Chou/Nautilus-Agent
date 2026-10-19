# Windows 兼容与安装包构建

当前版本：`0.0.2-alpha.2`。目标为 Windows 10 / 11 x64；本地 macOS 回归不能替代 Windows 原生 ACL、安装和卸载验证。

## 已实现的兼容逻辑

- Rust 密钥、Java 主题、语言和历史统一使用 `%APPDATA%\Nautilus`；缺少 `APPDATA` 时回退到用户目录的 `AppData\Roaming\Nautilus`。保留 `NAUTILUS_PROFILE_DIR` 覆盖选项。
- Windows 密钥文件使用原生 Win32 ACL：确认当前账户所有权，拒绝重解析点、符号链接及硬链接，关闭继承，只为当前用户保留完整控制。密钥仍为用户主动确认的明文存储，同一账户的其他程序和管理员仍可能读取。
- 先为目录和临时文件设置权限，再写密钥；关闭句柄、同步数据后替换，支持更新与删除。
- 打包后的 Java 从自身 JAR 所在目录寻找后端，支持中文及空格路径。源码调试目录仍兼容。
- 发布版后端隐藏独立控制台；安装器、启动器和窗口使用 Nautilus 图标，ICO 含七种尺寸。

## GitHub Actions 自动生成 MSI

工作流：[Windows MSI installer](../.github/workflows/windows-installer.yml)。将源码及 `.github/workflows` 上传到 GitHub 后，以下操作会触发构建：

- 推送提交到 `main` 或 `master`。
- 推送 `v*` 版本标签，例如 `v0.0.2-alpha.2`。
- 向 `main` 或 `master` 提交或更新 Pull Request。
- 在仓库 **Actions → Windows MSI installer → Run workflow** 手动运行（入口需工作流已在默认分支）。

工作流在 Windows Server 2022 x64 上准备完整 Temurin JDK 17、Python 3.12、Rust MSVC 和 WiX 3，执行原生 Rust、桌面及打包后运行时检查，再生成 MSI，检查其数据库及 SHA-256。构建无需模型 API key。

成功后，在该次运行的 **Summary → Artifacts** 下载 `Nautilus-Windows-x64-MSI-运行编号`，解压 ZIP 即可获得 `.msi` 安装器及 `.msi.sha256`。运行摘要也会提供下载链接，产物保留 30 天。工作流不自动创建 GitHub Release。

## 在 Windows 本地生成安装器

构建机需要 Python 3.11+、Rust stable MSVC、Visual Studio C++ Build Tools / Windows SDK、完整的 x64 JDK 17+ 和 WiX 3.x。Rust、WiX 命令须在 PATH；JDK 会从 `JAVA_HOME`、PATH 及常见安装目录自动查找。这些是构建工具，最终用户不需要安装。

在工程根目录运行，或双击 `desktop\build-windows.cmd`：

```powershell
py -3 scripts\package_windows.py
```

遇到 `Missing from PATH: jar` 时，通常需要完整 JDK，只有 JRE 或 `java.exe` 不足以构建。新版构建脚本会查找同一目录内的 `java.exe`、`javac.exe`、`jar.exe`、`jpackage.exe`、`jlink.exe`，确认 x64 与最低版本后仅为本次进程设置 PATH，不改系统环境变量。

也可以明确指定 JDK 根目录（不是 `bin`）：

```powershell
py -3 scripts\package_windows.py --jdk-home "C:\Program Files\Eclipse Adoptium\你的JDK目录"
```

构建过程中会打印 `Using JDK: ...`。如果未安装完整 JDK，请先安装后重新运行。`javac` 的参数文件读取也固定为 UTF-8，避免 Windows 中文路径乱码。

默认在 Windows 实际桌面目录生成：

- `Nautilus-0.0.2-alpha.2-Windows-x64-Setup.msi`
- 同名 `.msi.sha256` 校验文件

默认生成 MSI；若需要原来的 EXE 格式，可传入 `--installer-type exe`。

安装器包含 Java 运行时与 Rust 后端，按当前用户安装，提供开始菜单、桌面快捷方式和卸载入口。构建过程先执行 Rust 测试、桌面回归（包括 Windows 账户 ACL）、Java/Rust SSE 联调及打包后运行时冒烟测试，成功后才复制安装器。

所有构建中间文件位于系统临时目录。可用 `--output-dir D:\Installers` 指定工程外的目录；脚本拒绝把产物输出到工程内。默认 SSE；受限环境可用 `--transport stdio` 选择管道联调。

## 其他系统导出构建包

```bash
python3 scripts/package_windows.py --prepare-only --output-dir /tmp/Nautilus-Windows-Output
```

这只输出源码与构建脚本 ZIP，**不是安装包，也不是可运行的 Windows 应用**。包含当前白名单源码、依赖锁文件、图标、构建入口和逐文件校验，不包含开发者 `.env`、密钥、历史记录或本机编译产物。将其解压到 Windows 构建机后运行上面的命令。

仓库提供自动及手动触发的 Windows MSI 工作流，使用方式见本文开头。本地创建工作流文件不会自动上传源码或启动远程任务。

## 验证边界

原生 Windows 构建必须通过：账户专属受保护 DACL、密钥轮换及硬链接拒绝、中文/空格安装路径、首次启动、四平台配置、聊天历史恢复、系统主题、快捷方式、安装升级和卸载。未在 Windows 成功运行这些检查前，不应把构建包描述为已验证的安装器。
