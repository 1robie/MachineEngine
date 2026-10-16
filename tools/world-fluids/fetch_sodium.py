"""Research: Sodium's terrain shaders per Minecraft version, and whether a resource pack can replace them.

Downloads the newest Sodium (Fabric) for each version from Modrinth (sha1 checked), keeps its shader files
and its shader loader class, deletes the jar. Output: sodium/<mc version>/...
"""
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sodium")
VERSIONS = sys.argv[1:] or ["1.21.1", "1.21.4", "1.21.8", "1.21.11", "26.1", "26.2", "26.3"]

for mc in VERSIONS:
    target = os.path.join(OUT, mc)
    if os.path.isdir(target):
        print(mc, "already there")
        continue
    query = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": json.dumps([mc])})
    versions = json.loads(urllib.request.urlopen("https://api.modrinth.com/v2/project/sodium/version?" + query, timeout=60).read())
    if not versions:
        print(mc, "no Sodium")
        continue
    version = sorted(versions, key=lambda v: v["date_published"], reverse=True)[0]
    file = next(f for f in version["files"] if f["primary"])
    data = urllib.request.urlopen(file["url"], timeout=300).read()
    if hashlib.sha1(data).hexdigest() != file["hashes"]["sha1"]:
        print(mc, "SHA1 MISMATCH")
        continue
    jar_path = os.path.join(OUT, mc + ".jar")
    os.makedirs(OUT, exist_ok=True)
    open(jar_path, "wb").write(data)
    with zipfile.ZipFile(jar_path) as jar:
        names = jar.namelist()
        keep = [n for n in names if "/shaders/" in n and not n.endswith("/")]
        keep += [n for n in names if n.endswith("ShaderLoader.class") or n.endswith("ShaderParser.class")]
        for name in keep:
            path = os.path.join(target, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "wb").write(jar.read(name))
    os.remove(jar_path)
    open(os.path.join(target, "version.txt"), "w").write(version["version_number"] + "\n" + file["url"] + "\n" + file["hashes"]["sha1"] + "\n")
    print(mc, version["version_number"], len(keep), "files kept")
