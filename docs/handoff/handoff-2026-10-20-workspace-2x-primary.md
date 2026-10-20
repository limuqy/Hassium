# 工作区交接：主目录转回 2.X，master 独立保留

日期：2026-10-20。状态：**分支与本地数据迁移完成，构建及启动准备检查通过；未启动游戏。**

按用户后续要求，两份调研计划、本交接、`.agents` 及配套开发指引和文档索引纳入 `2.0.0` 的本地文档提交，先不推送；master 工作区的未提交改动保持原状。

## 工作安排

用户决定暂时专注 2.X，最重点组合为 **1.20.1 Forge / 1.21.1 NeoForge**。新功能先在 `2.0.0` 推进，后续统一安排向 master / 26.x 迁移；当前不要求每项改动同时维护两线。

此安排不删除 master 的代码、支持能力、构建产物或存档。

## 当前目录

| 用途 | 分支 / HEAD | 本机路径 |
|---|---|---|
| 日常开发主目录 | `2.0.0` / `329e00de` | `D:\project\MC\Hassium` |
| master 保留工作区 | `master` / `beef2f69` | `C:\Users\xiaolin\.codex\worktrees\master-maintenance\Hassium` |
| 原 2.X 数据副本 | detached HEAD / `329e00de` | `C:\Users\xiaolin\.codex\worktrees\bugfix-2x\Hassium` |
| 本次迁移恢复材料 | 非 Git 工作区 | `D:\project\MC\hassium-workspace-migration-20261020` |

其他既有 worktree 未调整。上述 HEAD 是迁移基线，后续提交以实际 Git 状态为准。

## 已保留和迁移的内容

- 两条分支保持原提交历史。master 原有 `.agents/skills/hassium-manifold/SKILL.md` 的暂存状态保留；按用户追加要求，`.agents` 也复制到主目录，随后纳入上述 2.X 本地文档提交。
- 两份后续调研计划及 `AGENTS.md`、文档索引、版本重点的未提交修改已经迁回主目录。
- master 的根目录和各模块构建产物、`run`、日志、IDE 配置及 CodeGraph 索引复制到独立工作区；原本地数据另有恢复副本。
- 2.X 的根目录和各模块构建产物、`run`、日志及 IDE 配置复制回主目录。根 `build` 包含 **370,617 个文件，122.690 GiB**，包含历史测试数据和存档，不只发布 JAR。
- `build/release-prep/gradle-game17` 下指向全局 Gradle `caches`、`jdks`、`wrapper` 的三个目录联接按原目标重建，未展开复制或修改共享缓存。
- `logo-16x10.png`、`video/` 在主目录保留，并在 master 工作区保留副本。
- Gradle/Loom 可复用内容已保留或复制；进程锁文件不作为可移植缓存。编译与启动准备任务已在新位置运行，重新生成当前路径所需的配置。
- 活跃 IDE / 启动参数中的旧工作区路径已经调整。master 的旧 Forge IDE 入口归档；NeoForge 入口改为调用当前 Gradle `runClient` / `runServer`，不再使用历史 Loom 启动参数。历史日志中的旧路径不改写。
- master 的 CodeGraph 索引在新目录可读，状态检查通过；未把该索引用作 2.X 索引，也未自动为 2.X 建索引。

主目录 `neoforge/build/26_2`、`26_3` 尚保留少量被空闲 Gradle 进程占用的重复文件。完整副本已在 master 工作区和恢复目录中核对；2.X 使用独立的 `1_*` 输出目录，不依赖这些文件。本次未强杀 Java 进程或强删占用文件。

## 验证

1. 复制后逐目录核对文件长度与修改时间；根 `build` 和各模块复制结果无遗漏或内容长度差异。后续编译、配置路径调整造成的预期变化另记。
2. 两线 `run` 目录的 **4,511 个原有文件逐一 SHA-256 比对通过**，包括存档、配置和运行依赖。
3. 两边 `.agents/skills/hassium-manifold/SKILL.md` 内容一致。
4. 主目录下以下两次命令均 `BUILD SUCCESSFUL`：

   ```powershell
   .\gradlew.bat forge:compileJava forge:ideaSyncTask forge:configureLaunch forge:configureClientLaunch "-Pmc_ver=1.20.1" --console=plain
   .\gradlew.bat neoforge:compileJava neoforge:ideaSyncTask neoforge:configureLaunch neoforge:configureClientLaunch "-Pmc_ver=1.21.1" --console=plain
   ```

5. master 新工作区 `26.3` 的任务配置、Fabric 启动准备及 NeoForge 双端启动准备/启动脚本生成通过；任务依赖包含 NeoForge 编译。未声称重跑 master 全版本矩阵。
6. 未启动客户端/服务端，未运行游戏冒烟或功能回归；本次是工作区迁移。

主目录 `gradle.properties` 保留原来的默认 `mc_ver=1.21.1`；1.20.1 开发继续显式传入 `"-Pmc_ver=1.20.1"`。迁移不改变版本选择方式。

## 恢复材料

恢复目录保留 `git-recovery.json`、两条工作改动快照、复制/验证日志、`run-directory-hashes.json`、路径改写前文件及 master 本地数据。Git stash 中保留两个带 `workspace-migration-20261020` 名称的恢复快照；它们已应用到各自新位置，**不要再次直接 apply**。

原 2.X worktree 保留为数据副本，已释放 `2.0.0` 分支占用。当前实际开发位置是 `D:\project\MC\Hassium`。
