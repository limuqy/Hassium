> 归档：历史 superpowers 规格（已完成使命）（2026-08-09）
# Common 动态配置 Schema 解耦设计

日期：2026-07-28

## 目标

将配置定义维护在 `common`，由 Fabric、Forge、NeoForge 各自的通用适配器根据同一份 Schema 动态生成配置后端。

新增一个已有类型的配置项时，只修改 `common` 的 Schema 和对应的运行时快照定义；不修改三个加载器的配置注册、字段绑定或读取代码。

同时移除 `common` 对 Forge、NeoForge、FCAP 配置 API 的编译依赖，保持 Loader API 只存在于对应 Loader 模块。

## 当前问题

当前 `common/src/main/java/.../config/HassiumConfigSpec.java` 同时承担三项职责：

1. 保存所有配置项定义；
2. 直接创建 `ForgeConfigSpec` 或 `ModConfigSpec`；
3. 将 ConfigSpec 的逐字段值映射为 `HassiumConfig`。

因此 `common` 直接导入 Loader API。Forge 1.20.6 为复用 NeoForge 风格 `ModConfigSpec`，还需要 FCAP 的 `NeoForgeConfigRegistry`，造成 Forge 配置适配和 FCAP 绑定。

Fabric 当前虽在运行时使用 `FabricTomlConfigIO`，但 shared `common` 仍然包含 Loader 配置类型和逐字段 Spec 代码。

## 目标架构

```text
common
  ConfigSchema
  ConfigEntry / ConfigType / ConfigScope
  配置键、默认值、范围、注释、翻译键
  ConfigValues（按 Schema 保存动态值）
  HassiumConfig（业务层快照/访问外观）
  HassiumConfigService

forge
  ForgeConfigBackend
  ForgeConfigRegistration
  Forge 原生配置 API 的类型映射

neoforge
  NeoForgeConfigBackend
  NeoForgeConfigRegistration
  NeoForge 原生配置 API 的类型映射

fabric
  FabricTomlConfigBackend
  NightConfig TOML 的类型映射
```

数据流：

```text
common ConfigSchema
        │ 遍历
        ├── ForgeConfigBackend      → Forge ConfigSpec → ConfigValues
        ├── NeoForgeConfigBackend   → NeoForge ModConfigSpec → ConfigValues
        └── FabricTomlConfigBackend → TOML             → ConfigValues
                                                        │
                                                        ▼
                                             HassiumConfigService
```

## Common Schema

### 配置项模型

`common` 只定义 Loader 无关的数据模型：

```java
public enum ConfigScope {
    CLIENT,
    SERVER
}

public enum ConfigType {
    BOOLEAN,
    INT,
    LONG,
    DOUBLE,
    STRING,
    STRING_LIST,
    OBJECT_LIST
}

public record ConfigEntry<T>(
        String path,
        ConfigScope scope,
        ConfigType type,
        T defaultValue,
        Optional<Number> min,
        Optional<Number> max,
        String comment,
        String translationKey
) {
}
```

实际实现可使用类型安全的工厂方法，禁止调用方直接构造不匹配的 `ConfigEntry`：

```java
ConfigSchema.bool("storage.enabled", SERVER, true, comment, translation);
ConfigSchema.intValue("network.compressionLevel", SERVER, 3, 1, 22, comment, translation);
ConfigSchema.stringList("network.compressionBlacklist", SERVER, defaults, comment, translation);
```

`ConfigEntry` 不得引用或导入：

- `ForgeConfigSpec`；
- `ModConfigSpec`；
- `ModConfig`；
- `ForgeConfig`；
- `NeoForgeConfigRegistry`；
- Fabric、NeoForge、Forge API。

### Schema 注册

Schema 是 common 的唯一配置元数据真相源。按当前文件模型，配置项使用 `CLIENT` 或 `SERVER` scope；`debug.*` 在两种 scope 中各注册一套同路径配置。

Schema 必须覆盖当前保留配置项，包括：

- `boolean`、`int`、`long`、`double`、`string`；
- `List<String>`，用于 blacklist、encoded endpoint 等值；
- endpoint / UDP listener 的结构化列表；
- 注释、翻译键、默认值和范围。

结构化列表不把 Loader 配置对象暴露到 common。Schema 仅描述结构化值的字段和类型；Loader 后端负责把它映射为本 Loader 支持的 list/object 表示。若某 Loader 的原生配置 API 只支持字符串列表，则使用 common 已有的编码格式，并由 common 解码与校验。

### 动态值

`ConfigValues` 保存按配置路径索引的已加载值，并提供 typed getter：

```java
boolean getBoolean(ConfigKey<Boolean> key);
int getInt(ConfigKey<Integer> key);
long getLong(ConfigKey<Long> key);
double getDouble(ConfigKey<Double> key);
String getString(ConfigKey<String> key);
List<String> getStringList(ConfigKey<List<String>> key);
```

业务代码不直接使用裸字符串；Schema 注册时返回 `ConfigKey<T>`，业务访问器只引用 common 中的 key 常量。这样新增配置项仍只改 common，同时避免业务层拼写路径。

`HassiumConfigService` 接收 `ConfigValues` 或由 common 完成的 `HassiumConfig` 快照，不再读取任何 ConfigSpec Value。

现有强类型 `HassiumConfig` record 保留作为业务层模型，避免把 Loader 类型扩散到业务代码。Schema 后端到 `HassiumConfig` 的组装逻辑集中在 common，不在三个 Loader 中重复。

## Loader 后端

### Forge

Forge 后端只实现一次通用遍历：

1. 按 `ConfigScope` 选择 client/server builder；
2. 遍历 `ConfigSchema.entries(scope)`；
3. 按 `ConfigType` 调用 Forge 原生 `define`、`defineInRange`、list API；
4. 保存 path 到原生 ConfigValue 的动态映射；
5. 注册对应 ConfigSpec；
6. reload 时遍历映射生成 `ConfigValues`。

Forge 版本差异只放在 Forge 模块的 `compat` 或版本条件中。Forge 不应因为 common 的配置模型引入 FCAP。

### NeoForge

NeoForge 后端与 Forge 后端共享设计但不共享 Loader API：

1. 遍历相同的 common Schema；
2. 生成 NeoForge `ModConfigSpec`；
3. 通过当前版本对应的 `ModLoadingContext` 或 `ModContainer` 注册；
4. reload 时生成相同的 `ConfigValues`。

NeoForge 版本差异继续留在现有版本条件和 NeoForge 适配层。

### Fabric

Fabric 后端遍历相同 Schema：

1. 根据 scope 和物理端选择 client/server TOML；
2. 按 Schema 写入缺失项、默认值、comment；
3. 从 TOML 按 Schema 读取值并做类型校验；
4. 生成 `ConfigValues`；
5. 复杂 endpoint/listener 结构保留现有 TOML 表结构和 common 校验语义。

Fabric 不使用 `ModConfigSpec`、Forge API 或 FCAP。

## 配置文件兼容性

必须保持：

- `config/hassium/hassium-client.toml`；
- `config/hassium/hassium-server.toml`；
- 现有配置路径；
- 现有默认值和范围；
- 现有 endpoint、UDP listener、blacklist 编码与校验；
- 缺失字段自动补默认值；
- 非法值回退默认值并记录日志；
- ConfigSpec/TOML reload 后更新 `HassiumConfigService`；
- 旧配置文件不因迁移被无条件覆盖。

Fabric TOML 的 comment 和 Forge/NeoForge GUI 的 translation/comment 均来自 Schema，避免三端文案漂移。

## 迁移边界

删除或重构 common 中直接创建 Loader ConfigSpec 的代码。`HassiumConfigSpec.java` 不再位于 common，或被拆为不含 Loader 类型的 common Schema 文件。

Forge 入口只负责调用 Forge 后端注册，不再调用 FCAP 的 `NeoForgeConfigRegistry`。

NeoForge 入口只负责调用 NeoForge 后端注册。

Fabric 入口继续调用 Fabric 配置后端，但 `FabricTomlConfigIO` 改为 Schema 驱动，删除逐字段读写重复表。

`HassiumConfigService` 改为接收统一加载结果，保留现有运行时 gate、锁和 reload 语义。

本设计不改变网络、存储、缓存、协议和业务行为；不关闭任何现有功能；不修改配置默认值。

## 复杂值处理

endpoint 和 UDP listener 继续使用 common 的 `DataPlaneEndpointConfig` 编码、解码和校验。

推荐 Schema 对这两类配置使用 `STRING_LIST` 编码，以兼容 Forge 1.20.1 的列表 API；Fabric 可继续将其展开为 TOML 表结构，但展开/收敛逻辑只存在于 Fabric 后端和 common codec，不复制到业务层。

`Set<String>` 配置使用 Schema 的 `STRING_LIST`，读取后在 common 快照构造阶段转换为不可变 Set。

## 验证标准

1. `common:compileJava` 不再需要 Forge、NeoForge 或 FCAP 配置类型。
2. Forge 1.20.1 与 Forge 1.20.6 配置编译和注册不依赖 FCAP。
3. NeoForge 各现有锚点配置编译通过。
4. Fabric 配置生成和读取通过。
5. 新增一个既有类型 Schema 项后，三个 Loader 无需修改即可生成并读取该项。
6. 当前全部配置项的路径、默认值、范围和复杂值格式保持不变。
7. Config reload 后 `HassiumConfigService` 的 gate 和业务 getter 仍返回正确值。
8. `client.toml` 不包含服务端专属项，`server.toml` 不包含客户端缓存项。

## 实施顺序

1. 在 common 创建 Schema、ConfigKey、ConfigValues 和统一转换入口。
2. 将现有配置元数据迁移到 Schema，先保持 `HassiumConfig` 业务模型不变。
3. 实现 Forge 通用后端，验证 1.20.1/1.20.6。
4. 实现 NeoForge 通用后端，覆盖现有版本条件。
5. 将 Fabric TOML IO 改为 Schema 驱动。
6. 迁移 `HassiumConfigService` 和三个 Loader 入口。
7. 删除 common Loader 配置依赖及 FCAP 配置注册路径。
8. 运行 common、Forge、NeoForge、Fabric 编译和配置读写测试。
