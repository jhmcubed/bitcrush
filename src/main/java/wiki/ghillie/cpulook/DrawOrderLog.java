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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.Model;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;

/**
 * A diagnostic: what the client hands CPU Look in one frame, and in what order, summed up in a few log lines.
 *
 * Asked by the grass that CPU Look loses beside logs and in front of tree bases (claude/cpu-look.md, section 9). It
 * answers: for the tiles round the player, how many have a ground decoration (grass) in the scene, how many of those
 * the client handed over, and how many CPU Look then skipped as off screen; and for each big object (a tree, a log)
 * round the player, whether the ground and grass in front of it came before it or after it (the CPU's way: after).
 * Objects are found by their own Renderable on the scene's tiles, not by their hash.
 */
@Slf4j
class DrawOrderLog
{
	private static final String[] KINDS = {"ground", "ground (shaped)", "grass/ground decoration", "big object", "wall", "wall decoration", "item", "player/npc", "other", "wall (second half)"};
	static final int GROUND = 0, SHAPED = 1, DECORATION = 2, BIG = 3, WALL = 4, WALL_DECORATION = 5, ITEM = 6, ACTOR = 7, OTHER = 8, WALL_2 = 9;
	private static final int OFFSET = CpuLookPlugin.SCENE_OFFSET;

	// kind, tile x, tile y, plane, skipped (1), footprint min x, min y, max x, max y
	private final List<int[]> entries = new ArrayList<>();
	// the renderable of each entry (null for the ground), for the model report
	private final List<Renderable> renderables = new ArrayList<>();
	private final Map<Renderable, Integer> drawn = new IdentityHashMap<>();
	// for each entry: the height it was handed over at and its hash (0 for the ground), for the report on players,
	// NPCs and the draws found on no tile ("other": projectiles, spell effects, objects there only for a moment)
	private final List<long[]> placed = new ArrayList<>();
	private boolean recording;
	private int camX, camY, playerX, playerY, plane;
	private Scene lastScene;
	private Scene clientScene;
	private int noScene;

	boolean recording()
	{
		return recording;
	}

	void start(Scene scene, int cameraTileX, int cameraTileY, int playerTileX, int playerTileY, int playerPlane)
	{
		entries.clear();
		renderables.clear();
		placed.clear();
		clientScene = scene;
		lastScene = scene;
		noScene = 0;
		drawn.clear();
		camX = cameraTileX;
		camY = cameraTileY;
		playerX = playerTileX;
		playerY = playerTileY;
		plane = playerPlane;
		recording = true;
	}

	void ground(boolean shaped, int tileX, int tileY, int plane)
	{
		entries.add(new int[]{shaped ? SHAPED : GROUND, tileX, tileY, plane, 0, tileX, tileY, tileX, tileY});
		renderables.add(null);
		placed.add(new long[]{0, 0});
	}

	void draw(Scene scene, Renderable r, int x, int y, int z, long hash)
	{
		int type = (int) (hash >> 16 & 7);
		int p = (int) (hash >> 14 & 3);
		int tx = x >> 7, ty = z >> 7;
		int[] e = {type == 0 || type == 1 ? ACTOR : OTHER, tx, ty, p, 0, tx, ty, tx, ty};
		if (scene == null)
		{
			// the old draw path hands no scene with each object: the client's own (top-level) one
			noScene++;
			scene = clientScene;
		}
		if (scene != null && e[0] == OTHER)
		{
			find(scene, r, e);
		}
		entries.add(e);
		renderables.add(r);
		placed.add(new long[]{y, hash});
		drawn.put(r, entries.size() - 1);
	}

	void skipped()
	{
		if (!entries.isEmpty())
		{
			entries.get(entries.size() - 1)[4] = 1;
		}
	}

	// which object on the tiles round (x, z) this renderable is
	// what kind of object this renderable is (DECORATION, BIG, WALL, WALL_2, ...; OTHER if not found), for the hide switches
	static int kindOf(Scene scene, Renderable r, int x, int z, long hash)
	{
		int type = (int) (hash >> 16 & 7);
		if (type == 0 || type == 1)
		{
			return ACTOR;
		}
		int[] e = {OTHER, x >> 7, z >> 7, (int) (hash >> 14 & 3), 0, 0, 0, 0, 0};
		if (scene != null)
		{
			find(scene, r, e);
		}
		return e[0];
	}

	private static void find(Scene scene, Renderable r, int[] e)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null || e[3] >= tiles.length)
		{
			return;
		}
		Tile[][] level = tiles[e[3]];
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int ex = e[1] + OFFSET + dx, ey = e[2] + OFFSET + dy;
				if (ex < 0 || ey < 0 || ex >= level.length || ey >= level[ex].length || level[ex][ey] == null)
				{
					continue;
				}
				Tile t = level[ex][ey];
				GroundObject g = t.getGroundObject();
				if (g != null && g.getRenderable() == r)
				{
					set(e, DECORATION, e[1] + dx, e[2] + dy);
					return;
				}
				WallObject w = t.getWallObject();
				if (w != null && (w.getRenderable1() == r || w.getRenderable2() == r))
				{
					set(e, w.getRenderable1() == r ? WALL : WALL_2, e[1] + dx, e[2] + dy);
					return;
				}
				DecorativeObject d = t.getDecorativeObject();
				if (d != null && (d.getRenderable() == r || d.getRenderable2() == r))
				{
					set(e, WALL_DECORATION, e[1] + dx, e[2] + dy);
					return;
				}
				ItemLayer i = t.getItemLayer();
				if (i != null && (i.getBottom() == r || i.getMiddle() == r || i.getTop() == r))
				{
					set(e, ITEM, e[1] + dx, e[2] + dy);
					return;
				}
				GameObject[] objects = t.getGameObjects();
				if (objects != null)
				{
					for (GameObject o : objects)
					{
						if (o != null && o.getRenderable() == r)
						{
							set(e, BIG, e[1] + dx, e[2] + dy);
							Point a = o.getSceneMinLocation(), b = o.getSceneMaxLocation();
							if (a != null && b != null)
							{
								e[5] = a.getX();
								e[6] = a.getY();
								e[7] = b.getX();
								e[8] = b.getY();
							}
							return;
						}
					}
				}
			}
		}
	}

	// what a big object's model is made of: corners in whole units or not, bent to its slope or not, its textures
	// (asked by the tree canopies' texels, which land a little off the CPU's)
	static String describe(Renderable r)
	{
		if (r == null)
		{
			return "none";
		}
		String mode = "render mode " + r.getRenderMode() + "; ";
		if (!(r instanceof Model))
		{
			Model own = r.getModel();
			return mode + r.getClass().getSimpleName() + ", its model: " + (own == null ? "none" : describe(own));
		}
		Model m = (Model) r;
		int n = m.getVerticesCount();
		float[] xs = m.getVerticesX(), ys = m.getVerticesY(), zs = m.getVerticesZ();
		int fx = 0, fy = 0, fz = 0;
		for (int i = 0; i < n; i++)
		{
			fx += xs[i] != Math.rint(xs[i]) ? 1 : 0;
			fy += ys[i] != Math.rint(ys[i]) ? 1 : 0;
			fz += zs[i] != Math.rint(zs[i]) ? 1 : 0;
		}
		short[] tex = m.getFaceTextures();
		byte[] tf = m.getTextureFaces();
		int textured = 0, ownTriangle = 0;
		for (int f = 0; tex != null && f < m.getFaceCount(); f++)
		{
			if (tex[f] != -1)
			{
				textured++;
				ownTriangle += tf != null && tf[f] != -1 ? 1 : 0;
			}
		}
		Model unskewed = m.getUnskewedModel();
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		for (int i = 0; i < n; i++)
		{
			lo = Math.min(lo, ys[i]);
			hi = Math.max(hi, ys[i]);
		}
		return mode + n + " corners, not whole numbers: " + fx + " x, " + fy + " y, " + fz + " z; height from " + lo + " to " + hi + "; "
			+ (unskewed != null ? "bent to its slope (by CPU Look's compute pass)" : "not bent by CPU Look (as handed over)")
			+ "; " + m.getFaceCount() + " faces, " + textured + " textured (" + ownTriangle + " with a texture triangle of their own)"
			+ "; bias " + bias(m.getFaceBias()) + (unskewed != null ? " (its unbent copy's: " + bias(unskewed.getFaceBias()) + ")" : "")
			+ "; " + see(m);
	}

	// how see-through a model is: its own transparency (-1: all of it) and its faces' (255: invisible), and its hidden faces
	static String see(Model m)
	{
		byte[] t = m.getFaceTransparencies();
		int[] c3 = m.getFaceColors3();
		int full = 0, partial = 0, hidden = 0;
		for (int f = 0; f < m.getFaceCount(); f++)
		{
			int a = SceneUploader.faceTransparency(m.getTransparency(), t != null ? t[f] & 0xff : 0);
			full += a == 255 ? 1 : 0;
			partial += a > 0 && a < 255 ? 1 : 0;
			hidden += c3 != null && c3[f] == -2 ? 1 : 0;
		}
		return "model transparency " + m.getTransparency() + ", faces fully see-through " + full + ", partly " + partial + ", hidden " + hidden;
	}

	// the object on the tiles round (tx, ty) with this hash: what it is and its renderable's render mode
	private static String byHash(Tile[][][] tiles, int tx, int ty, int plane, long hash)
	{
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return "no tiles";
		}
		for (int dx = -2; dx <= 2; dx++)
		{
			for (int dy = -2; dy <= 2; dy++)
			{
				int ex = tx + dx + OFFSET, ey = ty + dy + OFFSET;
				if (ex < 0 || ey < 0 || ex >= tiles[plane].length || ey >= tiles[plane][ex].length || tiles[plane][ex][ey] == null)
				{
					continue;
				}
				Tile t = tiles[plane][ex][ey];
				GameObject[] objects = t.getGameObjects();
				if (objects != null)
				{
					for (GameObject o : objects)
					{
						if (o != null && o.getHash() == hash)
						{
							Renderable r = o.getRenderable();
							return "game object " + o.getId() + " on " + (tx + dx) + "," + (ty + dy) + ", placed at height " + o.getZ()
								+ ", its renderable " + (r == null ? "none" : r.getClass().getSimpleName() + " with render mode " + r.getRenderMode());
						}
					}
				}
				WallObject w = t.getWallObject();
				if (w != null && w.getHash() == hash)
				{
					return "wall " + w.getId() + ", render modes " + (w.getRenderable1() == null ? "-" : w.getRenderable1().getRenderMode())
						+ " / " + (w.getRenderable2() == null ? "-" : w.getRenderable2().getRenderMode());
				}
				DecorativeObject d = t.getDecorativeObject();
				if (d != null && d.getHash() == hash)
				{
					return "wall decoration " + d.getId() + ", render mode " + (d.getRenderable() == null ? "-" : d.getRenderable().getRenderMode());
				}
				GroundObject g = t.getGroundObject();
				if (g != null && g.getHash() == hash)
				{
					return "ground decoration " + g.getId() + ", render mode " + (g.getRenderable() == null ? "-" : g.getRenderable().getRenderMode());
				}
			}
		}
		return "none with that hash on the tiles round it (a projectile, a spell effect, or an object added by a plugin)";
	}

	// a model's face bias (Model.getFaceBias, unsigned): which faces the CPU's depth key lifts toward the camera
	static String bias(byte[] b)
	{
		if (b == null)
		{
			return "none (no array)";
		}
		int nonzero = 0, lo = 255, hi = 0;
		for (byte v : b)
		{
			int u = v & 0xff;
			nonzero += u != 0 ? 1 : 0;
			lo = Math.min(lo, u);
			hi = Math.max(hi, u);
		}
		return nonzero + " of " + b.length + " faces non-zero, " + lo + " to " + hi;
	}

	private static void set(int[] e, int kind, int tx, int ty)
	{
		e[0] = kind;
		e[1] = tx;
		e[2] = ty;
		e[5] = e[7] = tx;
		e[6] = e[8] = ty;
	}

	private int far(int tx, int ty)
	{
		return Math.max(Math.abs(tx - camX), Math.abs(ty - camY));
	}

	private boolean nearPlayer(int tx, int ty, int r)
	{
		return Math.max(Math.abs(tx - playerX), Math.abs(ty - playerY)) <= r;
	}

	void finish()
	{
		recording = false;
		int n = entries.size();
		int[] count = new int[KINDS.length], skipped = new int[KINDS.length];
		for (int[] e : entries)
		{
			count[e[0]]++;
			skipped[e[0]] += e[4];
		}
		StringBuilder sb = new StringBuilder();
		sb.append("CPU Look draw order, one frame: ").append(n).append(" handed over");
		for (int k = 0; k < KINDS.length; k++)
		{
			sb.append(k == 0 ? " (" : ", ").append(count[k]).append(' ').append(KINDS[k]);
			if (skipped[k] > 0)
			{
				sb.append(" [").append(skipped[k]).append(" skipped as off screen]");
			}
		}
		sb.append(")\n");
		sb.append("  draws handed over with no scene: ").append(noScene).append(" (looked up in the client's own)\n");

		// the grass in the scene round the player: there, handed over, skipped
		int inScene = 0, handed = 0, skippedGrass = 0;
		List<String> missing = new ArrayList<>();
		Tile[][][] tiles = lastScene != null ? lastScene.getExtendedTiles() : null;
		if (tiles != null && plane < tiles.length)
		{
			for (int dx = -3; dx <= 3; dx++)
			{
				for (int dy = -3; dy <= 3; dy++)
				{
					int ex = playerX + dx + OFFSET, ey = playerY + dy + OFFSET;
					if (ex < 0 || ey < 0 || ex >= tiles[plane].length || ey >= tiles[plane][ex].length || tiles[plane][ex][ey] == null)
					{
						continue;
					}
					GroundObject g = tiles[plane][ex][ey].getGroundObject();
					if (g == null || g.getRenderable() == null)
					{
						continue;
					}
					inScene++;
					Integer at = drawn.get(g.getRenderable());
					if (at == null)
					{
						missing.add((playerX + dx) + "," + (playerY + dy));
					}
					else
					{
						handed++;
						skippedGrass += entries.get(at)[4];
					}
				}
			}
		}
		sb.append("  ground decorations within 3 tiles of the player: ").append(inScene).append(" in the scene, ").append(handed)
			.append(" handed over, ").append(skippedGrass).append(" of those skipped as off screen");
		sb.append(missing.isEmpty() ? "" : "; never handed over at " + String.join(" ", missing)).append('\n');

		// players, NPCs and the draws found on no tile within 10 tiles of the player (11/18/2026: buckets the CPU draws and
		// CPU Look's depth test hid turned out not to be items on the ground): what each is, the height it was handed over
		// at against its tile's corners, its hash's type and id, its model (render mode, height range, bias)
		int[][][] th = lastScene != null ? lastScene.getTileHeights() : null;
		for (int i = 0; i < n; i++)
		{
			int[] e = entries.get(i);
			if ((e[0] != OTHER && e[0] != ACTOR) || !nearPlayer(e[1], e[2], 10))
			{
				continue;
			}
			Renderable r = renderables.get(i);
			long hash = placed.get(i)[1];
			sb.append("  #").append(i).append(' ').append(KINDS[e[0]]).append(" at ").append(e[1]).append(',').append(e[2])
				.append(e[4] != 0 ? " (skipped as off screen)" : "").append(": ").append(r == null ? "?" : r.getClass().getSimpleName());
			if (r instanceof net.runelite.api.Actor)
			{
				sb.append(" \"").append(((net.runelite.api.Actor) r).getName()).append('"');
			}
			sb.append(", hash type ").append(hash >> 16 & 7).append(" id ").append(hash >>> 17 & 0xffffffffL)
				.append(", handed over at height ").append(placed.get(i)[0]);
			int ex = e[1] + OFFSET, ey = e[2] + OFFSET, pl = e[3];
			if (th != null && pl < th.length && ex >= 0 && ey >= 0 && ex + 1 < th[pl].length && ey + 1 < th[pl][ex].length)
			{
				sb.append(" (the tile's corners ").append(th[pl][ex][ey]).append(' ').append(th[pl][ex + 1][ey]).append(' ')
					.append(th[pl][ex][ey + 1]).append(' ').append(th[pl][ex + 1][ey + 1]).append(')');
			}
			sb.append("; ").append(describe(r)).append('\n');
			if (e[0] == OTHER && tiles != null && lastScene != null)
			{
				// an animated object (the bloodwood tree's bucket, 11/18/2026) hands over a fresh model each frame, so it's
				// found on no tile by its model: look its object up by the hash instead, for the object's own render mode
				sb.append("    its object: ").append(byHash(lastScene.getExtendedTiles(), e[1], e[2], e[3], hash)).append('\n');
			}
		}

		// items on the ground within 10 tiles of the player (11/18/2026: a bucket the CPU draws, CPU Look didn't): handed
		// over or not, the height they're placed at against the tile's corners, and their models (bias, render mode)
		int[][][] heights = lastScene != null ? lastScene.getTileHeights() : null;
		if (tiles != null && plane < tiles.length)
		{
			for (int dx = -10; dx <= 10; dx++)
			{
				for (int dy = -10; dy <= 10; dy++)
				{
					int ex = playerX + dx + OFFSET, ey = playerY + dy + OFFSET;
					if (ex < 0 || ey < 0 || ex >= tiles[plane].length || ey >= tiles[plane][ex].length || tiles[plane][ex][ey] == null)
					{
						continue;
					}
					ItemLayer il = tiles[plane][ex][ey].getItemLayer();
					if (il == null)
					{
						continue;
					}
					sb.append("  items on tile ").append(playerX + dx).append(',').append(playerY + dy).append(": placed at height ")
						.append(il.getZ()).append(", pile height ").append(il.getHeight());
					if (heights != null && plane < heights.length && ex + 1 < heights[plane].length && ey + 1 < heights[plane][ex].length)
					{
						sb.append(", the tile's corners ").append(heights[plane][ex][ey]).append(' ').append(heights[plane][ex + 1][ey])
							.append(' ').append(heights[plane][ex][ey + 1]).append(' ').append(heights[plane][ex + 1][ey + 1]);
					}
					sb.append('\n');
					Renderable[] parts = {il.getBottom(), il.getMiddle(), il.getTop()};
					String[] names = {"bottom", "middle", "top"};
					for (int k = 0; k < 3; k++)
					{
						if (parts[k] == null)
						{
							continue;
						}
						Integer at = drawn.get(parts[k]);
						sb.append("    ").append(names[k]).append(": ").append(at == null ? "never handed over" : "handed over"
							+ (entries.get(at)[4] != 0 ? " (skipped as off screen)" : "")).append("; ").append(describe(parts[k])).append('\n');
					}
				}
			}
		}

		// big objects round the player: the ground in front of them, before or after
		for (int i = 0; i < n; i++)
		{
			int[] e = entries.get(i);
			if (e[0] != BIG || !nearPlayer(e[1], e[2], 4))
			{
				continue;
			}
			int after = 0, before = 0, inside = 0;
			int fe = Math.min(far(e[5], e[6]), Math.min(far(e[7], e[8]), Math.min(far(e[5], e[8]), far(e[7], e[6]))));
			for (int j = 0; j < n; j++)
			{
				int[] g = entries.get(j);
				if (g[0] > DECORATION || g[3] != e[3])
				{
					continue;
				}
				boolean inFoot = g[1] >= e[5] && g[1] <= e[7] && g[2] >= e[6] && g[2] <= e[8];
				boolean touching = g[1] >= e[5] - 1 && g[1] <= e[7] + 1 && g[2] >= e[6] - 1 && g[2] <= e[8] + 1;
				if (inFoot)
				{
					inside += j > i ? 1 : 0;
				}
				else if (touching && far(g[1], g[2]) < fe)
				{
					if (j > i)
					{
						after++;
					}
					else
					{
						before++;
					}
				}
			}
			sb.append("  big object #").append(i).append(" on ").append(e[5]).append(',').append(e[6]).append(" to ").append(e[7]).append(',').append(e[8])
				.append(e[4] == 1 ? " (skipped as off screen)" : "").append(": nearer ground beside it drawn after it ").append(after)
				.append(", before it ").append(before).append("; its own ground drawn after it ").append(inside).append('\n');
			sb.append("    its model: ").append(describe(renderables.get(i))).append('\n');
		}

		sb.append("  the order round the player (tile ").append(playerX).append(',').append(playerY).append("; camera over tile ")
			.append(camX).append(',').append(camY).append("):\n");
		int shown = 0;
		for (int i = 0; i < n && shown < 70; i++)
		{
			int[] e = entries.get(i);
			if (nearPlayer(e[1], e[2], 2))
			{
				sb.append("    #").append(i).append(' ').append(KINDS[e[0]]).append(" at ").append(e[1]).append(',').append(e[2]);
				if (e[5] != e[7] || e[6] != e[8])
				{
					sb.append(" (covers ").append(e[5]).append(',').append(e[6]).append(" to ").append(e[7]).append(',').append(e[8]).append(')');
				}
				sb.append(e[3] != 0 ? " plane " + e[3] : "").append(e[4] == 1 ? " SKIPPED" : "").append('\n');
				shown++;
			}
		}
		log.info(sb.toString());
		entries.clear();
		drawn.clear();
		lastScene = null;
		clientScene = null;
	}
}
