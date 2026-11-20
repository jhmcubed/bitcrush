# Bitcrush

**The 07 look, kept exactly. The GPU's comforts, added around it.**

Old School RuneScape still carries the software renderer it inherited from its 2007 roots, and its look is part of the
game: corners snapped to whole pixels, light stepped in 4-pixel bricks from the game's own palette, textures stepped
every 8 pixels, faces covering one another by the game's own rules. RuneLite's GPU plugin trades that look for
smoothness and reach. The software renderer keeps it, but only 25 tiles out and at the game's 50 frames a second.

Bitcrush doesn't make you choose. It runs the software renderer's own arithmetic on your graphics card: every corner
turned and rounded, every triangle's edges, colour and depth stepped row by row, every face drawn in the game's own
order, the way the game's own drawing routines do it. The picture is the native renderer's, pixel for pixel, but for
the few exceptions listed below. Around it you get what only a graphics card can give: the GPU plugin's draw distance,
an unlocked frame rate, and fog at the far edge if you want it.

## Getting started

1. In RuneLite, open the configuration panel (the wrench in the side bar), press **Plugin Hub** at its bottom, search
   for Bitcrush and press **Install**.
2. Bitcrush installs switched off. Find it in the plugin list and switch it on. That switches the GPU plugin and
   117 HD off, since only one renderer can run at a time; switching either of them back on switches Bitcrush off.
3. That's all: out of the box it draws as the game's own renderer does, 50 tiles out, with the frame rate unlocked
   (a target of 60). Its settings (the cog beside it) are below.

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

**The CPU look** (turning one of the first five off draws that part the GPU plugin's way):

- **4-pixel bricks** (on): shading stepped in bricks of 4 pixels along each row, as the CPU renderer does.
- **Banded lightness** (on): the light in the game's own steps, not smooth.
- **8-pixel texture spans** (on): textures stepped every 8 pixels along each row.
- **The CPU's depth test** (on): what's in front decided by the CPU renderer's own rule.
- **Solid textures** (on): the textures the game marks solid keep their black pixels instead of showing holes.
- **Players' own faces**: how a player's own faces (and most NPCs') cover each other.
  - *As the CPU* (the default): the game's own way. Trousers cover boots that poke through them, but some items worn
    on the back cover part of the torso, as they do in the CPU renderer and RuneLite's GPU plugins.
  - *Nearest wins*: the torso stays whole, but boots poke through trousers.
  - *Both fixed*: Bitcrush's own, not the game's look. The game's order for faces close together, the nearest face
    for faces farther apart: at the default Band, trousers stay over boots and a log basket stays behind the torso.
- **Band** (12): for Both fixed, how far behind the nearest face at a pixel, in game units, another of the model's
  faces can be and still show by the game's order. Past it, the nearer face wins.

**Like the GPU plugin**: Draw distance (50 tiles, up to 128), Fog depth (off by default: the CPU renderer stops in a
hard edge; with fog, the far edge fades into the sky as the GPU plugin's does), Hide unrelated maps, Extended map
loading, UI scaling mode, Compute shaders, Unlock FPS, Vsync mode, FPS target, Remove vertex snapping.

**Diagnostics**:

- **Log frame timings**: every five seconds, writes to RuneLite's log where a frame's time goes, in lines starting
  "CPU Look frame timings" (CPU Look was Bitcrush's working title).
- **Set up triangles in compute** (on): the faster way to draw, where the graphics card has compute shaders. Off, the
  same pixels are drawn the older, slower way.

## Requirements and limits

- The fastest path needs OpenGL 4.3 with compute shaders (most Windows and Linux graphics cards of the last decade).
  Without them Bitcrush still draws the same picture, more slowly. On macOS it uses OpenCL, which hasn't been tested
  yet.
- Only one renderer runs at a time: Bitcrush, the GPU plugin or 117 HD.
- The CPU renderer itself only draws 25 tiles out. Past that, Bitcrush carries the same look on to your draw distance.
- Textures added in later game updates count as not solid until Bitcrush's list of solid textures is updated.
- Faces you can see through blend within a few levels of colour of the game's: a graphics card rounds a blend its own
  way.
- Sailing's boats are drawn wherever the fast path runs (OpenGL 4.3 compute shaders), rocking and all, through the
  game's own arithmetic for them. Without compute shaders, and on macOS, Bitcrush falls back to RuneLite's older way
  of handing a renderer the scene, which never passes a boat on, so there boats (and whoever is on them) are
  invisible, as they are with GPU (legacy).

## Questions and problems

Open an issue at https://github.com/jhmcubed/bitcrush/issues. For a picture that differs from the game's own renderer,
a screenshot of each (Bitcrush on, then off with the GPU plugin off too) from the same spot helps most.

## Credits

Made by ghillie.wiki. Bitcrush is a third-party plugin, not made or endorsed by Jagex or RuneLite. Built on RuneLite's
GPU plugin as it was in RuneLite 1.11.26 (BSD 2-Clause; its copyright notices are kept in each file it came from). The
CPU renderer's behaviour is written fresh for Bitcrush, not copied from the game.
