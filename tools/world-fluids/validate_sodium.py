"""Compiles the world fluids Sodium shaders (Sodium 0.9, 26.2+) with glslang, next to Sodium's own.

Includes: sodium:* from that Sodium's include folder, minecraft:* from the version's vanilla includes.
Define sets: vertex compression (Sodium requires it), fog on/off, cutout on/off, and on 26.3 each OIT pass.
Usage: python -I validate_sodium.py <glslang.exe> <vanilla folder> <sodium folder> <packs folder>
"""
import os
import re
import sys

GLSLANG, VANILLA, SODIUM, PACKS = sys.argv[1:5]
here = os.path.dirname(os.path.abspath(__file__))
sys.argv = [sys.argv[0], GLSLANG, VANILLA, PACKS]
shared = {}
exec(open(os.path.join(here, 'validate_shaders.py'), encoding='utf-8').read().split('failures = 0')[0], shared)
compile_pair, with_defines, variants = shared['compile_pair'], shared['with_defines'], shared['variants']
INCLUDE = re.compile(r'^\s*#(?:moj_import|include)\s*<(minecraft|sodium):([^>]+)>\s*$', re.MULTILINE)


def expand(text, dirs, seen=frozenset()):
    def repl(match):
        key = match.group(1) + ':' + match.group(2)
        if key in seen:
            raise SystemExit('include cycle: ' + key)
        with open(os.path.join(dirs[match.group(1)], match.group(2)), encoding='utf-8') as f:
            body = re.sub(r'^\s*#version.*$', '', f.read(), flags=re.MULTILINE)
        return expand(body, dirs, seen | {key})
    return INCLUDE.sub(repl, text)


failures = 0
for version in ('26.2', '26.3'):
    ours_dir = os.path.join(PACKS, version, 'resourcepack', 'machineengine_world_fluids', 'assets', 'sodium', 'shaders', 'blocks')
    theirs_dir = os.path.join(SODIUM, version, 'assets', 'sodium', 'shaders', 'blocks')
    if not os.path.isdir(ours_dir):
        print(version, 'no Sodium shader in the pack')
        continue
    dirs = {'sodium': os.path.join(SODIUM, version, 'assets', 'sodium', 'shaders', 'include'),
            'minecraft': os.path.join(VANILLA, version, 'assets', 'minecraft', 'shaders', 'include')}
    read = lambda folder, ext: expand(open(os.path.join(folder, 'block_layer_opaque' + ext), encoding='utf-8').read(), dirs)
    tv, tf, ov, of = read(theirs_dir, '.vsh'), read(theirs_dir, '.fsh'), read(ours_dir, '.vsh'), read(ours_dir, '.fsh')
    print(version)
    for base in variants(tv + tf):
        for fog in (True, False):
            defines = [('USE_VERTEX_COMPRESSION', '')] + ([('USE_FOG', '')] if fog else []) + base
            label = '+'.join(n if not v else n + '=' + v for n, v in defines if n not in ('OIT_WAVELET_RANK', 'OIT_COEFF_ATTACHMENT_COUNT'))
            ok, log = compile_pair(with_defines(tv, defines), with_defines(tf, defines), [])
            if not ok:
                print('    %s: skipped (Sodium fails too: %s)' % (label, [l for l in log.splitlines() if 'ERROR' in l][:1]))
                continue
            ok, log = compile_pair(with_defines(ov, defines), with_defines(of, defines), [])
            if ok:
                print('    %s: ok' % label)
            else:
                failures += 1
                print('    %s: FAILED\n        %s' % (label, '\n        '.join([l for l in log.splitlines() if 'ERROR' in l][:6])))
print('failures:', failures)
