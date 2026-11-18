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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;

/**
 * The CPU renderer's draw order, rebuilt.
 *
 * The client hands a GPU renderer the scene in an order of its own; the CPU renderer draws in another. Where they
 * differ, painter's order shows it: trees and logs drawn over the grass and ground that the CPU draws over them
 * (claude/cpu-look.md, section 9). This works out the CPU's order for the frame from the scene itself, so CPU Look
 * can re-sort what it's handed: the tiles in view swept from the far edge toward the camera, column by column; on
 * each tile its ground, its walls and wall decorations facing away from the camera, its ground decoration and items;
 * its big objects once every tile under them is drawn, the farthest first; then the walls that face the camera and
 * raised items; a queue carrying the sweep on to the neighbouring tiles nearer the camera. Written for CPU Look from
 * what the game's own scene drawing does (as read in the 2022 deobfuscation), not copied from it.
 *
 * Its output is a list of what's drawn, in order: each entry a Renderable, a SceneTilePaint or a SceneTileModel, with
 * the local x and z it was drawn at.
 */
final class CpuOrder
{
	interface Visible
	{
		boolean test(int plane, int x, int y);
	}

	private static final int SIZE = Constants.EXTENDED_SCENE_SIZE;
	private static final int OFFSET = CpuLookPlugin.SCENE_OFFSET;

	// by where the camera stands from the tile (x: before, on, after it; y: after, on, before it; 0 to 8):
	private static final int[] FIRST = {19, 55, 38, 155, 255, 110, 137, 205, 76};  // wall sides drawn with the tile's ground
	private static final int[] HELD = {160, 192, 80, 96, 0, 144, 80, 48, 160};     // corner walls held until the tile's objects are drawn
	private static final int[] LATER = {76, 8, 137, 4, 0, 1, 38, 2, 19};           // wall sides drawn after the tile's objects
	private static final int[] EDGE16 = {0, 0, 2, 0, 0, 2, 1, 1, 0};
	private static final int[] EDGE32 = {2, 0, 0, 2, 0, 0, 0, 4, 4};
	private static final int[] EDGE64 = {0, 4, 4, 8, 0, 0, 8, 0, 0};
	private static final int[] EDGE128 = {1, 1, 0, 0, 0, 8, 0, 0, 8};

	private static final class T
	{
		Tile tile;
		int x, y, p;
		boolean primary, secondary, objectsLeft;
		int edges, edgeA, edgeB, later, objMask;
		O[] objs = new O[8];
		int nobjs;
	}

	private static final class O
	{
		GameObject g;
		int sx, sy, ex, ey, drawn, dist;
	}

	private final T[][][] grid = new T[4][SIZE][SIZE];
	private final IdentityHashMap<GameObject, O> objects = new IdentityHashMap<>();
	private final ArrayDeque<T> deque = new ArrayDeque<>();
	private final List<O> ready = new ArrayList<>();
	private int stamp, camX, camZ, cx, cy, xmin, xmax, ymin, ymax, pending;
	// a guard: the sweep never takes more than this many steps (a bug here mustn't hang the client)
	private long steps, maxSteps;
	boolean gaveUp;

	// the order
	Object[] keys = new Object[8192];
	int[] kx = new int[8192], kz = new int[8192];
	int n;

	void build(Scene scene, int cameraX, int cameraZ, int drawDistance, Visible visible)
	{
		stamp++;
		n = 0;
		objects.clear();
		deque.clear();
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null)
		{
			return;
		}
		camX = cameraX;
		camZ = cameraZ;
		cx = Math.max(0, Math.min(SIZE - 1, (cameraX >> 7) + OFFSET));
		cy = Math.max(0, Math.min(SIZE - 1, (cameraZ >> 7) + OFFSET));
		int r = Math.max(1, drawDistance);
		xmin = Math.max(0, cx - r);
		xmax = Math.min(SIZE, cx + r);
		ymin = Math.max(0, cy - r);
		ymax = Math.min(SIZE, cy + r);
		int minLevel = Math.max(0, scene.getMinLevel());
		pending = 0;
		steps = 0;
		gaveUp = false;
		maxSteps = 64L * 4 * (xmax - xmin) * (ymax - ymin) + 100000;

		for (int p = 0; p < 4; p++)
		{
			for (int x = xmin; x < xmax; x++)
			{
				for (int y = ymin; y < ymax; y++)
				{
					Tile tile = p < tiles.length ? tiles[p][x][y] : null;
					T t = grid[p][x][y];
					if (t == null)
					{
						t = grid[p][x][y] = new T();
					}
					t.tile = tile;
					t.x = x;
					t.y = y;
					t.p = p;
					t.edges = t.edgeA = t.edgeB = t.later = t.objMask = t.nobjs = 0;
					boolean vis = tile != null && p >= minLevel && visible.test(p, x, y);
					t.primary = t.secondary = vis;
					t.objectsLeft = false;
					if (tile == null)
					{
						continue;
					}
					GameObject[] gos = tile.getGameObjects();
					if (gos != null)
					{
						for (GameObject g : gos)
						{
							if (g == null)
							{
								continue;
							}
							O o = objects.get(g);
							if (o == null)
							{
								o = new O();
								o.g = g;
								Point a = g.getSceneMinLocation(), b = g.getSceneMaxLocation();
								o.sx = (a != null ? a.getX() : x - OFFSET) + OFFSET;
								o.sy = (a != null ? a.getY() : y - OFFSET) + OFFSET;
								o.ex = (b != null ? b.getX() : x - OFFSET) + OFFSET;
								o.ey = (b != null ? b.getY() : y - OFFSET) + OFFSET;
								objects.put(g, o);
							}
							if (t.nobjs == t.objs.length)
							{
								t.objs = java.util.Arrays.copyOf(t.objs, t.nobjs * 2);
							}
							t.objs[t.nobjs++] = o;
							t.objMask |= mask(o, x, y);
						}
					}
					t.objectsLeft = vis && t.nobjs > 0;
					if (vis)
					{
						pending++;
					}
				}
			}
		}

		for (int pass = 0; pass < 2; pass++)
		{
			boolean first = pass == 0;
			for (int p = minLevel; p < 4; p++)
			{
				for (int dx = -r; dx <= 0; dx++)
				{
					int x1 = cx + dx, x2 = cx - dx;
					if (x1 < xmin && x2 >= xmax)
					{
						continue;
					}
					for (int dy = -r; dy <= 0; dy++)
					{
						int y1 = cy + dy, y2 = cy - dy;
						if (x1 >= xmin)
						{
							if (y1 >= ymin)
							{
								visit(p, x1, y1, first);
							}
							if (y2 < ymax)
							{
								visit(p, x1, y2, first);
							}
						}
						if (x2 < xmax)
						{
							if (y1 >= ymin)
							{
								visit(p, x2, y1, first);
							}
							if (y2 < ymax)
							{
								visit(p, x2, y2, first);
							}
						}
						if (first && pending == 0 || gaveUp)
						{
							return;
						}
					}
				}
			}
		}
	}

	private T tile(int p, int x, int y)
	{
		if (p < 0 || p > 3 || x < xmin || x >= xmax || y < ymin || y >= ymax)
		{
			return null;
		}
		T t = grid[p][x][y];
		return t != null && t.tile != null ? t : null;
	}

	private void push(int p, int x, int y)
	{
		T t = tile(p, x, y);
		if (t != null && t.secondary)
		{
			deque.addFirst(t);
		}
	}

	private static int mask(O o, int x, int y)
	{
		return (x > o.sx ? 1 : 0) + (x < o.ex ? 4 : 0) + (y > o.sy ? 8 : 0) + (y < o.ey ? 2 : 0);
	}

	private void emit(Object key, int x, int z)
	{
		if (key == null)
		{
			return;
		}
		if (n == keys.length)
		{
			keys = java.util.Arrays.copyOf(keys, n * 2);
			kx = java.util.Arrays.copyOf(kx, n * 2);
			kz = java.util.Arrays.copyOf(kz, n * 2);
		}
		keys[n] = key;
		kx[n] = x;
		kz[n] = z;
		n++;
	}

	private void visit(int p, int x, int y, boolean first)
	{
		T t = tile(p, x, y);
		if (t != null && t.primary)
		{
			drawTile(t, first);
		}
	}

	private void drawTile(T start, boolean first)
	{
		deque.addFirst(start);
		boolean waits = first;
		T t;
		while ((t = deque.pollLast()) != null)
		{
			if (++steps > maxSteps)
			{
				gaveUp = true;
				deque.clear();
				pending = 0;
				return;
			}
			if (!t.secondary)
			{
				continue;
			}
			final int x = t.x, y = t.y, p = t.p;
			if (t.primary)
			{
				if (waits)
				{
					// not yet: the tile under it, or a neighbour farther from the camera, isn't finished
					if (p > 0)
					{
						T b = tile(p - 1, x, y);
						if (b != null && b.secondary)
						{
							continue;
						}
					}
					if (x <= cx && x > xmin && blocks(tile(p, x - 1, y), t, 1)
						|| x >= cx && x < xmax - 1 && blocks(tile(p, x + 1, y), t, 4)
						|| y <= cy && y > ymin && blocks(tile(p, x, y - 1), t, 8)
						|| y >= cy && y < ymax - 1 && blocks(tile(p, x, y + 1), t, 2))
					{
						continue;
					}
				}
				else
				{
					waits = true;
				}
				t.primary = false;
				first(t);
			}

			// a corner wall held for the tile's objects: drawn once those it waits for are drawn
			if (t.edges != 0)
			{
				boolean ok = true;
				for (int i = 0; i < t.nobjs; i++)
				{
					O o = t.objs[i];
					if (o.drawn != stamp && (mask(o, x, y) & t.edges) == t.edgeA)
					{
						ok = false;
						break;
					}
				}
				if (ok)
				{
					WallObject w = t.tile.getWallObject();
					if (w != null)
					{
						emit(w.getRenderable1(), w.getX(), w.getY());
					}
					t.edges = 0;
				}
			}

			if (t.objectsLeft)
			{
				objects(t);
				if (t.objectsLeft)
				{
					continue;
				}
			}

			// the tile's second part waits for its neighbours farther from the camera
			if (!t.secondary || t.edges != 0)
			{
				continue;
			}
			if (x <= cx && x > xmin && pendingAt(p, x - 1, y)
				|| x >= cx && x < xmax - 1 && pendingAt(p, x + 1, y)
				|| y <= cy && y > ymin && pendingAt(p, x, y - 1)
				|| y >= cy && y < ymax - 1 && pendingAt(p, x, y + 1))
			{
				continue;
			}
			t.secondary = false;
			pending--;
			second(t);
			push(p + 1, x, y);
			if (x < cx)
			{
				push(p, x + 1, y);
			}
			if (y < cy)
			{
				push(p, x, y + 1);
			}
			if (x > cx)
			{
				push(p, x - 1, y);
			}
			if (y > cy)
			{
				push(p, x, y - 1);
			}
		}
	}

	private static boolean blocks(T n, T t, int bit)
	{
		return n != null && n.secondary && (n.primary || (t.objMask & bit) == 0);
	}

	private boolean pendingAt(int p, int x, int y)
	{
		T n = tile(p, x, y);
		return n != null && n.secondary;
	}

	private void ground(Tile tile, int x, int y)
	{
		int lx = (x - OFFSET) * 128 + 64, lz = (y - OFFSET) * 128 + 64;
		if (tile.getSceneTilePaint() != null)
		{
			emit(tile.getSceneTilePaint(), lx, lz);
		}
		else if (tile.getSceneTileModel() != null)
		{
			emit(tile.getSceneTileModel(), lx, lz);
		}
	}

	// a tile's first part: its ground, walls facing away, wall decorations, ground decoration and items
	private void first(T t)
	{
		final Tile tile = t.tile;
		final int x = t.x, y = t.y;
		Tile bridge = tile.getBridge();
		if (bridge != null)
		{
			ground(bridge, x, y);
			WallObject bw = bridge.getWallObject();
			if (bw != null)
			{
				emit(bw.getRenderable1(), bw.getX(), bw.getY());
			}
			GameObject[] bos = bridge.getGameObjects();
			if (bos != null)
			{
				for (GameObject g : bos)
				{
					if (g != null)
					{
						emit(g.getRenderable(), g.getX(), g.getY());
					}
				}
			}
		}
		boolean hasGround = tile.getSceneTilePaint() != null || tile.getSceneTileModel() != null;
		ground(tile, x, y);

		WallObject w = tile.getWallObject();
		DecorativeObject d = tile.getDecorativeObject();
		int dir = 0, firstMask = 0;
		if (w != null || d != null)
		{
			dir = (x == cx ? 1 : cx < x ? 2 : 0) + (y == cy ? 3 : cy > y ? 6 : 0);
			firstMask = FIRST[dir];
			t.later = LATER[dir];
		}
		if (w != null)
		{
			int a = w.getOrientationA();
			if ((a & HELD[dir]) != 0)
			{
				int[] table = a == 16 ? EDGE16 : a == 32 ? EDGE32 : a == 64 ? EDGE64 : EDGE128;
				t.edges = a == 16 ? 3 : a == 32 ? 6 : a == 64 ? 12 : 9;
				t.edgeA = table[dir];
				t.edgeB = t.edges - t.edgeA;
			}
			else
			{
				t.edges = 0;
			}
			if ((a & firstMask) != 0)
			{
				emit(w.getRenderable1(), w.getX(), w.getY());
			}
			if ((w.getOrientationB() & firstMask) != 0)
			{
				emit(w.getRenderable2(), w.getX(), w.getY());
			}
		}
		if (d != null)
		{
			// which way a wall decoration faces isn't in RuneLite's API: both halves go with the tile's first part
			emit(d.getRenderable(), d.getX(), d.getY());
			emit(d.getRenderable2(), d.getX(), d.getY());
		}
		if (hasGround)
		{
			GroundObject g = tile.getGroundObject();
			if (g != null)
			{
				emit(g.getRenderable(), g.getX(), g.getY());
			}
			ItemLayer i = tile.getItemLayer();
			if (i != null && i.getHeight() == 0)
			{
				items(i);
			}
		}

		// the tiles that share this tile's objects, toward the camera
		int m = t.objMask;
		if (m != 0)
		{
			if (x < cx && (m & 4) != 0)
			{
				push(t.p, x + 1, y);
			}
			if (y < cy && (m & 2) != 0)
			{
				push(t.p, x, y + 1);
			}
			if (x > cx && (m & 1) != 0)
			{
				push(t.p, x - 1, y);
			}
			if (y > cy && (m & 8) != 0)
			{
				push(t.p, x, y - 1);
			}
		}
	}

	private void items(ItemLayer i)
	{
		emit(i.getMiddle(), i.getX(), i.getY());
		emit(i.getTop(), i.getX(), i.getY());
		emit(i.getBottom(), i.getX(), i.getY());
	}

	// a tile's big objects, each once every tile under it has its first part drawn, the farthest first
	private void objects(T t)
	{
		t.objectsLeft = false;
		ready.clear();
		for (int i = 0; i < t.nobjs; i++)
		{
			O o = t.objs[i];
			if (o.drawn == stamp)
			{
				continue;
			}
			boolean blocked = false;
			for (int fx = o.sx; fx <= o.ex && !blocked; fx++)
			{
				for (int fy = o.sy; fy <= o.ey; fy++)
				{
					T f = tile(t.p, fx, fy);
					if (f == null)
					{
						continue;
					}
					if (f.primary || f.edges != 0 && (mask(o, fx, fy) & f.edges) == t.edgeB)
					{
						blocked = true;
						break;
					}
				}
			}
			if (blocked)
			{
				t.objectsLeft = true;
				continue;
			}
			o.dist = Math.max(cx - o.sx, o.ex - cx) + Math.max(cy - o.sy, o.ey - cy);
			ready.add(o);
		}
		while (true)
		{
			O best = null;
			for (O o : ready)
			{
				if (o.drawn == stamp)
				{
					continue;
				}
				if (best == null || o.dist > best.dist)
				{
					best = o;
				}
				else if (o.dist == best.dist)
				{
					long ox = o.g.getX() - camX, oz = o.g.getY() - camZ, bx = best.g.getX() - camX, bz = best.g.getY() - camZ;
					if (ox * ox + oz * oz > bx * bx + bz * bz)
					{
						best = o;
					}
				}
			}
			if (best == null)
			{
				break;
			}
			best.drawn = stamp;
			emit(best.g.getRenderable(), best.g.getX(), best.g.getY());
			for (int fx = best.sx; fx <= best.ex; fx++)
			{
				for (int fy = best.sy; fy <= best.ey; fy++)
				{
					T f = tile(t.p, fx, fy);
					if (f == null)
					{
						continue;
					}
					if (f.edges != 0)
					{
						deque.addFirst(f);
					}
					else if ((fx != t.x || fy != t.y) && f.secondary)
					{
						deque.addFirst(f);
					}
				}
			}
		}
	}

	// a tile's second part: raised items, and the walls that face the camera
	private void second(T t)
	{
		Tile tile = t.tile;
		ItemLayer i = tile.getItemLayer();
		if (i != null && i.getHeight() != 0)
		{
			items(i);
		}
		if (t.later != 0)
		{
			WallObject w = tile.getWallObject();
			if (w != null)
			{
				if ((w.getOrientationB() & t.later) != 0)
				{
					emit(w.getRenderable2(), w.getX(), w.getY());
				}
				if ((w.getOrientationA() & t.later) != 0)
				{
					emit(w.getRenderable1(), w.getX(), w.getY());
				}
			}
		}
	}
}
