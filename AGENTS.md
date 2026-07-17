# AGENTS.md

Guidance for AI agents working in the Hassium repository. See `CLAUDE.md` for comprehensive project overview.

## Project identity

Minecraft 1.20.1 multiloader mod (Forge + Fabric) that replaces vanilla Zlib with ZSTD compression for chunk storage and network transport. Three modules: `common/` (loader-agnostic), `fabric/`, `forge/`.

## Critical build commands

```bash
# First-time setup or if decompile artifacts missing
./gradlew common:decompile

# Compile checking (faster than full build, run after code changes)
./gradlew common:compileJava
./gradlew fabric:compileJava
./gradlew forge:compileJava

# Full build
./gradlew build                    # all platforms
./gradlew fabric:build             # Fabric only
./gradlew forge:build              # Forge only

# Dev testing
./gradlew fabric:runClient
./gradlew forge:runClient
./gradlew fabric:runServer
./gradlew forge:runServer

# Run benchmark/utility from common
./gradlew common:runJava -PmainClass=<fully.qualified.ClassName> -Pargs=arg1,arg2
```

**Command order matters:** After changing `common/` code, run `common:compileJava` first to catch errors before they cascade to loader modules.

## Module dependency rules

```
common/   ← loader-agnostic logic only, NO imports from fabric/forge
  ↑
fabric/   ← can import common
forge/    ← can import common
```

**Where code goes:**
- Business logic, algorithms, data structures, config records → `common/`
- Loader-specific API calls (events, registries, network channels) → `fabric/` or `forge/`
- When loader-specific implementation needed: define interface in `common/src/main/java/.../platform/services/`, implement in both loaders, register via `META-INF/services/<interface.fqn>` (see ServiceLoader pattern below)

## Platform abstraction (ServiceLoader)

When adding cross-platform functionality:

1. Define interface in `common/src/main/java/.../platform/services/IXxxHelper.java`
2. Access via `Services.XXX` in common code
3. Implement in `fabric/src/main/java/.../platform/FabricXxxHelper.java`
4. Implement in `forge/src/main/java/.../platform/ForgeXxxHelper.java`
5. **CRITICAL:** Register in both:
   - `fabric/src/main/resources/META-INF/services/io.github.limuqy.mc.hassium.platform.services.IXxxHelper`
   - `forge/src/main/resources/META-INF/services/io.github.limuqy.mc.hassium.platform.services.IXxxHelper`
   
   Each file contains one line: the fully qualified implementation class name.

**Most common mistake:** Forgetting one or both `META-INF/services/` registration files causes runtime `NoSuchElementException`, not compile error.

## Mixin injection (common only)

All Mixins live in `common/` at `io.github.limuqy.mc.hassium.mixin/`. Target classes use Mojang mappings (1.20.1).

**Standard pattern:**
```java
@Mixin(TargetClass.class)
public abstract class MixinTargetClass {
    @Unique
    private static final Logger hassium$LOGGER = LoggerFactory.getLogger("Hassium/TargetClass");
    
    @Inject(method = "methodName", at = @At("HEAD"), cancellable = true)
    private void hassium$onMethodName(CallbackInfoReturnable<ReturnType> cir) {
        if (!HassiumConfigService.getInstance().isStorageEnabled()) {
            return; // Feature disabled by default, must bail early
        }
        // ...
    }
}
```

**Rules:**
- All injected fields/methods must have `@Unique` + `hassium$` prefix
- Storage-related mixins MUST check `isStorageEnabled()` first (default: disabled)
- Private fields/methods access requires `@Accessor`/`@Invoker` interface (e.g., `RegionFileAccessor`)
- Add class name (no package) to `common/src/main/resources/hassium.mixins.json` under `"mixins"` array (or `"client"` if client-only)
- Avoid `@Overwrite`, prefer `@Inject` with `cancellable = true`

**Verification:** After adding/modifying mixin, run all three `compileJava` tasks. Mapping errors appear at runtime, not compile time.

## Configuration defaults

- `storage.enabled = false` (MUST default to off, world save safety)
- `network.enabled = true` (Hassium custom channel compression)
- `network.globalPacketCompression = true` (global ZSTD replaces vanilla Zlib)
- `clientCache.enabled = true`

New config fields that modify save format MUST default to disabled/safe.

## Testing

```bash
# Unit tests (common module has JUnit 5 setup)
./gradlew common:test

# Integration: use runClient/runServer
```

## Package structure

```
io.github.limuqy.mc.hassium/
├── api/          # Public API (HassiumApi, HassiumCapabilities)
├── storage/      # Region file format (interfaces, data classes)
├── compression/  # ZSTD codec (CompressionService, HassiumEnvelope, DictionaryRegistry)
├── cache/        # Client-side chunk cache
├── network/      # Custom packets (hassium:* channels)
├── config/       # Config records (HassiumConfig + subconfigs)
├── metrics/      # Performance stats
├── migration/    # Format migration tools
├── platform/     # ServiceLoader abstraction (Services.java, services/ interfaces)
└── mixin/        # All Mixin classes (common only)
```

## Dependencies

- `com.github.luben:zstd-jni:1.5.5-7` already added to `common/build.gradle`
- New dependencies go in `common/` (inherited by loaders via multiloader plugin in `buildSrc/`)
- Native libraries: verify jar-in-jar packing works on both loaders before merging

## Version constraints

- Target: Minecraft 1.20.1–1.21.11 via Manifold `#if MC_VER`
- **Forge `builds_for`：仅 1.20.1 / 1.20.6**；1.21+ 不构建 Forge（用 NeoForge）
- **Adaptation unit**: 9 effective segments × loaders in `builds_for` — see [`docs/version-segments.md`](docs/version-segments.md)
- PowerShell: always quote `-Pmc_ver=...` as `"-Pmc_ver=1.20.1"` (otherwise `1.20.1` is truncated to `1`)
- Mojang API diffs belong in `common/.../compat/`; do not scatter new `#if MC_VER` in business/Mixin code
- Legal boundary constants are whitelisted in `docs/version-segments.md` (`./gradlew scanVersionBoundaries`)
- Compression type ID `127` (vanilla's custom scheme marker)
- Design must allow future compression-scheme upgrade path (don't hardcode `127` checks everywhere)

## Skills available

Load via the `skill` tool:
- `hassium-dev`: General cross-platform development, ServiceLoader, config
- `hassium-mixin`: Mixin injection details (RegionFile, ChunkSerializer, etc.)
- `hassium-compression`: Compression codec, envelope format, dictionary training

## Key gotchas

1. **Mixin refmap failures**: Usually means target method/field name wrong for Mojang mappings or target class not in classpath
2. **ServiceLoader returning empty**: Missing `META-INF/services/` registration file
3. **Config not respected**: Check feature gate (`isStorageEnabled()`, etc.) exists at mixin entry point
4. **Build errors in loader modules**: Fix `common/` first, loader errors often cascade from common
5. **Proxy settings**: `gradle.properties` has hardcoded proxy (127.0.0.1:7890), may need adjustment for non-CN environments

## Documentation

- `CLAUDE.md`: Full project overview (architecture, status, todos)
- `docs/hassium-requirements.md`: Feature requirements and feasibility
- `docs/hassium-development.md`: Detailed architecture and design
- `docs/chunk-queue-implementation.md`: Network queue system details
- `.claude/skills/`: Detailed task-specific workflows
