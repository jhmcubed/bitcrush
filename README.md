# Bitcrush

Bitcrush draws Old School RuneScape the way the game's own CPU renderer draws it, pixel for pixel, while keeping the
GPU plugin's draw distance and frame rate.

The GPU plugin smooths the game out: exact corners, smooth shading, textures blended across every pixel. The CPU
renderer (what the game uses without a GPU plugin) has a look of its own: corners snapped to whole pixels, shading
stepped in 4-pixel bricks from the game's palette, textures stepped every 8 pixels, and its own rules for what's in
front. Bitcrush works out that same arithmetic on the graphics card, so the picture is the CPU renderer's, at any draw
distance and frame rate.

## Settings

**The CPU look** (each on by default; turning one off draws that part the GPU plugin's way):

- **4-pixel bricks**: shading stepped in bricks of 4 pixels along each row, as the CPU renderer does.
- **Banded lightness**: the light in the game's own steps, not smooth.
- **8-pixel texture spans**: textures stepped every 8 pixels along each row.
- **The CPU's depth test**: what's in front decided by the CPU renderer's own rule.
- **Solid textures**: the textures the game marks solid keep their black pixels instead of showing holes.

**Like the GPU plugin**: Draw distance (up to 128 tiles), Hide unrelated maps, Extended map loading, UI scaling mode,
Compute shaders, Unlock FPS, Vsync mode, FPS target, Remove vertex snapping.

**Diagnostics**:

- **Log frame timings**: every five seconds, writes to RuneLite's log where a frame's time goes.
- **Set up triangles in compute** (on): the faster way to draw, where the graphics card has compute shaders. Off, the
  same pixels are drawn the older, slower way.

## Requirements and limits

- The fastest path needs OpenGL 4.3 with compute shaders (most Windows and Linux graphics cards of the last decade).
  Without them Bitcrush still draws the same picture, more slowly. On macOS it uses OpenCL, which hasn't been tested yet.
- Bitcrush replaces the GPU plugin and 117 HD while it's on; only one renderer can run at a time.
- The CPU renderer itself only draws 25 tiles out. Past that, Bitcrush carries the same look on to your draw distance.
- Textures added in later game updates count as not solid until Bitcrush's list of solid textures is updated.

## Credits

Made by ghillie.wiki. Built on RuneLite's GPU plugin as it was in RuneLite 1.11.26 (BSD 2-Clause; its copyright
notices are kept in each file it came from). The CPU renderer's behaviour is written fresh for Bitcrush from what the
game draws, not copied from the game.

## For the Plugin Hub's review

- Java only. No reflection, JNI or JNA, java.io file access, network, other programs, `Thread.sleep`, or
  `Client.menuAction`.
- The only files it reads are its own resources (the shaders, `solid-textures.txt`, the map regions), through
  `getResourceAsStream` from its own jar.
- OpenGL and OpenCL through LWJGL, as RuneLite's GPU plugin does; both come with RuneLite's client.
- It hides nothing the game hands a renderer, apart from what the GPU plugin's own Hide unrelated maps setting
  does.
- `Build.java` is `DEVELOPER = false` here. The developer build of the same source has a few diagnostics for working
  on the renderer (a depth-buffer view, plain triangles, a face-bias view, a draw-order log, a re-sort into a rebuilt
  draw order, an older ground test). In this build they are hidden from the settings and never read.
- `solid-textures.txt`: which textures the game marks solid (texels of 0 drawn black, not left as holes), read from
  the game once with the developer build, since RuneLite's API doesn't expose the flag.
