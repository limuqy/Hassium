"""Read-only structure NBT fingerprints for offline smoke caches (type 126).

Compare a SeedGen snapshot with a cache-only snapshot using --baseline. Compound
key order is normalized; tag types, arrays, unknown IDs and extra fields are kept.
Requires the Python zstandard package. Never modifies the world being inspected.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct

import zstandard as zstd


def inspect_nbt(raw: bytes) -> dict:
    def name(pos):
        size = struct.unpack_from('>H', raw, pos)[0]
        end = pos + 2 + size
        return raw[pos + 2:end].decode('utf-8'), end

    def walk(kind, pos, capture=False):
        start = pos
        fixed = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        value = None
        if kind in fixed:
            pos += fixed[kind]
        elif kind == 8:
            _, pos = name(pos)
        elif kind in (7, 11, 12):
            size = struct.unpack_from('>i', raw, pos)[0]
            if size < 0:
                raise ValueError('Negative NBT array size')
            pos += 4 + size * {7: 1, 11: 4, 12: 8}[kind]
        elif kind == 9:
            item = raw[pos]
            size = struct.unpack_from('>i', raw, pos + 1)[0]
            if size < 0:
                raise ValueError('Negative NBT list size')
            pos += 5
            values = []
            for _ in range(size):
                entry, pos = walk(item, pos, capture)
                if capture:
                    values.append(entry)
            value = [item, values]
        elif kind == 10:
            values = {}
            while raw[pos]:
                item = raw[pos]
                key, pos = name(pos + 1)
                entry, pos = walk(item, pos, capture)
                if capture:
                    values[key] = entry
            pos += 1
            value = values
        else:
            raise ValueError(f'Unknown NBT type {kind}')
        if pos > len(raw):
            raise ValueError('Truncated NBT')
        if capture:
            return [kind, value if kind in (9, 10) else raw[start:pos].hex()], pos
        return None, pos

    if raw[0] != 10:
        raise ValueError('Chunk root must be a compound')
    _, pos = name(1)
    result = {}
    while raw[pos]:
        kind = raw[pos]
        key, pos = name(pos + 1)
        value, pos = walk(kind, pos, key == 'structures')
        if key == 'structures':
            canonical = json.dumps(value, sort_keys=True, separators=(',', ':')).encode()
            fields = value[1] if kind == 10 else {}
            starts = fields.get('starts', [10, {}])
            refs = fields.get('References', [10, {}])
            result = {
                'sha256': hashlib.sha256(canonical).hexdigest(),
                'starts': sorted(starts[1]) if starts[0] == 10 else [],
                'references': sorted(refs[1]) if refs[0] == 10 else [],
                'fields': sorted(fields),
            }
    if pos + 1 != len(raw):
        raise ValueError('Trailing bytes in chunk NBT')
    return result


def inspect_world(world: Path, dictionary_path: Path) -> dict:
    decoder = zstd.ZstdDecompressor(dict_data=zstd.ZstdCompressionDict(dictionary_path.read_bytes()))
    chunks = {}
    decoded = 0
    for path in sorted(world.rglob('r.*.*.mca')):
        if path.parent.name != 'region':
            continue
        data = path.read_bytes()
        _, rx, rz, _ = path.name.split('.')
        rx, rz = int(rx), int(rz)
        for slot in range(1024):
            location = struct.unpack_from('>I', data, slot * 4)[0]
            if not location:
                continue
            offset = (location >> 8) * 4096
            size = struct.unpack_from('>I', data, offset)[0]
            if data[offset + 4] != 126:
                raise ValueError(f'Non-type-126 cache slot: {path}:{slot}')
            payload = data[offset + 5:offset + 4 + size]
            payload = payload[9:] if payload[0] == 0x48 else payload
            raw = decoder.decompress(payload)
            structure = inspect_nbt(raw)
            decoded += 1
            if structure and (structure['starts'] or structure['references']):
                structure['chunkNbtSha256'] = hashlib.sha256(raw).hexdigest()
                key = f'{path.parent.relative_to(world).as_posix()}:{rx * 32 + slot % 32},{rz * 32 + slot // 32}'
                chunks[key] = structure
    return {'world': str(world), 'decodedChunks': decoded, 'structureChunks': len(chunks), 'chunks': chunks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('world', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    result = inspect_world(args.world, repo / 'common/src/main/resources/assets/hassium/hassium-dictionary.bin')
    if args.baseline:
        before = json.loads(args.baseline.read_text(encoding='utf-8'))['chunks']
        after = result['chunks']
        result['comparison'] = {
            'baselineChunks': len(before),
            'missing': sorted(before.keys() - after.keys()),
            'changed': sorted(key for key in before.keys() & after.keys()
                              if before[key]['sha256'] != after[key]['sha256']),
            'rewrittenWithStructuresPreserved': sorted(key for key in before.keys() & after.keys()
                              if before[key]['sha256'] == after[key]['sha256']
                              and before[key]['chunkNbtSha256'] != after[key]['chunkNbtSha256']),
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary = {key: value for key, value in result.items() if key not in ('chunks', 'comparison')}
    if 'comparison' in result:
        summary['comparison'] = {key: len(value) if isinstance(value, list) else value
                                 for key, value in result['comparison'].items()}
    print(json.dumps(summary, ensure_ascii=False))
    if args.baseline and (result['comparison']['missing'] or result['comparison']['changed']):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
