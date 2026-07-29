# kcp JiJ 内嵌卡点 — 捋清 (2026-07-29)

## 一、build 失败的根因(明错,neoforge/forge)

Loom `NestableJarGenerationTask.from`(`include` 配置的处理入口)逐个 artifact 要求:
- 要么 `variant.getOwner()` 是 `ModuleComponentIdentifier`(即 `group:name:version` 坐标),或
- 要么 `variant.getCapabilities()` 非空。

`include files(stripKcpTask)` 产出的是裸 `File` dependency,对应 `OwningFileComponentIdentifier`——
既非 module、也无 capability → 第 137 行 `if (moduleLocation == null) throw` → 报
`Attempted to nest artifact kcp-netty-1.6.2-no-netty-bootstrap.jar which is not a module component and has no capabilities.`

- 触发任务:`processIncludeJars`(只在 `:build`/`:remapJar` 链上,`compileJava` 和 `runServer` 冒烟不触发)。
- 引入提交:`e9a9e69`(feat(neoforge): strip kcp io.netty split-package)。该提交只验证了
  `compileJava` + `runServer` 冒烟,**从未跑过 `:build`**,所以从未暴露。
- 共用单一 architectury-loom 1.13.469 与此无关:这段 metadata 校验在 fabric-loom 各版本一致。

## 二、fabric 的隐藏暗错(绿但残缺)

fabric/build.gradle 里 kcp 只在 `implementation`,**不在 `include`**:
- `META-INF/jars/` 实测只有 `core-3.6.7.jar` / `toml-3.6.7.jar`(night-config,有坐标能内嵌)。
- **零 kcp 类**进入生产 jar(zstd 走 shade 进主 jar,kcp 没有任何路径进 jar)。

后果:玩家拿到 fabric jar 装 UDP 数据面 → `NoClassDefFoundError: io/jpower/kcp/netty/Kcp`,
"主控热切 / 加权分流 / UdpFailover" 等卖点全挂。dev `runServer` 不暴露是因为 implementation 在 dev classpath。

暗错因为 `:build` 不报、冒烟在 dev 环境跑(有 implementation)而被长期隐藏。

## 三、为何"只有 NeoForge 有问题"——其实是同一问题的两种表现

- neoforge/forge 走 `include` 路径(想给玩家开箱即用)→ 触发 Loom metadata 校验 → 明错挡住打包。
- fabric 没走 `include` → 不触发校验 → build 绿,但产物残缺 → 暗错。
- 两边本质是同一个问题:**怎么把剥离版 kcp 正确进生产 jar**。最终选定走 zstd-jni 同款 shade
  (不走 Loom JiJ),顺带修掉 fabric 暗错——见第五节。

## 四、"会不会覆盖游戏本体自带依赖"——答:不会

剥离后 jar(`neoforge/build/patched-kcp/kcp-netty-1.6.2-no-netty-bootstrap.jar`)实证:
- 顶层只剩 `io/jpower`(52 条目)+ MANIFEST
- `io/netty/` 残留 **0** —— 不再与 MC Netty 抢 `io.netty.bootstrap` 包
- 无任何第三方包(zstd/lz4/json/log/guava 都没有)

剥离版是纯 KCP 协议栈,运行期依赖面是 MC 自带的 Netty 4.1.x API(`Channel`/`EventLoop`/`Bootstrap`),
不"附带其他依赖覆盖游戏本体"。完整 kcp jar(未剥离)确含 `io.netty.bootstrap.Ukcp*` 会独占该包触发
CNFE——这正是剥离任务要消灭的部分,fabric 端若也走剥离 jar 则一并安全。

## 五、最终方案选定(2026-07-29 定):借鉴 zstd-jni 走 shade,不走 Loom JiJ

决策路径:本地 Maven 坐标(A)被否——给其他开发者/CI 留"先 publish 再 build"的隐性前置步骤,
协作代价系统性高;给裸 File 注入带 version 的 capability(B)经 Gradle API 验证不可行(File
dependency 的 variant 是 Gradle 合成的 default variant,无 capability 来源);旁路 Loom 手塞
`META-INF/jars/`(C)偏离官方 JiJ 语义、Forge 加载容忍度未验。最终采纳 D:照 zstd-jni 现成路径,
`jar { from(zipTree(patchedKcpJar)) }` 把剥离 jar 解包合并进主 jar 任务,随 Hassium mod 类一同加载。
完全不经过 `processIncludeJars`,永远不触发 metadata 校验;零协作代价(无需 publish/坐标/缓存层)。

## 六、三端落地实施

统一形态:每端各自保留 `stripKcpNettyBootstrapPackage` 任务(产出剥离 jar)与 `library files(stripKcpTask)`
dev game-layer 入口;**删掉** `include files(stripKcpTask)`,改在 build.gradle 末尾追加:

```groovy
jar {
    dependsOn stripKcpTask
    from(zipTree(patchedKcpJar.get().asFile)) {
        exclude 'META-INF/**'
    }
}
```

各端差异见 build.gradle 实际代码,要点:
- **neoforge** (neoforge/build.gradle):删 L105 `include` 行,末尾加 jar shade 块。`library files(stripKcpTask)`
  保留(dev game-layer),common 透传 exclude 保留。
- **forge** (forge/build.gradle):kcp 同款(同节末尾 jar shade 块)。**FCAP 配套拆分已在合并回
  master 时整段丢弃**(master config 重构已弃用 FCAP 路线),详见七节。
- **fabric** (fabric/build.gradle):此前 kcp 在 `implementation`(零剥离零内嵌),现补完整剥离任务
  (`kcpIncoming`/`patchedKcpJar`/`stripKcpTask`)+ `implementation files(stripKcpTask)` + 末尾 jar 块。
  fabric 无 SecureJarHandler 本不强制剥离,但为三端一致并修暗错,统一走剥离版 shade。

## 七、历史注:FCAP 曾触发同根问题(master 已弃用 FCAP 路线)

本卡点最初在 master-old 分支落地时,forge 1.20.6 段还用 `include files(stripFcapTask)` 把 FCAP
剥离 jar(剔 MixinExtras JiJ)走 JiJ——与 kcp 同型裸 File artifact,同样会触发
`NestableJarGenerationTask.from` 的 module-component 校验错。当时没暴露是因为 kcp 在 include
序列里先被校验报错挡住了 FCAP 那条路径,修完 kcp 后 FCAP 错误立刻浮出
(`forgeconfigapiport-forge-no-mixinextras-jij.jar which is not a module component`)。当时的修法
与 kcp 同款:删 `include files(stripFcapTask)`,jar shade 块追加 1.20.6 条件段 shade FCAP 主类;
`mixinextras-common` 的 `include`(有坐标)保留 JiJ。

**但 master 分支已不再走 FCAP 路线**:config 重构(`bed6af6`/`e50fc43`/`926cfeb`/`dd54c9f` 等)
改在 `forge/src/main/java/io/github/limuqy/mc/hassium/config/ForgeConfigRegistration.java` 直接
实现配置注册,源码再无 `fuzs.forgeconfigapiport` / `NeoForgeConfigRegistry` 引用,build.gradle
也无 `fcapIncoming`/`stripFcapTask`/`patchedFcapJar`/`mixinextras` 任何字样。把 master-old 合并回
master 时故**整段丢弃 FCAP 增加/拆分**(冲突取 ours),只保留 kcp shade 主修复。合并后 forge 1.20.6
产物实测 `META-INF/jars/` 为空(无 mixinextras JiJ),`builds_for=1.20.6` 仍正常升 `:build`。

本节留存作历史记录,说明这条"同根问题"曾存在及为何合并后消失,不再代表 master 当前状态。

## 八、实测验证(2026-07-29)

三端 `:build` 实跑通过,生产 jar 内容实测(kcp 类计数 / io/netty 残留 0 / 必要 JiJ 仍在):

| 段 | 命令 | 结果 | kcp 类 | io/netty 残留 | JiJ 剩余 |
|---|---|---|---|---|---|
| neoforge 1.20.1 | `neoforge:build -Pmc_ver=1.20.1` | **BUILD SUCCESSFUL** | 55 | 0 | 无(不走 JiJ) |
| forge 1.20.6 | `forge:build -Pmc_ver=1.20.6` | **BUILD SUCCESSFUL** | 55 | 0 | 无(master 已弃 FCAP 路线,`META-INF/jars/` 实测为空) |
| fabric 1.20.1 | `fabric:build -Pmc_ver=1.20.1` | **BUILD SUCCESSFUL** | 修缮前 0 → 修缮后 55 | 0 | night-config core/toml(JiJ) |

fabric 暗错实证修复:`Kcp.class` 在 `io/jpower/kcp/netty/Kcp.class` 见到,玩家 UDP 数据面不再 `NoClassDefFoundError`。
