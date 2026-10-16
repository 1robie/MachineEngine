# World fluids shader checks (docs/FLUID_TYPES_PLAN.md stage 5)

The pack builder patches each version's vanilla water shader at runtime. A shader that fails to compile
disables the whole resource pack, so check every version after changing `ShaderPatch` or when a new
Minecraft version comes out. Nothing from Mojang is kept in git: the files are extracted on your machine.

1. Extract the vanilla files (official client jars from Mojang's version list, checked by SHA-1; the
   jars are deleted after): `python -I tools/world-fluids/fetch_vanilla.py [versions...]`
   writes `tools/world-fluids/vanilla/<version>/` (git-ignored). Add the new version to its list.
2. Patch them all: `./gradlew :test:test --tests "*FluidPackTest*" -Pmachineengine.vanilla=tools/world-fluids/vanilla -Pmachineengine.vanillaOut=tools/world-fluids/packs`
   (fails when a version's shader no longer has the skeleton `ShaderPatch` relies on).
3. Compile them with [glslang](https://github.com/KhronosGroup/glslang/releases) (Khronos' release):
   `python -I tools/world-fluids/validate_shaders.py <glslang.exe> tools/world-fluids/vanilla tools/world-fluids/packs`.
   Every version, every define set the game uses (cutout, and on 26.3 multidraw and each OIT pass), vertex and
   fragment linked. Vanilla is compiled first: a set where vanilla fails too is "skipped", never "ok".

Result on 2026-10-16: 13 versions (1.20.6 to 26.3), 88 define sets, 0 failures. glslang checks OpenGL
rules; 26.2+ can also run on Vulkan, which only an in-game check covers.
