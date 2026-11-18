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

import java.util.Arrays;
import java.util.IdentityHashMap;
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
 * The CPU renderer's draw order, as CpuOrder works it out, faster (11/18/2026, stage B's step 1; claude/cpu-look-stage-b.md).
 *
 * The very same rules and the very same output as CpuOrder (which stays as the reference: OrderCheck and the walk's
 * benchmark compare the two, entry for entry). What changed is only how the walk keeps its state: CpuOrder's profile
 * on a full-size scene had half its time in reaching about 130,000 small per-tile objects scattered in memory, and a
 * sixth in an identity map telling a big object seen on its second tile from a new one. Here a tile's state is a slot
 * in flat arrays (its plane, x and y make its index), the queue is a ring of tile indices, and a big object is created
 * on the first tile of its footprint the sweep's setup reaches (the setup goes plane, x, y, so that's the footprint's
 * least x and y within the walked square) and found again by looking among that tile's objects. Anything that doesn't
 * fit that pattern (no footprint given, a tile of the footprint missing, an object outside its own footprint) goes
 * through a small identity map instead, so the result is CpuOrder's in every case.
 *
 * Since the evening of 11/18/2026 (Jace: "Faster sweep first") the setup reads everything the sweep will need from
 * each tile up front (its ground, wall, wall decoration, ground decoration, items, bridge, each object's renderable and
 * place), so the sweep itself touches only these arrays: in stage B that reading is done once when the scene loads and
 * the sweep is all a frame pays. Bridges, which are rare, are still read from the tile when the sweep reaches them.
 */
final class FastOrder
{
	private static final int SIZE = Constants.EXTENDED_SCENE_SIZE;
	private static final int OFFSET = CpuLookPlugin.SCENE_OFFSET;
	private static final int PLANE = SIZE * SIZE;
	private static final int ALL = 4 * PLANE;
	// a tile's objects: at most this many in its slot (the client's tiles hold five); past it, CpuOrder does the frame
	private static final int PER_TILE = 8;

	private static final int[] FIRST = {19, 55, 38, 155, 255, 110, 137, 205, 76};
	private static final int[] HELD = {160, 192, 80, 96, 0, 144, 80, 48, 160};
	private static final int[] LATER = {76, 8, 137, 4, 0, 1, 38, 2, 19};
	private static final int[] EDGE16 = {0, 0, 2, 0, 0, 2, 1, 1, 0};
	private static final int[] EDGE32 = {2, 0, 0, 2, 0, 0, 0, 4, 4};
	private static final int[] EDGE64 = {0, 4, 4, 8, 0, 0, 8, 0, 0};
	private static final int[] EDGE128 = {1, 1, 0, 0, 0, 8, 0, 0, 8};

	private static final int PRIMARY = 1, SECONDARY = 2, LEFT = 4, EXISTS = 8;

	// each tile slot (index (plane * SIZE + x) * SIZE + y), valid inside this frame's square for a tile that exists;
	// its numbers packed in st (STRIDE ints from index * STRIDE, two cache lines): flags, the objects' mask, the held
	// edges, edge A, edge B, the later walls, the number of objects, what it has, its wall's, wall decoration's, ground
	// decoration's and items' numbers, then the objects
	private final Tile[] tileAt = new Tile[ALL];
	// each slot's flags apart, four bytes a tile, for the sweep's many looks at the neighbours: whether it exists (in
	// this frame's square), is waiting for its first part, its second, its objects
	private final int[] fl = new int[ALL];
	private static final int STRIDE = 32;
	private final int[] st = new int[ALL * STRIDE];
	private static final int F = 0, MASK = 1, EDGES = 2, EA = 3, EB = 4, LATE = 5, NOBJ = 6, HAS = 7, WA = 8, WB = 9, WX = 10,
		WY = 11, DX = 12, DY = 13, GX = 14, GY = 15, IH = 16, IX = 17, IY = 18, OBJ = 20;
	// HAS: what the tile has
	private static final int H_WALL = 1, H_DECOR = 2, H_ITEMS = 4, H_BRIDGE = 8, H_GROUND = 16, H_GOBJ = 32;
	// the tile's renderables (and its bridge), read in the setup
	private static final int RSTRIDE = 16;
	private final Object[] refs = new Object[ALL * RSTRIDE];
	private static final int R_GROUND = 0, R_W1 = 1, R_W2 = 2, R_D1 = 3, R_D2 = 4, R_G = 5, R_IM = 6, R_IT = 7, R_IB = 8, R_BRIDGE = 9;
	// whether a plane has a tile in view this frame (planes without one are skipped by the sweep: nothing there to do)
	private final boolean[] planeUsed = new boolean[4];

	// each big object this frame
	private GameObject[] oG = new GameObject[4096];
	private int[] oSx = new int[4096], oSy = new int[4096], oEx = new int[4096], oEy = new int[4096];
	private int[] oX = new int[4096], oY = new int[4096], oDrawn = new int[4096], oDist = new int[4096];
	private Object[] oR = new Object[4096];
	private int nObj;
	private final IdentityHashMap<GameObject, Integer> strays = new IdentityHashMap<>();

	private int[] queue = new int[1 << 16];
	private int qHead, qSize;
	private int[] ready = new int[64];
	private int nReady;

	private int stamp, camX, camZ, cx, cy, xmin, xmax, ymin, ymax, pending;
	private long steps, maxSteps;
	boolean gaveUp;
	// a tile held more objects than its slot: CpuOrder works out this frame
	boolean overflowed;

	// this build's parts, in nanoseconds: the setup (reading the scene into the slots, readNs of it, then which tiles
	// are in view), and the sweep
	long setupNs, readNs, sweepNs;

	Object[] keys = new Object[8192];
	int[] kx = new int[8192], kz = new int[8192];
	int n;

	void build(Scene scene, int cameraX, int cameraZ, int drawDistance, CpuOrder.Visible visible)
	{
		final long t0 = System.nanoTime();
		setupNs = readNs = sweepNs = 0;
		stamp++;
		n = 0;
		nObj = 0;
		strays.clear();
		qHead = qSize = 0;
		overflowed = false;
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

		java.util.Arrays.fill(planeUsed, false);
		for (int p = 0; p < 4; p++)
		{
			Tile[][] plane = p < tiles.length ? tiles[p] : null;
			for (int x = xmin; x < xmax; x++)
			{
				Tile[] column = plane != null ? plane[x] : null;
				int i = (p * SIZE + x) * SIZE + ymin;
				for (int y = ymin; y < ymax; y++, i++)
				{
					Tile tile = column != null ? column[y] : null;
					tileAt[i] = tile;
					if (tile == null)
					{
						fl[i] = 0;
						continue;
					}
					final int b = i * STRIDE;
					st[b + MASK] = st[b + EDGES] = st[b + EA] = st[b + EB] = st[b + LATE] = st[b + NOBJ] = 0;
					fl[i] = EXISTS;
					GameObject[] gos = tile.getGameObjects();
					if (gos != null)
					{
						for (GameObject g : gos)
						{
							if (g == null)
							{
								continue;
							}
							int o = find(g, p, x, y, i);
							int k = st[i * STRIDE + NOBJ];
							if (k == PER_TILE)
							{
								overflowed = true;
								return;
							}
							st[i * STRIDE + OBJ + k] = o;
							st[i * STRIDE + NOBJ] = k + 1;
							st[i * STRIDE + MASK] |= mask(o, x, y);
						}
					}
					read(tile, i, b);
				}
			}
		}
		final long tRead = System.nanoTime();
		readNs = tRead - t0;

		// which tiles are in view this frame: the part of the setup stage B would still pay every frame (the reading
		// above it would do once when the scene loads)
		for (int p = 0; p < 4; p++)
		{
			for (int x = xmin; x < xmax; x++)
			{
				int i = (p * SIZE + x) * SIZE + ymin;
				for (int y = ymin; y < ymax; y++, i++)
				{
					if (fl[i] == 0 || p < minLevel || !visible.test(p, x, y))
					{
						continue;
					}
					fl[i] = EXISTS | PRIMARY | SECONDARY | (st[i * STRIDE + NOBJ] > 0 ? LEFT : 0);
					planeUsed[p] = true;
					pending++;
				}
			}
		}

		final long t1 = System.nanoTime();
		setupNs = t1 - t0;
		sweep(minLevel, r);
		sweepNs = System.nanoTime() - t1;
	}

	private void sweep(int minLevel, int r)
	{
		for (int pass = 0; pass < 2; pass++)
		{
			boolean first = pass == 0;
			for (int p = minLevel; p < 4; p++)
			{
				if (!planeUsed[p])
				{
					continue;
				}
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

	// everything the sweep needs from the tile, into its slot (b: the slot's place in st)
	private void read(Tile tile, int i, int b)
	{
		final int r = i * RSTRIDE;
		int has = 0;
		Object ground = tile.getSceneTilePaint();
		if (ground == null)
		{
			ground = tile.getSceneTileModel();
		}
		refs[r + R_GROUND] = ground;
		if (ground != null)
		{
			has |= H_GROUND;
		}
		WallObject w = tile.getWallObject();
		if (w != null)
		{
			has |= H_WALL;
			st[b + WA] = w.getOrientationA();
			st[b + WB] = w.getOrientationB();
			st[b + WX] = w.getX();
			st[b + WY] = w.getY();
			refs[r + R_W1] = w.getRenderable1();
			refs[r + R_W2] = w.getRenderable2();
		}
		DecorativeObject d = tile.getDecorativeObject();
		if (d != null)
		{
			has |= H_DECOR;
			st[b + DX] = d.getX();
			st[b + DY] = d.getY();
			refs[r + R_D1] = d.getRenderable();
			refs[r + R_D2] = d.getRenderable2();
		}
		GroundObject g = tile.getGroundObject();
		if (g != null)
		{
			has |= H_GOBJ;
			st[b + GX] = g.getX();
			st[b + GY] = g.getY();
			refs[r + R_G] = g.getRenderable();
		}
		ItemLayer il = tile.getItemLayer();
		if (il != null)
		{
			has |= H_ITEMS;
			st[b + IH] = il.getHeight();
			st[b + IX] = il.getX();
			st[b + IY] = il.getY();
			refs[r + R_IM] = il.getMiddle();
			refs[r + R_IT] = il.getTop();
			refs[r + R_IB] = il.getBottom();
		}
		Tile bridge = tile.getBridge();
		refs[r + R_BRIDGE] = bridge;
		if (bridge != null)
		{
			has |= H_BRIDGE;
		}
		st[b + HAS] = has;
	}

	// the big object g seen on tile (p, x, y), slot i: the one already made for it this frame, or a new one
	private int find(GameObject g, int p, int x, int y, int i)
	{
		Point a = g.getSceneMinLocation();
		if (a == null)
		{
			return stray(g, x, y);
		}
		int fx = Math.max(a.getX() + OFFSET, xmin), fy = Math.max(a.getY() + OFFSET, ymin);
		if (fx == x && fy == y)
		{
			// the footprint's first tile in the setup's order: made here (once, if the tile lists it twice)
			for (int k = 0; k < st[i * STRIDE + NOBJ]; k++)
			{
				int o = st[i * STRIDE + OBJ + k];
				if (oG[o] == g)
				{
					return o;
				}
			}
			if (!strays.isEmpty())
			{
				Integer s = strays.get(g);
				if (s != null)
				{
					return s;
				}
			}
			return make(g, a, g.getSceneMaxLocation(), x, y);
		}
		// set up earlier this frame: look among that tile's objects
		if (fx < x || fx == x && fy < y)
		{
			int f = (p * SIZE + fx) * SIZE + fy;
			if (fx >= xmin && fx < xmax && fy >= ymin && fy < ymax && (fl[f] & EXISTS) != 0)
			{
				for (int k = 0, nk = st[f * STRIDE + NOBJ]; k < nk; k++)
				{
					int o = st[f * STRIDE + OBJ + k];
					if (oG[o] == g)
					{
						return o;
					}
				}
			}
		}
		return stray(g, x, y);
	}

	// an object that doesn't sit where its footprint says, or gives none: found by identity, as CpuOrder finds them all
	private int stray(GameObject g, int x, int y)
	{
		Integer s = strays.get(g);
		if (s != null)
		{
			return s;
		}
		int o = make(g, g.getSceneMinLocation(), g.getSceneMaxLocation(), x, y);
		strays.put(g, o);
		return o;
	}

	private int make(GameObject g, Point a, Point b, int x, int y)
	{
		if (nObj == oG.length)
		{
			int len = nObj * 2;
			oG = Arrays.copyOf(oG, len);
			oSx = Arrays.copyOf(oSx, len);
			oSy = Arrays.copyOf(oSy, len);
			oEx = Arrays.copyOf(oEx, len);
			oEy = Arrays.copyOf(oEy, len);
			oX = Arrays.copyOf(oX, len);
			oY = Arrays.copyOf(oY, len);
			oDrawn = Arrays.copyOf(oDrawn, len);
			oDist = Arrays.copyOf(oDist, len);
			oR = Arrays.copyOf(oR, len);
		}
		int o = nObj++;
		oG[o] = g;
		oSx[o] = (a != null ? a.getX() : x - OFFSET) + OFFSET;
		oSy[o] = (a != null ? a.getY() : y - OFFSET) + OFFSET;
		oEx[o] = (b != null ? b.getX() : x - OFFSET) + OFFSET;
		oEy[o] = (b != null ? b.getY() : y - OFFSET) + OFFSET;
		oX[o] = g.getX();
		oY[o] = g.getY();
		oR[o] = g.getRenderable();
		oDrawn[o] = 0;
		return o;
	}

	private int tile(int p, int x, int y)
	{
		if (p < 0 || p > 3 || x < xmin || x >= xmax || y < ymin || y >= ymax)
		{
			return -1;
		}
		int i = (p * SIZE + x) * SIZE + y;
		return (fl[i] & EXISTS) != 0 ? i : -1;
	}

	private void add(int t)
	{
		if (qSize == queue.length)
		{
			int[] q = new int[queue.length * 2];
			for (int k = 0; k < qSize; k++)
			{
				q[k] = queue[(qHead + k) & (queue.length - 1)];
			}
			queue = q;
			qHead = 0;
		}
		queue[(qHead + qSize) & (queue.length - 1)] = t;
		qSize++;
	}

	private int poll()
	{
		if (qSize == 0)
		{
			return -1;
		}
		int t = queue[qHead];
		qHead = (qHead + 1) & (queue.length - 1);
		qSize--;
		return t;
	}

	private int mask(int o, int x, int y)
	{
		return (x > oSx[o] ? 1 : 0) + (x < oEx[o] ? 4 : 0) + (y > oSy[o] ? 8 : 0) + (y < oEy[o] ? 2 : 0);
	}

	private void emit(Object key, int x, int z)
	{
		if (key == null)
		{
			return;
		}
		if (n == keys.length)
		{
			keys = Arrays.copyOf(keys, n * 2);
			kx = Arrays.copyOf(kx, n * 2);
			kz = Arrays.copyOf(kz, n * 2);
		}
		keys[n] = key;
		kx[n] = x;
		kz[n] = z;
		n++;
	}

	// (the sweep only visits places inside the square, so the slot is read directly; PRIMARY implies the tile exists)
	private void visit(int p, int x, int y, boolean first)
	{
		int t = (p * SIZE + x) * SIZE + y;
		if ((fl[t] & PRIMARY) != 0)
		{
			drawTile(t, first);
		}
	}

	private void drawTile(int start, boolean first)
	{
		add(start);
		boolean waits = first;
		int t;
		while ((t = poll()) >= 0)
		{
			if (++steps > maxSteps)
			{
				gaveUp = true;
				qHead = qSize = 0;
				pending = 0;
				return;
			}
			if ((fl[t] & SECONDARY) == 0)
			{
				continue;
			}
			final int p = t / PLANE, rem = t - p * PLANE, x = rem / SIZE, y = rem - x * SIZE;
			if ((fl[t] & PRIMARY) != 0)
			{
				if (waits)
				{
					// (the neighbours by fixed steps from t: within the square, as the conditions say; a slot with
					// SECONDARY set is a tile that exists in this frame's square)
					if (p > 0 && (fl[t - PLANE] & SECONDARY) != 0)
					{
						continue;
					}
					final int mask = st[t * STRIDE + MASK];
					if (x <= cx && x > xmin && blocks(t - SIZE, mask, 1)
						|| x >= cx && x < xmax - 1 && blocks(t + SIZE, mask, 4)
						|| y <= cy && y > ymin && blocks(t - 1, mask, 8)
						|| y >= cy && y < ymax - 1 && blocks(t + 1, mask, 2))
					{
						continue;
					}
				}
				else
				{
					waits = true;
				}
				fl[t] &= ~PRIMARY;
				first(t, p, x, y);
			}

			if (st[t * STRIDE + EDGES] != 0)
			{
				boolean ok = true;
				for (int k = 0, nk = st[t * STRIDE + NOBJ]; k < nk; k++)
				{
					int o = st[t * STRIDE + OBJ + k];
					if (oDrawn[o] != stamp && (mask(o, x, y) & st[t * STRIDE + EDGES]) == st[t * STRIDE + EA])
					{
						ok = false;
						break;
					}
				}
				if (ok)
				{
					final int b = t * STRIDE;
					if ((st[b + HAS] & H_WALL) != 0)
					{
						emit(refs[t * RSTRIDE + R_W1], st[b + WX], st[b + WY]);
					}
					st[b + EDGES] = 0;
				}
			}

			if ((fl[t] & LEFT) != 0)
			{
				objects(t, p, x, y);
				if ((fl[t] & LEFT) != 0)
				{
					continue;
				}
			}

			if ((fl[t] & SECONDARY) == 0 || st[t * STRIDE + EDGES] != 0)
			{
				continue;
			}
			if (x <= cx && x > xmin && (fl[t - SIZE] & SECONDARY) != 0
				|| x >= cx && x < xmax - 1 && (fl[t + SIZE] & SECONDARY) != 0
				|| y <= cy && y > ymin && (fl[t - 1] & SECONDARY) != 0
				|| y >= cy && y < ymax - 1 && (fl[t + 1] & SECONDARY) != 0)
			{
				continue;
			}
			fl[t] &= ~SECONDARY;
			pending--;
			second(t);
			// (cx is inside the square, so a step toward it stays inside)
			if (p < 3)
			{
				pushAt(t + PLANE);
			}
			if (x < cx)
			{
				pushAt(t + SIZE);
			}
			if (y < cy)
			{
				pushAt(t + 1);
			}
			if (x > cx)
			{
				pushAt(t - SIZE);
			}
			if (y > cy)
			{
				pushAt(t - 1);
			}
		}
	}

	// the neighbour in slot n (inside the square) keeps tile t waiting (mask: t's objects' mask)
	private boolean blocks(int n, int mask, int bit)
	{
		final int f = fl[n];
		return (f & SECONDARY) != 0 && ((f & PRIMARY) != 0 || (mask & bit) == 0);
	}

	// push for a slot known to be inside the square
	private void pushAt(int t)
	{
		if ((fl[t] & SECONDARY) != 0)
		{
			add(t);
		}
	}

	// a tile's ground (its paint, or else its shaped model); whether it has any
	private boolean ground(Tile tile, int x, int y)
	{
		int lx = (x - OFFSET) * 128 + 64, lz = (y - OFFSET) * 128 + 64;
		Object paint = tile.getSceneTilePaint();
		if (paint != null)
		{
			emit(paint, lx, lz);
			return true;
		}
		Object model = tile.getSceneTileModel();
		if (model != null)
		{
			emit(model, lx, lz);
			return true;
		}
		return false;
	}

	private void first(int t, int p, int x, int y)
	{
		final int b = t * STRIDE, r = t * RSTRIDE, has = st[b + HAS];
		if ((has & H_BRIDGE) != 0)
		{
			// rare: read from the bridge's tile as CpuOrder does
			Tile bridge = (Tile) refs[r + R_BRIDGE];
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
		final boolean hasGround = (has & H_GROUND) != 0;
		if (hasGround)
		{
			emit(refs[r + R_GROUND], (x - OFFSET) * 128 + 64, (y - OFFSET) * 128 + 64);
		}

		final boolean w = (has & H_WALL) != 0, d = (has & H_DECOR) != 0;
		int dir = 0, firstMask = 0;
		if (w || d)
		{
			dir = (x == cx ? 1 : cx < x ? 2 : 0) + (y == cy ? 3 : cy > y ? 6 : 0);
			firstMask = FIRST[dir];
			st[b + LATE] = LATER[dir];
		}
		if (w)
		{
			int a = st[b + WA];
			if ((a & HELD[dir]) != 0)
			{
				int[] table = a == 16 ? EDGE16 : a == 32 ? EDGE32 : a == 64 ? EDGE64 : EDGE128;
				int e = a == 16 ? 3 : a == 32 ? 6 : a == 64 ? 12 : 9;
				st[b + EDGES] = e;
				st[b + EA] = table[dir];
				st[b + EB] = e - table[dir];
			}
			else
			{
				st[b + EDGES] = 0;
			}
			if ((a & firstMask) != 0)
			{
				emit(refs[r + R_W1], st[b + WX], st[b + WY]);
			}
			if ((st[b + WB] & firstMask) != 0)
			{
				emit(refs[r + R_W2], st[b + WX], st[b + WY]);
			}
		}
		if (d)
		{
			emit(refs[r + R_D1], st[b + DX], st[b + DY]);
			emit(refs[r + R_D2], st[b + DX], st[b + DY]);
		}
		if (hasGround)
		{
			if ((has & H_GOBJ) != 0)
			{
				emit(refs[r + R_G], st[b + GX], st[b + GY]);
			}
			if ((has & H_ITEMS) != 0 && st[b + IH] == 0)
			{
				items(t);
			}
		}

		int m = st[b + MASK];
		if (m != 0)
		{
			if (x < cx && (m & 4) != 0)
			{
				pushAt(t + SIZE);
			}
			if (y < cy && (m & 2) != 0)
			{
				pushAt(t + 1);
			}
			if (x > cx && (m & 1) != 0)
			{
				pushAt(t - SIZE);
			}
			if (y > cy && (m & 8) != 0)
			{
				pushAt(t - 1);
			}
		}
	}

	private void items(int t)
	{
		final int b = t * STRIDE, r = t * RSTRIDE;
		emit(refs[r + R_IM], st[b + IX], st[b + IY]);
		emit(refs[r + R_IT], st[b + IX], st[b + IY]);
		emit(refs[r + R_IB], st[b + IX], st[b + IY]);
	}

	private void objects(int t, int p, int x, int y)
	{
		fl[t] &= ~LEFT;
		nReady = 0;
		for (int k = 0, nk = st[t * STRIDE + NOBJ]; k < nk; k++)
		{
			int o = st[t * STRIDE + OBJ + k];
			if (oDrawn[o] == stamp)
			{
				continue;
			}
			boolean blocked = false;
			for (int fx = oSx[o]; fx <= oEx[o] && !blocked; fx++)
			{
				for (int fy = oSy[o]; fy <= oEy[o]; fy++)
				{
					int f = tile(p, fx, fy);
					if (f < 0)
					{
						continue;
					}
					if ((fl[f] & PRIMARY) != 0 || st[f * STRIDE + EDGES] != 0 && (mask(o, fx, fy) & st[f * STRIDE + EDGES]) == st[t * STRIDE + EB])
					{
						blocked = true;
						break;
					}
				}
			}
			if (blocked)
			{
				fl[t] |= LEFT;
				continue;
			}
			oDist[o] = Math.max(cx - oSx[o], oEx[o] - cx) + Math.max(cy - oSy[o], oEy[o] - cy);
			if (nReady == ready.length)
			{
				ready = Arrays.copyOf(ready, nReady * 2);
			}
			ready[nReady++] = o;
		}
		while (true)
		{
			int best = -1;
			for (int k = 0; k < nReady; k++)
			{
				int o = ready[k];
				if (oDrawn[o] == stamp)
				{
					continue;
				}
				if (best < 0 || oDist[o] > oDist[best])
				{
					best = o;
				}
				else if (oDist[o] == oDist[best])
				{
					long ox = oX[o] - camX, oz = oY[o] - camZ, bx = oX[best] - camX, bz = oY[best] - camZ;
					if (ox * ox + oz * oz > bx * bx + bz * bz)
					{
						best = o;
					}
				}
			}
			if (best < 0)
			{
				break;
			}
			oDrawn[best] = stamp;
			emit(oR[best], oX[best], oY[best]);
			for (int fx = oSx[best]; fx <= oEx[best]; fx++)
			{
				for (int fy = oSy[best]; fy <= oEy[best]; fy++)
				{
					int f = tile(p, fx, fy);
					if (f < 0)
					{
						continue;
					}
					if (st[f * STRIDE + EDGES] != 0)
					{
						add(f);
					}
					else if ((fx != x || fy != y) && (fl[f] & SECONDARY) != 0)
					{
						add(f);
					}
				}
			}
		}
	}

	private void second(int t)
	{
		final int b = t * STRIDE, r = t * RSTRIDE, has = st[b + HAS];
		if ((has & H_ITEMS) != 0 && st[b + IH] != 0)
		{
			items(t);
		}
		final int late = st[b + LATE];
		if (late != 0 && (has & H_WALL) != 0)
		{
			if ((st[b + WB] & late) != 0)
			{
				emit(refs[r + R_W2], st[b + WX], st[b + WY]);
			}
			if ((st[b + WA] & late) != 0)
			{
				emit(refs[r + R_W1], st[b + WX], st[b + WY]);
			}
		}
	}
}
