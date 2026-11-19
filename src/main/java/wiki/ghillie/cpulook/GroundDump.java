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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;

/**
 * Diagnostics -> Log the ground around me (developer, 11/19/2026: dark rocks beside the GE's tiled stone that the
 * client's CPU renderer draws and CPU Look doesn't, with no menu or object of their own). Everything on the tiles round
 * a scene tile, every level: each tile's settings, heights and render level, its paint (corner colours, texture, flat)
 * or its shaped model (corners, faces, each face's three colours and texture), and every object's model in full
 * (ModelDump: corners, faces, colours, priorities, transparency, bias, textures) with its place and turn and the
 * camera; one line each, so the spot can be drawn again away from the game.
 */
final class GroundDump
{
	private GroundDump()
	{
	}

	static List<String> dump(WorldView wv, int cx, int cy, int radius, int renderMode, int camX, int camY, int camZ, int yaw,
		int pitch, int zoom, int centerX, int centerY, int viewW, int viewH)
	{
		final List<String> out = new ArrayList<>();
		final Scene scene = wv.getScene();
		final Tile[][][] tiles = scene.getTiles();
		final byte[][][] settings = wv.getTileSettings();
		final int[][][] heights = wv.getTileHeights();
		out.add(String.format("camera %d %d %d yaw %d pitch %d zoom %d centre %d %d view %d %d; around scene tile %d %d, radius %d, plane %d",
			camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH, cx, cy, radius, wv.getPlane()));
		for (int z = 0; z < tiles.length; z++)
		{
			for (int x = Math.max(0, cx - radius); x <= Math.min(tiles[z].length - 1, cx + radius); x++)
			{
				for (int y = Math.max(0, cy - radius); y <= Math.min(tiles[z][x].length - 1, cy + radius); y++)
				{
					final Tile t = tiles[z][x][y];
					final StringBuilder s = new StringBuilder();
					s.append(String.format("tile %d %d %d: settings %d, heights %d %d %d %d", z, x, y, settings[z][x][y],
						heights[z][x][y], heights[z][x + 1][y], heights[z][x + 1][y + 1], heights[z][x][y + 1]));
					if (t == null)
					{
						out.add(s.append(", no tile").toString());
						continue;
					}
					s.append(String.format(", render level %d, plane %d%s", t.getRenderLevel(), t.getPlane(), t.getBridge() != null ? ", a bridge below" : ""));
					final SceneTilePaint p = t.getSceneTilePaint();
					if (p != null)
					{
						s.append(String.format("; paint sw %d se %d ne %d nw %d texture %d rgb %d flat %b", p.getSwColor(), p.getSeColor(),
							p.getNeColor(), p.getNwColor(), p.getTexture(), p.getRBG(), p.isFlat()));
					}
					final SceneTileModel m = t.getSceneTileModel();
					if (m != null)
					{
						s.append(String.format("; model shape %d rotation %d underlay %d overlay %d flat %b vx %s vy %s vz %s fa %s fb %s fc %s ca %s cb %s cc %s tex %s",
							m.getShape(), m.getRotation(), m.getModelUnderlay(), m.getModelOverlay(), m.isFlat(),
							Arrays.toString(m.getVertexX()), Arrays.toString(m.getVertexY()), Arrays.toString(m.getVertexZ()),
							Arrays.toString(m.getFaceX()), Arrays.toString(m.getFaceY()), Arrays.toString(m.getFaceZ()),
							Arrays.toString(m.getTriangleColorA()), Arrays.toString(m.getTriangleColorB()),
							Arrays.toString(m.getTriangleColorC()), Arrays.toString(m.getTriangleTextureId())));
					}
					out.add(s.toString());
					final GroundObject g = t.getGroundObject();
					if (g != null)
					{
						object(out, "ground object", g.getId(), g.getHash(), g.getConfig(), g.getRenderable(), 0, g.getX(), g.getZ(), g.getY(),
							camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH);
					}
					final DecorativeObject d = t.getDecorativeObject();
					if (d != null)
					{
						object(out, "decoration", d.getId(), d.getHash(), d.getConfig(), d.getRenderable(), 0, d.getX() + d.getXOffset(),
							d.getZ(), d.getY() + d.getYOffset(), camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH);
						object(out, "decoration's second", d.getId(), d.getHash(), d.getConfig(), d.getRenderable2(), 0,
							d.getX() + d.getXOffset2(), d.getZ(), d.getY() + d.getYOffset2(), camX, camY, camZ, yaw, pitch, zoom, centerX,
							centerY, viewW, viewH);
					}
					final WallObject w = t.getWallObject();
					if (w != null)
					{
						object(out, "wall", w.getId(), w.getHash(), w.getConfig(), w.getRenderable1(), 0, w.getX(), w.getZ(), w.getY(),
							camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH);
						object(out, "wall's second", w.getId(), w.getHash(), w.getConfig(), w.getRenderable2(), 0, w.getX(), w.getZ(),
							w.getY(), camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH);
					}
					for (GameObject o : t.getGameObjects())
					{
						if (o == null || o.getSceneMinLocation().getX() != x || o.getSceneMinLocation().getY() != y)
						{
							continue;  // a big object once, at its first tile
						}
						object(out, "object", o.getId(), o.getHash(), o.getConfig(), o.getRenderable(), o.getModelOrientation(), o.getX(),
							o.getZ(), o.getY(), camX, camY, camZ, yaw, pitch, zoom, centerX, centerY, viewW, viewH);
					}
				}
			}
		}
		return out;
	}

	private static void object(List<String> out, String kind, int id, long hash, int config, Renderable r, int orientation, int x,
		int y, int z, int camX, int camY, int camZ, int yaw, int pitch, int zoom, int centerX, int centerY, int viewW, int viewH)
	{
		if (r == null)
		{
			return;
		}
		final Model m = r instanceof Model ? (Model) r : r.getModel();
		final String what = String.format("  %s %d hash %x config %d renderable %s at %d %d %d turned %d", kind, id, hash, config,
			r.getClass().getSimpleName(), x, y, z, orientation);
		if (m == null)
		{
			out.add(what + ": no model");
			return;
		}
		out.add(what + String.format(": %d corners, %d faces, render mode %d; model %s", m.getVerticesCount(), m.getFaceCount(),
			r.getRenderMode(), ModelDump.dump(m, r.getRenderMode(), orientation, x, y, z, camX, camY, camZ, yaw, pitch, zoom, centerX,
				centerY, viewW, viewH)));
	}
}
