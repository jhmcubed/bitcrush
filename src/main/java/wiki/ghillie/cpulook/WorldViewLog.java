/*
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

import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FloatProjection;
import net.runelite.api.IntProjection;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * The developer build's log of world views (Sailing's boats, 11/18/2026): what the client hands CPU Look for a scene
 * that isn't the top level's (a boat's deck and what stands on it are a world view of their own, placed and turned on
 * the water). CPU Look's base, RuneLite 1.11.26's GPU plugin, predates them, so a boat drew nothing (Jace, on one).
 * Every five seconds while a world entity is in the top level's view (or any other scene's draws come): the draws by
 * the world view their scene says and the kind of projection with them (an IntProjection's camera and turn, or a
 * FloatProjection's matrix, with the first draw's place), the top level's own counted too, so a quiet log means none
 * came; the tile paints and tile models by scene; the main camera; each world entity's world view, place and turn; and
 * when a world view's scene loads or despawns. His first boat (11/18/2026): the boats' scenes loaded, and no draw came
 * from any other scene than the top level's, so this counts by projection too. Never in the Plugin Hub's build
 * (Build.DEVELOPER). The client read (claude/cpu-look.md, section 9, "Sailing's boats"): in this mode a boat comes as
 * one draw whose renderable is the boat's own Scene (it has no model, so nothing is drawn); counted on its own line.
 */
@Slf4j
class WorldViewLog
{
	private static final long PERIOD = 5_000_000_000L;

	private final Map<Integer, int[]> counts = new TreeMap<>();
	private final Map<Integer, String> seen = new TreeMap<>();
	private long last;

	private static int turn(float sin, float cos)
	{
		return (int) Math.round(Math.atan2(sin, cos) * 8192 / Math.PI) & 0x3fff;
	}

	private static String describe(Projection p)
	{
		if (p instanceof IntProjection)
		{
			IntProjection q = (IntProjection) p;
			return String.format("IntProjection camera %d %d %d yaw %d pitch %d", q.getCameraX(), q.getCameraY(), q.getCameraZ(),
				turn(q.getYawSin(), q.getYawCos()), turn(q.getPitchSin(), q.getPitchCos()));
		}
		if (p instanceof FloatProjection)
		{
			float[] m = ((FloatProjection) p).getProjection();
			StringBuilder s = new StringBuilder("FloatProjection");
			for (float f : m)
			{
				s.append(' ').append(String.format("%.4g", f));
			}
			return s.toString();
		}
		return p == null ? "no projection" : p.getClass().getSimpleName();
	}

	// draws by their scene's world view and their projection's kind
	private final Map<String, int[]> draws = new TreeMap<>();
	private final Map<String, String> firstDraw = new TreeMap<>();

	private int[] of(Scene scene)
	{
		return counts.computeIfAbsent(scene.getWorldViewId(), k -> new int[3]);
	}

	void draw(Scene scene, Projection projection, Renderable renderable, int x, int y, int z)
	{
		String kind = (renderable instanceof Scene ? "a boat's whole scene (world view " + ((Scene) renderable).getWorldViewId() + ") as the thing drawn, "
			: "") + (scene == null ? "no scene" : "world view " + scene.getWorldViewId()) + ", "
			+ (projection == null ? "no projection" : projection instanceof IntProjection ? "IntProjection"
			: projection instanceof FloatProjection ? "FloatProjection" : projection.getClass().getSimpleName());
		draws.computeIfAbsent(kind, k -> new int[1])[0]++;
		if (!firstDraw.containsKey(kind))
		{
			firstDraw.put(kind, describe(projection) + String.format("; the first draw at %d %d %d", x, y, z));
		}
	}

	void paint(Scene scene)
	{
		of(scene)[1]++;
	}

	void tileModel(Scene scene)
	{
		of(scene)[2]++;
	}

	void loaded(WorldView view, Scene scene)
	{
		log.info("CPU Look world views: world view {} loaded (top level {}, {} x {} tiles, base {} {}, its scene's id {})",
			view.getId(), view.isTopLevel(), view.getSizeX(), view.getSizeY(), view.getBaseX(), view.getBaseY(),
			scene != null ? scene.getWorldViewId() : null);
	}

	void despawned(WorldView view)
	{
		log.info("CPU Look world views: world view {} despawned", view.getId());
	}

	// once a frame: the report every five seconds, while any world view's draws came
	void frame(Client client, int camX, int camY, int camZ, int yaw, int pitch)
	{
		long now = System.nanoTime();
		if (now - last < PERIOD)
		{
			return;
		}
		last = now;
		WorldView top0 = client.getTopLevelWorldView();
		boolean entities = top0 != null && top0.worldEntities().iterator().hasNext();
		// the top level's own draws come with no scene in this mode ("no scene, IntProjection"): only others count
		boolean others = !counts.isEmpty() || draws.keySet().stream()
			.anyMatch(k -> !k.equals("world view -1, IntProjection") && !k.equals("no scene, IntProjection"));
		if (!entities && !others)
		{
			draws.clear();
			firstDraw.clear();
			return;
		}
		StringBuilder s = new StringBuilder("CPU Look world views, in the last five seconds: draws");
		for (Map.Entry<String, int[]> e : draws.entrySet())
		{
			s.append(String.format(" [%s: %d; %s]", e.getKey(), e.getValue()[0], firstDraw.get(e.getKey())));
		}
		for (Map.Entry<Integer, int[]> e : counts.entrySet())
		{
			int[] c = e.getValue();
			s.append(String.format(" [world view %d: %d tile paints, %d tile models]", e.getKey(), c[1], c[2]));
		}
		s.append(String.format("; the main camera %d %d %d yaw %d pitch %d", camX, camY, camZ, yaw, pitch));
		WorldView top = client.getTopLevelWorldView();
		if (top != null)
		{
			for (WorldEntity we : top.worldEntities())
			{
				LocalPoint at = we.getLocalLocation();
				WorldView v = we.getWorldView();
				s.append(String.format("; world entity of world view %s at %s %s turned %d", v != null ? v.getId() : "none",
					at != null ? at.getX() : "?", at != null ? at.getY() : "?", we.getOrientation()));
			}
		}
		log.info(s.toString());
		counts.clear();
		seen.clear();
		draws.clear();
		firstDraw.clear();
	}
}
