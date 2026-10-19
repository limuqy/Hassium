"""Windows HMCL production-JAR compatibility smoke; original instances are read-only.

Stage integrated checks full-pack loading and real terrain residency. It does not
certify the dedicated-server handshake, shadow cache, movement, or reconnect.
"""
import argparse
import contextlib
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import traceback
import zipfile

REPO = Path(__file__).resolve().parent.parent
PACKS = {
    "fo1201": "Fabulously Optimized 1.20.1",
    "xplus1201": "XPlus 2.0 based on Minecraft 1.20.1 (Fabric)",
    "bmc4": "BMC4 [FORGE] 1.20.1",
    "atm9": "All the Mods 9",
    "verity": "VerityCraft - MC 1.20.1",
    "pixelmon": "The Pixelmon Modpack",
    "create": "All of Create",
    "atm302": "ATM 3.0.2",
    "bmc5": "Better MC [NEOFORGE] BMC5",
    "tensura": "Tensura Neo Otherworld",
    "atm10": "All the Mods 10",
    "r3": "Hassium Community Reconstruction R3",
}
PACK_FOLDERS = (
    "config", "defaultconfigs", "configureddefaults", "kubejs", "scripts",
    "data", "datapacks", "global_packs", "global_data_packs", "openloader",
    "resourcepacks", "shaderpacks", "packmenu", "fancymenu_data",
    "patchouli_books", "moonlight-global-datapacks", "ModTranslations",
)
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def emit(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


@contextlib.contextmanager
def runtime_lock(args):
    """One game pair across all phase roots, including diagnostic controls."""
    path = REPO / "build/modpack-runtime.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise SystemExit("Another modpack runtime test owns the workspace lock; run clients sequentially.")
        try:
            save_json(path.with_suffix(".json"), {"pid": os.getpid(), "root": str(args.root),
                       "started": time.strftime("%Y-%m-%d %H:%M:%S"), "control": getattr(args, "control", False)})
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def memory_status():
    if os.name != "nt":
        return None
    class Status(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                    *[(name, ctypes.c_ulonglong) for name in ("total", "available", "pageTotal", "pageAvailable", "virtualTotal", "virtualAvailable", "extended")]]
    status = Status()
    status.length = ctypes.sizeof(status)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
        return None
    return {"totalBytes": status.total, "availableBytes": status.available, "loadPercent": status.load}


def build_agent(args):
    folder = args.root / "tools"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / "diagnostic-agent.jar"
    source = REPO / "scripts/smoke/DiagnosticSmokeAgent.java"
    if target.exists() and target.stat().st_mtime >= source.stat().st_mtime:
        return target
    asm = args.minecraft / "libraries/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
    classes = folder / "classes"
    classes.mkdir(exist_ok=True)
    subprocess.run([str(args.java.with_name("javac.exe")), "--release", "17", "-cp", str(asm),
                    "-d", str(classes), str(source)], check=True, creationflags=NO_WINDOW)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as out:
        out.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: DiagnosticSmokeAgent\n\n")
        for file in classes.glob("*.class"):
            out.write(file, file.name)
        with zipfile.ZipFile(asm) as library:
            for name in library.namelist():
                if name.startswith("org/objectweb/asm/") and name.endswith(".class"):
                    out.writestr(name, library.read(name))
    return target


def setup(args, key):
    case = args.root / key
    if (case / "setup.json").exists():
        return read_json(case / "setup.json")
    if case.exists():
        raise RuntimeError(f"Incomplete case already exists; select a fresh --root: {case}")
    source = args.minecraft / "versions" / PACKS[key]
    metadata = read_json(source / (source.name + ".json"))
    patches = {patch["id"]: patch for patch in metadata["patches"]}
    version = patches["game"]["version"]
    loader = next(name for name in ("fabric", "forge", "neoforge") if name in patches)
    if version not in ("1.20.1", "1.21.1"):
        raise RuntimeError(f"Unexpected Minecraft version: {version}")
    release = re.search(r"(?m)^mod_version\s*=\s*(\S+)", (REPO / "gradle.properties").read_text(encoding="utf-8-sig"))
    if release is None:
        raise RuntimeError("Missing mod_version in gradle.properties")
    artifacts = list((REPO / loader / "build" / version.replace(".", "_") / "libs").glob(
        f"hassium-{loader}-{release.group(1)}-{version}.jar"))
    if len(artifacts) != 1:
        raise RuntimeError(f"Build the exact production JAR first: {artifacts}")
    artifact = artifacts[0]
    client = case / "client"
    (client / "mods").mkdir(parents=True)
    pinned = case / "artifacts" / artifact.name
    pinned.parent.mkdir()
    shutil.copy2(artifact, pinned)
    source_diff = subprocess.check_output(["git", "diff", "--", "common", "fabric", "forge", "neoforge"], cwd=REPO)
    (pinned.parent / "source.diff").write_bytes(source_diff)
    untracked_source = {}
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--",
                                         "common/src", "fabric/src", "forge/src", "neoforge/src"], cwd=REPO, text=True)
    for relative in untracked.splitlines():
        source_file = REPO / relative
        destination = pinned.parent / "source-untracked" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, destination)
        untracked_source[relative] = sha(source_file)
    save_json(pinned.parent / "source-untracked.json", untracked_source)
    files, replaced = [], []
    for file in sorted((source / "mods").glob("*.jar")):
        if file.name.lower().startswith("hassium-"):
            replaced.append(file.name)
            continue
        shutil.copy2(file, client / "mods" / file.name)
        files.append({"name": file.name, "sha256": sha(file)})
    for folder in PACK_FOLDERS:
        if (source / folder).is_dir():
            shutil.copytree(source / folder, client / folder)
    for name in ("manifest.json", "modrinth.index.json"):
        if (source / name).is_file():
            shutil.copy2(source / name, client / name)
    overrides = []
    if key == "pixelmon":
        # Its starter selection screen pauses the integrated server before terrain
        # delivery finishes. Defer selection only in this disposable test copy.
        config = client / "config/pixelmon/config.yml"
        fallback = REPO / "build/modpack-compat/pixelmon/client/config/pixelmon/config.yml"
        text = config.read_text(encoding="utf-8-sig") if config.exists() else (
            fallback.read_text(encoding="utf-8-sig") if fallback.exists() else "starter-on-join: false\n")
        text, count = re.subn(r"(?m)^starter-on-join:.*$", "starter-on-join: false", text)
        if count == 0:
            text += "\nstarter-on-join: false\n"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(text, encoding="utf-8")
        overrides.append("pixelmon/config.yml: starter-on-join=false (defer paused starter selection UI)")
    cache_preparation = None
    if version == "1.20.1" and any(f["name"].startswith("gml-") for f in files):
        mapping = args.minecraft / "libraries/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip"
        if mapping.is_file():
            with zipfile.ZipFile(mapping) as archive:
                if archive.testzip() is not None:
                    raise RuntimeError(f"Invalid local GML mapping seed: {mapping}")
            destination = client / "mod_data/gml/1.20.1/srg.zip"
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(mapping, destination)
            cache_preparation = {"source": str(mapping), "sha256": sha(mapping), "path": str(destination)}
    # Only a project-owned level.dat is used; no personal saves or regions are copied.
    fixture = REPO / loader / "run/server" / f"parity_{loader}_{version.replace('.', '_')}" / "level.dat"
    if not fixture.is_file():
        raise FileNotFoundError(f"Run the project smoke first to create its fixture: {fixture}")
    target = client / "mods" / artifact.name
    shutil.copy2(artifact, target)
    with zipfile.ZipFile(target, "a", compression=zipfile.ZIP_DEFLATED) as jar:
        jar.writestr("hassium/smoke/scenario/pack_integrated.scenario",
                     "join integrated=true label=WAIT_PACK_WORLD since=start timeoutMs=${joinTimeoutMs}\n"
                     "wait ms=15000\n"
                     "assertProbe key=clientCache.loadedChunks op=gt value=64\n"
                     "assertProbe key=contentCheck.chunksWithNonAir op=gt value=0\n"
                     "dump label=ROUND1 round=1 vanilla=true\nexit rounds=1\n")
    with zipfile.ZipFile(artifact) as original, zipfile.ZipFile(target) as diagnostic:
        class_match = all(original.read(n) == diagnostic.read(n) for n in original.namelist() if n.endswith(".class"))
        if not class_match:
            raise RuntimeError("Diagnostic resource copy changed gameplay classes")
    (client / "options.txt").write_text(
        "renderDistance:10\nsimulationDistance:6\nmaxFps:60\nfullscreen:false\n"
        "enableVsync:false\ngraphicsMode:1\njoinedFirstServer:true\npauseOnLostFocus:false\n", encoding="utf-8")
    # No storage format changes in this integrated-world stage.
    save_json(case / "version.json", metadata)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    record = dict(id=key, name=source.name, source=str(source), version=version, loader=loader,
                  loaderVersion=patches[loader]["version"], sourceCommit=commit,
                  hassiumJar=str(artifact), pinnedHassiumJar=str(pinned), hassiumSha256=sha(artifact), diagnosticJarSha256=sha(target),
                  gameplayClassBytesMatch=class_match, replacedHassium=replaced, mods=files,
                  testOverrides=overrides,
                  cachePreparation=cache_preparation,
                  sourceDiffSha256=hashlib.sha256(source_diff).hexdigest(),
                  fixtureLevelDat=str(fixture), fixtureSha256=sha(fixture),
                  stage="integrated", dedicatedNetworkStatus="NOT_TESTED")
    save_json(case / "setup.json", record)
    emit(f"Prepared {key}: {source.name} / {loader} {patches[loader]['version']} / {len(files)} pack JARs + Hassium")
    return record


def allowed(entry):
    result = not entry.get("rules")
    for rule in entry.get("rules", []):
        system = rule.get("os", {})
        matches = system.get("name", "windows") in ("windows", "universal")
        if system.get("arch") not in (None, "x86_64", "amd64"):
            matches = False
        if rule.get("features"):
            matches = False
        if matches:
            result = rule["action"] == "allow"
    return result


def maven_path(name):
    group, artifact, version, *classifier = name.split(":")
    suffix = "-" + classifier[0] if classifier else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}.jar"


def client_command(args, key, run, agent):
    case = args.root / key
    record = read_json(case / "setup.json")
    metadata = read_json(case / "version.json")
    client = case / "client"
    source = Path(record["source"])
    libraries = args.minecraft / "libraries"
    natives = client / "natives"
    natives.mkdir(exist_ok=True)
    paths = []
    for lib in metadata["libraries"]:
        if not allowed(lib) or any(s in lib["name"] for s in ("natives-windows-arm64", "natives-windows-x86")):
            continue
        artifact = lib.get("downloads", {}).get("artifact", {})
        path = libraries / artifact.get("path", maven_path(lib["name"]))
        if not path.exists():
            raise FileNotFoundError(path)
        paths.append(path)
        if "natives-windows" in lib["name"]:
            with zipfile.ZipFile(path) as jar:
                for name in jar.namelist():
                    if name.lower().endswith(".dll"):
                        target = natives / Path(name).name
                        if not target.exists():
                            target.write_bytes(jar.read(name))
    version, loader_version = record["version"], record["loaderVersion"]
    if record["loader"] == "forge":
        forge_version = version + "-" + loader_version
        for artifact, classifier in (("forge", "universal"), ("forge", "client"), ("fmlcore", None),
                                     ("javafmllanguage", None), ("lowcodelanguage", None), ("mclanguage", None)):
            paths.append(libraries / maven_path(f"net.minecraftforge:{artifact}:{forge_version}" + (":" + classifier if classifier else "")))
        paths.append(libraries / "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-extra.jar")
    elif record["loader"] == "neoforge":
        # NeoForgeClientLaunchHandler locates BOTH the patched client and universal
        # system mod via libraryDirectory. Adding them to the boot classpath claims
        # the artifacts too early, hiding the system mod or creating split packages.
        paths.append(libraries / "net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar")
    game_jar = source / (source.name + ".jar")
    paths.append(game_jar)
    for path in paths:
        if not path.exists():
            raise FileNotFoundError(path)
    values = dict(natives_directory=str(natives), launcher_name="HassiumPackCompat", launcher_version="1",
                  classpath=";".join(str(p) for p in dict.fromkeys(paths)), library_directory=str(libraries),
                  classpath_separator=";", version_name=source.name, primary_jar_name=game_jar.name,
                  auth_player_name="HassiumCompat", game_directory=str(client), assets_root=str(args.minecraft / "assets"),
                  assets_index_name=metadata["assetIndex"]["id"], auth_uuid="80d5a03af2c23bb3b0d6a53e7920bc41",
                  auth_access_token="0", clientid="", auth_xuid="", user_type="legacy", version_type="compatibility-test")

    def expand(value):
        return re.sub(r"\$\{([^}]+)\}", lambda m: values[m[1]], value)

    def arguments(entries):
        result = []
        for entry in entries:
            if isinstance(entry, str):
                result.append(expand(entry))
            elif allowed(entry):
                parts = entry["value"]
                result.extend(expand(p) for p in (parts if isinstance(parts, list) else [parts]))
        return result

    return ["-Xms1G", "-Xmx10G", "-XX:ActiveProcessorCount=16", f"-javaagent:{agent}",
            "-Dhassium.smokeTest=true", "-Dhassium.smokeScenario=pack_integrated",
            "-Dhassium.smokeTest.gracefulStopGraceMs=60000",
            f"-Dhassium.smokeTest.probeDir={run / 'probe'}", "-Dhassium.smokeTest.joinTimeoutMs=600000",
            *arguments(metadata["arguments"]["jvm"]), metadata["mainClass"],
            *arguments(metadata["arguments"]["game"]), "--quickPlaySingleplayer", "compat_" + run.name,
            "--width", "854", "--height", "480"]


def run_case(args, key, agent):
    case = args.root / key
    record = read_json(case / "setup.json")
    run = case / "runs" / time.strftime("%Y%m%d_%H%M%S")
    run.mkdir(parents=True)
    result = dict(id=key, name=record["name"], stage="integrated", status="RUNNING", started=time.strftime("%Y-%m-%d %H:%M:%S"),
                  sourceCommit=record["sourceCommit"], hassiumSha256=record["hassiumSha256"],
                  setupFile=str(case / "setup.json"),
                  log=str(run / "client.log"), agentSha256=sha(agent), java=str(args.java),
                  dedicatedNetworkStatus="NOT_TESTED")
    result["gracefulStopGraceMs"] = 60000
    process = None
    try:
        world = case / "client/saves" / ("compat_" + run.name)
        world.mkdir(parents=True)
        shutil.copy2(Path(record["fixtureLevelDat"]), world / "level.dat")
        result["testWorld"] = str(world)
        command = client_command(args, key, run, agent)
        argfile = run / "client.args"
        argfile.write_text("\n".join('"' + part.replace("\\", "/").replace('"', '\\"') + '"' for part in command), encoding="utf-8")
        save_json(run / "command.json", [str(args.java), "@" + str(argfile)])
        with (run / "client.log").open("w", encoding="utf-8") as stream:
            process = subprocess.Popen([str(args.java), "@" + str(argfile)], cwd=case / "client", stdout=stream,
                                       stderr=subprocess.STDOUT, stdin=subprocess.PIPE, creationflags=NO_WINDOW)
            result["pid"] = process.pid
            save_json(case / "latest.json", result)
            report(args)
            emit(f"{key}: client PID {process.pid}; log {run / 'client.log'}")
            deadline = time.monotonic() + args.timeout
            while process.poll() is None and time.monotonic() < deadline:
                if (args.root / "STOP").exists():
                    result["stopRequested"] = True
                    break
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    pass
            result["exitCode"] = process.poll()
            if process.poll() is None:
                result["status"] = "STOPPED" if result.get("stopRequested") else "TIMEOUT"
                with (run / "threads.txt").open("w", encoding="utf-8") as dump:
                    try:
                        subprocess.run([str(args.java.with_name("jcmd.exe")), str(process.pid), "Thread.print", "-l"],
                                       stdout=dump, stderr=subprocess.STDOUT, timeout=20, creationflags=NO_WINDOW)
                    except subprocess.TimeoutExpired:
                        pass
            else:
                result["status"] = "FAIL"
        text = (run / "client.log").read_text(encoding="utf-8", errors="replace")
        probes = [read_json(p) for p in sorted((run / "probe").glob("round*.json"))]
        result["probes"] = probes
        result["forcedExit"] = "force System.exit(0) after stop()" in text
        result["allChunksSaved"] = "All chunks are saved" in text
        if result["exitCode"] == 0 and "HassiumSmokeTest:PASS" in text and "HassiumSmokeTest:FAIL" not in text and probes:
            probe = probes[-1]
            if probe.get("clientCache", {}).get("loadedChunks", 0) > 64 and probe.get("contentCheck", {}).get("chunksWithNonAir", 0) > 0:
                result["status"] = "PASS_INTEGRATED"
        result["errorExcerpt"] = [line for line in text.splitlines() if re.search(
            r"HassiumSmokeTest:FAIL|Caused by:|Exception in thread|^[\w.$]+(?:Exception|Error):|ModLoadingException|requires.*version|Incompatible mod set|Mixin apply.*failed", line)][-30:]
    except Exception:
        result["status"] = "HARNESS_ERROR"
        result["exception"] = traceback.format_exc()
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            result["cleanupExitCode"] = process.returncode
        result["ended"] = time.strftime("%Y-%m-%d %H:%M:%S")
        save_json(run / "result.json", result)
        save_json(case / "latest.json", result)
        emit(f"{key}: {result['status']}")
    return result


def report(args):
    records = []
    for key in PACKS:
        case = args.root / key
        if not (case / "setup.json").exists():
            continue
        latest = read_json(case / "latest.json") if (case / "latest.json").exists() else {"status": "QUEUED"}
        setup_record = read_json(Path(latest.get("setupFile", case / "setup.json")))
        probes = latest.get("probes", [])
        probe = probes[-1] if probes else {}
        forced_exit = latest.get("forcedExit")
        all_saved = latest.get("allChunksSaved")
        if forced_exit is None and latest.get("log"):
            text = Path(latest["log"]).read_text(encoding="utf-8", errors="replace")
            forced_exit = "force System.exit(0) after stop()" in text
            all_saved = "All chunks are saved" in text
        records.append({"id": key, "name": setup_record["name"], "version": setup_record["version"],
                        "loader": setup_record["loader"], "loaderVersion": setup_record["loaderVersion"],
                        "packJars": len(setup_record["mods"]), "status": latest["status"],
                        "residentChunks": probe.get("clientCache", {}).get("loadedChunks"),
                        "nonAirChunks": probe.get("contentCheck", {}).get("chunksWithNonAir"),
                        "testOverrides": setup_record.get("testOverrides", []),
                        "forcedExit": forced_exit, "allChunksSaved": all_saved, "log": latest.get("log"),
                        "baselineResultFile": latest.get("baselineResultFile"),
                        "followupReason": latest.get("followupReason"),
                        "dedicatedNetworkStatus": "NOT_TESTED"})
    save_json(args.root / "summary.json", records)
    rows = ["# HMCL 整合包兼容性首轮", "", "本轮仅验证完整包启动、单人新世界加载、实际区块及非空气地形驻留。",
            "多人握手、影子端、缓存重连与移动光照尚未验证；PASS_INTEGRATED 不等于完整兼容性通过。", "",
            "| MC | 整合包 | 加载器 | 包内 JAR（另加 Hassium） | 首轮状态 | 实驻留 / 非空气 |", "|---|---|---|---:|---|---|"]
    rows.extend(f"| {r['version']} | {r['name']} | {r['loader']} {r['loaderVersion']} | {r['packJars']} | {r['status']} | {r['residentChunks']} / {r['nonAirChunks']} |" for r in records)
    rows.extend(["", "Pixelmon 测试副本延后初始宝可梦选择界面（starter-on-join=false），避免暂停内置服；原配置保留。",
                 "每次运行使用新的测试世界。shutdown forcedExit 与原始日志单独保留，不将强退兜底视为正常保存证明。"])
    for record in records:
        if record.get("forcedExit"):
            rows.extend(["", f"{record['name']}：进程退出码 0，但触发过退出兜底；各维度保存完成日志={record['allChunksSaved']}。该记录只支持首轮启动/世界驻留通过，退出链单独保留跟踪。"])
        if record.get("followupReason"):
            rows.extend(["", f"{record['name']} 复测说明：{record['followupReason']}",
                         f"首场结果：`{record['baselineResultFile']}`。本表使用复测的探针和退出结果，详细构建见 latest.json 的 setupFile。"])
    (args.root / "report.md").write_text("\n".join(rows) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("setup", "run", "status"))
    parser.add_argument("--packs", nargs="+", choices=list(PACKS), default=list(PACKS))
    parser.add_argument("--root", type=Path, default=REPO / "build/modpack-compat")
    parser.add_argument("--minecraft", type=Path, default=Path(r"D:\MC\HMCL\.minecraft"))
    parser.add_argument("--java", type=Path, default=Path(r"D:\app\graalvm-jdk-21.0.5\bin\java.exe"))
    parser.add_argument("--timeout", type=int, default=780)
    args = parser.parse_args()
    args.root = args.root.resolve()
    args.root.mkdir(parents=True, exist_ok=True)
    with runtime_lock(args) if args.action == "run" else contextlib.nullcontext():
        execute(args)


def execute(args):
    if args.action != "status":
        for key in args.packs:
            setup(args, key)
        report(args)
    if args.action == "run":
        agent = build_agent(args)
        failures = 0
        for key in args.packs:
            if (args.root / "STOP").exists():
                break
            result = run_case(args, key, agent)
            failures += result["status"] != "PASS_INTEGRATED"
            report(args)
        if failures:
            raise SystemExit(2)
    report(args)


if __name__ == "__main__":
    main()
