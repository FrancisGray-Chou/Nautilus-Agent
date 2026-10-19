# 发布 Alpha 0.0.2-alpha.1

[项目首页](../README.md) · [发布说明](releases/v0.0.2-alpha.1.md) · [测试](TESTING.md)

| 项目 | 值 |
| --- | --- |
| 展示名称 | Nautilus Alpha 0.0.2-alpha.1 |
| Cargo / 源码包版本 | `0.0.2-alpha.1` |
| Git 标签 | `v0.0.2-alpha.1` |
| GitHub 类型 | Draft + Pre-release，审阅完成后再公开 |
| 附件 | 源码 ZIP、源码 TAR.GZ、SHA256SUMS |
| GitHub 仓库 | `FrancisGray-Chou/Nautilus-Agent` |

## 当前准备边界

目标仓库为 [FrancisGray-Chou/Nautilus-Agent](https://github.com/FrancisGray-Chou/Nautilus-Agent)。本地已准备文档、版本元数据、发布检查、归档脚本、CI 和草稿工作流。最初的本地工作区没有提交记录；远程 `main` 已有 README 的历史，导入时应保留这些提交。发布状态以 GitHub 仓库中的提交、Actions 和 Releases 为准。

工作区归档可以提前审阅，但其 `SOURCE-MANIFEST.json` 会标记尚未提交/不干净的状态。**正式上传的归档应在目标提交和标签确定后重新生成**。项目级许可证仍待决定，本次没有增加默认许可证。

## 一条命令发布

在可以正常连接 GitHub、已安装 GitHub CLI 并登录的本机终端，从工程根目录执行：

```bash
python3 scripts/publish_github.py --publish
```

不带 `--publish` 时仅验证本地发布清单，不连接或修改远端。正式执行会在 `dist/` 中克隆远端默认分支，保留现有提交历史，只复制当前已检查的源码白名单并提交；创建本地标签、严格检查并重新生成干净提交的源码附件，推送分支并等待对应提交的 GitHub CI。CI 通过后才推送新标签，创建公开的 **Pre-release** 并上传 ZIP、TAR.GZ、SHA256SUMS。这里的显式发布命令对应维护者已决定公开预发布的情况；下方草稿流程仍可用于提前审阅。

脚本不会强制推送或移动已有标签。已有版本标签、远端额外跟踪文件、CI 失败或网络错误均停止后续发布；失败时保留 `dist/github-publish-*` 工作目录供检查，不宣称上传成功。若已经推送标签但 Release 创建失败，请从保留目录继续执行下方 CLI 创建 Release 的命令，避免重新创建同名标签。

## 1. 检查并生成本地测试包

执行 [TESTING.md](TESTING.md) 中的全部本地检查，然后运行：

```bash
python3 scripts/package_release.py
```

产物在 `dist/`。脚本使用显式白名单，不会把 `.env`、Git 元数据、用户配置、IDE 项目文件或 `target/`、`desktop/build/`、`desktop/out/` 放进源码包。随附 CommonMark JAR、许可与哈希会一起保留。

脚本回读 ZIP 和 TAR 校验文件内容，并生成 `SHA256SUMS`。同样的源码、提交状态及打包环境可重复生成相同归档；包内时间固定，不包含开发者主目录。

## 2. 建立目标仓库和首个提交

目标仓库地址已确定；项目级许可证仍由作者决定。不要使用包含访问 token 的 remote URL。

当前远程有已有历史。推荐先在新目录克隆，再仅复制发布白名单中的文件，避免把无关的本地根提交强行推到远程：

```bash
# 在包含准备好的源码的目录执行；目标目录已存在时 clone 会停止，不覆盖它。
git clone https://github.com/FrancisGray-Chou/Nautilus-Agent.git ../Nautilus-Agent-github
python3 - <<'PY'
import shutil
import sys
from pathlib import Path
sys.path.insert(0, 'scripts')
from check_release import ROOT, validate
target = (ROOT.parent / 'Nautilus-Agent-github').resolve()
for source in validate():
    destination = target / source.relative_to(ROOT)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
PY
cd ../Nautilus-Agent-github

# 先检查 git status 和暂存差异，确认没有凭据、IDE 文件和构建产物。
git add .gitignore .env.example Cargo.toml Cargo.lock README.md CHANGELOG.md 更新内容.md src desktop docs scripts .github
git diff --cached --stat
git diff --cached --check
git commit -m "Prepare Nautilus Alpha 0.0.2-alpha.1"
# 如有新建的 LICENSE，还需一并加入提交。不要强制推送或覆盖远程历史。
git push -u origin main
```

`.github/workflows/ci.yml` 会在 push / pull request 时执行 macOS、Linux 与 JDK 17 检查。它尚未在本地准备阶段运行于 GitHub，上传后需查看真实结果。

## 3. 固定版本标签

确认目标提交的 CI 结果，随后执行：

```bash
git tag -a v0.0.2-alpha.1 -m "Nautilus Alpha 0.0.2-alpha.1"
python3 scripts/check_release.py --tag v0.0.2-alpha.1
git push origin v0.0.2-alpha.1
```

严格标签检查要求版本一致、HEAD 与标签对应、工作区干净，且 Git 跟踪文件与发布白名单一致。不要移动已经对外发布的标签；修复应使用新的预发布版本号。

## 4. 创建 GitHub 草稿

### GitHub Actions（已准备）

在目标仓库默认分支的 Actions 中选择 **Draft Alpha release** → **Run workflow**，填写已推送的标签 `v0.0.2-alpha.1`。

工作流重新检出该标签，运行可复用的双平台 CI，严格校验标签与工作区，生成源码包，再创建 **Draft + Pre-release** 并上传三个附件。它不会自动公开发布，也不会覆盖已有 Release。

### GitHub CLI（可选）

在自己的环境安装并登录 GitHub CLI 后，检出目标标签，重新打包：

```bash
git switch --detach v0.0.2-alpha.1
python3 scripts/check_release.py --tag v0.0.2-alpha.1
python3 scripts/package_release.py
gh release create v0.0.2-alpha.1 \
  dist/nautilus-0.0.2-alpha.1-source.zip \
  dist/nautilus-0.0.2-alpha.1-source.tar.gz \
  dist/SHA256SUMS \
  --verify-tag --draft --prerelease \
  --title "Nautilus Alpha 0.0.2-alpha.1" \
  --notes-file docs/releases/v0.0.2-alpha.1.md
```

没有 CLI 时，也可在 GitHub Releases 页面选择现有标签，粘贴发布说明并附加三个文件，勾选 Pre-release 后保存草稿。

## 5. 公开前审阅

- 标签对应已检查的提交，归档清单标记 `clean_worktree: true`，附件哈希可验证。
- 发布说明准确列出平台与测试边界，不把模拟回复写成真实 API 已验证。
- 确认项目级授权选择，保留第三方依赖声明。
- 从归档解压构建，确认文档、依赖和启动脚本齐全。
- 如补做真实 API 测试，记录已验证项目；不要上传凭据或私人对话。

审阅后由维护者在 GitHub 草稿中执行 Publish release，保留 Pre-release 标记。准备说明中的本地状态记录不会自动更新为远程发布状态。

参考：[GitHub Release 管理](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)、[gh release create](https://cli.github.com/manual/gh_release_create)。
