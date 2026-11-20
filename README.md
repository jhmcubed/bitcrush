# Bitcrush

**The 07 look, kept exactly. The GPU's comforts, added around it.**

Old School RuneScape still carries the software renderer it inherited from its 2007 roots, and its look is part of the
game: corners snapped to whole pixels, light stepped in 4-pixel bricks from the game's own palette, textures stepped
every 8 pixels, faces covering one another by the game's own rules. RuneLite's GPU plugin trades that look for
smoothness and reach. The software renderer keeps it, but only 25 tiles out and at the game's 50 frames a second.

Bitcrush doesn't make you choose. It runs the software renderer's own arithmetic on your graphics card: every corner
turned and rounded, every triangle's edges, colour and depth stepped row by row, every face drawn in the game's own
order, the way the game's own drawing routines do it. The picture is the native renderer's, pixel for pixel, but
for the few exceptions listed below. Around it you get what only a graphics card can give: the GPU plugin's draw distance, an unlocked frame rate, and fog at the
far edge if you want it.

## Why

The native renderer isn't a lesser version of the game's look; it is the game's look. Smoothing it away changes the
game's character, and keeping it has always meant giving up how far you can see and how smoothly it runs. Bitcrush
keeps the renderer's integrity and adds only the quality of life a graphics card makes possible.

## How exact

Bitcrush is built against a check that draws test scenes twice, once on the graphics card and once by a reference
written from the game's own drawing routines, and compares every pixel; and it's compared in the game with the CPU
renderer itself, side by side. Where the two have differed, Bitcrush was changed to the game's way, down to how the
game adds up a triangle's edges in floating point. The few places it still can't match are listed under Requirements
and limits.

## Settings

**The CPU look** (each on by default; turning one off draws that part the GPU plugin's way):

- **4-pixel bricks**: shading stepped in bricks of 4 pixels along each row, as the CPU renderer does.
- **Banded lightness**: the light in the game's own steps, not smooth.
- **8-pixel texture spans**: textures stepped every 8 pixels along each row.
- **The CPU's depth test**: what's in front decided by the CPU renderer's own rule.
- **Solid textures**: the textures the game marks solid keep their black pixels instead of showing holes.

**Like the GPU plugin**: Draw distance (up to 128 tiles), Fog depth (off by default: the CPU renderer stops in a hard
edge; with fog, the far edge fades into the sky as the GPU plugin's does), Hide unrelated maps, Extended map loading,
UI scaling mode, Compute shaders, Unlock FPS, Vsync mode, FPS target, Remove vertex snapping.

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
- Faces you can see through blend within a few levels of colour of the game's: a graphics card rounds a blend its own
  way.
- Sailing's boats are drawn wherever the fast path runs (OpenGL 4.3 compute shaders), rocking and all, through the
  game's own arithmetic for them. Without compute shaders, and on macOS, Bitcrush falls back to RuneLite's older way
  of handing a renderer the scene, which never passes a boat on, so there boats (and whoever is on them) are
  invisible, as they are with GPU (legacy).

## Credits

Made by ghillie.wiki. Bitcrush is a third-party plugin, not made or endorsed by Jagex or RuneLite. Built on RuneLite's GPU plugin as it was in RuneLite 1.11.26 (BSD 2-Clause; its copyright
notices are kept in each file it came from). The CPU renderer's behaviour is written fresh for Bitcrush from what the
game draws, not copied from the game.

## For the Plugin Hub's review

Checked against the Plugin Hub's README, RuneLite's Rejected or Rolled Back Features (including its forbidden language
features and sensitive and restricted APIs) and Jagex's third-party client guidelines:

- It draws the game's own presentation and changes no in-game content or assets, gives no combat or boss aid, adds or
  changes no menu entries, moves or resizes no interface, and doesn't touch RuneLite's own UI or other plugins'
  settings.
- Java only. No reflection, JNI or JNA, java.io file access, network, other programs, `Thread.sleep`, or
  `Client.menuAction`.
- The only files it reads are its own resources (the shaders, `solid-textures.txt`, the map regions), through
  `getResourceAsStream` from its own jar.
- OpenGL and OpenCL through LWJGL, as RuneLite's GPU plugin does; both come with RuneLite's client. No dependencies
  beyond the client and the template's own (Lombok, JUnit for tests). Its package is `wiki.ghillie.cpulook`.
- It talks to no server and reads no player data beyond what the game hands a renderer.
- It hides nothing the game hands a renderer, apart from what the GPU plugin's own Hide unrelated maps setting
  does.
- `Build.java` is `DEVELOPER = false` here. The developer build of the same source has a few diagnostics for working
  on the renderer (a depth-buffer view, plain triangles, a face-bias view, a draw-order log, a re-sort into a rebuilt
  draw order, an older ground test). In this build they are hidden from the settings and never read.
- `solid-textures.txt`: which textures the game marks solid (texels of 0 drawn black, not left as holes), read from
  the game once with the developer build, since RuneLite's API doesn't expose the flag.
