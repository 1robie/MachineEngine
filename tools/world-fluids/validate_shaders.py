"""Compiles the world fluids shaders of every version with glslang, next to vanilla (the baseline).

For each version: includes inlined from that version's include folder, each define variant the game
uses, vertex and fragment linked together (-l) so the varyings must match. A variant whose vanilla
pair fails too (a mode glslang can't check) is reported as skipped, never as passed.
Usage: python -I validate_shaders.py <glslang.exe> <vanilla folder> <packs folder>
"""
import itertools
import os
import re
import subprocess
import sys
import tempfile

GLSLANG, VANILLA, PACKS = sys.argv[1:4]
INCLUDE = re.compile(r'^\s*#(?:moj_import|include)\s*<(?:minecraft:)?([^>]+)>\s*$', re.MULTILINE)


def expand(text, include_dir, seen=None):
    seen = set() if seen is None else seen

    def repl(match):
        name = match.group(1)
        if name in seen:
            raise SystemExit('include cycle: ' + name)
        path = os.path.join(include_dir, name)
        with open(path, encoding='utf-8') as f:
            # The game drops an include's own #version line when it inlines it.
            body = re.sub(r'^\s*#version.*$', '', f.read(), flags=re.MULTILINE)
            return expand(body, include_dir, seen | {name})
    return INCLUDE.sub(repl, text)


def with_defines(text, defines):
    lines = text.split('\n')
    insert = 1
    while insert < len(lines) and lines[insert].startswith('#extension'):
        insert += 1
    return '\n'.join(lines[:insert] + ['#define %s %s' % (k, v) for k, v in defines] + lines[insert:])


def compile_pair(vert, frag, extra):
    with tempfile.TemporaryDirectory() as tmp:
        v, f = os.path.join(tmp, 'shader.vert'), os.path.join(tmp, 'shader.frag')
        open(v, 'w', encoding='utf-8', newline=chr(10)).write(vert.replace(chr(13), ''))
        open(f, 'w', encoding='utf-8', newline=chr(10)).write(frag.replace(chr(13), ''))
        run = subprocess.run([GLSLANG] + extra + ['-l', v, f], capture_output=True, text=True)
        return run.returncode == 0, (run.stdout + run.stderr).strip()


def variants(text):
    """The define sets the game compiles: cutout or not; on 26.3 also multidraw and each OIT pass."""
    passes = [[]]
    if 'OIT_ALPHA_ONLY' in text:
        def oit(n, *names):
            rank = n.bit_length() - 1
            return [('OIT', '')] + [(name, '') for name in names] + [
                ('OIT_COEFF_COUNT', str(n)), ('OIT_WAVELET_RANK', str(rank)), ('OIT_COEFF_ATTACHMENT_COUNT', str(max(1, n // 4)))]
        for n in (4, 8, 16):
            passes += [oit(n, 'OIT_ALPHA_ONLY', 'OIT_DEPTH_BOUNDS'), oit(n, 'OIT_ALPHA_ONLY', 'OIT_TRANSMITTANCE'),
                       oit(n, 'OIT_ALPHA_ONLY', 'OIT_ADDITIVE'), oit(n, 'OIT_ACCUMULATE'), oit(n, 'OIT_ACCUMULATE', 'OIT_ADDITIVE')]
    out = []
    for multidraw in ((False, True) if 'MULTIDRAW_TERRAIN' in text else (False,)):
        for cutout in (None, '0.1'):
            for each in passes:
                defines = list(each)
                if multidraw:
                    defines.append(('MULTIDRAW_TERRAIN', ''))
                if cutout:
                    defines.append(('ALPHA_CUTOUT', cutout))
                out.append(defines)
    return out


failures = 0
for version in sorted(os.listdir(PACKS)):
    pack = os.path.join(PACKS, version, 'resourcepack', 'machineengine_world_fluids', 'assets', 'minecraft', 'shaders', 'core')
    if not os.path.isdir(pack):
        continue
    name = 'terrain' if os.path.exists(os.path.join(pack, 'terrain.vsh')) else 'rendertype_translucent'
    core = os.path.join(VANILLA, version, 'assets', 'minecraft', 'shaders', 'core')
    include = os.path.join(VANILLA, version, 'assets', 'minecraft', 'shaders', 'include')
    read = lambda folder, ext: expand(open(os.path.join(folder, name + ext), encoding='utf-8').read(), include)
    vanilla_v, vanilla_f, ours_v, ours_f = read(core, '.vsh'), read(core, '.fsh'), read(pack, '.vsh'), read(pack, '.fsh')
    results = []
    for defines in variants(vanilla_v + vanilla_f):
        label = '+'.join(n if not v else n + '=' + v for n, v in defines if n not in ('OIT_WAVELET_RANK', 'OIT_COEFF_ATTACHMENT_COUNT')) or 'plain'
        base_ok, base_log = compile_pair(with_defines(vanilla_v, defines), with_defines(vanilla_f, defines), [])
        if not base_ok:
            results.append('%s: skipped (vanilla fails too: %s)' % (label, base_log.splitlines()[-1] if base_log else '?'))
            continue
        ok, log = compile_pair(with_defines(ours_v, defines), with_defines(ours_f, defines), [])
        if ok:
            results.append('%s: ok' % label)
        else:
            failures += 1
            errors = [l for l in log.splitlines() if 'ERROR' in l][:6]
            results.append('%s: FAILED\n        %s' % (label, '\n        '.join(errors)))
    print('%-8s %s' % (version, name))
    for line in results:
        print('    ' + line)
print('failures:', failures)
