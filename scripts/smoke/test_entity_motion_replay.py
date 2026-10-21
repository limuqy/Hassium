"""Meaningful R0 failure controls, separate from the native-codec oracle."""
import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest

from scripts.smoke import entity_motion_replay as r


class EntityMotionReplayTest(unittest.TestCase):
    def spawn(self, ts=0, x=1.0, entity_uuid=None):
        fields = dict(x=x, y=64.00001, z=-0.10001)
        if entity_uuid:
            fields["uuid"] = entity_uuid
        return r.fixture_event("add", ts=ts, **fields)

    def test_java_rounding_and_zero_axis_are_not_python_rounding(self):
        self.assertEqual(r.quantize(0.5/4096), 1)
        self.assertEqual(r.quantize(-0.5/4096), 0)
        self.assertEqual(r.relative((0.00001, -0.00001, -0.5/4096), (0, 1, 1)),
                         (0.00001, 1/4096, 1/4096))

    def test_legacy_and_modern_motion_names_decode_the_same_body(self):
        e = r.fixture_event("position_rotation", dx=-32768, dy=32767, dz=0,
                            yaw=-128, pitch=127, ground=True)
        old = r.decode("minecraft:pos_rot", e.raw)
        new = r.decode("minecraft:move_entity_pos_rot", e.raw)
        self.assertEqual(old.fields, new.fields)
        self.assertEqual(old.fields["dx"], -32768)
        self.assertEqual(old.fields["yaw"], -128)

    def test_truncated_packet_and_tail_are_rejected(self):
        e = r.fixture_event("position", dx=1, dy=2, dz=3)
        with self.assertRaises((ValueError, struct.error)):
            r.decode("minecraft:pos", e.raw[:-1])
        with self.assertRaisesRegex(ValueError, "tail"):
            r.decode("minecraft:pos", e.raw+b"x")

    def test_unknown_mod_payload_is_never_parsed_as_native_motion(self):
        event = r.decode("another_mod:pos", b"opaque mod payload")
        self.assertEqual(event.kind, "other")

    def test_current_position_repair_is_detectably_wrong(self):
        canonical, observer = r.Receiver(), r.Observer("wrong-current-position")
        canonical.apply(self.spawn())
        observer.receiver.apply(self.spawn(x=1.25))
        before = copy.deepcopy(canonical)
        event = r.fixture_event("position", dx=2048, dy=0, dz=0)
        canonical.apply(event)
        with self.assertRaisesRegex(AssertionError, "diverged"):
            observer.consume(event, before, canonical, False)
        self.assertEqual(observer.receiver.entities[7].position[0], 1.75)
        self.assertEqual(canonical.entities[7].position[0], 1.5)

    def test_restore_rereads_latest_base_not_base_at_enqueue(self):
        canonical, observer = r.Receiver(), r.Observer("late-resume")
        spawn = self.spawn()
        canonical.apply(spawn)
        observer.receiver.apply(spawn)
        token = observer.token(7, canonical)
        observer.pending[7] = token
        canonical.apply(r.fixture_event("position", dx=4096, dy=0, dz=0))
        canonical.apply(r.fixture_event("position", dx=2048, dy=0, dz=0))
        self.assertTrue(observer.repair(7, token, canonical))
        self.assertEqual(observer.receiver.entities[7], canonical.entities[7])
        before = copy.deepcopy(canonical)
        observer.pending.clear()
        delta = r.fixture_event("position", dx=-1, dy=0, dz=0)
        canonical.apply(delta)
        observer.consume(delta, before, canonical, False)

    def test_snapshot_applied_after_its_delta_is_detected(self):
        replay = r.Replay()
        replay.group([self.spawn()])
        old = copy.deepcopy(replay.canonical)
        replay.group([r.fixture_event("position", ts=100, dx=2048, dy=0, dz=0)])
        observer = replay.afk[0]
        # Fault injection: an old snapshot arrives after the delta it should precede.
        observer.repair(7, observer.token(7, old), old)
        with self.assertRaisesRegex(AssertionError, "Final state mismatch"):
            replay.finish()

    def test_entity_id_reuse_rejects_old_queued_snapshot(self):
        canonical, observer = r.Receiver(), r.Observer("reuse")
        for receiver in (canonical, observer.receiver):
            receiver.apply(self.spawn())
        old = observer.token(7, canonical)
        for receiver in (canonical, observer.receiver):
            receiver.apply(r.Event("remove", {"ids": [7]}))
            receiver.apply(self.spawn(entity_uuid="00000000-0000-0000-0000-000000000700"))
        current = observer.token(7, canonical)
        observer.pending[7] = current
        self.assertFalse(observer.repair(7, old, canonical))
        self.assertEqual(observer.pending[7], current)
        self.assertTrue(observer.repair(7, current, canonical))

    def test_same_uuid_after_world_change_still_rejects_old_generation(self):
        canonical, observer = r.Receiver(), r.Observer("world")
        for receiver in (canonical, observer.receiver):
            receiver.apply(self.spawn())
        old = observer.token(7, canonical)
        for receiver in (canonical, observer.receiver):
            receiver.apply(r.Event("reset", {}))
            receiver.apply(self.spawn())
        self.assertFalse(observer.repair(7, old, canonical))

    def test_snapshot_restores_head_velocity_and_body_not_only_position(self):
        canonical, observer = r.Receiver(), r.Observer("full-state")
        for receiver in (canonical, observer.receiver):
            receiver.apply(self.spawn())
        canonical.apply(r.fixture_event("rotation", yaw=-128, pitch=127, ground=True))
        canonical.apply(r.fixture_event("head", head=-99))
        canonical.apply(r.fixture_event("velocity", vx=-0.25, vy=0.125, vz=0.0))
        self.assertTrue(observer.repair(7, observer.token(7, canonical), canonical))
        self.assertEqual(observer.receiver.entities[7], canonical.entities[7])

    def test_atomic_bundle_repairs_before_forwarding_relative_motion(self):
        replay = r.Replay()
        replay.group([self.spawn()])
        replay.group([r.fixture_event("position", ts=2000, dx=4096, dy=0, dz=0)])
        self.assertIn(7, replay.afk[0].pending)
        replay.group([r.Event("bundle", {}, b"start", 2050),
                      r.fixture_event("position", ts=2050, dx=2048, dy=0, dz=0),
                      r.Event("bundle", {}, b"end", 2050)])
        self.assertNotIn(7, replay.afk[0].pending)
        self.assertEqual(replay.afk[0].receiver.entities[7], replay.canonical.entities[7])
        replay.finish()

    def test_normal_observer_trace_guard_detects_global_gate(self):
        replay = r.Replay()
        replay.active[0].record = lambda event: None
        replay.group([self.spawn()])
        with self.assertRaisesRegex(AssertionError, "normal observer"):
            replay.finish()

    def test_recovery_budget_keeps_unrepaired_entities_quarantined(self):
        canonical, observer = r.Receiver(), r.Observer("budget")
        for entity_id in range(130):
            spawn = r.fixture_event("add", entity_id, x=float(entity_id), y=64.0, z=0.0)
            canonical.apply(spawn)
            observer.receiver.apply(spawn)
            observer.pending[entity_id] = observer.token(entity_id, canonical)
        observer.advance(5500, 0, canonical)
        self.assertEqual(len(observer.pending), 66)
        before = copy.deepcopy(canonical)
        event = r.fixture_event("position", 129, dx=4096, dy=0, dz=0)
        canonical.apply(event)
        observer.consume(event, before, canonical, False)
        self.assertIn(129, observer.pending)
        self.assertEqual(observer.receiver.entities[129].position[0], 129.0)
        observer.advance(5550, 0, canonical)
        self.assertEqual(len(observer.pending), 2)
        observer.advance(5600, 0, canonical)
        self.assertFalse(observer.pending)
        self.assertEqual(observer.receiver.entities[129], canonical.entities[129])

    def test_wire_fixture_mixed_roles_and_lifecycle_complete(self):
        replay = r.Replay()
        for group in r.fixture_groups():
            replay.group(group)
        report = replay.finish()
        for observer in report["afkObservers"].values():
            self.assertGreater(observer["heldMotionPackets"], 0)
            self.assertGreater(observer["snapshots"], 0)
        self.assertEqual(len(report["activeObserversVerified"]), 2)

    def test_observer_retracking_does_not_reuse_an_old_repair_token(self):
        canonical, observer, normal = r.Receiver(), r.Observer("retrack"), r.Observer("normal")
        spawn = self.spawn()
        for receiver in (canonical, observer.receiver, normal.receiver):
            receiver.apply(spawn)
        old = observer.token(7, canonical)
        observer.pending[7] = old
        observer.detach(7)
        observer.attach(7, canonical)
        current = observer.token(7, canonical)
        self.assertNotEqual(old, current)
        observer.pending[7] = current
        before = copy.deepcopy(canonical)
        delta = r.fixture_event("position", dx=4096, dy=0, dz=0)
        canonical.apply(delta)
        normal.consume(delta, before, canonical, False)
        self.assertFalse(observer.repair(7, old, canonical))
        self.assertEqual(observer.pending[7], current)
        self.assertTrue(observer.repair(7, current, canonical))
        self.assertEqual(normal.receiver.entities[7], observer.receiver.entities[7])

    def test_final_result_refuses_a_different_native_request_stream(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            request = directory/"native-requests.jsonl.gz"
            request.write_bytes(b"request stream A")
            digest = hashlib.sha256(request.read_bytes()).hexdigest()
            metadata = {"mcVersion": "1.20.1", "loader": "forge", "sourceSession": "test"}
            model = {**metadata, "modelResult": "PASS", "oracleRequests": {"position": 100},
                     "nativeRequestsSHA256": digest}
            native = {"metadata": metadata, "result": "PASS", "compiledMinecraftVersion": "1.20.1",
                      "nativeRequestsSHA256": digest, "kinds": {"position": 100},
                      "nativePacketRoundTrips": 100, "nativePositionChecks": 100,
                      "negativeControlsPassed": 2}
            (directory/"model-result.json").write_text(json.dumps(model), encoding="utf-8")
            (directory/"native-result.json").write_text(json.dumps(native), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(r.finalize(directory)["result"], "PASS")
            request.write_bytes(b"request stream B")
            with self.assertRaisesRegex(ValueError, "another request stream"):
                r.finalize(directory)


if __name__ == "__main__":
    unittest.main()
