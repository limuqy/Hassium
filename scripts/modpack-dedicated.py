"""Run full HMCL clients against isolated, matching dedicated servers."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import subprocess
import time
import tomllib
import traceback
import urllib.request
import urllib.error
import zipfile

spec = importlib.util.spec_from_file_location("pack_base", Path(__file__).with_name("modpack-compat.py"))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

# Display/input/render components have no required dedicated-server counterpart.
# Keep every client JAR by default; explicit diagnostic exclusions are recorded separately.
CLIENT_IDS = set("""animatica advancementplaques betteradvancements betterf3 bettergrassify
capes chat_heads citresewn continuity controlify controllable customskinloader drippyloadingscreen
dynamic_fps eatinganimation eatinganimations embeddium entityculling entity_model_features
e4mc e4mc_minecraft mcwifipnp xplus-config-checker colorwheel sodiumoptionsapi flerovium
audioimprovements audio_improvements stop_rendering
bettergrass distraction_free_recipes particle_core fancytoasts ymktn smart_particles enhancedcelestials2shaders
darkmodeeverywhere particle_effects
perception fastlaunch fastloading simplesorter weatherrefind imblocker
entity_texture_features euphoria_patcher fancymenu fabricskyboxes immediatelyfast iris
legendarytooltips modmenu modernui moreculling mousetweaks notenoughanimations oculus
reeses_sodium_options rubidium skinlayers3d sodium sodium_extra visuality xaerominimap xaeroworldmap""".split())

# Server preparation workarounds, separate from Hassium compatibility conclusions.
SERVER_FILE_EXCLUSIONS = {
    "verity": {"gml-4.0.11-all.jar": "Unused Groovy language provider; its bundled CGL extensions load client PoseStack on dedicated server"},
    "atm302": {
        "Palladium-1.1.8-1.21-1.21.1.jar": "Server preparation workaround: Palladium CompoundTagMixin cannot resolve its PalladiumConfig on dedicated server",
        "Toadlib-1.3.2-1.21-1.21.1.jar": "Unused Palladium companion; ToadConfig is declared client-only and fails server initialization",
    },
}

SCENARIO = """join label=WAIT_DEDICATED_1 since=start timeoutMs=${joinTimeoutMs}
wait ms=90000
dump label=ROUND1 round=1
assertProbe key=isolation.integrated op=eq value=0
assertProbe key=isolation.memoryConnection op=eq value=0
assertProbe key=isolation.shadowRunning op=eq value=1
assertProbe key=clientCache.loadedChunks op=gt value=64
assertProbe key=contentCheck.chunksWithNonAir op=gt value=0
disconnect
reconnect delayMs=3000
join label=WAIT_DEDICATED_2 since=disconnect timeoutMs=${joinTimeoutMs}
wait ms=60000
dump label=ROUND2 round=2
assertProbe key=isolation.integrated op=eq value=0
assertProbe key=isolation.memoryConnection op=eq value=0
assertProbe key=isolation.shadowRunning op=eq value=1
assertProbe key=clientCache.loadedChunks op=gt value=64
assertProbe key=contentCheck.chunksWithNonAir op=gt value=0
assertProbe key=stats.cacheHitFullChunkCount op=gt value=0
exit
"""

CONTROL_SCENARIO = SCENARIO.replace("wait ms=90000", "wait ms=30000").replace("wait ms=60000", "wait ms=20000") \
    .replace("key=isolation.shadowRunning op=eq value=1", "key=isolation.shadowRunning op=eq value=0") \
    .replace("assertProbe key=stats.cacheHitFullChunkCount op=gt value=0\n", "") \
    .replace("dump label=ROUND1 round=1", "dump label=ROUND1 round=1 vanilla=true") \
    .replace("dump label=ROUND2 round=2", "dump label=ROUND2 round=2 vanilla=true")


def patch_toml(path, updates):
    text = path.read_text(encoding="utf-8-sig") if path.exists() else ""
    lines = text.splitlines()
    for section, values in updates.items():
        start = next((i for i, line in enumerate(lines) if line.strip() == f"[{section}]"), None)
        if start is None:
            lines.extend([f"[{section}]", *[f"{key} = {value}" for key, value in values.items()]])
            continue
        end = next((i for i in range(start + 1, len(lines)) if lines[i].lstrip().startswith("[")), len(lines))
        for key, value in values.items():
            at = next((i for i in range(start + 1, end) if re.match(r"\s*" + re.escape(key) + r"\s*=", lines[i])), None)
            if at is None:
                lines.insert(end, f"{key} = {value}")
                end += 1
            else:
                lines[at] = f"{key} = {value}"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def jar_metadata(path, loader):
    ids, client = [], False
    with zipfile.ZipFile(path) as jar:
        if (loader == "fabric" or not any(n in jar.namelist() for n in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"))) and "fabric.mod.json" in jar.namelist():
            data = json.loads(jar.read("fabric.mod.json"), strict=False)
            ids.append(data.get("id", ""))
            client = data.get("environment") == "client"
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if name in jar.namelist():
                try:
                    data = tomllib.loads(jar.read(name).decode("utf-8-sig"))
                    mods = data.get("mods", [])
                    ids.extend(mod.get("modId", "") for mod in mods)
                    if data.get("clientSideOnly") is True or (mods and all(mod.get("clientSideOnly") is True for mod in mods)):
                        client = True
                except (UnicodeError, tomllib.TOMLDecodeError):
                    pass
    return set(ids), client


def reconcile_server_exclusions(case, record):
    path = case / "dedicated-setup.json"
    data = base.read_json(path)
    if data.get("referenceInventory"):
        return  # Exact, previously verified fixture takes precedence over heuristics.
    for jar in sorted((case / "server/mods").glob("*.jar")):
        ids, client = jar_metadata(jar, record["loader"])
        reason = SERVER_FILE_EXCLUSIONS.get(record["id"], {}).get(jar.name)
        if not reason and (client or ids & CLIENT_IDS):
            reason = "Client environment/component: " + ",".join(sorted(ids))
        if reason:
            target = case / "server-excluded" / jar.name
            target.parent.mkdir(exist_ok=True)
            assert jar.resolve().is_relative_to(case.resolve()) and target.resolve().is_relative_to(case.resolve())
            jar.rename(target)
            data["serverJars"].remove(jar.name)
            data["serverExclusions"].append({"file": jar.name, "ids": sorted(ids), "reason": reason})
            base.emit(f"{record['id']}: server-only exclusion {jar.name}: {reason}")
    base.save_json(path, data)


def setup_pair(args, key):
    case = args.root / key
    record = base.setup(args, key)
    reference_path = getattr(args, "reference_inventory", None)
    reference = base.read_json(reference_path) if reference_path else None
    reference_server = None
    if reference:
        reference_server = {f["name"]: f["sha256"] for side in reference if side["side"] == "server" for f in side["files"]
                            if not f["name"].startswith("hassium-")}
        reference_client = {f["name"]: f["sha256"] for side in reference if side["side"] == "client" for f in side["files"]
                            if not f["name"].startswith("hassium-")}
        actual_client = {f["name"]: f["sha256"] for f in record["mods"] if f["name"] not in getattr(args, "diagnostic_exclude", [])}
        if actual_client != reference_client:
            raise ValueError("Client JAR names/hashes differ from the successful reference fixture")
    test_exclusions = sorted(set(getattr(args, "diagnostic_exclude", [])))
    for name in test_exclusions:
        if Path(name).name != name or not name.endswith(".jar"):
            raise ValueError("Diagnostic exclusions must name exact JAR filenames")
        if name not in {mod["name"] for mod in record["mods"]}:
            raise ValueError(f"Diagnostic exclusion is not an original pack JAR: {name}")
    if (case / "dedicated-setup.json").exists():
        previous = base.read_json(case / "dedicated-setup.json")
        if previous.get("referenceInventory") != (str(reference_path.resolve()) if reference_path else None):
            raise ValueError("Reference fixtures require a fresh matching output root")
        if previous.get("vanillaChunksControl", False) != getattr(args, "control", False):
            raise ValueError("Control and full-feature tests require separate output roots")
        if previous.get("dimension") != getattr(args, "dimension", None):
            raise ValueError("Dimension tests require a matching fresh output root")
        if previous.get("waitResources", False) != getattr(args, "wait_resources", False):
            raise ValueError("Connection timing tests require a matching fresh output root")
        if previous.get("testModExclusions", []) != test_exclusions:
            raise ValueError("Diagnostic mod exclusions require a matching fresh output root")
        reconcile_server_exclusions(case, record)
        return record
    client, server = case / "client", case / "server"
    (server / "mods").mkdir(parents=True)
    for folder in base.PACK_FOLDERS:
        if (client / folder).is_dir() and folder not in ("resourcepacks", "shaderpacks", "packmenu", "fancymenu_data"):
            shutil.copytree(client / folder, server / folder)
    mapping = record.get("cachePreparation")
    server_mapping = None
    if mapping:
        destination = server / "mod_data/gml/1.20.1/srg.zip"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(mapping["source"], destination)
        server_mapping = {"source": mapping["source"], "sha256": mapping["sha256"], "path": str(destination)}
    mr = Path(record["source"]) / "modrinth.index.json"
    unsupported = set()
    if mr.exists():
        unsupported = {Path(entry["path"]).name for entry in base.read_json(mr)["files"]
                       if entry.get("env", {}).get("server") == "unsupported"}
    excluded, included = [], []
    for jar in sorted((client / "mods").glob("*.jar")):
        ids, explicit_client = jar_metadata(jar, record["loader"])
        if jar.name in test_exclusions:
            disabled = client / "diagnostic-disabled-mods" / jar.name
            disabled.parent.mkdir(parents=True, exist_ok=True)
            jar.rename(disabled)
            excluded.append({"file": jar.name, "ids": sorted(ids),
                             "reason": "Explicit diagnostic exclusion from both test sides; original pack unchanged"})
            base.emit(f"{key}: DIAGNOSTIC EXCLUSION from both test sides: {jar.name}")
            continue
        reason = None
        if jar.name in unsupported:
            reason = "Modrinth manifest: server unsupported"
        elif explicit_client:
            reason = "Mod metadata: client environment"
        elif ids & CLIENT_IDS:
            reason = "Client display/input/render or LAN-sharing component: " + ",".join(sorted(ids & CLIENT_IDS))
            if "perception" in ids:
                reason += "; its Entity trail interface inherits RenderProvider.render(PoseStack), which fails dedicated-server subscriber reflection"
        reason = SERVER_FILE_EXCLUSIONS.get(key, {}).get(jar.name, reason)
        if reference_server is not None:
            reason = None if jar.name in reference_server or jar.name.startswith("hassium-") else "Server omission from user-supplied successful R3 reference inventory"
            if jar.name in reference_server and base.sha(jar) != reference_server[jar.name]:
                raise ValueError("Server JAR hash differs from reference: " + jar.name)
        if reason:
            excluded.append({"file": jar.name, "ids": sorted(ids), "reason": reason})
        else:
            shutil.copy2(jar, server / "mods" / jar.name)
            included.append(jar.name)
    for side in (client, server):
        patch_toml(side / "config/hassium/hassium-client.toml", {
            "chunk": {"enabled": "false" if getattr(args, "control", False) else "true", "seedGenEnabled": "false", "viewDistanceExtensionEnabled": "true", "maxRenderDistance": "16"}})
        patch_toml(side / "config/hassium/hassium-server.toml", {
            "storage": {"enabled": "false"}, "master": {"enabled": "true"},
            "chunk": {"seedGenEnabled": "false", "lightStrip": "true"}})
        target = next((side / "mods").glob("hassium-*.jar"))
        with zipfile.ZipFile(target, "a", compression=zipfile.ZIP_DEFLATED) as jar:
            scenario = CONTROL_SCENARIO if getattr(args, "control", False) else SCENARIO
            if getattr(args, "wait_resources", False):
                scenario = "reconnect delayMs=0 waitForResources=true\n" + scenario
            if getattr(args, "dimension", None):
                dimension = args.dimension
                scenario = scenario.replace("wait ms=90000", "wait ms=90000\ndimension to=" + dimension
                    + " timeoutMs=60000\nwait ms=60000\nassertProbe key=dimension op=eq value=" + dimension, 1)
                scenario = scenario.replace("dump label=ROUND2 round=2\n", "dump label=ROUND2 round=2\nassertProbe key=dimension op=eq value="
                    + dimension + "\n")
            jar.writestr("hassium/smoke/scenario/pack_dedicated.scenario", scenario)
        with zipfile.ZipFile(Path(record["pinnedHassiumJar"])) as original, zipfile.ZipFile(target) as modified:
            assert all(original.read(n) == modified.read(n) for n in original.namelist() if n.endswith(".class"))
    options = (client / "options.txt").read_text(encoding="utf-8")
    (client / "options.txt").write_text(re.sub(r"renderDistance:\d+", "renderDistance:16", options), encoding="utf-8")
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    base.save_json(case / "dedicated-setup.json", {"stage": "dedicated", "serverJars": included,
        "serverExclusions": excluded, "clientJarsKept": len(list((client / "mods").glob("*.jar"))),
        "seedGen": False, "storageCompression": False, "viewDistances": [args.vd1, args.vd2],
        "clientRenderDistance": 16, "sourceSetup": str(case / "setup.json"),
        "serverMappingCachePreparation": server_mapping, "vanillaChunksControl": getattr(args, "control", False),
        "dimension": getattr(args, "dimension", None), "waitResources": getattr(args, "wait_resources", False),
        "testModExclusions": test_exclusions,
        "referenceInventory": str(reference_path.resolve()) if reference_path else None})
    base.emit(f"{key}: dedicated server prepared ({len(included)} JARs; {len(excluded)} client components omitted)")
    return record


def download(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".download")
    base.emit(f"Downloading {url}")
    proxy = urllib.request.ProxyHandler({"http": "http://127.0.0.1:7890", "https": "http://127.0.0.1:7890"})
    with urllib.request.build_opener(proxy).open(url, timeout=60) as response, temporary.open("wb") as out:
        shutil.copyfileobj(response, out)
    temporary.replace(target)


def prepare_runtime(args, record):
    if record["loader"] == "fabric":
        return None
    loader, version, lv = record["loader"], record["version"], record["loaderVersion"]
    runtime = args.root / "runtime"
    coordinate = f"{version}-{lv}" if loader == "forge" else lv
    group_path = "net/minecraftforge/forge" if loader == "forge" else "net/neoforged/neoforge"
    if loader == "forge":
        cached = Path(r"D:\MC\HassiumDiag211\r3-runtime/libraries") / group_path / coordinate / "win_args.txt"
        if cached.is_file():
            return cached
    argument_file = runtime / "libraries" / group_path / coordinate / "win_args.txt"
    if argument_file.exists():
        return argument_file
    shared_runtime = base.REPO / "build/modpack-dedicated/runtime/libraries" / group_path / coordinate / "win_args.txt"
    if shared_runtime.exists():
        return shared_runtime
    installer = runtime / "installers" / f"{loader}-{coordinate}-installer.jar"
    if not installer.exists():
        host = "https://maven.minecraftforge.net/" if loader == "forge" else "https://maven.neoforged.net/releases/"
        url = f"{host}{group_path}/{coordinate}/{loader}-{coordinate}-installer.jar"
        try:
            download(url, installer)
        except urllib.error.HTTPError as error:
            if loader != "forge" or error.code != 403:
                raise
            url = url.replace("https://maven.minecraftforge.net/", "https://bmclapi2.bangbang93.com/maven/")
            download(url, installer)
        base.save_json(installer.with_suffix(".provenance.json"), {"url": url, "sha256": base.sha(installer)})
    with zipfile.ZipFile(installer) as jar:
        profile = json.loads(jar.read("install_profile.json"))
        version_json = json.loads(jar.read("version.json"))
    metadata = base.read_json(args.root / record["id"] / "version.json")
    for lib in profile.get("libraries", []) + version_json.get("libraries", []) + metadata["libraries"]:
        artifact = lib.get("downloads", {}).get("artifact", {})
        rel = artifact.get("path")
        if not rel:
            try:
                rel = base.maven_path(lib["name"])
            except (ValueError, KeyError):
                continue
        target = runtime / "libraries" / rel
        if target.exists():
            continue
        candidates = [args.minecraft / "libraries" / rel,
                      Path(r"D:\MC\HassiumDiag211\r3-runtime\libraries") / rel]
        try:
            group, name, ver, *_ = lib["name"].split(":")
            cache = Path.home() / ".gradle/caches/modules-2/files-2.1" / group / name / ver.split("@")[0]
            candidates.extend(cache.glob("*/" + Path(rel).name))
        except (ValueError, KeyError):
            pass
        source = next((p for p in candidates if p.is_file()), None)
        if source:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    # Seed the authentic bundled vanilla server, avoiding a redundant download.
    bundled = Path.home() / ".gradle/caches/fabric-loom" / version / "minecraft-server.jar"
    expected_sha = metadata.get("downloads", {}).get("server", {}).get("sha1")
    if bundled.exists() and expected_sha:
        import hashlib
        assert hashlib.sha1(bundled.read_bytes()).hexdigest() == expected_sha
        for value in profile.get("data", {}).values():
            server_value = value.get("server", "") if isinstance(value, dict) else ""
            if isinstance(server_value, str) and "net.minecraft:server:" in server_value and "bundled" in server_value:
                rel = base.maven_path(server_value.strip("[]"))
                target = runtime / "libraries" / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(bundled, target)
    log = runtime / f"install-{loader}-{coordinate}.log"
    base.emit(f"Installing dedicated runtime {loader} {lv}; log {log}")
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run([str(args.java), "-Xmx2G", "-XX:ActiveProcessorCount=4", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
                                 "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890",
                                 "-jar", str(installer), "--installServer", str(runtime)],
                                cwd=runtime, stdout=out, stderr=subprocess.STDOUT, timeout=900,
                                creationflags=base.NO_WINDOW)
    if result.returncode != 0 or not argument_file.exists():
        raise RuntimeError(f"Dedicated runtime installation failed ({result.returncode}): {log}")
    return argument_file


def write_args(path, arguments):
    path.write_text("\n".join('"' + value.replace("\\", "/").replace('"', '\\"') + '"' for value in arguments), encoding="utf-8")


def client_arguments(args, key, run, agent):
    command = base.client_command(args, key, run, agent)
    command = [s.replace("hassium.smokeScenario=pack_integrated", "hassium.smokeScenario=pack_dedicated") for s in command]
    at = command.index("--quickPlaySingleplayer")
    command[at:at + 2] = [] if getattr(args, "wait_resources", False) else ["--quickPlayMultiplayer", f"127.0.0.1:{args.port}"]
    command.insert(0, f"-Dhassium.smokeTest.host=127.0.0.1:{args.port}")
    if getattr(args, "trace_login", False):
        command.insert(0, "-Dhassium.diagnostic.traceLogin=true")
    if getattr(args, "trace_mixin", False):
        command.insert(0, "-Dhassium.diagnostic.traceMixin=true")
    if getattr(args, "trace_cache_restores", False):
        command.insert(0, "-Dhassium.diagnostic.traceCacheRestores=true")
    return command


def cache_restore_read_stage(client_log):
    """Successful restore returns, bounded by each new cache owner and its sampling point."""
    assemblies = list(re.finditer(r"\[SHADOW-CACHE\] assembly=connection", client_log))
    probes = [list(re.finditer(f"HassiumSmokeTest:PROBE_WRITTEN ROUND{round_}", client_log))
              for round_ in (1, 2)]
    if (len(assemblies) != 2 or any(len(items) != 1 for items in probes)
            or not assemblies[0].start() < probes[0][0].start() < assemblies[1].start() < probes[1][0].start()):
        return {"complete": False, "rounds": []}
    rounds = []
    for index in range(2):
        events = re.findall(r"\[DIAGNOSTIC-CACHE\] restored ([a-z0-9_.-]+:[a-z0-9_./-]+) ([^\r\n]+)",
                            client_log[assemblies[index].end():probes[index][0].start()])
        types = {}
        treasure_positions = []
        for type_, pos in events:
            types[type_] = types.get(type_, 0) + 1
            if type_ == "treasure2:proximity_mobset_spawner":
                treasure_positions.append(pos)
        rounds.append({"round": index + 1, "total": len(events), "types": types,
                       "treasureSpawnerPositions": treasure_positions})
    return {"complete": True, "rounds": rounds}


def server_arguments(args, key, run, agent, runtime_args):
    record = base.read_json(args.root / key / "setup.json")
    properties = ["-Xms1G", "-Xmx10G", "-XX:ActiveProcessorCount=16", f"-javaagent:{agent}",
                  "-Dhassium.serverSmokeTest=true", "-Dhassium.smokePhases=classic",
                  f"-Dhassium.serverSmokeTest.vd1={args.vd1}", f"-Dhassium.serverSmokeTest.vd2={args.vd2}",
                  "-Dhassium.serverSmokeScenario=pack_dedicated"]
    if key == "verity":
        properties.append("-Dhassium.diagnostic.traceEventSubscribers=true")
    if getattr(args, "trace_login", False):
        properties.append("-Dhassium.diagnostic.traceLogin=true")
    if record["loader"] == "fabric":
        client = base.client_command(args, key, run, agent)
        cp = client[client.index("-cp") + 1].split(";")
        game_jar = str(Path(record["source"]) / (record["name"] + ".jar"))
        cp = [p for p in cp if p != game_jar]
        server = Path.home() / ".gradle/caches/fabric-loom" / record["version"] / "minecraft-extracted_server.jar"
        cp.append(str(server))
        return properties + ["-cp", ";".join(cp), "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
    text = runtime_args.read_text(encoding="utf-8-sig")
    library_root = next(parent for parent in runtime_args.parents if parent.name == "libraries").as_posix()
    text = text.replace("libraries/", library_root + "/").replace("=libraries\n", "=" + library_root + "\n")
    target = run / "server-runtime.args"
    target.write_text(text, encoding="utf-8")
    # Flatten the installer args: Java argument files do not recursively expand @files.
    return properties + shlex.split(text) + ["nogui"]


def read_log(path):
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def critical_client_failure(text):
    match = re.search(r"Invalid modId found[^\r\n]*|HassiumSmokeTest:FAIL[^\r\n]*|Shadow server creation failed|Failed to create shadow seed server|java\.util\.ConcurrentModificationException|Light timeout \(|Failed to decode packet 'clientbound/|Hassium egress v3 connection failed|shadow main loop did not run a POI task", text)
    return match.group(0) if match else None


def dump(args, process, path):
    if process and process.poll() is None:
        with path.open("w", encoding="utf-8") as out:
            try:
                subprocess.run([str(args.java.with_name("jcmd.exe")), str(process.pid), "Thread.print", "-l"],
                               stdout=out, stderr=subprocess.STDOUT, timeout=20, creationflags=base.NO_WINDOW)
            except subprocess.TimeoutExpired:
                pass


def run_pair(args, key, agent):
    case = args.root / key
    record = base.read_json(case / "setup.json")
    run = case / "dedicated-runs" / time.strftime("%Y%m%d_%H%M%S")
    run.mkdir(parents=True)
    pinned_agent = run / "diagnostic-agent.jar"
    shutil.copy2(agent, pinned_agent)
    shutil.copy2(base.REPO / "scripts/smoke/DiagnosticSmokeAgent.java", run / "DiagnosticSmokeAgent.java")
    agent = pinned_agent
    shutil.copy2(case / "dedicated-setup.json", run / "dedicated-setup.json")
    result = {"id": key, "name": record["name"], "stage": "dedicated", "status": "PREPARING_RUNTIME",
              "started": time.strftime("%Y-%m-%d %H:%M:%S"), "setupFile": str(run / "dedicated-setup.json"),
              "hassiumSha256": record["hassiumSha256"], "serverLog": str(run / "server.log"),
              "clientLog": str(run / "client.log"), "viewDistances": [args.vd1, args.vd2], "seedGen": False,
              "vanillaChunksControl": getattr(args, "control", False), "harnessSha256": base.sha(Path(__file__))}
    result["expectCacheBootstrap"] = getattr(args, "expect_cache_bootstrap", False)
    result["traceCacheRestores"] = getattr(args, "trace_cache_restores", False)
    result["baseHarnessSha256"] = base.sha(Path(base.__file__))
    result["playerName"] = base.PLAYER_NAME
    result["offlinePlayerUuid"] = base.offline_player_uuid(base.PLAYER_NAME)
    result["agentSha256"] = base.sha(agent)
    result["traceLogin"] = getattr(args, "trace_login", False)
    result["traceMixin"] = getattr(args, "trace_mixin", False)
    if getattr(args, "reference_inventory", None):
        result["referenceInventory"] = {"path": str(args.reference_inventory.resolve()), "sha256": base.sha(args.reference_inventory)}
    result["testModExclusions"] = base.read_json(case / "dedicated-setup.json").get("testModExclusions", [])
    server = client = None
    streams = []
    try:
        base.save_json(case / "dedicated-latest.json", result)
        report(args)
        runtime_args = prepare_runtime(args, record)
        with socket.socket() as check:
            check.bind(("127.0.0.1", args.port))
        cache = case / "client/hassium_cache"
        if cache.exists():
            target = run / "previous-client-cache"
            assert cache.resolve().is_relative_to(case.resolve()) and target.resolve().is_relative_to(case.resolve())
            cache.rename(target)
        world_name = "dedicated_" + run.name
        (case / "server/server.properties").write_text(
            f"online-mode=false\nserver-ip=127.0.0.1\nserver-port={args.port}\nlevel-name={world_name}\n"
            f"level-seed=42\nview-distance={args.vd1}\nsimulation-distance=6\nspawn-protection=0\nmax-tick-time=0\n"
            "gamemode=creative\nforce-gamemode=true\n", encoding="utf-8")
        for side in ("server", "client"):
            memory = base.memory_status()
            if memory and memory["availableBytes"] < 4 * 1024**3:
                raise RuntimeError(f"Insufficient host memory before {side} launch: {memory}")
            values = server_arguments(args, key, run, agent, runtime_args) if side == "server" else client_arguments(args, key, run, agent)
            argfile = run / (side + ".args")
            write_args(argfile, values)
            stream = (run / (side + ".log")).open("w", encoding="utf-8")
            streams.append(stream)
            process = subprocess.Popen([str(args.java), "@" + str(argfile)], cwd=case / side, stdin=subprocess.PIPE,
                                       stdout=stream, stderr=subprocess.STDOUT, creationflags=base.NO_WINDOW)
            result[side + "Pid"] = process.pid
            base.emit(f"{key}: {side} PID={process.pid}; log {run / (side + '.log')}")
            if side == "server":
                server = process
                result["status"] = "WAIT_SERVER"
                base.save_json(case / "dedicated-latest.json", result)
                report(args)
                deadline = time.monotonic() + args.server_timeout
                while server.poll() is None and time.monotonic() < deadline:
                    server_log = read_log(run / "server.log")
                    startup_failure = critical_client_failure(server_log)
                    if startup_failure:
                        result["serverStartupFatal"] = startup_failure
                        break
                    if re.search(r"Done \([\d.,]+s\)!", server_log):
                        result["serverReady"] = True
                        break
                    if re.search(r"Failed to start the minecraft server|Exception in thread .main.", server_log):
                        result["serverStartupFatal"] = True
                        break
                    if (args.root / "STOP").exists():
                        result["stopRequested"] = True
                        break
                    try:
                        server.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        pass
                if not result.get("serverReady"):
                    result["status"] = "STOPPED" if result.get("stopRequested") else "SERVER_NOT_READY"
                    dump(args, server, run / "server-startup.threads.txt")
                    return result
                base.emit(f"{key}: dedicated server ready; launching full client")
            else:
                client = process
        result["status"] = "CLIENT_RUNNING"
        base.save_json(case / "dedicated-latest.json", result)
        report(args)
        deadline = time.monotonic() + args.timeout
        monitor = (run / "client.log").open("r", encoding="utf-8", errors="replace")
        server_monitor = (run / "server.log").open("r", encoding="utf-8", errors="replace")
        streams.extend((monitor, server_monitor))
        previous_text = previous_server_text = ""
        while client.poll() is None and server.poll() is None and time.monotonic() < deadline:
            if (args.root / "STOP").exists():
                result["stopRequested"] = True
                break
            memory = base.memory_status()
            if memory:
                result["minimumAvailableMemoryBytes"] = min(result.get("minimumAvailableMemoryBytes", memory["availableBytes"]), memory["availableBytes"])
                if memory["availableBytes"] < 4 * 1024**3:
                    result["runtimeFailure"] = "Host available physical memory below 4 GiB"
                    result["memoryGuard"] = memory
                    base.emit(f"{key}: low host memory; stopping this test for repair: {memory}")
                    break
            new_text = previous_text + monitor.read()
            new_server_text = previous_server_text + server_monitor.read()
            failure = critical_client_failure(new_text) or critical_client_failure(new_server_text)
            previous_text = new_text[-512:]
            previous_server_text = new_server_text[-512:]
            if failure:
                result["runtimeFailure"] = failure
                base.emit(f"{key}: runtime failure detected; stopping this test for repair: {failure}")
                break
            try:
                client.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
        result["clientExitCode"] = client.poll()
        result["status"] = "STOPPED" if result.get("stopRequested") else (
            "FAIL_RUNTIME" if result.get("runtimeFailure") else ("FAIL" if client.poll() is not None else "CLIENT_TIMEOUT"))
        if client.poll() is None:
            dump(args, client, run / "client-final.threads.txt")
            dump(args, server, run / "server-final.threads.txt")
        cl, sl = read_log(run / "client.log"), read_log(run / "server.log")
        probes = [base.read_json(p) for p in sorted((run / "probe").glob("round*.json"))]
        result["probes"] = probes
        result["handshakeCount"] = cl.count("Hassium: play init (caps=")
        result["aggregationCount"] = sl.count("Hassium: Aggregation enabled for")
        config = tomllib.loads((case / "server/config/hassium/hassium-server.toml").read_text(encoding="utf-8-sig"))
        result["aggregationConfigured"] = config.get("master", {}).get("enablePacketAggregation", True)
        result["forcedClientExit"] = "force System.exit(0) after stop()" in cl
        gates = {"exitZero": result["clientExitCode"] == 0, "smokePass": "HassiumSmokeTest:PASS" in cl and "HassiumSmokeTest:FAIL" not in cl,
                 "twoRounds": len(probes) == 2, "twoHandshakes": result["handshakeCount"] >= 2,
                 "aggregation": not result["aggregationConfigured"] or result["aggregationCount"] >= 2,
                 "serverViewSwitched": "view-distance switched to" in sl,
                 "normalClientExit": not result["forcedClientExit"],
                 "noShadowCreationFailure": not bool(re.search(r"Shadow server creation failed|Failed to create shadow seed server", cl)),
                 "noLightTimeout": "Light timeout (" not in cl,
                 "noConcurrentModification": "java.util.ConcurrentModificationException" not in cl,
                 "noPacketDecodeFailure": "Failed to decode packet 'clientbound/" not in cl,
                 "noEgressFailure": "Hassium egress v3 connection failed" not in cl + sl,
                 "noShadowBlockEntitySaveFailure": not bool(re.search(
                     r"\[hassium-[^\]\r\n]+/ERROR\].*A BlockEntity type .*exception trying to write state", cl)),
                 "noMixinLocalAnalysisFailure": "AnalyzerException: Error at instruction" not in cl + sl,
                 "noPlayerIdentityMismatch": not bool(re.search(r"Local player id .* was not found in the known players list", cl)),
                 "noPoiDispatchTimeout": "shadow main loop did not run a POI task" not in cl,
                 "noDuplicateLogin": "You logged in from another location" not in cl + sl,
                 "noFullPushTimeout": not bool(re.search(r"confirms timed out.*direct-pushing", sl))}
        if getattr(args, "expect_cache_bootstrap", False):
            gates["cacheBootstrapBothRounds"] = cl.count("[SHADOW-CACHE] assembly=connection") >= 2
            gates["noLocalDatapackAssembly"] = "Shadow packs available=" not in cl
            gates["noLocalGeneration"] = len(probes) == 2 and all(
                probe.get("stats", {}).get("locallyGeneratedChunkCount") == 0 for probe in probes)
            gates["cacheModeAndDimensionTypes"] = len(probes) == 2 and all(
                probe.get("isolation", {}).get("shadowCacheOnly") is True
                and probe.get("isolation", {}).get("shadowDimensionTypeMatches") is True for probe in probes)
        if getattr(args, "dimension", None):
            gates["authoritativeDimensionBothRounds"] = len(probes) == 2 and all(
                probe.get("dimension") == args.dimension for probe in probes)
        if getattr(args, "control", False):
            # Disabling chunk core can suppress play_init entirely on NeoForge.
            # This control tests vanilla TCP delivery, not Hassium capabilities.
            gates.pop("twoHandshakes")
            gates.pop("aggregation")
            result["notApplicable"] = {"twoHandshakes": "chunk core disabled control", "aggregation": "chunk core disabled control"}
        for index, probe in enumerate(probes, 1):
            gates[f"round{index}Creative"] = probe.get("isolation", {}).get("creativeMode") is True
            gates[f"round{index}Resident"] = probe.get("clientCache", {}).get("loadedChunks", 0) > 64
            gates[f"round{index}Terrain"] = probe.get("contentCheck", {}).get("chunksWithNonAir", 0) > 0
        if len(probes) == 2:
            result["reconnectCacheHits"] = probes[1].get("stats", {}).get("cacheHitFullChunkCount", 0)
            if not getattr(args, "control", False):
                gates["reconnectCacheHit"] = result["reconnectCacheHits"] > 0
            if key == "r3" and getattr(args, "reference_inventory", None) and not getattr(args, "dimension", None):
                gates["referenceResidentBothRounds"] = all(p.get("clientCache", {}).get("loadedChunks", 0) > 1400 for p in probes)
                gates["referenceCenterTerrainBothRounds"] = all(p.get("contentCheck", {}).get("chunksWithNonAir", 0) == 9 for p in probes)
                gates["referenceReconnectCacheHits"] = result["reconnectCacheHits"] > 100
                gates["treasureSpawnerRetainedBothRounds"] = all(p.get("isolation", {}).get("shadowTreasureSpawnerSnapshots", 0) > 0 for p in probes)
                gates["authoritativeBeRestoredFromDisk"] = probes[1].get("isolation", {}).get("shadowBeSnapshotsRestored", 0) > 0
                gates["treasureSpawnerRestoredFromDisk"] = probes[1].get("isolation", {}).get("shadowTreasureSpawnerSnapshotsRestored", 0) > 0
                if getattr(args, "trace_cache_restores", False):
                    evidence = result["cacheRestoreReadStage"] = cache_restore_read_stage(cl)
                    restored = evidence["rounds"][1] if evidence["complete"] else {}
                    gates["authoritativeBeRestoredFromDisk"] = restored.get("total", 0) > 0
                    gates["treasureSpawnerRestoredFromDisk"] = bool(restored.get("treasureSpawnerPositions"))
        result["gates"] = gates
        if all(gates.values()):
            result["status"] = ("PASS_CONTROL" if getattr(args, "control", False) else
                                "PASS_WITH_EXCLUSIONS" if result["testModExclusions"] else "PASS_DEDICATED")
    except Exception:
        result["status"] = "HARNESS_ERROR"
        result["exception"] = traceback.format_exc()
    finally:
        if client and client.poll() is None:
            result["harnessTerminatedClient"] = True
            client.terminate()
            try:
                client.wait(timeout=20)
            except subprocess.TimeoutExpired:
                client.kill()
                client.wait(timeout=10)
        if server and server.poll() is None:
            try:
                if result.get("serverStartupFatal"):
                    server.terminate()
                    result["forcedServerStop"] = True
                    server.wait(timeout=20)
                else:
                    server.stdin.write(b"stop\n")
                    server.stdin.flush()
                    server.wait(timeout=120)
            except (OSError, subprocess.TimeoutExpired):
                dump(args, server, run / "server-shutdown.threads.txt")
                server.terminate()
                try:
                    server.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=10)
                result["forcedServerStop"] = True
        result["serverExitCode"] = server.poll() if server else None
        if result["status"] in ("PASS_DEDICATED", "PASS_CONTROL", "PASS_WITH_EXCLUSIONS") and (result["serverExitCode"] != 0 or result.get("forcedServerStop")):
            result["status"] = "FAIL_SERVER_SHUTDOWN"
        for process in (client, server):
            if process and process.stdin:
                try:
                    process.stdin.close()
                except OSError:
                    pass
        for stream in streams:
            stream.close()
        result["ended"] = time.strftime("%Y-%m-%d %H:%M:%S")
        base.save_json(run / "result.json", result)
        base.save_json(case / "dedicated-latest.json", result)
        base.emit(f"{key}: {result['status']}")
    return result


def report(args):
    rows = []
    for key in base.PACKS:
        case = args.root / key
        if not (case / "dedicated-setup.json").exists():
            continue
        setup = base.read_json(case / "setup.json")
        latest = base.read_json(case / "dedicated-latest.json") if (case / "dedicated-latest.json").exists() else {"status": "QUEUED"}
        findings = base.read_json(case / "findings.json") if (case / "findings.json").exists() else None
        rows.append({"id": key, "name": setup["name"], "version": setup["version"], "loader": setup["loader"],
                     "loaderVersion": setup["loaderVersion"], "status": latest["status"], "handshakes": latest.get("handshakeCount"),
                     "aggregationConfigured": latest.get("aggregationConfigured"), "cacheHitsR2": latest.get("reconnectCacheHits"),
                     "result": str(case / "dedicated-latest.json"), "findings": findings})
    base.save_json(args.root / "dedicated-summary.json", rows)
    text = ["# 完整整合包客户端 ↔ 专用服", "", "两端均安装本工作区 Hassium；客户端保留完整整合包，服务端排除项见各包 dedicated-setup.json。",
            "验证 TCP 专用服、两次能力激活、区块驻留/地形、断连重连缓存命中与双端正常退出。SeedGen 关闭，存档压缩关闭。", "",
            "| MC | 包 | 加载器 | 状态 | 激活次数 | R2 缓存命中 |", "|---|---|---|---|---:|---:|"]
    if getattr(args, "control", False):
        text[0] = "# 区块核心关闭对照：客户端 ↔ 专用服"
        text[3] = "chunk.enabled=false：仅验证原版 TCP 两轮连接、区块驻留/地形及正常退出，不代表 Hassium 区块、握手、聚合或缓存兼容通过。"
    text.extend(f"| {r['version']} | {r['name']} | {r['loader']} {r['loaderVersion']} | {r['status']} | {r['handshakes']} | {r['cacheHitsR2']} |" for r in rows)
    notes = [r for r in rows if r["findings"]]
    if notes:
        text.extend(["", "## 失败与阻塞证据", ""])
        for row in notes:
            finding = row["findings"]
            description = finding.get("observation") or "; ".join(item["symptom"] for item in finding.get("observations", []))
            evidence = (args.root / row["id"] / "findings.json").as_posix()
            text.append(f"- {row['name']}: {description}。 [详情]({evidence})")
    text.extend(["", "范围：仅覆盖上述静止驻留与重连场景；未验收移动中的屋檐/洞口光照。服务端准备失败不等同于 Hassium 兼容失败。"])
    (args.root / "dedicated-report.md").write_text("\n".join(text) + "\n", encoding="utf-8")


def overview(args):
    """Collect phase roots without relabeling an older PASS as the current build."""
    destination = base.REPO / "build/modpack-dedicated"
    rows = []
    for key, name in base.PACKS.items():
        paths = set((base.REPO / "build").glob(f"*/{key}/dedicated-latest.json"))
        if (args.root / key / "dedicated-latest.json").exists():
            paths.add(args.root / key / "dedicated-latest.json")
        runs = [(path, base.read_json(path)) for path in paths]
        runs = [(path, run) for path, run in runs if not run.get("vanillaChunksControl")]
        if not runs:
            continue
        path, latest = max(runs, key=lambda item: item[1].get("started", ""))
        setup = base.read_json(path.parent / "setup.json")
        passed = [run for _, run in runs if run["status"] == "PASS_DEDICATED"]
        rows.append({"id": key, "name": name, "version": setup["version"], "packJarCount": len(setup["mods"]),
                     "latest": latest, "resultFile": str(path),
                     "latestPassed": max(passed, key=lambda run: run["started"]) if passed else None})
    base.save_json(destination / "overview.json", rows)
    lines = ["# 整合包专用服测试总览", "", f"更新：{time.strftime('%Y-%m-%d %H:%M:%S')}；工作区：2.0.0（产品 2.1.1）。",
             "每行显示该包最新一次运行；历史通过可能属于较早修复构建，各次 JAR 哈希和完整门禁见结果 JSON。", "",
             "| MC | 整合包 | 原包第三方 JAR 数 | 最新状态 | R2 缓存命中 | 结果 |", "|---|---|---:|---|---:|---|"]
    for row in rows:
        run = row["latest"]
        lines.append(f"| {row['version']} | {row['name']} | {row['packJarCount']} | {run['status']} | {run.get('reconnectCacheHits', '—')} | [JSON]({Path(row['resultFile']).as_posix()}) |")
    lines.extend(["", "只统计完整客户端 ↔ 本机专用服的双轮驻留/重连场景；关闭核心对照与单人通过另存。",
                  "未验收移动中的屋檐/洞口光照。服务端准备失败须先解决，不能当作 Hassium 兼容通过。"])
    (destination / "overview.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("setup", "run", "status", "runtime"))
    parser.add_argument("--packs", nargs="+", choices=list(base.PACKS), default=list(base.PACKS))
    parser.add_argument("--root", type=Path)
    parser.add_argument("--control", action="store_true", help="Diagnostic comparison with chunk.enabled=false; never counts as full-feature PASS")
    parser.add_argument("--expect-cache-bootstrap", action="store_true", help="Require connection-registry assembly in both rounds, with no local datapack assembly")
    parser.add_argument("--dimension", help="Visit an authoritative custom dimension in round one and reconnect there in round two")
    parser.add_argument("--wait-resources", action="store_true", help="Connect from the normal menu after initial resource loading, instead of QuickPlay during startup")
    parser.add_argument("--diagnostic-exclude", action="append", default=[], metavar="JAR", help="Explicitly exclude an exact original JAR from both disposable test sides; can only produce PASS_WITH_EXCLUSIONS")
    parser.add_argument("--trace-login", action="store_true", help="Test-agent packet class/size and disconnect traces, without payload contents")
    parser.add_argument("--trace-mixin", action="store_true", help="Trace Mixin local-table analysis target/method/maxStack without modifying its computation")
    parser.add_argument("--trace-cache-restores", action="store_true", help="Observe successful disk BE restores before authority refresh replaces the sampled provenance")
    parser.add_argument("--reference-inventory", type=Path, help="Use an exact successful client/server JAR inventory, verified by SHA256")
    parser.add_argument("--continue-on-failure", action="store_true", help="Explicitly inventory failures instead of stopping for a fix")
    parser.add_argument("--minecraft", type=Path, default=Path(r"D:\MC\HMCL\.minecraft"))
    parser.add_argument("--java", type=Path, default=Path(r"D:\app\graalvm-jdk-21.0.5\bin\java.exe"))
    parser.add_argument("--port", type=int, default=25579)
    parser.add_argument("--vd1", type=int, default=12)
    parser.add_argument("--vd2", type=int, default=8)
    parser.add_argument("--server-timeout", type=int, default=900)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()
    args.root = (args.root or base.REPO / ("build/modpack-dedicated-control" if args.control else "build/modpack-dedicated")).resolve()
    args.root.mkdir(parents=True, exist_ok=True)
    with base.runtime_lock(args) if args.action == "run" else base.contextlib.nullcontext():
        execute(args)


def execute(args):
    if args.action != "status":
        for key in args.packs:
            setup_pair(args, key)
        report(args)
    if args.action == "runtime":
        for key in args.packs:
            prepare_runtime(args, base.read_json(args.root / key / "setup.json"))
    if args.action == "run":
        agent = base.build_agent(args)
        failures = 0
        for key in args.packs:
            if (args.root / "STOP").exists():
                break
            result = run_pair(args, key, agent)
            failed = result["status"] not in ("PASS_DEDICATED", "PASS_CONTROL", "PASS_WITH_EXCLUSIONS")
            failures += failed
            report(args)
            if failed and not args.continue_on_failure:
                base.emit(f"Stopping queue after {key}; fix this failure before testing another pack")
                break
        if failures:
            raise SystemExit(2)
    report(args)
    if args.action == "status":
        overview(args)


if __name__ == "__main__":
    main()
