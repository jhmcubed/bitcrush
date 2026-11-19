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

/**
 * Render mode 2 models (players, most NPCs) drawn in batches (11/19/2026). Each such model is drawn as the CPU draws
 * it, its colour tested against the keys from before it, then its keys (CpuLookPlugin.drawPainters), so a model
 * depends on every earlier one it shares a pixel with. Models whose screen boxes don't overlap share no pixel and can't
 * affect each other; so each model goes into the first batch after every earlier model whose box it overlaps
 * ({@link #layers}), and a batch is drawn in two multi-draws instead of two draws a model. Every pixel is the
 * one-at-a-time drawing's, as long as each box holds every pixel its model can touch: {@link #bounds} takes the model's
 * own corners (not the client's cylinder, which it works out from whole units and only once a model), and
 * {@link #box} projects the box round them, turned any way about its axis, with a margin; a model it can't bound (too
 * near the camera, or its bounds unknown) overlaps everything and is drawn on its own. OrderCheck's batch check
 * projects random models' corners as the card does and finds every one inside its box.
 */
final class PainterBatch
{
	// pixels added round each box: the corners' rounding to whole pixels (one) and one more
	static final int MARGIN = 2;

	private int camX, camY, camZ, centerX, centerY, zoom;
	private float yawSin, yawCos, pitchSin, pitchCos;

	// each model's batch (layers' result) and its screen box
	int[] layer = new int[64];
	private int[] x0 = new int[64], y0 = new int[64], x1 = new int[64], y1 = new int[64];
	private boolean[] all = new boolean[64];

	/**
	 * A model's own bounds from its corners: the greatest distance of a corner from the model's axis (x and z), and its
	 * lowest and highest y, each one unit wider (the corners are floats; the rest is the margin's).
	 */
	static void bounds(float[] vx, float[] vy, float[] vz, int count, float[] m, float[] yLo, float[] yHi, int at)
	{
		float r = 0f, lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
		for (int v = 0; v < count; v++)
		{
			final float a = vx[v], c = vz[v], b = vy[v];
			final float q = a * a + c * c;
			if (q > r)
			{
				r = q;
			}
			if (b < lo)
			{
				lo = b;
			}
			if (b > hi)
			{
				hi = b;
			}
		}
		if (count == 0)
		{
			lo = hi = 0f;
		}
		m[at] = (float) Math.sqrt(r) + 1f;
		yLo[at] = lo - 1f;
		yHi[at] = hi + 1f;
	}

	// the frame's camera, as the compute pass and the setup have it (the uniforms: whole units, the client's float
	// sines and cosines, its centre and scale)
	void camera(int camX, int camY, int camZ, float yawSin, float yawCos, float pitchSin, float pitchCos, int centerX,
		int centerY, int zoom)
	{
		this.camX = camX;
		this.camY = camY;
		this.camZ = camZ;
		this.yawSin = yawSin;
		this.yawCos = yawCos;
		this.pitchSin = pitchSin;
		this.pitchCos = pitchCos;
		this.centerX = centerX;
		this.centerY = centerY;
		this.zoom = zoom;
	}

	/**
	 * The screen box (x0, y0, x1, y1, inclusive, not clipped to the screen) round a model at (x, y, z) with its own
	 * bounds, or false if it can't be bounded (unknown bounds, or a corner of its box within a unit of the near
	 * plane). The box round the model (its axis distance m every way in x and z, so any turn about its axis stays
	 * inside) is turned as cpu_turn turns a corner, in doubles, and its eight corners projected; a convex box seen from
	 * in front projects inside its corners' projections. Then the margin, and a hundred-thousandth of each coordinate
	 * for the floats' differences.
	 */
	boolean box(int x, int y, int z, float m, float yLo, float yHi, int[] out)
	{
		if (!(m >= 0f))
		{
			return false;
		}
		final double rx = x - camX, ry = y - camY, rz = z - camZ;
		double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < 8; k++)
		{
			final double vx = rx + ((k & 1) == 0 ? -m : m);
			final double vy = ry + ((k & 2) == 0 ? yLo : yHi);
			final double vz = rz + ((k & 4) == 0 ? -m : m);
			// cpu_turn: the yaw, then the pitch
			final double x1 = vz * yawSin + vx * yawCos;
			final double z1 = vz * yawCos - vx * yawSin;
			final double y2 = vy * pitchCos - z1 * pitchSin;
			final double z2 = vy * pitchSin + z1 * pitchCos;
			if (!(z2 >= 51.0))
			{
				return false;
			}
			final double sx = centerX + x1 * zoom / z2;
			final double sy = centerY + y2 * zoom / z2;
			minX = Math.min(minX, sx);
			maxX = Math.max(maxX, sx);
			minY = Math.min(minY, sy);
			maxY = Math.max(maxY, sy);
		}
		if (!(Math.abs(minX) < 1e7 && Math.abs(maxX) < 1e7 && Math.abs(minY) < 1e7 && Math.abs(maxY) < 1e7))
		{
			return false;
		}
		out[0] = (int) Math.floor(minX - MARGIN - 1e-5 * Math.abs(minX));
		out[1] = (int) Math.floor(minY - MARGIN - 1e-5 * Math.abs(minY));
		out[2] = (int) Math.ceil(maxX + MARGIN + 1e-5 * Math.abs(maxX));
		out[3] = (int) Math.ceil(maxY + MARGIN + 1e-5 * Math.abs(maxY));
		return true;
	}

	/**
	 * The batches: each model's (in {@link #layer}) the first after every earlier model's whose box its own overlaps,
	 * the boxes clipped to the screen (a model wholly off it in a batch of its own). Returns how many batches.
	 */
	int layers(int n, int[] px, int[] py, int[] pz, float[] pm, float[] pLo, float[] pHi, int width, int height)
	{
		if (layer.length < n)
		{
			final int c = Math.max(n, layer.length * 2);
			layer = new int[c];
			x0 = new int[c];
			y0 = new int[c];
			x1 = new int[c];
			y1 = new int[c];
			all = new boolean[c];
		}
		final int[] b = new int[4];
		int batches = 0;
		for (int i = 0; i < n; i++)
		{
			all[i] = !box(px[i], py[i], pz[i], pm[i], pLo[i], pHi[i], b);
			if (!all[i])
			{
				x0[i] = Math.max(0, b[0]);
				y0[i] = Math.max(0, b[1]);
				x1[i] = Math.min(width - 1, b[2]);
				y1[i] = Math.min(height - 1, b[3]);
			}
			int l = 0;
			for (int j = 0; j < i; j++)
			{
				if (layer[j] >= l && (all[i] || all[j] || !(x1[i] < x0[j] || x1[j] < x0[i] || y1[i] < y0[j] || y1[j] < y0[i])))
				{
					l = layer[j] + 1;
				}
			}
			layer[i] = l;
			batches = Math.max(batches, l + 1);
		}
		return batches;
	}
}
