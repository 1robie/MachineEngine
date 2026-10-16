"""Research for stage 5: the vanilla files the pack builder patches, per Minecraft version.

Downloads each official client jar listed in Mojang's version manifest (checked against its sha1),
keeps only what the builder reads (core shaders, shader includes, water/lava textures, biome water
colors, pack format), then deletes the jar. Output: vanilla/<version>/...
"""
import hashlib
import json
import os
import shutil
import sys
import urllib.request
import zipfile

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vanilla")
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VERSIONS = sys.argv[1:] or ["1.20.6", "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.9", "1.21.10",
                            "1.21.11", "26.1", "26.2", "26.3"]


def get(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


manifest = json.loads(get(MANIFEST))
by_id = {v["id"]: v for v in manifest["versions"]}
for version in VERSIONS:
    target = os.path.join(OUT, version)
    if os.path.isdir(target):
        print(version, "already there")
        continue
    if version not in by_id:
        print(version, "NOT IN MANIFEST")
        continue
    meta = json.loads(get(by_id[version]["url"]))
    client = meta["downloads"]["client"]
    data = get(client["url"])
    if hashlib.sha1(data).hexdigest() != client["sha1"]:
        print(version, "SHA1 MISMATCH")
        continue
    jar_path = os.path.join(OUT, version + ".jar")
    os.makedirs(OUT, exist_ok=True)
    with open(jar_path, "wb") as f:
        f.write(data)
    colors = set()
    with zipfile.ZipFile(jar_path) as jar:
        for name in jar.namelist():
            keep = (name.startswith("assets/minecraft/shaders/core/") or name.startswith("assets/minecraft/shaders/include/")
                    or name in ("version.json", "pack.mcmeta")
                    or any(name.startswith("assets/minecraft/textures/block/" + t) for t in ("water_", "lava_")))
            if keep and not name.endswith("/"):
                path = os.path.join(target, name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as f:
                    f.write(jar.read(name))
            if name.startswith("data/minecraft/worldgen/biome/") and name.endswith(".json"):
                effects = json.loads(jar.read(name)).get("effects", {})
                color = effects.get("water_color")
                if color is not None:
                    colors.add(color if isinstance(color, int) else int(str(color).lstrip("#"), 16))
    with open(os.path.join(target, "water_colors.json"), "w") as f:
        json.dump(sorted(colors), f)
    os.remove(jar_path)
    print(version, "ok", len(data) // 1_000_000, "MB jar,", len(colors), "water colors")
