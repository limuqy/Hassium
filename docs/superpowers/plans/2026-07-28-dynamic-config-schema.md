# Dynamic Config Schema Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all configuration metadata into common and dynamically generate Forge, NeoForge, and Fabric configuration backends from one Loader-neutral Schema, so adding an existing config type changes only common.

**Architecture:** `common` owns `ConfigSchema`, typed `ConfigKey`s, dynamic `ConfigValues`, and conversion into the existing `HassiumConfig` business snapshot. Each loader owns one generic backend that iterates the Schema and maps supported types to its native configuration API. Forge no longer uses FCAP for configuration registration; NeoForge and Fabric keep their own registration/read-write APIs.

**Tech Stack:** Java records/generics, Gradle multi-loader project, Forge `ForgeConfigSpec`, NeoForge `ModConfigSpec`, Fabric NightConfig TOML, JUnit 5, Manifold version preprocessing.

## Global Constraints

- `common` MUST NOT import Forge, NeoForge, FCAP, or Fabric APIs.
- New Schema entries of existing supported types MUST require no loader-file edits.
- Preserve `hassium-client.toml` and `hassium-server.toml`, paths, defaults, ranges, comments, reload semantics, endpoint/listener validation, and business behavior.
- Preserve `HassiumConfig` as the business-layer snapshot during migration; no feature gate or default may be weakened.
- Forge remains supported only for 1.20.1 and 1.20.6; use `"-Pmc_ver=<version>"` in PowerShell commands.
- Do not run formatters, linters, or project-wide test suites during individual implementation tasks; run verification once at the end.

## File Map

- Create in `common/.../config/`: `ConfigScope`, `ConfigType`, `ConfigKey`, `ConfigEntry`, `ConfigSchema`, `ConfigValues`, `ConfigSnapshotAdapter`.
- Modify `common/.../config/HassiumConfig.java` only where Schema keys/default metadata must be centralized; keep its public record accessors stable.
- Modify `common/.../config/HassiumConfigService.java` to accept a common snapshot instead of reading ConfigSpec fields.
- Remove or replace `common/.../config/HassiumConfigSpec.java`; no Loader imports remain in common.
- Create in `forge/.../config/`: `ForgeConfigBackend` and registration glue using Forge-native APIs only.
- Create in `neoforge/.../config/`: `NeoForgeConfigBackend` and registration glue using NeoForge-native APIs only.
- Modify `forge/.../HassiumMod.java` and `neoforge/.../HassiumNeoForge.java` to register/load their backend.
- Modify `common/.../config/FabricTomlConfigIO.java` to iterate common Schema while preserving current structured TOML representation, or move the implementation to Fabric if required by classpath boundaries.
- Modify `common/build.gradle`, loader build files, `versionProperties/*.properties`, and ServiceLoader metadata only if required by the selected backend wiring.
- Add focused tests under `common/src/test/.../config/` for Schema coverage, defaults/ranges, dynamic values, and snapshot conversion.

## Task 1: Define the Loader-Neutral Schema Runtime

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigScope.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigType.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigKey.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigEntry.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigValues.java`
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigSchema.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/config/ConfigSchemaTest.java`

**Interfaces:**
- `ConfigSchema.clientEntries()` and `ConfigSchema.serverEntries()` return immutable ordered lists.
- `ConfigSchema.CLIENT_*` and `SERVER_*` constants are typed `ConfigKey<T>` values used by common adapters/business conversion.
- `ConfigValues.defaults(ConfigSchema.entries())` creates a complete default value set.
- `ConfigValues.get(ConfigKey<T>)` and `with(ConfigKey<T>, T)` enforce key type and schema scope.
- `ConfigEntry` stores path, scope, type, default, optional numeric bounds, comment, translation key, and a list/object encoding descriptor where needed.

- [ ] Add enum/type records with no loader imports and validation for nonblank paths, legal scope, default type, and `min <= max`.
- [ ] Register every current retained config path once per applicable scope, including both `debug.*` scopes, all scalar fields, blacklist, control endpoints, data-plane scalars, and encoded listener values.
- [ ] Preserve exact current defaults/ranges and config paths from `HassiumConfig`/`HassiumConfigSpec`/`FabricTomlConfigIO`.
- [ ] Add tests asserting all paths are unique within a scope, defaults are typed, range metadata is present where currently enforced, and defaults include the built-in compression blacklist/data-plane defaults.
- [ ] Run `./gradlew --no-daemon common:test --tests '*ConfigSchemaTest'` and confirm PASS.

## Task 2: Centralize Snapshot Conversion

**Files:**
- Create: `common/src/main/java/io/github/limuqy/mc/hassium/config/ConfigSnapshotAdapter.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfig.java`
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigService.java`
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/config/ConfigSnapshotAdapterTest.java`

**Interfaces:**
- `ConfigSnapshotAdapter.fromValues(ConfigValues values, ConfigScope activeScope): HassiumConfig` merges active client/server values with defaults for the inactive side.
- `ConfigSnapshotAdapter.toValues(HassiumConfig config): ConfigValues` writes the existing business snapshot back to Schema keys.
- `HassiumConfigService.apply(HassiumConfig)` remains the single update path for runtime gates.

- [ ] Move the existing `HassiumConfigSpec.toHassiumConfig()` and `applyFrom()` field mapping into common code that reads/writes `ConfigValues`, retaining endpoint and data-plane decode/validation behavior.
- [ ] Keep `HassiumConfig` record constructors and existing getter methods stable unless a compile error proves a narrow adjustment is required.
- [ ] Replace `syncFromSpec()` with `applyLoaded(HassiumConfig)` or equivalent common method; preserve reload race handling and defaults.
- [ ] Add round-trip tests for all scalar records, compression blacklist, reachable endpoints, and data-plane listeners.
- [ ] Run focused common config tests.

## Task 3: Implement Generic Forge Backend

**Files:**
- Create: `forge/src/main/java/io/github/limuqy/mc/hassium/config/ForgeConfigBackend.java`
- Create: `forge/src/main/java/io/github/limuqy/mc/hassium/config/ForgeConfigRegistration.java`
- Modify: `forge/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`
- Modify: `forge/build.gradle`
- Modify: `versionProperties/1.20.6.properties` only if the resolved Forge-native config dependency requires an explicit version change.

**Interfaces:**
- `ForgeConfigRegistration.CLIENT_SPEC` and `.SERVER_SPEC` expose the generated native specs to the Forge entrypoint.
- `ForgeConfigBackend.create()` iterates `ConfigSchema` and returns native specs plus typed path maps.
- `ForgeConfigBackend.load()` returns `HassiumConfig` through `ConfigSnapshotAdapter`.

- [ ] Implement one generic mapping from `ConfigType` to Forge `define`, `defineInRange`, and list APIs; do not add per-config fields.
- [ ] Preserve Forge 1.20.1 version branches and isolate 1.20.6 API differences in Forge code only.
- [ ] Replace FCAP `NeoForgeConfigRegistry` registration with Forge-native registration or a Forge-owned config registration path supported by the target version.
- [ ] Remove FCAP configuration dependency from Forge/common only after native Forge compile confirms the classes are available.
- [ ] Update config load/reload events to call the common snapshot apply path.
- [ ] Run `./gradlew --no-daemon forge:compileJava "-Pmc_ver=1.20.1"` and `./gradlew --no-daemon forge:compileJava "-Pmc_ver=1.20.6"`.

## Task 4: Implement Generic NeoForge Backend

**Files:**
- Create: `neoforge/src/main/java/io/github/limuqy/mc/hassium/config/NeoForgeConfigBackend.java`
- Create: `neoforge/src/main/java/io/github/limuqy/mc/hassium/config/NeoForgeConfigRegistration.java`
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/HassiumNeoForge.java`
- Modify: `neoforge/build.gradle` only if required by API ownership.

**Interfaces:**
- `NeoForgeConfigRegistration.register(...)` handles the existing `ModLoadingContext`/`ModContainer` split.
- `NeoForgeConfigBackend.load()` returns the same `HassiumConfig` model as Forge and Fabric.

- [ ] Generate NeoForge `ModConfigSpec` objects by iterating common Schema.
- [ ] Keep version-specific registration and import branches in the NeoForge module.
- [ ] Remove `HassiumConfigSpec` references from NeoForge entrypoint.
- [ ] Update loading/reloading events to use common snapshot apply.
- [ ] Run the relevant NeoForge anchor compile command for the current `mc_ver`.

## Task 5: Make Fabric TOML Schema-Driven

**Files:**
- Modify: `common/src/main/java/io/github/limuqy/mc/hassium/config/FabricTomlConfigIO.java` or create the implementation under `fabric/.../config/` if common classpath constraints require it.
- Modify: `fabric/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java` and other Fabric entrypoints only if the load call changes.
- Test: `common/src/test/java/io/github/limuqy/mc/hassium/config/FabricTomlConfigIOTest.java`

**Interfaces:**
- `FabricTomlConfigIO.load()` and `.save(HassiumConfig)` retain their existing public behavior.
- Schema iteration supplies scalar defaults/comments and type checks; structured TOML tables remain compatible.

- [ ] Replace scalar hand-written field declarations with Schema iteration while retaining structured endpoint/listener serialization and common validation.
- [ ] Ensure client files contain only client/debug entries and server files contain only server/debug entries.
- [ ] Preserve malformed-value fallback and missing-file creation behavior.
- [ ] Add temp-directory tests for generated defaults, round-trip values, and malformed scalar/list values.
- [ ] Run focused Fabric/common config tests.

## Task 6: Remove Shared Loader Config Types and Migrate Callers

**Files:**
- Remove/replace: `common/src/main/java/io/github/limuqy/mc/hassium/config/HassiumConfigSpec.java`
- Modify: `common/build.gradle`
- Modify: `forge/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`
- Modify: `neoforge/src/main/java/io/github/limuqy/mc/hassium/HassiumNeoForge.java`
- Modify: any common callers found by LSP references to `HassiumConfigSpec`, `syncFromSpec`, or `applyFrom`.
- Modify: `versionProperties/*.properties` only where dependency metadata becomes unused.

- [ ] Use LSP references before changing exported methods/classes and migrate every caller.
- [ ] Remove ConfigSpec imports and compileOnly FCAP config dependencies from common.
- [ ] Remove FCAP `NeoForgeConfigRegistry` from Forge entrypoint and its runtime dependency if no other Forge path uses it.
- [ ] Keep unrelated FCAP/JiJ stripping code out of the change unless dependency removal makes it dead; delete dead configuration-specific stripping only after compile verifies no remaining consumer.
- [ ] Run `./gradlew --no-daemon common:compileJava` and loader compile commands at their supported anchors.

## Task 7: End-to-End Verification and Cleanup

**Files:**
- Modify: focused config tests and docs only if verification finds a real contract mismatch.
- Check: `docs/superpowers/specs/2026-07-28-dynamic-config-schema-design.md`

- [ ] Run `./gradlew --no-daemon common:test`.
- [ ] Run `./gradlew --no-daemon common:compileJava`.
- [ ] Run Forge 1.20.1 and 1.20.6 compile commands with quoted `-Pmc_ver`.
- [ ] Run the current Fabric and NeoForge anchor compile commands.
- [ ] Exercise config generation/read/reload for client and dedicated-server TOML paths.
- [ ] Verify `common` source has no Forge/NeoForge/FCAP config imports and no Loader config registration calls.
- [ ] Verify new Schema-only smoke entry: add a temporary existing-type entry in a test Schema fixture, generate all backends, read it, then remove the fixture without changing loader source.
- [ ] Run formatter only if the repository has an established formatter task and only after behavior passes.
