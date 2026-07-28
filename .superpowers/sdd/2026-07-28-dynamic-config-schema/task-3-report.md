# Task 3 Report: Generic Forge Backend

## Status
Completed.

## Changed files
- `forge/src/main/java/io/github/limuqy/mc/hassium/config/ForgeConfigBackend.java`: added schema-driven Forge-native `ForgeConfigSpec` backend. It iterates `ConfigSchema` and maps BOOLEAN/STRING, numeric range types, and STRING_LIST to Forge builder APIs without per-field members.
- `forge/src/main/java/io/github/limuqy/mc/hassium/config/ForgeConfigRegistration.java`: added registration helper exposing `CLIENT_SPEC` and `SERVER_SPEC`, registering both through Forge `ModLoadingContext`.
- `forge/src/main/java/io/github/limuqy/mc/hassium/HassiumMod.java`: switched Forge entrypoint to the generated native specs and retained common snapshot sync on loading/reloading events.
- `forge/src/main/resources/META-INF/services/io.github.limuqy.mc.hassium.platform.services.IConfigBackend`: updated ServiceLoader implementation path to the new `config` package.
- `forge/build.gradle`: removed the Forge configuration API dependency and its 1.20.6 config JiJ wiring; unrelated KCP and FCAP MixinExtras stripping logic was left intact.

## Verification commands and output summary
1. `powershell -NoProfile -Command "& '.\\gradlew.bat' '--no-daemon' 'forge:compileJava' '-Pmc_ver=1.20.1'"`
   - Result: `BUILD SUCCESSFUL in 7s`.
   - Output contained only two deprecation warnings for `ModLoadingContext.get()`.
2. `powershell -NoProfile -Command "& '.\\gradlew.bat' '--no-daemon' 'forge:compileJava' '-Pmc_ver=1.20.6'"`
   - Result: `BUILD SUCCESSFUL in 10s`.
   - Output contained four deprecation warnings (two registration warnings and two existing `ResourceLocation` constructor warnings).

The requested Gradle compile commands were run through `gradlew.bat` because the workstation is Windows.
