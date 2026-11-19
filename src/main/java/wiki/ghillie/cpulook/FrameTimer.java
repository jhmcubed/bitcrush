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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL43C;

/**
 * A diagnostic (Diagnostics, "Log frame timings"): where a frame's time goes, written to RuneLite's log every five
 * seconds as three lines, each starting "CPU Look frame timings". Asked by the frame rate zoomed fully out at a draw
 * distance of 90 (11/18/2026: about 50, the same with plain triangles and a small window, so not the pixels).
 *
 * The processor (the first line): the game walking the scene from drawScene to postDrawScene, less the time spent in
 * CPU Look's own calls for what it hands over (each object, tile and shaped tile: CPU Look's Java), then the uploads
 * and compute dispatch (postDrawScene), then the frame's drawing (draw: the interface's upload, the scene's commands,
 * the buffer swap), and the rest of the frame, between draws: the game's own work, and its frame cap's wait.
 *
 * The graphics card (the second line), by timestamps read a few frames late, so nothing waits for them: the compute
 * pass by workgroup size (TIERS, with each one's models and faces), and the scene: with the record path, counting,
 * placing, noting each record's face and writing the records, then the solid faces' boxes and the see-through faces';
 * with the geometry stage, its two passes. A timestamp waits for what's
 * before it to finish, so the card's work runs a little less overlapped while this is on.
 *
 * Slow frames (the third line), for the brief dips (11/18/2026: 165 dropping to 90-100 for a split second, at a
 * regular interval, zoomed in): every frame's parts are kept for the period, and the frames over one and a half times
 * the median are counted, how far apart they come, what their parts were on average against every frame's, how many
 * had a Java garbage collection in them, and how many a game tick (the plugin's GameTick: the game's own 0.6-second
 * work, and every plugin's that waits on it).
 */
@Slf4j
class FrameTimer
{
	// the compute pass's workgroup sizes, in the order they're dispatched
	static final String[] TIERS = {"unordered", "512 threads", "64 threads", "256 threads", "512 x 2", "1024 x 2", "1024 x 4",
		"1024 x 6"};
	// the card's marks, in the order a frame reaches them: the compute pass's start, then after each tier (TIER_END + its
	// index; the last is the pass's end), then the scene's
	static final int COMPUTE_START = 0, TIER_END = 1, COMPUTE_END = TIER_END + TIERS.length - 1,
		SCENE_START = COMPUTE_END + 1, AFTER_COUNT = SCENE_START + 1, AFTER_PLACE = SCENE_START + 2,
		AFTER_SCATTER = SCENE_START + 3, AFTER_WRITE = SCENE_START + 4, AFTER_SOLID = SCENE_START + 5, SCENE_END = SCENE_START + 6;
	private static final int MARKS = SCENE_END + 1;
	// the card's stages, from the marks: each tier's (0 to 7), then
	private static final int G_COMPUTE = TIERS.length, G_SCENE = G_COMPUTE + 1, G_COUNT = G_COMPUTE + 2,
		G_PLACE = G_COMPUTE + 3, G_SCATTER = G_COMPUTE + 4, G_WRITE = G_COMPUTE + 5, G_SOLID = G_COMPUTE + 6,
		G_SEE = G_COMPUTE + 7;
	private static final int STAGES = G_SEE + 1;

	private static final int RING = 6;
	private static final long PERIOD = 5_000_000_000L;
	private static final int MAX_FRAMES = 8192;

	private int[][] queries;
	private final boolean[] pending = new boolean[RING];
	private final int[] issued = new int[RING];
	private final long[] stamp = new long[MARKS];
	private int slot;
	private boolean frameOpen, frameActive;
	private final long[] gpuSum = new long[STAGES];
	private final int[] gpuN = new int[STAGES];

	// the processor, this frame
	private long sceneStart, postStart, drawStart, uploaded, sceneDone, lastDrawStart, periodStart;
	private boolean sceneStarted;
	private long frameCallbacks, frameWalk, frameCalls, framePost;
	private int frameDraws;
	private long lastDrawing;
	// the processor, summed over the period (nanoseconds)
	private long walk, callbacks, post, drawing, upload, sceneCommands, swap, frame, rest;
	// within the rest: from the end of the scene's work (postDrawScene) to the frame's drawing (draw), where the client
	// draws its interface and RuneLite its overlays (11/19/2026: the time CPU Look's walk could run beside, on a thread)
	private long between, postEndAt;
	private int frames;
	private long draws, triangles;
	private final long[] models = new long[TIERS.length], faces = new long[TIERS.length];
	private int modelFrames;
	private boolean tickSeen;
	private long recordsSolid, recordsSee;
	// CPU Look working out the CPU's order itself (the re-sort diagnostic's CpuOrder.build): time, draws, frames
	private long orderNs, orderDraws, orderReadNs, orderViewNs, orderSweepNs;
	// zone mode (stage B): the moving models copied inside the client's drawTemp and drawDynamic, and how many
	private long zoneCopyNs, zoneCopies, zoneCopyOwnNs, zoneCopiesOwn;
	// zone mode's own thread: its work, the frame's wait for it in draw, the uploads after; frames it had to stop in
	private long zoneThreadNs, zoneWaitNs, zoneUploadNs, zoneThreadFrames, zoneRedone;
	private boolean zoneFrames;
	private int orderFrames, orderChecked, orderDiffered;
	private int recordReads;

	// every frame of the period: when it began, how long it took, and its parts
	private final long[] fAt = new long[MAX_FRAMES], fLen = new long[MAX_FRAMES], fDraw = new long[MAX_FRAMES],
		fWalk = new long[MAX_FRAMES], fCalls = new long[MAX_FRAMES], fPost = new long[MAX_FRAMES];
	private final boolean[] fGc = new boolean[MAX_FRAMES], fTick = new boolean[MAX_FRAMES];
	private final long[] scratch = new long[MAX_FRAMES];
	private int kept;

	private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
	private long gcCountLast, gcCountStart, gcTimeStart;

	void sceneStart()
	{
		sceneStart = System.nanoTime();
		sceneStarted = true;
		frameCallbacks = 0;
		frameDraws = 0;
	}

	void callback(long t0)
	{
		frameCallbacks += System.nanoTime() - t0;
		frameDraws++;
	}

	// zone mode's prepared draws: a run of them timed as one (count draws in ns)
	void callbacks(long ns, int count)
	{
		frameCallbacks += ns;
		frameDraws += count;
	}

	// the calls' time so far this frame (to take the ones timed apart out of a run timed whole)
	long callbacksSoFar()
	{
		return frameCallbacks;
	}

	void postStart()
	{
		postStart = System.nanoTime();
		if (sceneStarted)
		{
			frameWalk = postStart - sceneStart - frameCallbacks;
			frameCalls = frameCallbacks;
			walk += frameWalk;
			callbacks += frameCalls;
			draws += frameDraws;
			sceneStarted = false;
		}
	}

	void postEnd()
	{
		postEndAt = System.nanoTime();
		framePost = postEndAt - postStart;
		post += framePost;
	}

	// the compute pass's models and their faces by workgroup size this frame (TIERS' order)
	void tiers(int[] tierModels, int[] tierFaces)
	{
		for (int i = 0; i < TIERS.length; i++)
		{
			models[i] += tierModels[i];
			faces[i] += tierFaces[i];
		}
		modelFrames++;
	}

	// the re-sort diagnostic worked out the CPU's order this frame, in ns, for n draws
	// the walk's parts: reading the scene (stage B would do it once at load), which tiles are in view, the sweep
	// zone mode: one copy of a moving model, inside the client's own call (any thread)
	// own: on the client's own thread (the temporary objects, items, some animated scenery), which the frame waits for
	synchronized void zoneCopy(long ns, boolean own)
	{
		zoneCopyNs += ns;
		zoneCopies++;
		if (own)
		{
			zoneCopyOwnNs += ns;
			zoneCopiesOwn++;
		}
		zoneFrames = true;
	}

	void zoneThread(long workNs, long waitNs, long uploadNs, boolean redone)
	{
		zoneThreadNs += workNs;
		zoneWaitNs += waitNs;
		zoneUploadNs += uploadNs;
		zoneThreadFrames++;
		zoneRedone += redone ? 1 : 0;
	}

	void order(long ns, int n, long readNs, long viewNs, long sweepNs)
	{
		orderNs += ns;
		orderDraws += n;
		orderReadNs += readNs;
		orderViewNs += viewNs;
		orderSweepNs += sweepNs;
		orderFrames++;
	}

	// the fast walk's order compared with the reference walk's on the same frame
	void orderChecked(boolean same)
	{
		orderChecked++;
		orderDiffered += same ? 0 : 1;
	}

	// a game tick came (RuneLite's GameTick, on the client's thread between frames)
	void gameTick()
	{
		tickSeen = true;
	}

	// an earlier frame's record counts, as the overflow check read them
	void records(int solid, int see)
	{
		recordsSolid += solid;
		recordsSee += see;
		recordReads++;
	}

	void drawStart()
	{
		long now = System.nanoTime();
		if (postEndAt != 0)
		{
			between += now - postEndAt;
			postEndAt = 0;
		}
		if (periodStart == 0)
		{
			periodStart = now;
			gcCountStart = gcCountLast = gcCount();
			gcTimeStart = gcTime();
		}
		if (lastDrawStart != 0)
		{
			long len = now - lastDrawStart;
			long gc = gcCount();
			frame += len;
			long r = len - lastDrawing - frameWalk - frameCalls - framePost;
			rest += r;
			if (kept < MAX_FRAMES)
			{
				fAt[kept] = now;
				fLen[kept] = len;
				fDraw[kept] = lastDrawing;
				fWalk[kept] = frameWalk;
				fCalls[kept] = frameCalls;
				fPost[kept] = framePost;
				fGc[kept] = gc != gcCountLast;
				fTick[kept] = tickSeen;
				kept++;
			}
			gcCountLast = gc;
			frames++;
		}
		tickSeen = false;
		frameWalk = frameCalls = framePost = 0;
		lastDrawStart = now;
		drawStart = now;
		uploaded = sceneDone = 0;
	}

	void uploaded()
	{
		uploaded = System.nanoTime();
	}

	void sceneDone()
	{
		sceneDone = System.nanoTime();
	}

	void swapped(long t0)
	{
		swap += System.nanoTime() - t0;
	}

	void drawEnd(int triangleCount)
	{
		long now = System.nanoTime();
		lastDrawing = now - drawStart;
		drawing += lastDrawing;
		if (uploaded != 0)
		{
			upload += uploaded - drawStart;
			if (sceneDone != 0)
			{
				sceneCommands += sceneDone - uploaded;
			}
		}
		triangles += triangleCount;
		if (frameOpen)
		{
			if (frameActive && issued[slot] != 0)
			{
				pending[slot] = true;
			}
			frameOpen = false;
			slot = (slot + 1) % RING;
		}
		collect();
		if (now - periodStart >= PERIOD && frames > 0)
		{
			report();
			periodStart = now;
			gcCountStart = gcCountLast = gcCount();
			gcTimeStart = gcTime();
		}
	}

	// a point in the card's work: the time it gets there (a GL timestamp)
	void mark(int m)
	{
		if (queries == null)
		{
			queries = new int[RING][MARKS];
			for (int s = 0; s < RING; s++)
			{
				GL43C.glGenQueries(queries[s]);
			}
		}
		if (!frameOpen)
		{
			frameOpen = true;
			frameActive = !pending[slot];  // its last frame's results not read yet: this frame goes unmeasured
			issued[slot] = 0;
		}
		if (!frameActive)
		{
			return;
		}
		GL43C.glQueryCounter(queries[slot][m], GL43C.GL_TIMESTAMP);
		issued[slot] |= 1 << m;
	}

	// the frames whose marks have all come in
	private void collect()
	{
		if (queries == null)
		{
			return;
		}
		for (int s = 0; s < RING; s++)
		{
			if (!pending[s])
			{
				continue;
			}
			final int mask = issued[s];
			boolean ready = true;
			for (int m = 0; m < MARKS && ready; m++)
			{
				if ((mask & 1 << m) != 0 && GL43C.glGetQueryObjecti(queries[s][m], GL43C.GL_QUERY_RESULT_AVAILABLE) == 0)
				{
					ready = false;
				}
			}
			if (!ready)
			{
				continue;
			}
			for (int m = 0; m < MARKS; m++)
			{
				if ((mask & 1 << m) != 0)
				{
					stamp[m] = GL43C.glGetQueryObjecti64(queries[s][m], GL43C.GL_QUERY_RESULT);
				}
			}
			pending[s] = false;
			stage(mask, G_COMPUTE, COMPUTE_START, COMPUTE_END);
			for (int t = 0; t < TIERS.length; t++)
			{
				stage(mask, t, t == 0 ? COMPUTE_START : TIER_END + t - 1, TIER_END + t);
			}
			stage(mask, G_SCENE, SCENE_START, SCENE_END);
			stage(mask, G_COUNT, SCENE_START, AFTER_COUNT);
			stage(mask, G_PLACE, AFTER_COUNT, AFTER_PLACE);
			stage(mask, G_SCATTER, AFTER_PLACE, AFTER_SCATTER);
			stage(mask, G_WRITE, AFTER_SCATTER, AFTER_WRITE);
			stage(mask, G_SOLID, (mask & 1 << AFTER_WRITE) != 0 ? AFTER_WRITE : SCENE_START, AFTER_SOLID);
			stage(mask, G_SEE, AFTER_SOLID, SCENE_END);
		}
	}

	private void stage(int mask, int g, int from, int to)
	{
		if ((mask & 1 << from) != 0 && (mask & 1 << to) != 0)
		{
			gpuSum[g] += stamp[to] - stamp[from];
			gpuN[g]++;
		}
	}

	private String gpu(int g)
	{
		return gpuN[g] > 0 ? String.format("%.2f", gpuSum[g] / (double) gpuN[g] / 1e6) : "-";
	}

	// a tier's time, its models and faces a frame
	private String tier(int t)
	{
		return String.format("%s %s for %.0f models, %.0f faces", TIERS[t], gpu(t),
			modelFrames > 0 ? models[t] / (double) modelFrames : 0, modelFrames > 0 ? faces[t] / (double) modelFrames : 0);
	}

	private long gcCount()
	{
		long c = 0;
		for (GarbageCollectorMXBean b : collectors)
		{
			c += Math.max(0, b.getCollectionCount());
		}
		return c;
	}

	private long gcTime()
	{
		long t = 0;
		for (GarbageCollectorMXBean b : collectors)
		{
			t += Math.max(0, b.getCollectionTime());
		}
		return t;
	}

	private void report()
	{
		final double n = frames;
		log.info(String.format("CPU Look frame timings, average over %d frames (ms): a frame %.2f (%.0f a second); "
				+ "processor: the game walking the scene %.2f, CPU Look's calls for what it hands over %.2f (%.0f calls), "
				+ "uploads and compute dispatch %.2f, the frame's drawing %.2f (the interface's upload %.2f, the scene's "
				+ "commands %.2f, the buffer swap %.2f), the rest of the frame (the game's own work and its frame cap's wait) %.2f "
				+ "(of it, between the scene's work and the frame's drawing, where the client draws its interface and RuneLite its "
				+ "overlays, %.2f); %.0f triangles a frame%s",
			frames, frame / n / 1e6, frame > 0 ? 1e9 * n / frame : 0,
			walk / n / 1e6, callbacks / n / 1e6, draws / n,
			post / n / 1e6, drawing / n / 1e6, upload / n / 1e6, sceneCommands / n / 1e6, swap / n / 1e6, rest / n / 1e6,
			between / n / 1e6,
			triangles / n,
			(zoneFrames ? String.format("; zone mode: inside the game's part, CPU Look's walk (below), and its copying of the "
				+ "moving models the client hands over %.2f (%.0f models; added up over the client's threads, each copying its own "
				+ "at the same time as the others, so the frame waits for less than this; on the client's own thread %.2f for %.0f)",
				zoneCopyNs / n / 1e6, zoneCopies / n, zoneCopyOwnNs / n / 1e6, zoneCopiesOwn / n) : "")
			+ (zoneThreadFrames > 0 ? String.format("; zone mode's own thread, in %d frames: the walk's second half and the draw list "
				+ "%.2f (beside the client's interface and RuneLite's overlays), then in the frame's drawing the wait for it %.2f and the "
				+ "uploads %.2f; done again on the client's thread in %d frames", zoneThreadFrames,
				zoneThreadNs / (double) zoneThreadFrames / 1e6, zoneWaitNs / (double) zoneThreadFrames / 1e6,
				zoneUploadNs / (double) zoneThreadFrames / 1e6, zoneRedone) : "")
			+ (orderFrames > 0 ? String.format("; within the uploads, CPU Look working out the CPU's order itself (the re-sort "
				+ "diagnostic, the fast walk) %.2f for %.0f draws (reading the scene, in zone mode the zones read again and the temporary objects put on, %.2f, which tiles are in view %.2f, the sweep "
				+ "%.2f), checked against the reference walk in %d frames, %d differing", orderNs / (double) orderFrames / 1e6,
				orderDraws / (double) orderFrames, orderReadNs / (double) orderFrames / 1e6, orderViewNs / (double) orderFrames / 1e6,
				orderSweepNs / (double) orderFrames / 1e6, orderChecked, orderDiffered) : "")));
		StringBuilder tiers = new StringBuilder();
		for (int t = 0; t < TIERS.length; t++)
		{
			tiers.append(t == 0 ? "" : ", ").append(tier(t));
		}
		log.info(String.format("CPU Look frame timings, the graphics card (ms, over %d frames): compute %s (%s); "
				+ "the scene %s (the records: counting %s, placing %s, noting their faces %s, writing %s; the solid faces %s, "
				+ "the see-through faces %s); records a frame: %s solid, %s see-through",
			Math.max(gpuN[G_COMPUTE], gpuN[G_SCENE]), gpu(G_COMPUTE), tiers,
			gpu(G_SCENE), gpu(G_COUNT), gpu(G_PLACE), gpu(G_SCATTER), gpu(G_WRITE), gpu(G_SOLID), gpu(G_SEE),
			recordReads > 0 ? String.format("%.0f", recordsSolid / (double) recordReads) : "-",
			recordReads > 0 ? String.format("%.0f", recordsSee / (double) recordReads) : "-"));
		reportSlow();

		walk = callbacks = post = drawing = upload = sceneCommands = swap = frame = rest = between = 0;
		frames = 0;
		draws = triangles = 0;
		Arrays.fill(models, 0);
		Arrays.fill(faces, 0);
		modelFrames = 0;
		recordsSolid = recordsSee = 0;
		recordReads = 0;
		orderNs = orderDraws = orderReadNs = orderViewNs = orderSweepNs = 0;
		zoneCopyNs = zoneCopies = zoneCopyOwnNs = zoneCopiesOwn = 0;
		zoneThreadNs = zoneWaitNs = zoneUploadNs = zoneThreadFrames = zoneRedone = 0;
		zoneFrames = false;
		orderFrames = orderChecked = orderDiffered = 0;
		Arrays.fill(gpuSum, 0);
		Arrays.fill(gpuN, 0);
		kept = 0;
	}

	private void reportSlow()
	{
		if (kept < 2)
		{
			return;
		}
		System.arraycopy(fLen, 0, scratch, 0, kept);
		Arrays.sort(scratch, 0, kept);
		final long median = scratch[kept / 2];
		final long worst = scratch[kept - 1];
		final long limit = median * 3 / 2;
		int slow = 0, slowGc = 0, allGc = 0, slowTick = 0, allTick = 0, gaps = 0;
		long sDraw = 0, sWalk = 0, sCalls = 0, sPost = 0, sRest = 0;
		long aDraw = 0, aWalk = 0, aCalls = 0, aPost = 0, aRest = 0;
		long lastSlowAt = 0;
		for (int i = 0; i < kept; i++)
		{
			long r = fLen[i] - fDraw[i] - fWalk[i] - fCalls[i] - fPost[i];
			aDraw += fDraw[i];
			aWalk += fWalk[i];
			aCalls += fCalls[i];
			aPost += fPost[i];
			aRest += r;
			allGc += fGc[i] ? 1 : 0;
			allTick += fTick[i] ? 1 : 0;
			if (fLen[i] <= limit)
			{
				continue;
			}
			slow++;
			slowGc += fGc[i] ? 1 : 0;
			slowTick += fTick[i] ? 1 : 0;
			sDraw += fDraw[i];
			sWalk += fWalk[i];
			sCalls += fCalls[i];
			sPost += fPost[i];
			sRest += r;
			if (lastSlowAt != 0)
			{
				scratch[gaps++] = fAt[i] - lastSlowAt;  // the sorted lengths aren't needed past here
			}
			lastSlowAt = fAt[i];
		}
		String spacing = "-";
		if (gaps > 0)
		{
			Arrays.sort(scratch, 0, gaps);
			spacing = String.format("%.0f", scratch[gaps / 2] / 1e6);
		}
		final double s = Math.max(1, slow), a = kept;
		log.info(String.format("CPU Look frame timings, slow frames (over 1.5 times the median frame of %.2f ms): %d of %d, "
				+ "the slowest %.2f ms, typically %s ms apart; their parts on average (every frame's in brackets): the previous "
				+ "frame's drawing %.2f (%.2f), the game walking the scene %.2f (%.2f), CPU Look's calls %.2f (%.2f), uploads "
				+ "and compute dispatch %.2f (%.2f), the rest %.2f (%.2f); Java garbage collections in the period: %d "
				+ "(%d ms), in %d slow frames and %d frames in all; game ticks in %d slow frames and %d frames in all",
			median / 1e6, slow, kept, worst / 1e6, spacing,
			sDraw / s / 1e6, aDraw / a / 1e6, sWalk / s / 1e6, aWalk / a / 1e6, sCalls / s / 1e6, aCalls / a / 1e6,
			sPost / s / 1e6, aPost / a / 1e6, sRest / s / 1e6, aRest / a / 1e6,
			gcCount() - gcCountStart, gcTime() - gcTimeStart, slowGc, allGc, slowTick, allTick));
	}

	// when the diagnostic is turned off or the plugin stops: the queries freed, the sums dropped
	void reset()
	{
		if (queries != null)
		{
			for (int s = 0; s < RING; s++)
			{
				GL43C.glDeleteQueries(queries[s]);
			}
			queries = null;
		}
		Arrays.fill(pending, false);
		Arrays.fill(issued, 0);
		frameOpen = frameActive = false;
		slot = 0;
		sceneStart = postStart = drawStart = uploaded = sceneDone = lastDrawStart = periodStart = 0;
		sceneStarted = false;
		tickSeen = false;
		frameCallbacks = frameWalk = frameCalls = framePost = lastDrawing = 0;
		frameDraws = 0;
		walk = callbacks = post = drawing = upload = sceneCommands = swap = frame = rest = between = 0;
		frames = 0;
		draws = triangles = 0;
		Arrays.fill(models, 0);
		Arrays.fill(faces, 0);
		modelFrames = 0;
		recordsSolid = recordsSee = 0;
		recordReads = 0;
		orderNs = orderDraws = orderReadNs = orderViewNs = orderSweepNs = 0;
		zoneCopyNs = zoneCopies = zoneCopyOwnNs = zoneCopiesOwn = 0;
		zoneThreadNs = zoneWaitNs = zoneUploadNs = zoneThreadFrames = zoneRedone = 0;
		zoneFrames = false;
		orderFrames = orderChecked = orderDiffered = 0;
		Arrays.fill(gpuSum, 0);
		Arrays.fill(gpuN, 0);
		kept = 0;
	}
}
