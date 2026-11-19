/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2026, ghillie.wiki (the CPU look)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package wiki.ghillie.cpulook;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import static wiki.ghillie.cpulook.CpuLookPlugin.MAX_DISTANCE;
import static wiki.ghillie.cpulook.CpuLookPlugin.MAX_FOG_DEPTH;
import wiki.ghillie.cpulook.config.UIScalingMode;

@ConfigGroup(CpuLookConfig.GROUP)
public interface CpuLookConfig extends Config
{
	String GROUP = "cpulook";

	@ConfigSection(
		name = "The CPU look",
		description = "How much of the CPU renderer's drawing to keep. Corners on whole pixels, its fill rule and its draw order are always on.",
		position = 0
	)
	String cpuLook = "cpuLook";

	@ConfigItem(
		keyName = "bricks",
		name = "4-pixel bricks",
		description = "Shading worked out once every 4 pixels along each row, as the CPU does it.",
		position = 1,
		section = cpuLook
	)
	default boolean bricks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bands",
		name = "Banded lightness",
		description = "Shading rounded down to one of the palette's 128 lightness steps, as the CPU does it.",
		position = 2,
		section = cpuLook
	)
	default boolean bands()
	{
		return true;
	}

	@ConfigItem(
		keyName = "textureSpans",
		name = "8-pixel texture spans",
		description = "Texture perspective worked out every 8 pixels and drawn straight between, with the shading fixed for each 8, as the CPU does it.",
		position = 3,
		section = cpuLook
	)
	default boolean textureSpans()
	{
		return true;
	}

	@ConfigItem(
		keyName = "cpuDepthTest",
		name = "The CPU's depth test",
		description = "Today's CPU renderer keeps a depth key for every pixel and draws a pixel only where it's nearer than what's there (claude/cpu-look.md, \"Read from today's client\"): the ground in front hides a log's sunk side and the trunks' bottoms, and a canopy's nearer faces cover its farther ones. Off: the game's order alone decides, which draws them over the ground in front.",
		position = 4,
		section = cpuLook
	)
	default boolean cpuDepthTest()
	{
		return true;
	}

	@ConfigSection(
		name = "Diagnostics",
		description = "Switches for finding where CPU Look differs from the CPU renderer. Off in normal use.",
		position = 30,
		closedByDefault = true
	)
	String diagnostics = "diagnostics";

	@ConfigItem(
		keyName = "depthBuffer",
		name = "Depth buffer",
		description = "Decide what's in front pixel by pixel, as the GPU plugin does, instead of the game's back-to-front order. If something missing comes back with this on, it was being drawn over in the wrong order.",
		position = 31,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean depthBuffer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "plainTriangles",
		name = "Plain triangles",
		description = "Fill each triangle in one flat colour by the graphics card, on the same whole-pixel corners, instead of the CPU's fill and shading. If something missing comes back with this on, the CPU-style fill was losing it.",
		position = 32,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean plainTriangles()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groundOnlyDepth",
		name = "Ground-only depth (old)",
		description = "The stopgap before the CPU's depth test was known (11/18/2026): the ground drawn first into a depth buffer, scenery hidden only where it's behind that ground. Only with The CPU's depth test off.",
		position = 37,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean groundHides()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groundAllowance",
		name = "Ground allowance",
		description = "How far behind the ground (in the game's units, 128 a tile) scenery can sit and still be drawn, with Ground-only depth (old) on. Too little and things standing on a slope lose a sliver where they touch the ground; too much and sunk bases show again.",
		position = 38,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	@Range(min = 0, max = 128)
	default int groundAllowance()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "cpuOrderTest",
		name = "Re-sort into the CPU's order",
		description = "Re-sort each frame into the CPU renderer's order as CPU Look rebuilds it from the scene (CpuOrder.java, from the 2022 reading of the game's scene drawing). Tried 11/18/2026: it came out the same as the order the client already hands over, so it changes nothing on screen, and it costs frames. Kept for testing other rules.",
		position = 39,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean cpuOrderTest()
	{
		return false;
	}

	@ConfigItem(
		keyName = "solidTextures",
		name = "Solid textures",
		description = "Draws a texel of 0 black in the textures the game marks solid, as the CPU renderer does; the others leave a hole there. Off: every texel of 0 is a hole, as RuneLite's GPU plugin has it. Which textures are solid comes from a list read from the game.",
		position = 5,
		section = cpuLook
	)
	default boolean solidTextures()
	{
		return true;
	}

	enum PlayerFaces
	{
		CPU("As the CPU"),
		DEPTH("Nearest wins"),
		BOTH("Both fixed");

		private final String name;

		PlayerFaces(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigItem(
		keyName = "playerFaces",
		name = "Players' own faces",
		description = "How a player's own faces (and most NPCs') cover each other. As the CPU (the default): in the game's sorted order, a later face covering an earlier one even where the earlier is nearer: trousers cover boots that poke through them, but some items worn on the back cover part of the torso, as in the CPU renderer and RuneLite's GPU plugins. Nearest wins: the torso stays whole, and boots poke through trousers. Both fixed (Bitcrush's own, not the CPU's look): the game's order for faces close together, the nearest for faces far apart (Band).",
		position = 6,
		section = cpuLook
	)
	default PlayerFaces playerFaces()
	{
		return PlayerFaces.CPU;
	}

	@Range(min = 1, max = 64)
	@ConfigItem(
		keyName = "playerFacesBand",
		name = "Band",
		description = "For Both fixed: how far behind a player's nearest face at a pixel (in game units) one of its faces can still show by the game's order. Beyond it the nearer face wins. 12 keeps trousers over boots and a log basket behind the torso.",
		position = 7,
		section = cpuLook
	)
	default int playerFacesBand()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "showBias",
		name = "Show each face's bias",
		description = "Colours every face by its bias (Model.getFaceBias), the amount the CPU's depth key lifts it toward the camera (twice the bias, in units) so it isn't hidden by the ground it lies on: no bias dimmed, 1 to 12 in reds (brighter for more), over 12 in magenta.",
		position = 41,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean showBias()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logTimings",
		name = "Log frame timings",
		description = "Every five seconds while on, writes three lines to RuneLite's log, each starting \"CPU Look frame timings\": where a frame's time goes on the processor (the game walking the scene, CPU Look's own calls for what it hands over, the uploads and compute dispatch, the frame's drawing, the rest), on the graphics card (the compute pass by workgroup size, the scene's drawing by stage), and the slow frames (how many, how far apart, what took the time, Java's garbage collections).",
		position = 42,
		section = diagnostics
	)
	default boolean logTimings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "recordPath",
		name = "Set up triangles in compute",
		description = "The record path (stage A3): each triangle set up once, in the CPU's order, by a compute pass, and drawn from what it wrote. Off: the geometry stage sets them up, as before 11/18/2026. The same pixels either way (run.py check draws its scenes both ways); for comparing their frame timings.",
		position = 43,
		section = diagnostics
	)
	default boolean recordPath()
	{
		return true;
	}

	@ConfigItem(
		keyName = "zoneMode",
		name = "Zone mode (stage B)",
		description = "Stage B (claude/cpu-look-stage-b.md): the client in its zone mode, which hands a renderer only what moves, and CPU Look drawing the scene from its own walk, in the CPU's order. Turn the plugin off and on after changing it. With compute shaders on only.",
		position = 44,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean zoneMode()
	{
		return false;
	}

	@Range(min = 0, max = 15)
	@ConfigItem(
		keyName = "zoneThreads",
		name = "Zone mode's render threads",
		description = "How many threads the client spreads its own zone-mode work over (its picking: every tile and object's clickbox), as RuneLite's GPU plugin's Threads (3). Turn the plugin off and on after changing it.",
		position = 45,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default int zoneThreads()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "zoneIdle",
		name = "Zone mode: draw nothing",
		description = "A measurement: in zone mode, CPU Look does none of its own work (no walk, no copying, no scene: only the interface is drawn), so Log frame timings shows what the client's zone mode itself costs a frame.",
		position = 46,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean zoneIdle()
	{
		return false;
	}

	@ConfigItem(
		keyName = "zoneCheck",
		name = "Zone mode: check the walk",
		description = "A check: every 30th frame in zone mode, the order worked out from the scene as kept (read when it loads, its zones read again when the client says they changed) is compared entry for entry with the order from the scene read afresh; Log frame timings isn't needed, the result is logged every five seconds. Costs a few milliseconds every 30th frame.",
		position = 47,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean zoneCheck()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logDrawOrder",
		name = "Log the draw order",
		description = "Writes one frame's draw order to RuneLite's log (the window run.py opens, and .runelite/logs/client.log): whether the ground in front of trees and logs is handed over before or after them. Turn it off and on again for another frame.",
		position = 33,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean logDrawOrder()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logPlayerModel",
		name = "Log my player's model",
		description = "Writes your player's model as the game hands it over (its corners, faces, colours, priorities and the camera) to RuneLite's log, once, as one line starting \"Bitcrush player model:\". For working out how render mode 2 could keep trousers over boots without an item on the back covering the torso. Turn it off and on again for another.",
		position = 34,
		section = diagnostics,
		hidden = !Build.DEVELOPER
	)
	default boolean logPlayerModel()
	{
		return false;
	}

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance, in tiles. The CPU renderer draws 25.",
		position = 10
	)
	default int drawDistance()
	{
		return 50;
	}
	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "How many tiles in from the edge of the draw distance the fog reaches, as the GPU plugin's fog does: the far edge fades into the sky instead of stopping. 0: no fog, the hard edge the CPU renderer has.",
		position = 10
	)
	default int fogDepth()
	{
		return 0;
	}


	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 11
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 12
	)
	default int expandedMapLoadingChunks()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode. The game view is always scaled up nearest-neighbour, as whole game pixels.",
		position = 13
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.NEAREST;
	}

	@ConfigItem(
		keyName = "useComputeShaders",
		name = "Compute shaders",
		description = "Sorts each model's faces on the GPU, as the client sorts them. Requires plugin restart.",
		warning = "This feature requires OpenGL 4.3 to use. Please check that your GPU supports this.\nRestart the plugin for changes to take effect.",
		position = 14
	)
	default boolean useComputeShaders()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 15
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 16
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 17
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Keeps animated models' corners between whole units. Off, as the CPU renderer has them.",
		position = 18
	)
	default boolean removeVertexSnapping()
	{
		return false;
	}
}
