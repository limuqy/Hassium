"""R0 offline receiver replay. Never installs a game hook or changes a live packet.

Use an existing, validated traffic-profile directory. Native Minecraft codecs
independently verify the emitted oracle requests via common:entityMotionReplayNativeCheck.
"""
import argparse
import base64
from collections import Counter, OrderedDict
from dataclasses import dataclass, replace
import gzip
import hashlib
import json
import math
from pathlib import Path
import struct
import uuid


MOTION = {"position", "rotation", "position_rotation", "teleport", "velocity", "head"}
NAMES = {"pos": "position", "rot": "rotation", "pos_rot": "position_rotation",
         "move_entity_pos": "position", "move_entity_rot": "rotation",
         "move_entity_pos_rot": "position_rotation", "teleport_entity": "teleport",
         "set_entity_motion": "velocity", "rotate_head": "head",
         "add_entity": "add", "add_player": "add_player",
         "add_experience_orb": "add_orb", "remove_entities": "remove",
         "respawn": "reset", "login": "login", "bundle_delimiter": "bundle"}


def quantize(value):
    # Java Math.round, including negative half-grid values; Python round is different.
    return math.floor(value * 4096.0 + 0.5)


def relative(base, delta):
    # Zero axes preserve their original non-grid coordinate in native VecDeltaCodec.
    return tuple(b if d == 0 else (quantize(b) + d) / 4096.0 for b, d in zip(base, delta))


def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def read(self, fmt):
        size = struct.calcsize(">" + fmt)
        result = struct.unpack_from(">" + fmt, self.data, self.offset)
        self.offset += size
        return result[0] if len(result) == 1 else result

    def integer(self):
        result = 0
        for i in range(5):
            byte = self.read("B")
            result |= (byte & 127) << (i * 7)
            if not byte & 128:
                if result > 0x7fffffff:
                    result -= 0x100000000
                return result
        raise ValueError("VarInt longer than five bytes")

    def boolean(self):
        value = self.read("B")
        if value not in (0, 1):
            raise ValueError("Invalid boolean")
        return bool(value)

    def end(self):
        if self.offset != len(self.data):
            raise ValueError(f"Unexpected packet tail: {len(self.data)-self.offset} bytes")


@dataclass
class Event:
    kind: str
    fields: dict
    raw: bytes = b""
    ts: int = 0

    @property
    def entity_id(self):
        return self.fields.get("id")


def decode(type_name, raw, ts=0):
    if not type_name.startswith("minecraft:"):
        return Event("other", {}, raw, ts)
    name = type_name.split(":", 1)[-1].removeprefix("clientbound_").removesuffix("_packet")
    kind = NAMES.get(name, "other")
    if kind in ("other", "reset", "bundle"):
        return Event(kind, {}, raw, ts)
    r = Reader(raw)
    r.integer()  # Native numerical packet ID, retained in raw bytes, never guessed.
    if kind == "login":
        return Event(kind, {"id": r.read("i")}, raw, ts)
    if kind == "remove":
        count = r.integer()
        if not 0 <= count <= 65536:
            raise ValueError("Invalid removal count")
        fields = {"ids": [r.integer() for _ in range(count)]}
    else:
        fields = {"id": r.integer()}
        if fields["id"] < 0:
            raise ValueError("Negative entity ID")
        if kind in ("add", "add_player"):
            fields["uuid"] = str(uuid.UUID(bytes=raw[r.offset:r.offset+16]))
            r.offset += 16
            if kind == "add":
                fields["entityType"] = r.integer()
            fields["x"], fields["y"], fields["z"] = r.read("ddd")
            if kind == "add":
                fields["pitch"], fields["yaw"], fields["head"] = r.read("bbb")
                fields["data"] = r.integer()
                fields["vx"], fields["vy"], fields["vz"] = (v / 8000 for v in r.read("hhh"))
            else:
                fields["yaw"], fields["pitch"] = r.read("bb")
        elif kind in ("add_orb", "teleport"):
            fields["x"], fields["y"], fields["z"] = r.read("ddd")
            if kind == "add_orb":
                fields["value"] = r.read("h")
                fields["uuid"] = f"orb:{fields['id']}"
            else:
                fields["yaw"], fields["pitch"] = r.read("bb")
                fields["ground"] = r.boolean()
        elif kind in ("position", "position_rotation", "rotation"):
            if kind != "rotation":
                fields["dx"], fields["dy"], fields["dz"] = r.read("hhh")
            if kind != "position":
                fields["yaw"], fields["pitch"] = r.read("bb")
            fields["ground"] = r.boolean()
        elif kind == "velocity":
            fields["vx"], fields["vy"], fields["vz"] = (v / 8000 for v in r.read("hhh"))
        elif kind == "head":
            fields["head"] = r.read("b")
    r.end()
    if any(isinstance(v, float) and not math.isfinite(v) for v in fields.values()):
        raise ValueError("Non-finite entity state")
    return Event(kind, fields, raw, ts)


def fixture_event(kind, entity_id=7, ts=0, **fields):
    """Wire fixtures use ID=0 solely as an opaque header; no registry ID assumption."""
    f = {"id": entity_id, **fields}
    body = varint(entity_id)
    if kind == "add":
        body += uuid.UUID(f.get("uuid", str(uuid.UUID(int=entity_id)))).bytes + varint(0)
        body += struct.pack(">dddbbb", f["x"], f["y"], f["z"], f.get("pitch", 0),
                            f.get("yaw", 0), f.get("head", 0))
        body += varint(0) + struct.pack(">hhh", *[round(f.get(k, 0)*8000) for k in ("vx", "vy", "vz")])
    elif kind in ("position", "position_rotation", "rotation"):
        if kind != "rotation":
            body += struct.pack(">hhh", *[f[k] for k in ("dx", "dy", "dz")])
        if kind != "position":
            body += struct.pack(">bb", f["yaw"], f["pitch"])
        body += bytes([f.get("ground", False)])
    elif kind == "teleport":
        body += struct.pack(">dddbb?", *[f[k] for k in ("x", "y", "z", "yaw", "pitch")], f.get("ground", False))
    elif kind == "velocity":
        body += struct.pack(">hhh", *[round(f[k]*8000) for k in ("vx", "vy", "vz")])
    elif kind == "head":
        body += struct.pack(">b", f["head"])
    else:
        raise ValueError(kind)
    raw = b"\x00" + body
    name = next(k for k, v in NAMES.items() if v == kind)
    return decode("minecraft:"+name, raw, ts)


@dataclass
class State:
    identity: tuple
    position: tuple
    yaw: int = 0
    pitch: int = 0
    head: int = 0
    velocity: tuple = (0.0, 0.0, 0.0)
    ground: bool = False


class Receiver:
    def __init__(self):
        self.entities = {}
        self.world = 0
        self.generation = 0
        self.self_id = None

    def apply(self, e):
        f, kind = e.fields, e.kind
        if kind == "login":
            self.self_id = f["id"]
        elif kind == "reset":
            self.entities.clear()
            self.world += 1
        elif kind in ("add", "add_player", "add_orb"):
            self.generation += 1
            self.entities[f["id"]] = State((self.world, f["uuid"], self.generation),
                tuple(f[k] for k in ("x", "y", "z")), f.get("yaw", 0), f.get("pitch", 0),
                f.get("head", 0), tuple(f.get(k, 0.0) for k in ("vx", "vy", "vz")))
        elif kind == "remove":
            for entity_id in f["ids"]:
                self.entities.pop(entity_id, None)
        elif kind in MOTION:
            state = self.entities.get(f["id"])
            if state is None:
                return False
            if kind in ("position", "position_rotation"):
                state.position = relative(state.position, tuple(f[k] for k in ("dx", "dy", "dz")))
            elif kind == "teleport":
                state.position = tuple(f[k] for k in ("x", "y", "z"))
            if "yaw" in f:
                state.yaw, state.pitch = f["yaw"], f["pitch"]
            if "ground" in f:
                state.ground = f["ground"]
            if kind == "velocity":
                state.velocity = tuple(f[k] for k in ("vx", "vy", "vz"))
            if kind == "head":
                state.head = f["head"]
        return True


class Oracle:
    def __init__(self, path, metadata):
        self.stream = gzip.open(path, "wt", encoding="utf-8", newline="\n")
        self.stream.write(json.dumps({"metadata": metadata}) + "\n")
        self.counts = Counter()

    def write(self, event, before=None, after=None, source="capture"):
        if event.kind not in MOTION:
            return
        self.counts[event.kind] += 1
        row = {"kind": event.kind, "raw": base64.b64encode(event.raw).decode(),
               "fields": event.fields, "source": source}
        if before is not None:
            row["before"] = before.position
            row["after"] = after.position
        self.stream.write(json.dumps(row, separators=(",", ":")) + "\n")

    def close(self):
        self.stream.close()


class Observer:
    def __init__(self, name, offset=0):
        self.name, self.offset = name, offset
        self.receiver = Receiver()
        self.pending = OrderedDict()
        self.tracking_versions = {}
        self.counts = Counter()
        self.next_tick = -1
        self.trace = hashlib.sha256()

    def record(self, e):
        self.trace.update(struct.pack(">qI", e.ts, len(e.raw)))
        self.trace.update(e.raw)

    def token(self, entity_id, canonical):
        return (canonical.entities[entity_id].identity, self.tracking_versions.get(entity_id, 0))

    def detach(self, entity_id):
        self.receiver.entities.pop(entity_id, None)
        self.pending.pop(entity_id, None)
        self.tracking_versions[entity_id] = self.tracking_versions.get(entity_id, 0)+1

    def attach(self, entity_id, canonical):
        # A virtual native pairing installs the source state without respawning the source entity.
        self.receiver.entities[entity_id] = replace(canonical.entities[entity_id])
        self.pending.pop(entity_id, None)
        self.tracking_versions[entity_id] = self.tracking_versions.get(entity_id, 0)+1

    def repair(self, entity_id, token, canonical, oracle=None):
        source = canonical.entities.get(entity_id)
        if source is None or token != self.token(entity_id, canonical):
            self.counts["staleRepairsDiscarded"] += 1
            if self.pending.get(entity_id) == token:
                self.pending.pop(entity_id, None)
            return False
        target = self.receiver.entities.get(entity_id)
        if target is None or target.identity != source.identity:
            raise AssertionError("Repair attempted without the matching pairing generation")
        before = replace(target)
        # Native packet fields use the canonical receiver base, not rendered/current world position.
        snap = fixture_event("teleport", entity_id, x=source.position[0], y=source.position[1],
            z=source.position[2], yaw=source.yaw, pitch=source.pitch, ground=source.ground)
        self.receiver.apply(snap)
        if oracle:
            oracle.write(snap, before, target, "snapshot")
        for snap in (fixture_event("velocity", entity_id, **dict(zip(("vx", "vy", "vz"), source.velocity))),
                     fixture_event("head", entity_id, head=source.head)):
            self.receiver.apply(snap)
            if oracle:
                oracle.write(snap, source="snapshot")
        if target != source:
            raise AssertionError("Incomplete motion snapshot")
        self.counts["snapshots"] += 1
        return True

    def advance(self, ts, origin, canonical, oracle=None, force=False):
        tick = (ts-origin)//50
        if tick == self.next_tick and not force:
            return
        self.next_tick = tick
        hold = 1500 <= ((ts-origin+self.offset) % 7000) < 5000
        # Model a bounded recovery scheduler; periodic HOLD snapshots keep quarantine enabled.
        if force or not hold or tick % 20 == 0:
            for entity_id, identity in list(self.pending.items())[:(len(self.pending) if force else 64)]:
                self.repair(entity_id, identity, canonical, oracle)
                if force or not hold:
                    self.pending.pop(entity_id, None)
            if not hold:
                self.counts["recoveryTicks"] += 1

    def consume(self, event, canonical_before, canonical_after, hold, atomic_bundle=False, oracle=None):
        entity_id = event.entity_id
        if event.kind in MOTION and entity_id in canonical_before.entities:
            if hold and not atomic_bundle and event.kind != "teleport":
                self.pending[entity_id] = self.token(entity_id, canonical_before)
                self.counts["heldMotionPackets"] += 1
                return
            if entity_id in self.pending:
                if not atomic_bundle and event.kind != "teleport":
                    # Logical wake-up does not open a still-unrepaired entity's relative stream.
                    # Only the bounded scheduler may remove its quarantine.
                    self.counts["recoveryQuarantinedPackets"] += 1
                    return
                self.repair(entity_id, self.pending[entity_id], canonical_before, oracle)
                self.pending.pop(entity_id, None)
        self.receiver.apply(event)
        self.record(event)
        if event.kind in ("add", "add_player", "add_orb"):
            self.pending.pop(entity_id, None)
            self.tracking_versions[entity_id] = self.tracking_versions.get(entity_id, 0)+1
        elif event.kind == "remove":
            for entity_id in event.fields["ids"]:
                self.counts["cancelledRemovals"] += int(entity_id in self.pending)
                self.pending.pop(entity_id, None)
                self.tracking_versions[entity_id] = self.tracking_versions.get(entity_id, 0)+1
        elif event.kind == "reset":
            self.counts["cancelledWorldChanges"] += len(self.pending)
            self.pending.clear()
            self.tracking_versions.clear()
        if entity_id in canonical_after.entities and entity_id not in self.pending:
            if self.receiver.entities.get(entity_id) != canonical_after.entities[entity_id]:
                raise AssertionError(f"PASS observer {self.name} diverged after {event.kind}")


class Replay:
    def __init__(self, oracle=None, source="capture"):
        self.canonical = Receiver()
        self.active = [Observer("normal-near"), Observer("normal-far")]
        self.afk = [Observer("afk-far", 0), Observer("afk-near", 2500)]
        self.oracle = oracle
        self.source = source
        self.origin = None
        self.expected_trace = hashlib.sha256()
        self.counts = Counter()

    def group(self, events):
        if self.origin is None:
            self.origin = events[0].ts
        ts, bundle = events[0].ts, events[0].kind == "bundle"
        for observer in self.afk:
            observer.advance(ts, self.origin, self.canonical, self.oracle)
        # All rebase barriers precede an atomic bundle; never insert into a pairing bundle.
        if bundle:
            touched = {e.entity_id for e in events if e.kind in MOTION}
            for observer in self.afk:
                for entity_id in touched & observer.pending.keys():
                    observer.repair(entity_id, observer.pending[entity_id], self.canonical, self.oracle)
                    observer.pending.pop(entity_id, None)
        for event in events:
            self.counts[event.kind] += 1
            self.expected_trace.update(struct.pack(">qI", event.ts, len(event.raw)))
            self.expected_trace.update(event.raw)
            before_state = self.canonical.entities.get(event.entity_id)
            before_copy = replace(before_state) if before_state else None
            # Expose only this entity's pre-packet state to repair, without cloning a world per packet.
            before = Receiver()
            before.entities = {}
            if before_copy:
                before.entities[event.entity_id] = before_copy
            known = self.canonical.apply(event)
            after = self.canonical.entities.get(event.entity_id)
            if event.kind in MOTION:
                self.counts["knownMotion" if known else "unpairedMotion"] += 1
                if self.oracle:
                    self.oracle.write(event, before_copy, after, source=self.source)
            for observer in self.active:
                observer.consume(event, before, self.canonical, False, bundle, self.oracle)
            for observer in self.afk:
                hold = 1500 <= ((ts-self.origin+observer.offset) % 7000) < 5000
                observer.consume(event, before, self.canonical, hold, bundle, self.oracle)
            if event.kind in ("remove", "reset"):
                for observer in self.active+self.afk:
                    if set(observer.receiver.entities) != set(self.canonical.entities):
                        raise AssertionError("Observer lifecycle diverged")

    def finish(self):
        for observer in self.afk:
            observer.advance(10**12, self.origin or 0, self.canonical, self.oracle, force=True)
        for observer in self.active+self.afk:
            if observer.receiver.entities != self.canonical.entities:
                raise AssertionError(f"Final state mismatch for {observer.name}")
        expected = self.expected_trace.hexdigest()
        if any(o.trace.hexdigest() != expected for o in self.active):
            raise AssertionError("AFK observer changed a normal observer's bytes/order/timestamps")
        return {"sourcePackets": dict(self.counts), "activeTraceSHA256": expected,
                "activeObserversVerified": [o.name for o in self.active],
                "afkObservers": {o.name: dict(o.counts) for o in self.afk}}


def fixture_groups():
    """Adversarial native wire vectors, separate from captured game data."""
    rows = [[fixture_event("add", ts=0, x=-0.10001, y=64.00001, z=-1.00001)]]
    # Multiple complete HOLD/resume cycles; boundary deltas and zero axes are intentional.
    for i in range(1, 361):
        ts = i*50
        kind = ("position", "rotation", "position_rotation", "velocity", "head")[i % 5]
        fields = {"position": dict(dx=(-32768, -1, 0, 1, 32767)[(i // 5) % 5], dy=0, dz=1),
                  "rotation": dict(yaw=-128+i % 256, pitch=-127),
                  "position_rotation": dict(dx=1, dy=-1, dz=0, yaw=-64, pitch=63),
                  "velocity": dict(vx=0.0 if i % 2 else -0.25, vy=0.125, vz=0.0),
                  "head": dict(head=-127+i % 255)}[kind]
        rows.append([fixture_event(kind, ts=ts, ground=bool(i % 2), **fields)])
    rows += [[Event("remove", {"ids": [7]}, ts=18100)],
             [fixture_event("add", ts=18200, x=5.25, y=70.0, z=-5.0, uuid=str(uuid.UUID(int=700)))],
             [Event("reset", {}, ts=18300)],
             [fixture_event("add", ts=18400, x=0.00001, y=-64.0, z=-0.00001)]]
    rows += [[fixture_event("position", ts=18500+i*50, dx=i % 2, dy=0, dz=-1)] for i in range(50)]
    for i, x in enumerate((0.5/4096, -0.5/4096, 0.00001, -0.00001, -1000000.00001)):
        ts = 22000+i*100
        rows += [[fixture_event("add", ts=ts, x=x, y=-x, z=x)],
                 [fixture_event("position", ts=ts+50, dx=1, dy=-1, dz=0)]]
    return rows


def negative_controls():
    base = (1.0, 64.0, 0.0)
    current = (1.25, 64.0, 0.0)
    delta = (2048, 0, 0)
    expected = relative(base, delta)
    wrong = relative(current, delta)
    assert expected[0] == 1.5 and wrong[0] == 1.75
    # Ordering is a separate failure: forwarding the delta before the correct snapshot loses it.
    late_snapshot = base
    assert late_snapshot != expected
    return {"currentPositionSnapshotRejected": {"expectedX": expected[0], "wrongX": wrong[0]},
            "snapshotAfterDeltaRejected": True}


def run(directory, output, version, loader):
    if (version, loader) not in (("1.20.1", "forge"), ("1.21.1", "neoforge")):
        raise ValueError("Only the two verified priority combinations are supported")
    summary = json.loads((directory/"traffic-summary.json").read_text(encoding="utf-8"))
    if summary["harnessResult"] != "PASS" or summary["qualityProblems"]:
        raise ValueError("Source traffic capture did not pass its original gates")
    result = json.loads(next(directory.glob("result_*.json")).read_text(encoding="utf-8-sig"))
    if (result["Ver"], result["Loader"]) != (version, loader):
        raise ValueError("Capture version/loader does not match the requested oracle")
    output.mkdir(parents=True, exist_ok=False)
    metadata = {"schema": 1, "mcVersion": version, "loader": loader, "sourceSession": directory.name}
    oracle = Oracle(output/"native-requests.jsonl.gz", metadata)
    connections, bundles, inputs = {}, {}, {}
    try:
        for path in sorted(directory.glob("agg-*.jsonl")):
            with path.open("rb") as stream:
                sha = hashlib.file_digest(stream, "sha256").hexdigest()
            expected = summary["evidence"].get(path.name, {}).get("sha256")
            if sha != expected:
                raise ValueError(f"Capture hash mismatch: {path}")
            inputs[path.name] = {"sha256": sha, "bytes": path.stat().st_size}
            with path.open(encoding="utf-8") as stream:
                for line in stream:
                    row = json.loads(line)
                    if row.get("src") != "native_v3":
                        continue
                    raw = base64.b64decode(row["payload"], validate=True)
                    if len(raw) != row["size"]:
                        raise ValueError("Native packet length mismatch")
                    event = decode(row["type"], raw, row["ts"])
                    connection = row["conn"]
                    if connection not in connections:
                        connections[connection] = Replay(oracle)
                    replay = connections[connection]
                    if event.kind == "bundle":
                        if connection not in bundles:
                            bundles[connection] = [event]
                        else:
                            bundles[connection].append(event)
                            replay.group(bundles.pop(connection))
                    elif connection in bundles:
                        bundles[connection].append(event)
                        if len(bundles[connection]) > 4098:
                            raise ValueError("Unbounded native bundle")
                    else:
                        replay.group([event])
        if bundles:
            raise ValueError("Capture ends inside a native bundle")
        if not connections:
            raise ValueError("No native source packets")
        actual = {name: replay.finish() for name, replay in connections.items()}
        if sum(r["sourcePackets"].get("knownMotion", 0) for r in actual.values()) < 100:
            raise ValueError("Insufficient paired motion coverage")
        if any(not o.get("heldMotionPackets") or not o.get("snapshots")
               for r in actual.values() for o in r["afkObservers"].values()):
            raise ValueError("The mixed-observer HOLD/rebase path was not exercised")
        fixture = Replay(oracle, source="wire_fixture")
        for group in fixture_groups():
            fixture.group(group)
        report = {**metadata, "modelResult": "PASS", "nativeCodecResult": "PENDING",
                  "scope": "offline virtual mixed observers; source sessions each used one real client",
                  "inputs": inputs, "captures": actual, "wireFixtures": fixture.finish(),
                  "negativeControls": negative_controls(), "oracleRequests": dict(oracle.counts)}
        oracle.close()
        with (output/"native-requests.jsonl.gz").open("rb") as stream:
            report["nativeRequestsSHA256"] = hashlib.file_digest(stream, "sha256").hexdigest()
        (output/"model-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps({k: v for k, v in report.items() if k != "inputs"}, indent=2))
    finally:
        oracle.close()


def finalize(directory):
    """Never turn a model-only or mismatched oracle run into an overall PASS."""
    model = json.loads((directory/"model-result.json").read_text(encoding="utf-8"))
    native = json.loads((directory/"native-result.json").read_text(encoding="utf-8"))
    if model["modelResult"] != "PASS" or native["result"] != "PASS":
        raise ValueError("Model and native oracle must both pass")
    if any(model[k] != native["metadata"][k] for k in ("mcVersion", "loader", "sourceSession")):
        raise ValueError("Native oracle metadata mismatch")
    with (directory/"native-requests.jsonl.gz").open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != model["nativeRequestsSHA256"] or actual != native["nativeRequestsSHA256"]:
        raise ValueError("Native oracle checked another request stream")
    if native["compiledMinecraftVersion"] != model["mcVersion"] or native["kinds"] != model["oracleRequests"]:
        raise ValueError("Native oracle version or packet coverage mismatch")
    if native["nativePacketRoundTrips"] != sum(model["oracleRequests"].values()):
        raise ValueError("Native oracle did not check every requested packet")
    if native.get("negativeControlsPassed") != 2:
        raise ValueError("Native baseline failure controls missing")
    report = {**model, "result": "PASS", "nativeCodecResult": "PASS", "nativeOracle": native}
    (directory/"r0-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"result": report["result"], "mcVersion": model["mcVersion"],
                      "nativeRoundTrips": native["nativePacketRoundTrips"],
                      "nativePositionChecks": native["nativePositionChecks"]}, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--loader")
    parser.add_argument("--finalize", action="store_true")
    args = parser.parse_args()
    if args.finalize:
        finalize(args.directory)
    elif args.output is None:
        parser.error("--output is required for model replay")
    else:
        run(args.directory, args.output, args.version, args.loader)
