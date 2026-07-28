
## Fix round 1

Addressed reviewer findings:
- `HassiumMod` now reuses the `ForgeConfigBackend` instance supplied by `Services.CONFIG`, so ServiceLoader load, registration, and runtime sync share one pair of native specs.
- `ForgeConfigBackend.load` now starts from the complete `ConfigSchema.entries()` defaults and merges both client and server native value maps, regardless of the requested scope, producing the complete snapshot required by `ConfigSnapshotAdapter.fromValues`.
- `ForgeConfigBackend.save` now writes every typed schema value into the corresponding native `ConfigValue` path map and saves both specs.

Verification:
- `gradlew.bat --no-daemon forge:compileJava "-Pmc_ver=1.20.1"`: `BUILD SUCCESSFUL in 9s`; 4 deprecation warnings (ModLoadingContext and existing ResourceLocation constructors).
- `gradlew.bat --no-daemon forge:compileJava "-Pmc_ver=1.20.6"`: `BUILD SUCCESSFUL in 9s`; 4 deprecation warnings (same categories).
