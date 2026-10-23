"""Paired Hassium JAR benchmark with independent input/metrics.

Run from pwsh with --arms before after and explicit --before-jar / --after-jar.
Output goes to a fresh <output-root>/<label>-<arm> folder. The before/after
arms each install the explicitly selected Hassium JAR with identical fixtures.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import shutil
import socket
import subprocess
import time
import zipfile
from smoke.cpu_isolation import cpu_plan, popen_pinned, verify_affinity

REPO = Path(__file__).resolve().parent.parent
JAVA = Path(r"D:\app\graalvm-jdk-21.0.5\bin\java.exe")
MINECRAFT = Path(r"D:\MC\HMCL\.minecraft")
ROOT = REPO / "build/analysis/spark-20261023"
ACTIVE_ROOT = ROOT
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
spec = importlib.util.spec_from_file_location("dedicated", REPO / "scripts/modpack-dedicated.py")
dedicated = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dedicated)
base = dedicated.base


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def emit(text):
    print(time.strftime("%H:%M:%S"), text, flush=True)


def agent(tools):
    classes = tools / "classes"
    sources = [REPO / "scripts/smoke" / (name + ".java") for name in ("SparkBenchAgent", "SparkBenchBridge")]
    source_hashes = {path.name: base.sha(path) for path in sources}
    instrument, helper = tools / "agent.jar", tools / "bridge.jar"
    manifest = tools / "instrumentation.json"
    if manifest.exists():
        record = json.loads(manifest.read_text(encoding="utf-8"))
        if record.get("sourceSha256") != source_hashes or not instrument.is_file() or not helper.is_file():
            raise RuntimeError(f"Frozen instrumentation does not match source or is incomplete: {tools}")
        if record.get("agentSha256") != base.sha(instrument) or record.get("bridgeSha256") != base.sha(helper):
            raise RuntimeError(f"Frozen instrumentation artifacts were modified: {tools}")
        return instrument, helper
    if tools.exists() and any(tools.iterdir()):
        raise RuntimeError(f"Refusing to overwrite non-empty instrumentation directory: {tools}")
    classes.mkdir(parents=True, exist_ok=True)
    asm = MINECRAFT / "libraries/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
    subprocess.run([str(JAVA.with_name("javac.exe")), "--release", "17", "-cp", str(asm),
                    "-d", str(classes), *map(str, sources)], check=True, creationflags=FLAGS)
    with zipfile.ZipFile(helper, "w") as out:
        for file in classes.glob("SparkBenchBridge*.class"):
            out.write(file, file.name)
    with zipfile.ZipFile(instrument, "w") as out:
        out.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: SparkBenchAgent\n\n")
        for file in classes.glob("SparkBenchAgent*.class"):
            out.write(file, file.name)
        with zipfile.ZipFile(asm) as library:
            for name in library.namelist():
                if name.startswith("org/objectweb/asm/") and name.endswith(".class"):
                    out.writestr(name, library.read(name))
    save(manifest, {"sourceSha256": source_hashes, "agentSha256": base.sha(instrument),
                    "bridgeSha256": base.sha(helper), "asmSha256": base.sha(asm)})
    return instrument, helper


def datapack(world, version, entities):
    pack = world / "datapacks/sparkbench"
    singular = "function" if version == "1.21.1" else "functions"
    funcs = pack / "data/sparkbench" / singular
    funcs.mkdir(parents=True)
    save(pack / "pack.mcmeta", {"pack": {"pack_format": 48 if version == "1.21.1" else 15,
                                          "description": "Local deterministic packet stress fixture"}})
    save(pack / "data/minecraft/tags" / singular / "tick.json", {"values": ["sparkbench:tick"]})
    (funcs / "tick.mcfunction").write_text(
        "execute if score enabled sparkbench matches 1 run function sparkbench:load\n", encoding="utf-8")
    (funcs / "load.mcfunction").write_text(
        "scoreboard players add clock sparkbench 1\n"
        "execute if score clock sparkbench matches 2.. run scoreboard players set clock sparkbench 0\n"
        "execute if score clock sparkbench matches 0 run function sparkbench:a\n"
        "execute if score clock sparkbench matches 1 run function sparkbench:b\n", encoding="utf-8")
    rng = random.Random(20261023)
    for name, offset in (("a", -4), ("b", 4)):
        payload = "".join(rng.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(1024))
        component = json.dumps({"text": payload}, separators=(",", ":"))
        commands = [f'execute at HassiumCompat as @e[type=minecraft:armor_stand,tag=sparkbench_load] run tp @s ~{offset} ~-8 ~',
                    f'execute as @e[type=minecraft:armor_stand,tag=sparkbench_load] run data merge entity @s {{CustomName:\'{component}\'}}']
        (funcs / f"{name}.mcfunction").write_text("\n".join(commands) + "\n", encoding="utf-8")
    commands = ['kill @e[tag=sparkbench_load]']
    for i in range(entities):
        commands.append('execute at HassiumCompat run summon minecraft:armor_stand ~ ~-8 ~ '
                        '{Tags:["sparkbench_load"],Invisible:1b,Marker:1b,NoGravity:1b,Invulnerable:1b}')
    (funcs / "spawn.mcfunction").write_text("\n".join(commands) + "\n", encoding="utf-8")


def setup(options, arm, instrument, helper):
    key = "atm9" if options.loader == "forge" else "atm302"
    template = REPO / "build/modpack-dedicated" / key
    record = base.read_json(template / "setup.json")
    version = record["version"]
    run = options.output_root / f"{options.label}-{arm}"
    run.mkdir(parents=True, exist_ok=False)
    case = run / key
    case.mkdir()
    save(case / "setup.json", record)
    shutil.copy2(template / "version.json", case / "version.json")
    worldzip = REPO / "build/analysis/egress-window-20261022" / f"world-{options.loader}.zip"
    server, client = case / "server", case / "client"
    for side in (server, client):
        (side / "mods").mkdir(parents=True)
        spark = Path(r"C:\Users\xiaolin\Downloads") / ("spark-1.10.53-forge.jar" if options.loader == "forge" else "spark-1.10.124-neoforge.jar")
        shutil.copy2(spark, side / "mods" / spark.name)
        save(side / "config/spark/config.json", {"backgroundProfiler": False, "enableNetworkMonitoring": False})
        jar = None
        if arm in ("hassium", "before", "after"):
            if arm == "before":
                jar = options.before_jar
            elif arm == "after":
                jar = options.after_jar
            else:
                jar = REPO / options.loader / "build" / version.replace(".", "_") / "libs" / f"hassium-{options.loader}-2.1.3-{version}.jar"
            if not jar or not jar.is_file():
                raise FileNotFoundError(f"Hassium JAR for arm {arm} does not exist: {jar}")
            shutil.copy2(jar, side / "mods" / jar.name)
            for kind in ("client", "server"):
                source = REPO / options.loader / "run" / kind / "config/hassium" / f"hassium-{kind}.toml"
                target = side / "config/hassium" / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                config = target.read_text(encoding="utf-8-sig")
                head, debug = config.split("[debug]", 1)
                debug = re.sub(r"(?m)^(\s*\w+\s*=\s*)true\s*$", r"\1false", debug)
                target.write_text(head + "[debug]" + debug, encoding="utf-8")
            dedicated.patch_toml(side / "config/hassium/hassium-client.toml", {
                "chunk": {"enabled": "true", "seedGenEnabled": "false", "viewDistanceExtensionEnabled": "false"},
                })
            dedicated.patch_toml(side / "config/hassium/hassium-server.toml", {
                "storage": {"enabled": "false"}, "master": {"enabled": "true", "sleepEnabled": "false", "aggregationMaxWaitTimeMs": "50"},
                "chunk": {"seedGenEnabled": "false"}})
    world = server / "world"
    world.mkdir()
    with zipfile.ZipFile(worldzip) as archive:
        for member in archive.infolist():
            target = (world / member.filename).resolve()
            if not target.is_relative_to(world.resolve()):
                raise ValueError("Invalid world archive path")
        archive.extractall(world)
    datapack(world, version, options.entities)
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text(
        "level-name=world\nlevel-seed=42\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=25565\n"
        "view-distance=8\nsimulation-distance=6\ngamemode=creative\nforce-gamemode=true\ndifficulty=peaceful\n"
        "spawn-protection=0\nallow-flight=true\nnetwork-compression-threshold=256\nmax-tick-time=60000\n"
        "sync-chunk-writes=true\n", encoding="utf-8")
    (client / "options.txt").write_text(
        "renderDistance:8\nsimulationDistance:6\nmaxFps:260\nfullscreen:false\nenableVsync:false\n"
        "graphicsMode:1\njoinedFirstServer:true\npauseOnLostFocus:false\nentityShadows:false\n"
        "cloudStatus:0\nparticles:2\nrenderClouds:false\nfov:0.0\nsoundCategory_master:0.0\n", encoding="utf-8")
    args = argparse.Namespace(root=run, minecraft=MINECRAFT, java=JAVA, port=25565, vd1=8, vd2=8)
    runtime = dedicated.prepare_runtime(args, record)
    commands = {}
    for side in ("server", "client"):
        args_list = (dedicated.server_arguments(args, key, run, instrument, runtime) if side == "server"
                     else dedicated.client_arguments(args, key, run, instrument))
        args_list = [p for p in args_list if not p.startswith(("-Dhassium", "-javaagent:", "-Xmx", "-Xms", "-XX:ActiveProcessorCount"))]
        domain = options.cpu_plan.get(side) if options.cpu_plan["enabled"] else None
        processors = len(domain["logicalProcessors"]) if domain else 16
        args_list = ["-Xms2G", "-Xmx6G", f"-XX:ActiveProcessorCount={processors}", "-Dhassium.chunkProfile=true",
                     f"-javaagent:{instrument}={helper}",
                     f"-Dsparkbench.dir={run / (side + '-control')}", f"-Dsparkbench.side={side}", *args_list]
        dedicated.write_args(run / f"{side}.args", args_list)
        commands[side] = [str(JAVA), "@" + str(run / f"{side}.args")]
    source_paths = sorted(p for module in ("common", options.loader) for p in (REPO / module / "src").rglob("*")
                          if p.is_file() and p.suffix in (".java", ".json", ".toml", ".properties"))
    source_digest = hashlib.sha256()
    for path in source_paths:
        source_digest.update(path.relative_to(REPO).as_posix().encode("utf-8"))
        source_digest.update(b"\0")
        source_digest.update(bytes.fromhex(base.sha(path)))
    inputs = {"arm": arm, "version": version, "loader": options.loader, "loaderVersion": record["loaderVersion"],
              "java": str(JAVA), "worldZipSha256": base.sha(worldzip), "entities": options.entities,
              "warmupSeconds": options.warmup, "measureSeconds": options.seconds,
              "chunkPipelineProfile": True,
              "primaryServerMetric": "tickServer wall MSPT (mean, P50, P95, P99, ticks > 50 ms)",
              "cpuIsolation": options.cpu_plan,
              "outputRoot": str(options.output_root), "productionSourceFileCount": len(source_paths),
              "productionSourceTreeSha256": source_digest.hexdigest(),
              "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "mods": {side: {p.name: base.sha(p) for p in (case / side / "mods").glob("*.jar")} for side in ("server", "client")},
              "hassiumJar": ({"source": str(jar.resolve()), "sha256": base.sha(jar)} if jar else None),
              "instrumentation": {"root": str(options.instrumentation_root.resolve()),
                                  "agentSha256": base.sha(instrument), "bridgeSha256": base.sha(helper)},
              "configSha256": {p.relative_to(case).as_posix(): base.sha(p) for p in sorted(case.rglob("*"))
                               if p.is_file() and (p.name.endswith(".toml") or p.name in
                                   ("config.json", "server.properties", "options.txt"))},
              "source": {p.name: base.sha(p) for p in [Path(__file__), REPO / "scripts/smoke/SparkBenchAgent.java", REPO / "scripts/smoke/SparkBenchBridge.java",
                         REPO / "scripts/smoke/cpu_isolation.py", REPO / "scripts/smoke/cpu-isolation.ps1",
                         REPO / "scripts/smoke/describe-cpu-isolation.ps1", REPO / "scripts/smoke/WindowsCpuTopology.cs"]}}
    save(run / "inputs.json", inputs)
    return run, case, commands


def wait_for(predicate, timeout, processes, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"{description}: process {process.pid} exited {process.returncode}")
        if predicate():
            return
        if (ACTIVE_ROOT / "STOP").exists():
            raise RuntimeError("Benchmark stop requested")
        # Bounded condition polling, never an unconditional wait for a build.
        time.sleep(0.2)
    raise TimeoutError(description)


def command(run, side, text, process):
    folder = run / (side + "-control")
    folder.mkdir(exist_ok=True)
    token = str(time.time_ns())
    temporary = folder / (token + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.rename(folder / (token + ".cmd"))
    ack = folder / (token + ".ack")
    wait_for(ack.exists, 75, [process], text)
    if ack.read_text(encoding="utf-8") != "OK":
        raise RuntimeError(ack.read_text(encoding="utf-8"))


def pair_command(run, text, processes):
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(command, run, side, text, process) for side, process in processes.items()]
        for future in futures:
            future.result()


def console(process, text):
    process.stdin.write((text + "\n").encode())
    process.stdin.flush()


def run_arm(options, arm, instrument, helper):
    with socket.socket() as check:
        check.bind(("127.0.0.1", 25565))
    run, case, commands = setup(options, arm, instrument, helper)
    processes, streams = {}, []
    result = {"status": "RUNNING", "started": time.time(), "path": str(run)}
    save(run / "result.json", result)
    try:
        for side in ("server", "client"):
            stream = (run / (side + ".log")).open("w", encoding="utf-8")
            streams.append(stream)
            domain = options.cpu_plan.get(side) if options.cpu_plan["enabled"] else None
            processes[side] = popen_pinned(commands[side], domain, cwd=case / side, stdout=stream,
                                                stderr=subprocess.STDOUT, stdin=subprocess.PIPE, creationflags=FLAGS)
            emit(f"{options.label} {arm} {side} PID={processes[side].pid}")
            result[side + "Pid"] = processes[side].pid
            result[side + "Affinity"] = verify_affinity(processes[side], domain)
            save(run / "result.json", result)
            if side == "server":
                wait_for(lambda: 'Done (' in dedicated.read_log(run / "server.log"), 180, list(processes.values()), "server ready")
                for cmd in ("gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false",
                            "time set noon", "weather clear", "gamerule commandBlockOutput false", "gamerule sendCommandFeedback false",
                            "scoreboard objectives add sparkbench dummy", "scoreboard players set enabled sparkbench 0"):
                    console(processes[side], cmd)
        wait_for(lambda: 'HassiumCompat joined the game' in dedicated.read_log(run / "server.log"), 180, list(processes.values()), "client joined")
        wait_for(lambda: all((run / (side + '-control/spark-ready.txt')).exists() for side in processes), 30, list(processes.values()), "spark ready")
        wait_for(lambda: (run / 'client-control/client-ready.txt').exists(), 30, list(processes.values()), "client world ready")
        console(processes['server'], "tp HassiumCompat 0 140 0 -90 12")
        command(run, "client", "move 0", processes['client'])
        pair_command(run, "spark profiler cancel", processes)
        emit(f"{options.label} {arm} warmup {options.warmup}s, flying x=0..224")
        started = time.monotonic()
        wait_for(lambda: time.monotonic() - started >= options.warmup, options.warmup + 5, list(processes.values()), "warmup")
        for phase in ("movement", "movement_load"):
            for side, process in processes.items():
                domain = options.cpu_plan.get(side) if options.cpu_plan["enabled"] else None
                result[side + "Affinity"] = verify_affinity(process, domain)
            if phase.endswith("_load"):
                console(processes['server'], "function sparkbench:spawn")
                console(processes['server'], "scoreboard players set enabled sparkbench 1")
                started = time.monotonic()
                wait_for(lambda: time.monotonic() - started >= 15, 25, list(processes.values()), "load settle")
            pair_command(run, "spark profiler start --interval 4 --force-java-sampler", processes)
            pair_command(run, "begin " + phase, processes)
            emit(f"{options.label} {arm} measuring {phase} {options.seconds}s")
            started = time.monotonic()
            wait_for(lambda: time.monotonic() - started >= options.seconds, options.seconds + 10, list(processes.values()), phase)
            pair_command(run, "end", processes)
            pair_command(run, "spark profiler stop --save-to-file", processes)
            for side, process in processes.items():
                domain = options.cpu_plan.get(side) if options.cpu_plan["enabled"] else None
                result[side + "Affinity"] = verify_affinity(process, domain)
            for side in processes:
                stats = json.loads((run / f"{side}-control/{phase}.json").read_text())
                if side == "client" and stats["maxX"] - stats["minX"] < min(80, options.seconds * 3):
                    raise RuntimeError("Movement did not cover the expected distance: " + str(stats))
            pair_command(run, "spark tps", processes)
            pair_command(run, "spark gc", processes)
            for side in processes:
                errors = [p for p in (run / (side + '-control')).glob('*.ack') if p.read_text().startswith('ERROR')]
                if errors:
                    raise RuntimeError(f"Controller errors: {errors}")
        console(processes['server'], "scoreboard players set enabled sparkbench 0")
        command(run, "client", "halt", processes['client'])
        result['status'] = 'PASS'
    except BaseException as error:
        result.update(status="FAIL", error=str(error))
        raise
    finally:
        for side, process in reversed(list(processes.items())):
            if process.poll() is None:
                try:
                    if side == "client":
                        command(run, side, "quit", process)
                    else:
                        console(process, "stop")
                    process.wait(timeout=60)
                except Exception:
                    if process.poll() is None:
                        process.terminate()
                        result[side + "ForcedStop"] = True
                        process.wait(timeout=20)
            result[side + "ExitCode"] = process.returncode
        for stream in streams:
            stream.close()
        result['ended'] = time.time()
        save(run / "result.json", result)
        emit(f"{options.label} {arm}: {result['status']} {run}")
    return result


def main():
    global ACTIVE_ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", choices=("forge", "neoforge"), required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--arms", nargs="+", choices=("baseline", "hassium", "before", "after"), default=["baseline", "hassium"])
    parser.add_argument("--before-jar", type=Path, help="Explicit existing Hassium JAR for the before arm")
    parser.add_argument("--after-jar", type=Path, help="Explicit built Hassium JAR for the after arm")
    parser.add_argument("--output-root", type=Path, default=ROOT, help="Fresh independent output directory")
    parser.add_argument("--instrumentation-root", type=Path,
                        help="Directory containing a previously frozen tools/agent.jar and bridge.jar")
    parser.add_argument("--warmup", type=int, default=60)
    parser.add_argument("--seconds", type=int, default=90)
    parser.add_argument("--entities", type=int, default=256)
    parser.add_argument("--cpu-isolation", choices=("Auto", "Required", "Off"), default="Auto")
    parser.add_argument("--server-ccd", type=int, default=0)
    parser.add_argument("--client-ccd", type=int, default=1)
    options = parser.parse_args()
    options.cpu_plan = cpu_plan(options.cpu_isolation, options.server_ccd, options.client_ccd)
    emit("CPU isolation: " + json.dumps(options.cpu_plan, ensure_ascii=False))
    options.output_root = options.output_root.resolve()
    options.output_root.mkdir(parents=True, exist_ok=True)
    ACTIVE_ROOT = options.output_root
    options.root = options.output_root
    if "before" in options.arms and not options.before_jar:
        parser.error("--before-jar is required when using the before arm")
    if "after" in options.arms and not options.after_jar:
        parser.error("--after-jar is required when using the after arm")
    for path in (options.before_jar, options.after_jar):
        if path is not None:
            path = path.resolve()
            if not path.is_file():
                parser.error(f"Hassium JAR does not exist: {path}")
    options.before_jar = options.before_jar.resolve() if options.before_jar else None
    options.after_jar = options.after_jar.resolve() if options.after_jar else None
    options.instrumentation_root = (options.instrumentation_root or options.output_root / "tools").resolve()
    instrument, helper = agent(options.instrumentation_root)
    with base.runtime_lock(options):
        for arm in options.arms:
            run_arm(options, arm, instrument, helper)


if __name__ == "__main__":
    main()
