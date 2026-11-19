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
	private static final int F = 0, MASK = 1, NOBJ = 6, HAS = 7, WA = 8, WB = 9, WX = 10,
		WY = 11, DX = 12, DY = 13, GX = 14, GY = 15, IH = 16, IX = 17, IY = 18, OBJ = 20;
	// each slot's state for this frame's walls, apart from st so a frame clears it cheaply (zone mode keeps st from
	// frame to frame): the held edges (bits 0-3), edge A (4-7), edge B (8-11), the later walls (12-19)
	private final int[] pf = new int[ALL];
	// HAS: what the tile has
	private static final int H_WALL = 1, H_DECOR = 2, H_ITEMS = 4, H_BRIDGE = 8, H_GROUND = 16, H_GOBJ = 32;
	// the tile's renderables (and its bridge), read in the setup
	private static final int RSTRIDE = 16;
	private final Object[] refs = new Object[ALL * RSTRIDE];
	private static final int R_GROUND = 0, R_W1 = 1, R_W2 = 2, R_D1 = 3, R_D2 = 4, R_G = 5, R_IM = 6, R_IT = 7, R_IB = 8, R_BRIDGE = 9,
		R_WOBJ = 10, R_DOBJ = 11, R_GOBJ = 12, R_IL = 13, R_TILE = 14;
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

	// zone mode's temporary objects this frame (players, NPCs, projectiles, graphics: the client's drawTemp), put on the
	// tiles under them as the CPU's own walk finds them; none in the old mode, where the tiles list them themselves
	private GameObject[] temps = new GameObject[0];
	private int nTemps;
	private final int[] tempFirst = new int[ALL], tempLast = new int[ALL];
	private int[] tempNext = new int[256], tempOf = new int[256], touched = new int[256];
	private int nEntries, nTouched;

	{
		Arrays.fill(tempFirst, -1);
	}

	void setTemps(GameObject[] g, int count)
	{
		temps = g;
		nTemps = g == null ? 0 : count;
	}

	// each temporary object on every tile of its footprint in this frame's square (its plane's)
	private void placeTemps()
	{
		nEntries = nTouched = 0;
		for (int t = 0; t < nTemps; t++)
		{
			GameObject g = temps[t];
			if (g == null)
			{
				continue;
			}
			int p = g.getPlane();
			if (p < 0 || p > 3)
			{
				continue;
			}
			Point a = g.getSceneMinLocation(), b = g.getSceneMaxLocation();
			int sx = a != null ? a.getX() : g.getX() >> 7, sy = a != null ? a.getY() : g.getY() >> 7;
			int ex = b != null ? b.getX() : sx, ey = b != null ? b.getY() : sy;
			for (int x = Math.max(sx + OFFSET, xmin); x <= Math.min(ex + OFFSET, xmax - 1); x++)
			{
				for (int y = Math.max(sy + OFFSET, ymin); y <= Math.min(ey + OFFSET, ymax - 1); y++)
				{
					int i = (p * SIZE + x) * SIZE + y;
					if (nEntries == tempNext.length)
					{
						tempNext = Arrays.copyOf(tempNext, nEntries * 2);
						tempOf = Arrays.copyOf(tempOf, nEntries * 2);
					}
					if (tempFirst[i] < 0)
					{
						if (nTouched == touched.length)
						{
							touched = Arrays.copyOf(touched, nTouched * 2);
						}
						touched[nTouched++] = i;
					}
					// (appended: a tile lists its temporary objects in the order the client adds them, as CpuOrder does)
					tempOf[nEntries] = t;
					tempNext[nEntries] = -1;
					if (tempFirst[i] < 0)
					{
						tempFirst[i] = nEntries;
					}
					else
					{
						tempNext[tempLast[i]] = nEntries;
					}
					tempLast[i] = nEntries++;
				}
			}
		}
	}

	private void clearTemps()
	{
		for (int k = 0; k < nTouched; k++)
		{
			tempFirst[touched[k]] = -1;
		}
		nTouched = 0;
	}

	// a game object on tile slot i (p, x, y): onto the tile's list; false when the slot is full
	private boolean put(GameObject g, int p, int x, int y, int i)
	{
		return putObject(find(g, p, x, y, i), i, x, y);
	}

	private boolean putObject(int o, int i, int x, int y)
	{
		int k = st[i * STRIDE + NOBJ];
		for (int j = 0; j < k; j++)
		{
			if (st[i * STRIDE + OBJ + j] == o)
			{
				return true;  // listed by the tile already
			}
		}
		if (k == PER_TILE)
		{
			return false;
		}
		st[i * STRIDE + OBJ + k] = o;
		st[i * STRIDE + NOBJ] = k + 1;
		st[i * STRIDE + MASK] |= mask(o, x, y);
		return true;
	}

	Object[] keys = new Object[8192];
	int[] kx = new int[8192], kz = new int[8192];
	// each entry's source (its tile for the ground, its tile object, item layer or game object otherwise) and kind: the
	// draw in full is worked out from them (draw), as the client's tile draw hands it over (stage B, B1)
	Object[] ksrc = new Object[8192];
	byte[] kkind = new byte[8192];
	int[] kref = new int[8192];
	int n;

	static final byte K_GROUND = 0, K_WALL1 = 1, K_WALL2 = 2, K_DECOR1 = 3, K_DECOR2 = 4, K_GOBJ = 5, K_ITEM = 6, K_OBJ = 7;

	/**
	 * Entry k as the client's tile draw hands it to a renderer (claude/cpu-look-stage-b.md, 5b; the client's getters read
	 * the very fields its draw passes): into out its turn, x, height (less the renderable's animation height offset, as
	 * the old path's draw call takes it) and z; the hash returned. The ground has none (-1 in out[0]).
	 */
	long draw(int k, int[] out)
	{
		final Object src = ksrc[k];
		final net.runelite.api.Renderable r = keys[k] instanceof net.runelite.api.Renderable ? (net.runelite.api.Renderable) keys[k] : null;
		final int lift = r != null ? r.getAnimationHeightOffset() : 0;
		switch (kkind[k])
		{
			case K_WALL1:
			case K_WALL2:
			case K_GOBJ:
			{
				net.runelite.api.TileObject o = (net.runelite.api.TileObject) src;
				out[0] = 0;
				out[1] = o.getX();
				out[2] = o.getZ() - lift;
				out[3] = o.getY();
				return o.getHash();
			}
			case K_DECOR1:
			case K_DECOR2:
			{
				DecorativeObject d = (DecorativeObject) src;
				boolean second = kkind[k] == K_DECOR2;
				out[0] = 0;
				out[1] = d.getX() + (second ? d.getXOffset2() : d.getXOffset());
				out[2] = d.getZ() - lift;
				out[3] = d.getY() + (second ? d.getYOffset2() : d.getYOffset());
				return d.getHash();
			}
			case K_ITEM:
			{
				ItemLayer il = (ItemLayer) src;
				out[0] = 0;
				out[1] = il.getX();
				out[2] = il.getZ() - il.getHeight() - lift;
				out[3] = il.getY();
				return il.getHash();
			}
			case K_OBJ:
			{
				GameObject g = (GameObject) src;
				out[0] = g.getModelOrientation();
				out[1] = g.getX();
				out[2] = g.getZ() - lift;
				out[3] = g.getY();
				return g.getHash();
			}
			default:
				out[0] = -1;
				return 0;
		}
	}

	void build(Scene scene, int cameraX, int cameraZ, int drawDistance, CpuOrder.Visible visible)
	{
		final long t0 = System.nanoTime();
		Tile[][][] tiles = begin(scene, cameraX, cameraZ, drawDistance);
		if (tiles == null)
		{
			return;
		}
		// this way reads the frame's square into the slots, over whatever zone mode kept there
		cached = null;
		nStatic = nObj = 0;
		placeTemps();
		try
		{
			if (!readTiles(tiles))
			{
				overflowed = true;
				return;
			}
		}
		finally
		{
			clearTemps();
		}
		readNs = System.nanoTime() - t0;
		view(scene, visible);
		setupNs = System.nanoTime() - t0;
		final long t1 = System.nanoTime();
		sweep(minLevel, radius);
		sweepNs = System.nanoTime() - t1;
	}

	/**
	 * Zone mode's frame (stage B, 11/19/2026): the same walk, from the scene as read when it loaded, with the zones the
	 * client has said changed since (invalidateZone) read again; only this frame's temporary objects are added to the
	 * tiles under them, and taken off again after. readNs is then that work: the zones read again and the temporary
	 * objects put on.
	 */
	void buildCached(Scene scene, int cameraX, int cameraZ, int drawDistance, CpuOrder.Visible visible)
	{
		final long t0 = System.nanoTime();
		Tile[][][] tiles = begin(scene, cameraX, cameraZ, drawDistance);
		if (tiles == null)
		{
			return;
		}
		sync(scene, tiles);
		if (overflowTiles > 0)
		{
			overflowed = true;
			return;
		}
		placeTemps();
		boolean fits;
		try
		{
			fits = putTemps();
		}
		finally
		{
			clearTemps();
		}
		if (!fits)
		{
			takeTempsOff();
			overflowed = true;
			return;
		}
		putItems();
		readNs = System.nanoTime() - t0;
		view(scene, visible);
		setupNs = System.nanoTime() - t0;
		final long t1 = System.nanoTime();
		sweep(minLevel, radius);
		takeTempsOff();
		sweepNs = System.nanoTime() - t1;
	}

	private int minLevel, radius;

	// the frame's start: the camera, its square, the counts; the scene's tiles, or null
	private Tile[][][] begin(Scene scene, int cameraX, int cameraZ, int drawDistance)
	{
		setupNs = readNs = sweepNs = 0;
		stamp++;
		n = 0;
		if (nObj > nStatic)
		{
			nObj = nStatic;
		}
		strays.clear();
		qHead = qSize = 0;
		overflowed = false;
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null)
		{
			return null;
		}
		camX = cameraX;
		camZ = cameraZ;
		cx = Math.max(0, Math.min(SIZE - 1, (cameraX >> 7) + OFFSET));
		cy = Math.max(0, Math.min(SIZE - 1, (cameraZ >> 7) + OFFSET));
		radius = Math.max(1, drawDistance);
		xmin = Math.max(0, cx - radius);
		xmax = Math.min(SIZE, cx + radius);
		ymin = Math.max(0, cy - radius);
		ymax = Math.min(SIZE, cy + radius);
		minLevel = Math.max(0, scene.getMinLevel());
		pending = 0;
		steps = 0;
		gaveUp = false;
		maxSteps = 64L * 4 * (xmax - xmin) * (ymax - ymin) + 100000;
		java.util.Arrays.fill(planeUsed, false);
		return tiles;
	}

	// which tiles are in view this frame, and every tile's state for the frame cleared (the part of the setup zone mode
	// still pays every frame)
	private void view(Scene scene, CpuOrder.Visible visible)
	{
		for (int p = 0; p < 4; p++)
		{
			for (int x = xmin; x < xmax; x++)
			{
				int i = (p * SIZE + x) * SIZE + ymin;
				for (int y = ymin; y < ymax; y++, i++)
				{
					if (tileAt[i] == null)
					{
						fl[i] = 0;
						continue;
					}
					pf[i] = 0;
					if (p < minLevel || !visible.test(p, x, y))
					{
						fl[i] = EXISTS;
						continue;
					}
					fl[i] = EXISTS | PRIMARY | SECONDARY | (st[i * STRIDE + NOBJ] > 0 ? LEFT : 0);
					planeUsed[p] = true;
					pending++;
				}
			}
		}
	}

	// ---- zone mode: the scene kept from frame to frame ----

	private Scene cached;
	// the objects read from the scene (the first nStatic of the objects' arrays; this frame's temporary ones after them)
	private int nStatic;
	private final IdentityHashMap<GameObject, Integer> objIndex = new IdentityHashMap<>();
	// tiles holding more objects than a slot (CpuOrder then does every frame, as in the old mode)
	private final boolean[] overflow = new boolean[ALL];
	private int overflowTiles;
	private static final int ZONES = (SIZE + 7) >> 3;
	private final boolean[] dirty = new boolean[ZONES * ZONES];
	private int[] dirtyList = new int[64];
	private int nDirty;
	private boolean dirtyAll;
	// a zone's reading: the slots to read again, marked by a stamp
	private final int[] rereadMark = new int[ALL];
	private int rereadStamp;
	private int[] rereadList = new int[1024];
	private int nReread;
	// the temporary objects' tiles: their objects' count and mask before them
	private int[] tempSlot = new int[256], tempNobj = new int[256], tempMask = new int[256];
	private int nTempSlots;
	private final IdentityHashMap<GameObject, Integer> tempIndex = new IdentityHashMap<>();
	// zone reading this frame: the zones and the slots read (for the timings and the developer log)
	int zonesRead, slotsRead;
	boolean fullRead;

	/**
	 * Prepared draws (zone mode, kept by the plugin): for each tile slot and role (ROLES a slot), and for each object read
	 * from the scene, the index of the plugin's record for its draw, -1 for none yet (-2: not one to prepare). Cleared
	 * where the scene is read again. records counts the times every one was cleared (a new scene).
	 */
	static final int ROLES = 9;
	final int[] tileRec = new int[ALL * ROLES];
	int[] oRec = new int[4096];
	int records;

	{
		Arrays.fill(tileRec, -1);
	}

	// every prepared draw dropped (the plugin's settings for them changed)
	void dropRecords()
	{
		Arrays.fill(tileRec, -1);
		Arrays.fill(oRec, 0, nObj, -1);
		records++;
	}

	// the client says zone (zx, zz) of the scene changed (invalidateZone; extended tiles >> 3)
	synchronized void invalidate(int zx, int zz)
	{
		if (zx < 0 || zz < 0 || zx >= ZONES || zz >= ZONES)
		{
			return;
		}
		int z = zx * ZONES + zz;
		if (dirty[z])
		{
			return;
		}
		dirty[z] = true;
		if (nDirty == dirtyList.length)
		{
			dirtyList = Arrays.copyOf(dirtyList, nDirty * 2);
		}
		dirtyList[nDirty++] = z;
	}

	// the whole scene to be read again before the next frame (a new scene, or anything zone mode can't follow)
	synchronized void invalidateAll()
	{
		dirtyAll = true;
	}

	private void sync(Scene scene, Tile[][][] tiles)
	{
		boolean all;
		int[] zones;
		int nz;
		synchronized (this)
		{
			all = dirtyAll || cached != scene;
			dirtyAll = false;
			zones = nDirty == 0 ? null : Arrays.copyOf(dirtyList, nDirty);
			nz = nDirty;
			for (int k = 0; k < nDirty; k++)
			{
				dirty[dirtyList[k]] = false;
			}
			nDirty = 0;
		}
		zonesRead = slotsRead = 0;
		fullRead = all;
		if (all)
		{
			readScene(scene, tiles);
			return;
		}
		for (int k = 0; k < nz; k++)
		{
			readZone(tiles, zones[k] / ZONES, zones[k] % ZONES);
		}
	}

	// the whole scene into the slots
	private void readScene(Scene scene, Tile[][][] tiles)
	{
		cached = scene;
		nObj = nStatic = 0;
		objIndex.clear();
		Arrays.fill(overflow, false);
		overflowTiles = 0;
		Arrays.fill(tileRec, -1);
		records++;
		for (int p = 0; p < 4; p++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				for (int y = 0; y < SIZE; y++)
				{
					readSlot(tiles, p, x, y);
				}
			}
		}
		nStatic = nObj;
		slotsRead = ALL;
	}

	/**
	 * Zone (zx, zz) read again. The client reports a big object's change only in the zone of its first tile (ei.an and
	 * ei.bu, read 11/19/2026), so the tiles of every object reaching out of the zone, before and after, are read again
	 * with it: an object taken away leaves none of its tiles, and one put there is on all of them.
	 */
	private void readZone(Tile[][][] tiles, int zx, int zz)
	{
		zonesRead++;
		rereadStamp++;
		nReread = 0;
		final int x0 = zx << 3, y0 = zz << 3, x1 = Math.min(SIZE, x0 + 8), y1 = Math.min(SIZE, y0 + 8);
		for (int p = 0; p < 4; p++)
		{
			for (int x = x0; x < x1; x++)
			{
				for (int y = y0; y < y1; y++)
				{
					int i = (p * SIZE + x) * SIZE + y;
					mark(i);
					footprints(i, p);
				}
			}
		}
		// (the list grows as the slots read find more objects reaching out: read until none are left)
		for (int k = 0; k < nReread; k++)
		{
			int i = rereadList[k];
			int p = i / PLANE, rem = i - p * PLANE, x = rem / SIZE, y = rem - x * SIZE;
			readSlot(tiles, p, x, y);
			footprints(i, p);
		}
		nStatic = nObj;
		slotsRead += nReread;
	}

	private void mark(int i)
	{
		if (rereadMark[i] == rereadStamp)
		{
			return;
		}
		rereadMark[i] = rereadStamp;
		if (nReread == rereadList.length)
		{
			rereadList = Arrays.copyOf(rereadList, nReread * 2);
		}
		rereadList[nReread++] = i;
	}

	// every tile of each object slot i lists (on plane p), to be read again
	private void footprints(int i, int p)
	{
		if (tileAt[i] == null)
		{
			return;
		}
		for (int k = 0, nk = st[i * STRIDE + NOBJ]; k < nk; k++)
		{
			int o = st[i * STRIDE + OBJ + k];
			for (int fx = Math.max(0, oSx[o]); fx <= Math.min(SIZE - 1, oEx[o]); fx++)
			{
				for (int fy = Math.max(0, oSy[o]); fy <= Math.min(SIZE - 1, oEy[o]); fy++)
				{
					mark((p * SIZE + fx) * SIZE + fy);
				}
			}
		}
	}

	// one tile read from the scene into its slot: its objects (each made once for the scene), and what the sweep needs
	private void readSlot(Tile[][][] tiles, int p, int x, int y)
	{
		final int i = (p * SIZE + x) * SIZE + y;
		Tile[][] plane = p < tiles.length ? tiles[p] : null;
		Tile[] column = plane != null && x < plane.length ? plane[x] : null;
		Tile tile = column != null && y < column.length ? column[y] : null;
		tileAt[i] = tile;
		Arrays.fill(tileRec, i * ROLES, i * ROLES + ROLES, -1);
		if (overflow[i])
		{
			overflow[i] = false;
			overflowTiles--;
		}
		final int b = i * STRIDE;
		st[b + MASK] = st[b + NOBJ] = 0;
		if (tile == null)
		{
			fl[i] = 0;
			return;
		}
		fl[i] = EXISTS;
		GameObject[] gos = tile.getGameObjects();
		if (gos != null)
		{
			for (GameObject g : gos)
			{
				if (g == null || !scenery(g))
				{
					continue;
				}
				Integer known = objIndex.get(g);
				int o;
				if (known != null)
				{
					o = known;
				}
				else
				{
					o = make(g, g.getSceneMinLocation(), g.getSceneMaxLocation(), x, y);
					objIndex.put(g, o);
				}
				if (!putObject(o, i, x, y) && !overflow[i])
				{
					overflow[i] = true;
					overflowTiles++;
				}
			}
		}
		read(tile, i, b, false);
	}

	/**
	 * Whether a game object on a tile is scenery (its tag's type, bits 16-18, is 2, as the client's own despawn reads it,
	 * ei.an). In zone mode the client also lists the frame's temporary objects (players, NPCs, projectiles, graphics) in
	 * the tiles' lists (ei.bu, from a pool it reuses), and hands them over by drawTemp: only the scenery is kept
	 * (11/19/2026: kept, they stayed as NPCs no longer there).
	 */
	static boolean scenery(GameObject g)
	{
		return (g.getHash() >> 16 & 7) == 2;
	}

	// zone mode's ground items this frame: the item piles the client handed over (drawDynamic; it never tells of items
	// put down or picked up, ei.ek and ei.ge, so the kept tiles hold none), put on their tiles for the frame
	private ItemLayer[] items = new ItemLayer[0];
	private int nItems;
	private int[] itemSlot = new int[64], itemHas = new int[64];
	private int nItemSlots;

	void setItems(ItemLayer[] l, int count)
	{
		items = l;
		nItems = l == null ? 0 : count;
	}

	private void putItems()
	{
		nItemSlots = 0;
		for (int k = 0; k < nItems; k++)
		{
			final ItemLayer il = items[k];
			if (il == null)
			{
				continue;
			}
			final int p = il.getPlane(), x = (il.getX() >> 7) + OFFSET, y = (il.getY() >> 7) + OFFSET;
			if (p < 0 || p > 3 || x < 0 || y < 0 || x >= SIZE || y >= SIZE)
			{
				continue;
			}
			final int i = (p * SIZE + x) * SIZE + y, b = i * STRIDE, r = i * RSTRIDE;
			if (tileAt[i] == null || (st[b + HAS] & H_ITEMS) != 0)
			{
				continue;
			}
			if (nItemSlots == itemSlot.length)
			{
				itemSlot = Arrays.copyOf(itemSlot, nItemSlots * 2);
				itemHas = Arrays.copyOf(itemHas, nItemSlots * 2);
			}
			itemSlot[nItemSlots] = i;
			itemHas[nItemSlots] = st[b + HAS];
			nItemSlots++;
			st[b + HAS] |= H_ITEMS;
			st[b + IH] = il.getHeight();
			st[b + IX] = il.getX();
			st[b + IY] = il.getY();
			refs[r + R_IM] = il.getMiddle();
			refs[r + R_IT] = il.getTop();
			refs[r + R_IB] = il.getBottom();
			refs[r + R_IL] = il;
		}
	}

	// this frame's temporary objects onto the tiles under them (each made once, as CpuOrder finds them by identity);
	// false when a tile can't hold them
	private boolean putTemps()
	{
		nTempSlots = 0;
		tempIndex.clear();
		for (int k = 0; k < nTouched; k++)
		{
			final int i = touched[k];
			if (tileAt[i] == null)
			{
				continue;
			}
			if (nTempSlots == tempSlot.length)
			{
				tempSlot = Arrays.copyOf(tempSlot, nTempSlots * 2);
				tempNobj = Arrays.copyOf(tempNobj, nTempSlots * 2);
				tempMask = Arrays.copyOf(tempMask, nTempSlots * 2);
			}
			tempSlot[nTempSlots] = i;
			tempNobj[nTempSlots] = st[i * STRIDE + NOBJ];
			tempMask[nTempSlots] = st[i * STRIDE + MASK];
			nTempSlots++;
			final int p = i / PLANE, rem = i - p * PLANE, x = rem / SIZE, y = rem - x * SIZE;
			for (int e = tempFirst[i]; e >= 0; e = tempNext[e])
			{
				GameObject g = temps[tempOf[e]];
				Integer known = tempIndex.get(g);
				int o;
				if (known != null)
				{
					o = known;
				}
				else
				{
					// (in CpuOrder's setup order, plane, x, y, the first tile of its own reached; its place then)
					o = make(g, g.getSceneMinLocation(), g.getSceneMaxLocation(), x, y);
					tempIndex.put(g, o);
				}
				if (!putObject(o, i, x, y))
				{
					return false;
				}
			}
		}
		return true;
	}

	// the temporary objects taken off their tiles again (the tiles as read from the scene)
	private void takeTempsOff()
	{
		for (int k = 0; k < nTempSlots; k++)
		{
			final int i = tempSlot[k];
			st[i * STRIDE + NOBJ] = tempNobj[k];
			st[i * STRIDE + MASK] = tempMask[k];
		}
		nTempSlots = 0;
		tempIndex.clear();
		nObj = nStatic;
		for (int k = 0; k < nItemSlots; k++)
		{
			st[itemSlot[k] * STRIDE + HAS] = itemHas[k];
		}
		nItemSlots = 0;
	}

	// every tile in the square into its slot: its objects (and the temporary ones on it), and what the sweep needs
	private boolean readTiles(Tile[][][] tiles)
	{
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
					st[b + MASK] = st[b + NOBJ] = 0;
					fl[i] = EXISTS;
					GameObject[] gos = tile.getGameObjects();
					if (gos != null)
					{
						for (GameObject g : gos)
						{
							if (g != null && !put(g, p, x, y, i))
							{
								return false;
							}
						}
					}
					for (int e = tempFirst[i]; e >= 0; e = tempNext[e])
					{
						if (!put(temps[tempOf[e]], p, x, y, i))
						{
							return false;
						}
					}
					read(tile, i, b);
				}
			}
		}
		return true;
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
		read(tile, i, b, true);
	}

	// items: whether to read the tile's items (zone mode keeps none: the client changes them without telling, and hands
	// over the ones it draws each frame; putItems)
	private void read(Tile tile, int i, int b, boolean items)
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
			refs[r + R_WOBJ] = w;
		}
		DecorativeObject d = tile.getDecorativeObject();
		if (d != null)
		{
			has |= H_DECOR;
			st[b + DX] = d.getX();
			st[b + DY] = d.getY();
			refs[r + R_D1] = d.getRenderable();
			refs[r + R_D2] = d.getRenderable2();
			refs[r + R_DOBJ] = d;
		}
		GroundObject g = tile.getGroundObject();
		if (g != null)
		{
			has |= H_GOBJ;
			st[b + GX] = g.getX();
			st[b + GY] = g.getY();
			refs[r + R_G] = g.getRenderable();
			refs[r + R_GOBJ] = g;
		}
		ItemLayer il = items ? tile.getItemLayer() : null;
		if (il != null)
		{
			has |= H_ITEMS;
			st[b + IH] = il.getHeight();
			st[b + IX] = il.getX();
			st[b + IY] = il.getY();
			refs[r + R_IM] = il.getMiddle();
			refs[r + R_IT] = il.getTop();
			refs[r + R_IB] = il.getBottom();
			refs[r + R_IL] = il;
		}
		Tile bridge = tile.getBridge();
		refs[r + R_BRIDGE] = bridge;
		refs[r + R_TILE] = tile;
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
			oRec = Arrays.copyOf(oRec, len);
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
		oRec[o] = -1;
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

	// ref: where the entry's prepared draw is kept (zone mode): a tile's slot and role (t * ROLES + role, 0 or more), a
	// static object (-(o + 2)), or none (-1)
	private void emit(Object key, int x, int z, Object src, byte kind, int ref)
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
			ksrc = Arrays.copyOf(ksrc, n * 2);
			kkind = Arrays.copyOf(kkind, n * 2);
			kref = Arrays.copyOf(kref, n * 2);
		}
		keys[n] = key;
		kx[n] = x;
		kz[n] = z;
		ksrc[n] = src;
		kkind[n] = kind;
		kref[n] = ref;
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

			if ((pf[t] & 15) != 0)
			{
				boolean ok = true;
				final int edges = pf[t] & 15, ea = pf[t] >> 4 & 15;
				for (int k = 0, nk = st[t * STRIDE + NOBJ]; k < nk; k++)
				{
					int o = st[t * STRIDE + OBJ + k];
					if (oDrawn[o] != stamp && (mask(o, x, y) & edges) == ea)
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
						emit(refs[t * RSTRIDE + R_W1], st[b + WX], st[b + WY], refs[t * RSTRIDE + R_WOBJ], K_WALL1, t * ROLES + R_W1);
					}
					pf[t] &= ~15;
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

			if ((fl[t] & SECONDARY) == 0 || (pf[t] & 15) != 0)
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
			emit(paint, lx, lz, tile, K_GROUND, -1);
			return true;
		}
		Object model = tile.getSceneTileModel();
		if (model != null)
		{
			emit(model, lx, lz, tile, K_GROUND, -1);
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
				emit(bw.getRenderable1(), bw.getX(), bw.getY(), bw, K_WALL1, -1);
			}
			GameObject[] bos = bridge.getGameObjects();
			if (bos != null)
			{
				for (GameObject g : bos)
				{
					if (g != null)
					{
						emit(g.getRenderable(), g.getX(), g.getY(), g, K_OBJ, -1);
					}
				}
			}
		}
		final boolean hasGround = (has & H_GROUND) != 0;
		if (hasGround)
		{
			emit(refs[r + R_GROUND], (x - OFFSET) * 128 + 64, (y - OFFSET) * 128 + 64, refs[r + R_TILE], K_GROUND, t * ROLES + R_GROUND);
		}

		final boolean w = (has & H_WALL) != 0, d = (has & H_DECOR) != 0;
		int dir = 0, firstMask = 0;
		if (w || d)
		{
			dir = (x == cx ? 1 : cx < x ? 2 : 0) + (y == cy ? 3 : cy > y ? 6 : 0);
			firstMask = FIRST[dir];
			// (the frame's wall state starts at 0 for every tile: the view's setup clears it)
			pf[t] = LATER[dir] << 12;
		}
		if (w)
		{
			int a = st[b + WA];
			if ((a & HELD[dir]) != 0)
			{
				int[] table = a == 16 ? EDGE16 : a == 32 ? EDGE32 : a == 64 ? EDGE64 : EDGE128;
				int e = a == 16 ? 3 : a == 32 ? 6 : a == 64 ? 12 : 9;
				pf[t] |= e | table[dir] << 4 | (e - table[dir]) << 8;
			}
			if ((a & firstMask) != 0)
			{
				emit(refs[r + R_W1], st[b + WX], st[b + WY], refs[r + R_WOBJ], K_WALL1, t * ROLES + R_W1);
			}
			if ((st[b + WB] & firstMask) != 0)
			{
				emit(refs[r + R_W2], st[b + WX], st[b + WY], refs[r + R_WOBJ], K_WALL2, t * ROLES + R_W2);
			}
		}
		if (d)
		{
			emit(refs[r + R_D1], st[b + DX], st[b + DY], refs[r + R_DOBJ], K_DECOR1, t * ROLES + R_D1);
			emit(refs[r + R_D2], st[b + DX], st[b + DY], refs[r + R_DOBJ], K_DECOR2, t * ROLES + R_D2);
		}
		if (hasGround)
		{
			if ((has & H_GOBJ) != 0)
			{
				emit(refs[r + R_G], st[b + GX], st[b + GY], refs[r + R_GOBJ], K_GOBJ, t * ROLES + R_G);
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
		emit(refs[r + R_IM], st[b + IX], st[b + IY], refs[r + R_IL], K_ITEM, t * ROLES + R_IM);
		emit(refs[r + R_IT], st[b + IX], st[b + IY], refs[r + R_IL], K_ITEM, t * ROLES + R_IT);
		emit(refs[r + R_IB], st[b + IX], st[b + IY], refs[r + R_IL], K_ITEM, t * ROLES + R_IB);
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
					if ((fl[f] & PRIMARY) != 0 || (pf[f] & 15) != 0 && (mask(o, fx, fy) & pf[f] & 15) == (pf[t] >> 8 & 15))
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
			emit(oR[best], oX[best], oY[best], oG[best], K_OBJ, best < nStatic ? -(best + 2) : -1);
			for (int fx = oSx[best]; fx <= oEx[best]; fx++)
			{
				for (int fy = oSy[best]; fy <= oEy[best]; fy++)
				{
					int f = tile(p, fx, fy);
					if (f < 0)
					{
						continue;
					}
					if ((pf[f] & 15) != 0)
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
		final int late = pf[t] >> 12 & 255;
		if (late != 0 && (has & H_WALL) != 0)
		{
			if ((st[b + WB] & late) != 0)
			{
				emit(refs[r + R_W2], st[b + WX], st[b + WY], refs[r + R_WOBJ], K_WALL2, t * ROLES + R_W2);
			}
			if ((st[b + WA] & late) != 0)
			{
				emit(refs[r + R_W1], st[b + WX], st[b + WY], refs[r + R_WOBJ], K_WALL1, t * ROLES + R_W1);
			}
		}
	}
}
