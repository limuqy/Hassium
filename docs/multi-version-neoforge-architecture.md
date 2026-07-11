# Hassium 多版本兼容 + NeoForge 引入技术架构方案

## 1. 背景分析

### 1.1 当前状态

| 项目 | 值 |
|---|---|
| MC 版本 | 1.20.1 |
| Java 版本 | 17 |
| 支持加载器 | Fabric 0.92.1, Forge 47.2.30 |
| 构建插件 | fabric-loom 1.13-SNAPSHOT, net.neoforged.moddev.legacyforge 2.0.77 |
| 平台抽象 | Java ServiceLoader |

### 1.2 NeoForge 与 Forge 的关系

- **NeoForge** 是 Forge 的继任者，从 MC 1.20.2 开始分叉
- Forge 继续维护 1.20.1 及更早版本，1.20.2+ 由 NeoForge 接手
- 两者的 API 在分叉后逐渐分化，但核心概念（事件系统、注册机制、Mixin 使用）保持相似
- 对于 1.20.1：只有 Forge，没有 NeoForge
- 对于 1.20.2+：只有 NeoForge，Forge 停止更新

### 1.3 关键约束

1. common 模块必须保持加载器无关（不能 import forge/neoforge/fabric 特定类）
2. Forge 和 NeoForge 的 Mixin 注入点基本相同（都基于 Minecraft 源码），但访问转换器格式不同
3. 多版本支持时，Minecraft 源码在版本间有变化，Mixin 注入点可能需要调整
4. zstd-jni 依赖在所有平台通用，无需版本特定处理

---

## 2. 目标模块结构

### 2.1 方案一：按加载器分模块（推荐，当前方案扩展）

```
Hassium/
├── common/                    # 共享代码（加载器无关 + 版本无关）
├── fabric/
│   ├── 1.20.1/                # Fabric 1.20.1 特定代码
│   └── 1.21.4/                # Fabric 1.21.4 特定代码
├── forge/
│   └── 1.20.1/                # Forge 仅支持 1.20.1（Forge 停更）
├── neoforge/
│   ├── 1.20.4/                # NeoForge 1.20.4
│   └── 1.21.4/                # NeoForge 1.21.4
└── buildSrc/
```

**优点**：结构清晰，每个子模块独立构建
**缺点**：模块数量多，Gradle 配置复杂

### 2.2 方案二：源集分离（推荐，更适合当前项目）

```
Hassium/
├── common/                              # 共享代码（所有版本、所有加载器）
│   └── src/main/java/...               # 加载器无关的核心逻辑
├── common-1.20.1/                       # 1.20.1 版本特定的 common 代码
│   └── src/main/java/...
├── common-1.21/                         # 1.21+ 版本特定的 common 代码
│   └── src/main/java/...
├── fabric/
│   ├── src/main/java/...               # Fabric 通用代码
│   ├── src/main-1.20.1/java/...        # Fabric 1.20.1 特定
│   └── src/main-1.21/java/...          # Fabric 1.21+ 特定
├── forge/
│   └── src/main/java/...               # Forge 1.20.1（不需版本分离）
├── neoforge/
│   ├── src/main/java/...               # NeoForge 通用代码
│   ├── src/main-1.20.4/java/...        # NeoForge 1.20.4 特定
│   └── src/main-1.21/java/...          # NeoForge 1.21+ 特定
└── buildSrc/
```

**优点**：模块数量可控，源集按版本灵活组合
**缺点**：构建脚本稍复杂

### 2.3 推荐方案：方案二

采用方案二，原因：
- 与当前 multiloader 模板兼容性最好
- Forge 只需支持 1.20.1，无需版本分离
- NeoForge 和 Fabric 通过源集处理版本差异
- common 模块可按版本拆分为 common（核心）+ common-version（版本适配层）

---

## 3. 详细模块设计

### 3.1 common 模块

```
common/
├── src/main/java/io/github/limuqy/mc/hassium/
│   ├── api/                  # 公共 API（不变）
│   ├── storage/              # 存储逻辑（不变）
│   ├── compression/          # 压缩逻辑（不变）
│   ├── cache/                # 缓存逻辑（不变）
│   ├── network/              # 网络协议（不变）
│   ├── config/               # 配置（不变）
│   ├── metrics/              # 指标（不变）
│   ├── migration/            # 迁移（不变）
│   ├── mixin/                # Mixin 定义（可能需要版本分支）
│   └── platform/             # 平台抽象（不变）
│       ├── Services.java
│       └── services/
│           ├── IPlatformHelper.java
│           ├── INetworkManagerService.java
│           └── IClientChunkApplier.java
└── src/main/resources/
    ├── hassium.mixins.json
    └── META-INF/accesstransformer.cfg
```

**版本适配策略**：

对于 Mixin 注入点在版本间有变化的情况，使用条件编译：

```java
// 在 common 中定义抽象的 Mixin，通过版本特定模块覆盖
// 方案A：使用 @Mixin 的 remap=false + 版本特定子模块
// 方案B：在 buildSrc 中根据版本动态选择 Mixin 配置文件
```

### 3.2 fabric 模块

```
fabric/
├── src/main/java/.../
│   ├── platform/
│   │   ├── FabricPlatformHelper.java
│   │   ├── FabricNetworkManagerService.java
│   │   └── FabricClientChunkApplier.java
│   ├── mixin/                # Fabric 特定 Mixin
│   └── HassiumFabric.java    # Fabric 入口
├── src/main-1.20.1/java/...  # 1.20.1 特定代码
├── src/main-1.21/java/...    # 1.21+ 特定代码
└── src/main/resources/
    ├── fabric.mod.json
    ├── hassium.mixins.json
    ├── hassium.fabric.mixins.json
    └── META-INF/services/
```

### 3.3 forge 模块（仅 1.20.1）

```
forge/
├── src/main/java/.../
│   ├── platform/
│   │   ├── ForgePlatformHelper.java
│   │   ├── ForgeNetworkManagerService.java
│   │   └── ForgeClientChunkApplier.java
│   ├── mixin/                # Forge 特定 Mixin
│   └── HassiumForge.java     # Forge 入口
└── src/main/resources/
    ├── META-INF/mods.toml
    ├── hassium.mixins.json
    ├── hassium.forge.mixins.json
    └── META-INF/services/
```

### 3.4 neoforge 模块（1.20.4+）

```
neoforge/
├── src/main/java/.../
│   ├── platform/
│   │   ├── NeoForgePlatformHelper.java
│   │   ├── NeoForgeNetworkManagerService.java
│   │   └── NeoForgeClientChunkApplier.java
│   ├── mixin/                # NeoForge 特定 Mixin
│   └── HassiumNeoForge.java  # NeoForge 入口
├── src/main-1.20.4/java/...  # 1.20.4 特定
├── src/main-1.21/java/...    # 1.21+ 特定
└── src/main/resources/
    ├── META-INF/neoforge.mods.toml
    ├── hassium.mixins.json
    ├── hassium.neoforge.mixins.json
    └── META-INF/services/
```

---

## 4. 构建系统配置

### 4.1 settings.gradle 改造

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { name = 'Forge'; url = 'https://maven.minecraftforge.net/' }
        maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
        maven { name = 'NeoForge'; url = 'https://maven.neoforged.net/releases' }
        maven { name = 'Sponge Snapshots'; url = 'https://repo.spongepowered.org/repository/maven-public/' }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven { name = 'Aliyun Maven Public'; url = uri('https://maven.aliyun.com/repository/public') }
        maven { name = 'Fabric'; url = uri('https://maven.fabricmc.net/') }
        maven { name = 'Forge'; url = uri('https://maven.minecraftforge.net/') }
        maven { name = 'NeoForge'; url = uri('https://maven.neoforged.net/releases') }
        maven { name = 'Sponge Snapshots'; url = uri('https://repo.spongepowered.org/repository/maven-public/') }
        maven { name = 'ParchmentMC'; url = uri('https://maven.parchmentmc.org/') }
    }
}

rootProject.name = 'Hassium'
include("common")
include("fabric")
include("forge")
include("neoforge")   // 新增
```

### 4.2 gradle.properties 扩展

```properties
# Project
version=1.0.0-beta
group=io.github.limuqy.mc.hassium
java_version=17

# Common
minecraft_version=1.20.1
mod_name=Hassium
mod_author=limuqy
mod_id=hassium
license=GPL-3.0-or-later
credits=
description=ZSTD compression and chunk caching for Minecraft.
minecraft_version_range=[1.20.1, 1.22)
parchment_minecraft=1.20.1
parchment_version=2023.09.03

# Fabric
fabric_version=0.92.1+1.20.1
fabric_loader_version=0.16.9

# Forge
forge_version=47.2.30
forge_loader_version_range=[47,)

# NeoForge（新增）
neoforge_version=20.4.237
neoforge_loader_version_range=[20.4,)

# 多版本支持（新增）
# 构建目标版本列表，逗号分隔
# build_versions=1.20.1,1.20.4,1.21.4
```

### 4.3 build.gradle 插件声明

```groovy
plugins {
    id 'fabric-loom' version '1.13-SNAPSHOT' apply(false)
    id 'net.neoforged.moddev.legacyforge' version '2.0.77' apply(false)  // Forge 1.20.1
    id 'net.neoforged.moddev' version '2.0.77' apply(false)              // NeoForge 1.20.4+
}
```

### 4.4 neoforge/build.gradle

```groovy
plugins {
    id 'multiloader-loader'
    id 'net.neoforged.moddev'
}

neoForge {
    version = neoforge_version

    parchment {
        minecraftVersion = parchment_minecraft
        mappingsVersion = parchment_version
    }

    runs {
        client {
            client()
        }
        data {
            data()
            programArguments.addAll '--mod', mod_id, '--all', '--output',
                file('src/generated/resources/').getAbsolutePath(),
                '--existing', file('src/main/resources/').getAbsolutePath()
        }
        server {
            server()
        }
    }

    mods {
        "${mod_id}" {
            sourceSet sourceSets.main
        }
    }
}

sourceSets.main.resources.srcDir 'src/generated/resources'

dependencies {
    compileOnly project(":common")
    implementation group: 'com.github.luben', name: 'zstd-jni', version: '1.5.5-7'
    implementation 'org.lz4:lz4-java:1.8.0'
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT:processor")
}

jar {
    manifest.attributes([
        "MixinConfigs": "${mod_id}.mixins.json,${mod_id}.neoforge.mixins.json"
    ])
}
```

### 4.5 buildSrc 扩展

在 `multiloader-common.gradle` 中添加版本源集支持：

```groovy
// 根据目标 MC 版本动态添加源集
def mcMajor = minecraft_version.split('\\.')[1] // "20" or "21"
def versionSourceSet = "src/main-${minecraft_version}"
def majorSourceSet = "src/main-1.${mcMajor}"

if (file(versionSourceSet).exists()) {
    sourceSets.main.java.srcDir(versionSourceSet)
}
if (file(majorSourceSet).exists()) {
    sourceSets.main.java.srcDir(majorSourceSet)
}
```

---

## 5. 代码组织策略

### 5.1 加载器 API 差异处理

Forge 和 NeoForge 的核心差异：

| 特性 | Forge (1.20.1) | NeoForge (1.20.4+) |
|---|---|---|
| 事件总线 | `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| 注册机制 | `DeferredRegister` | `DeferredRegister`（相同） |
| 网络 | `SimpleChannel` | `PayloadRegistrar` |
| 配置 | `ForgeConfigSpec` | `NeoForgeConfig` / `ModConfigSpec` |
| Mod 入口 | `@Mod` 注解 | `@Mod` 注解（相同） |
| mods.toml | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| 访问转换器 | AT 格式 | AT 格式（相同） |

**处理方式**：

1. **平台接口层**：在 `common/platform/services/` 中定义的接口保持不变
2. **实现类**：分别在 `forge/platform/` 和 `neoforge/platform/` 中实现
3. **事件注册**：在各自的入口类中处理，不暴露到 common
4. **网络通信**：`INetworkManagerService` 接口已抽象，实现类各自处理平台差异

### 5.2 Mixin 版本适配策略

**原则**：Mixin 注入点基于 Minecraft 源码，版本间可能有方法签名变化

**策略**：

```
common/src/main/resources/hassium.mixins.json
    → 包含所有版本通用的 Mixin

fabric/src/main/resources/hassium.fabric.mixins.json
    → Fabric 特定 Mixin

forge/src/main/resources/hassium.forge.mixins.json
    → Forge 特定 Mixin

neoforge/src/main/resources/hassium.neoforge.mixins.json
    → NeoForge 特定 Mixin
```

对于版本间有变化的 Mixin：

```java
// common 中定义基础 Mixin
@Mixin(targets = "net.minecraft.server.level.ChunkMap")
public abstract class MixinChunkMap {
    // 通用逻辑
}

// 在 neoforge/src/main-1.21/java/... 中定义版本特定覆盖
// 或者使用 @Overwrite / @Redirect 的版本特定实现
```

**推荐做法**：尽量让 Mixin 在所有版本中兼容，使用 `remap = true` 让构建时自动重映射。仅在 API 签名不兼容时才创建版本特定 Mixin。

### 5.3 版本特定代码的抽象层

在 `common` 中定义版本适配接口：

```java
// common/src/main/java/.../platform/services/IVersionAdapter.java
public interface IVersionAdapter {
    // 获取区块序列化器的版本特定实现
    IChunkSerializer getChunkSerializer();

    // 获取网络包编解码的版本特定实现
    IPacketCodec getPacketCodec();
}
```

版本特定实现在各自的模块中提供。

---

## 6. NeoForge 与 Forge 的共存问题

### 6.1 问题

由于 NeoForge 从 1.20.2 开始分叉，以下场景需要处理：

- **1.20.1**：只有 Forge，没有 NeoForge
- **1.20.2-1.20.4**：只有 NeoForge，Forge 停更
- **1.21+**：只有 NeoForge

因此 `forge` 模块和 `neoforge` 模块不会同时针对同一版本构建。

### 6.2 构建矩阵

```
版本       | Fabric | Forge | NeoForge
-----------|--------|-------|----------
1.20.1     |   ✓    |   ✓   |    ✗
1.20.4     |   ✓    |   ✗   |    ✓
1.21.x     |   ✓    |   ✗   |    ✓
```

### 6.3 构建脚本条件控制

```groovy
// settings.gradle
def buildVersions = findProperty('build_versions')?.split(',') ?: [minecraft_version]

if (buildVersions.contains('1.20.1')) {
    include("forge")
}
if (buildVersions.any { it >= '1.20.4' }) {
    include("neoforge")
}
```

---

## 7. 实现步骤

### 阶段一：添加 NeoForge 模块（保持单版本 1.20.1）

> 注意：NeoForge 不支持 1.20.1，此阶段实际目标是为 1.20.4+ 做准备

1. **创建 `neoforge/` 目录结构**
2. **添加 `neoforge/build.gradle`**
3. **迁移平台实现**：将 `forge/platform/` 中的实现复制到 `neoforge/platform/`，调整 API 调用
4. **创建 NeoForge 入口类**
5. **配置 `neoforge.mods.toml`**
6. **添加 ServiceLoader 注册文件**
7. **验证构建**

### 阶段二：多版本支持

1. **在 `gradle.properties` 中定义版本矩阵**
2. **改造 `buildSrc` 插件支持源集分离**
3. **为 Fabric 创建版本特定源集**
4. **为 NeoForge 创建版本特定源集**
5. **处理 Mixin 版本差异**
6. **验证多版本构建**

### 阶段三：CI/CD 集成

1. **GitHub Actions 多版本构建矩阵**
2. **自动发布到 CurseForge / Modrinth**
3. **版本特定 changelog 生成**

---

## 8. 关键技术细节

### 8.1 NeoForge Mod 入口类

```java
@Mod(Constants.MOD_ID)
public class HassiumNeoForge {
    public HassiumNeoForge() {
        // 注册事件
        IEventBus modEventBus = NeoForge.EVENT_BUS;

        // 初始化通用逻辑
        HassiumApi.initialize();

        // 注册网络通道
        NeoForgeNetworkManagerService.registerPackets();
    }
}
```

### 8.2 NeoForge 网络实现差异

NeoForge 1.20.4+ 使用 `PayloadRegistrar` 替代 Forge 的 `SimpleChannel`：

```java
public class NeoForgeNetworkManagerService implements INetworkManagerService {
    @Override
    public void sendToServer(Object packet) {
        // NeoForge: 使用 PacketDistributor
        PacketDistributor.SERVER.noPayload().send(...);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, Object packet) {
        // NeoForge: 使用 PacketDistributor
        PacketDistributor.sendToPlayer(player, ...);
    }
}
```

### 8.3 多版本 Mixin 兼容性检查清单

在引入新版本前，需验证以下 Mixin 注入点的兼容性：

| Mixin 目标类 | 1.20.1 | 1.20.4 | 1.21.x | 备注 |
|---|---|---|---|---|
| RegionFile | ✓ | ? | ? | 存储层，可能稳定 |
| ChunkHolder | ✓ | ? | ? | 区块广播 |
| ServerPlayer | ✓ | ? | ? | trackChunk |
| ChunkMap | ✓ | ? | ? | 区块管理 |
| Connection | ✓ | ? | ? | 网络层 |
| ChunkSerializer | ✓ | ? | ? | 序列化 |

> 每次版本升级前，需要反编译目标版本验证注入点是否仍然存在且签名兼容。

---

## 9. 风险与注意事项

1. **NeoForge 不支持 1.20.1**：`neoforge` 模块最低支持 1.20.2（实际建议从 1.20.4 开始）
2. **Mixin 维护成本**：多版本意味着每个 Mixin 都需要在目标版本中验证
3. **构建时间**：多版本构建会显著增加 CI 时间
4. **依赖管理**：zstd-jni 版本需要在所有平台保持一致
5. **测试覆盖**：每个版本×加载器组合都需要独立测试

---

## 10. 总结

| 设计决策 | 选择 | 理由 |
|---|---|---|
| 模块组织 | 源集分离（方案二） | 与现有 multiloader 模板兼容 |
| NeoForge 支持 | 独立 neoforge 模块 | 与 forge 模块并行，不破坏现有结构 |
| 版本适配 | 版本特定源集 | 灵活，不影响 common 模块 |
| 平台抽象 | 保持 ServiceLoader | 已验证可用，无需重构 |
| Mixin 策略 | 通用优先 + 版本覆盖 | 减少维护成本 |
