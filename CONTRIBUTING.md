# Contributing to Bitcrush

Issues and pull requests are welcome at https://github.com/jhmcubed/bitcrush.

## Building and trying it

The build is RuneLite's example-plugin template, the same one the Plugin Hub builds:

```
gradle build
```

To try it, run `wiki.ghillie.cpulook.CpuLookPluginTest` (`src/test/java`) from your IDE with `-ea` among its VM
options. It opens RuneLite with Bitcrush loaded; switch it on in the plugin list.

## The source

`src/main/java/wiki/ghillie/cpulook/`, built on RuneLite's GPU plugin as it was in RuneLite 1.11.26:

- `CpuLookPlugin.java`: the renderer: the scene handed over by the client, the compute passes, the draws.
- `CpuLookConfig.java`: the settings.
- `SceneUploader.java`: models and the scene's tiles, as the card reads them.
- `CpuOrder.java`, `FastOrder.java`: the CPU renderer's draw order, worked out on the processor.
- `PainterBatch.java`: players and most NPCs, drawn as the CPU renderer draws them (Players' own faces).
- `BoatMath.java`: Sailing's boats, in the game's own float arithmetic.
- `Palette.java`: the CPU renderer's palette.
- `TextureManager.java`: textures, and which ones the game marks solid.
- `FmaProbe.java`: asks the graphics card whether its `fma()` rounds once, which picks the shaders' way of dividing
  exactly.
- `FrameTimer.java`: Log frame timings.
- `DrawOrderLog.java`, `ModelDump.java`, `GroundDump.java`, `WorldViewLog.java`: diagnostics for the developer build
  (below), never run in this one.

`src/main/resources/wiki/ghillie/cpulook/`: the shaders (OpenGL, and OpenCL for macOS), the map regions for Hide
unrelated maps, and `solid-textures.txt`.

`CpuLook` is the plugin's working title, kept in its code, packages and log lines.

## The developer build

`Build.java` is `DEVELOPER = false` here. The author's own copy of the same source sets it true, which shows more
switches under Diagnostics (a depth-buffer view, plain triangles, each face's bias, a draw-order log and others), for
working on the renderer. In this build they are hidden from the settings and never read.

The pixel-by-pixel checks (the test scenes drawn by the shaders and by a reference written from the game's drawing
routines, then compared) live in that copy too: they need an OpenGL test harness that isn't one of the client's own
dependencies.

`solid-textures.txt` lists the textures the game marks solid (a texel of 0 drawn black, not left as a hole). RuneLite's
API doesn't expose that flag, so the list was read from the game once with the developer build.

## Where changes are made

This repository is written by a script from the author's own copy, where Bitcrush is developed beside its checks. A
pull request here is welcome; it's carried over there by hand, so the next copy keeps it.
