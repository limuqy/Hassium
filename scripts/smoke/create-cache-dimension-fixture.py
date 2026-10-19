"""Create a read-only world fixture with two dimension IDs sharing vanilla types."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def create_fixture(level_dat: Path, minecraft_jar: Path, output: Path) -> dict:
    level = level_dat.read_bytes()
    # 26.x stores generation settings outside level.dat. Keep the authoritative seed/preset.
    gen_path = level_dat.parent / "data/minecraft/world_gen_settings.dat"
    generation = gen_path.read_bytes()
    with zipfile.ZipFile(minecraft_jar) as jar:
        version = json.loads(jar.read("version.json"))
    pack = version["pack_version"]
    pack_format = [pack["data_major"], pack["data_minor"]]
    metadata = {"pack": {"description": "Hassium cache dimension smoke fixture",
                         "min_format": pack_format, "max_format": pack_format}}
    generator = {"type": "minecraft:flat", "settings": {
        "biome": "minecraft:plains", "lakes": False, "features": False,
        "structure_overrides": [], "layers": [
            {"height": 1, "block": "minecraft:bedrock"},
            {"height": 100, "block": "minecraft:stone"}]}}
    root = "datapacks/hassium_smoke/"
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation: neither the input world nor an existing fixture is overwritten.
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("level.dat", level)
        archive.writestr("data/minecraft/world_gen_settings.dat", generation)
        archive.writestr(root + "pack.mcmeta", json.dumps(metadata))
        for dimension, type_id in (("first", "minecraft:the_nether"),
                                   ("second", "minecraft:overworld")):
            archive.writestr(root + f"data/hassium_smoke/dimension/{dimension}.json",
                             json.dumps({"type": type_id, "generator": generator}))
    return {"path": str(output.resolve()), "minecraft": version["id"],
            "sourceLevelSha256": hashlib.sha256(level).hexdigest(),
            "sourceGenerationSha256": hashlib.sha256(generation).hexdigest(),
            "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--level-dat", type=Path, required=True)
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(create_fixture(args.level_dat, args.minecraft_jar, args.output), indent=2))
