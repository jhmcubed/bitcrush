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
 * Sailing's boats in the client's own float maths (11/19/2026; claude/cpu-look.md, section 9, "Sailing's boats", and
 * claude/cpu-look-stage-b.md, "Boats"). The client draws a boat's scene through a 4 x 4 float matrix, model to camera
 * (its ea projection's vw, which RuneLite hands over as FloatProjection.getProjection(): sixteen floats, column by
 * column); a model on the boat gets its own turn and place as a quaternion and a matrix, multiplied into the boat's
 * (fo.mi), and every corner, cast to a whole unit and back to a float, goes through the product (vw.ap); a tile's
 * corners go through the boat's matrix itself (ea.al, ea.av). Each step here is the client's expression as read from
 * the 1.13.7 client (vn.av, vn.iy, vn.af, vw.aa, vw.xg, vw.ap, vw.ac), in the same order and grouping: Java's floats
 * round each product and sum the same way, so the corners come out bit for bit the client's, and the card is handed
 * them already turned (the CAM_SPACE flag) instead of turning them itself.
 */
final class BoatMath
{
	private BoatMath()
	{
	}

	// the matrix's sixteen numbers as getProjection() gives them (vw.ia): the client's fields in that order
	static final int CP = 0, BR = 1, SB = 2, CG = 3, MZ = 4, LQ = 5, YI = 6, UZ = 7, TG = 8, LH = 9, LO = 10, DP = 11,
		FT = 12, VM = 13, XQ = 14, EN = 15;

	/**
	 * A model's matrix on a boat (fo.mi): its turn (orientation * 0.0030679614, a quaternion about y, vn.av) and its
	 * place as a matrix (vw.aa), times the boat's (vw.xg(model, boat)), into out (sixteen floats, the same order).
	 */
	static void model(int orientation, int x, int y, int z, float[] boat, float[] out)
	{
		// vn.av(a, 0, 0): iy(this, 0, 1, 0, a), then times iy(1, 0, 0, 0) and iy(0, 0, 1, 0) (vn.af)
		final float a = orientation * 0.0030679614F;
		float s = (float) Math.sin(0.5F * a);
		float c = (float) Math.cos(0.5F * a);
		float al = s * 0.0F, as = s * 1.0F, ag = 0.0F * s, au = c;
		// the other two turns, by nothing: iy(v, 1, 0, 0, 0) and iy(v, 0, 0, 1, 0)
		final float s0 = (float) Math.sin(0.5F * 0.0F), c0 = (float) Math.cos(0.5F * 0.0F);
		for (int k = 0; k < 2; k++)
		{
			final float bal = k == 0 ? s0 * 1.0F : s0 * 0.0F;
			final float bas = s0 * 0.0F;
			final float bag = k == 0 ? 0.0F * s0 : 1.0F * s0;
			final float bau = c0;
			// vn.af(b), this = (al, as, ag, au)
			final float nal = bas * ag + (au * bal + bau * al) - as * bag;
			final float nas = bau * as - ag * bal + au * bas + bag * al;
			final float nag = bal * as + ag * bau - bas * al + bag * au;
			final float nau = au * bau - bal * al - bas * as - bag * ag;
			al = nal;
			as = nas;
			ag = nag;
			au = nau;
		}
		// vw.aa(vp): the quaternion and the place (vr.am: the ints as floats)
		final float v3 = au * au;
		final float v4 = au * al;
		final float v5 = as * au;
		final float v6 = ag * au;
		final float v7 = al * al;
		final float v8 = as * al;
		final float v9 = ag * al;
		final float v10 = as * as;
		final float v11 = ag * as;
		final float v12 = ag * ag;
		final float[] m = out;
		m[CP] = v3 + v7 - v12 - v10;
		m[BR] = v6 + v8 + v6 + v8;
		m[SB] = v9 - v5 - v5 + v9;
		m[MZ] = v8 - v6 - v6 + v8;
		m[LQ] = v3 + v10 - v7 - v12;
		m[YI] = v11 + v11 + v4 + v4;
		m[TG] = v9 + v9 + v5 + v5;
		m[LH] = v11 - v4 - v4 + v11;
		m[LO] = v3 + v12 - v10 - v7;
		m[DP] = 0.0F;
		m[UZ] = 0.0F;
		m[CG] = 0.0F;
		m[FT] = (float) x;
		m[VM] = (float) y;
		m[XQ] = (float) z;
		m[EN] = 1.0F;
		multiply(m, boat);
	}

	/** vw.xg(a, b): a = the product, every element the client's expression. */
	static void multiply(float[] a, float[] b)
	{
		final float r0 = b[MZ] * a[BR] + b[CP] * a[CP] + b[TG] * a[SB] + a[CG] * b[FT];
		final float r1 = b[LH] * a[SB] + (a[BR] * b[LQ] + a[CP] * b[BR]) + b[VM] * a[CG];
		final float r2 = a[CG] * b[XQ] + (b[SB] * a[CP] + b[YI] * a[BR] + a[SB] * b[LO]);
		final float r3 = a[CP] * b[CG] + a[BR] * b[UZ] + a[SB] * b[DP] + a[CG] * b[EN];
		final float r4 = b[FT] * a[UZ] + (b[MZ] * a[LQ] + a[MZ] * b[CP] + b[TG] * a[YI]);
		final float r5 = b[LQ] * a[LQ] + a[MZ] * b[BR] + b[LH] * a[YI] + b[VM] * a[UZ];
		final float r6 = a[UZ] * b[XQ] + (b[LO] * a[YI] + (b[YI] * a[LQ] + a[MZ] * b[SB]));
		final float r7 = a[MZ] * b[CG] + b[UZ] * a[LQ] + b[DP] * a[YI] + a[UZ] * b[EN];
		final float r8 = a[LO] * b[TG] + (b[MZ] * a[LH] + a[TG] * b[CP]) + b[FT] * a[DP];
		final float r9 = a[DP] * b[VM] + (b[LQ] * a[LH] + a[TG] * b[BR] + b[LH] * a[LO]);
		final float r10 = a[DP] * b[XQ] + (a[LH] * b[YI] + a[TG] * b[SB] + a[LO] * b[LO]);
		final float r11 = b[UZ] * a[LH] + b[CG] * a[TG] + a[LO] * b[DP] + a[DP] * b[EN];
		final float r12 = b[FT] * a[EN] + (b[TG] * a[XQ] + (b[CP] * a[FT] + b[MZ] * a[VM]));
		final float r13 = b[VM] * a[EN] + (b[BR] * a[FT] + a[VM] * b[LQ] + b[LH] * a[XQ]);
		final float r14 = b[SB] * a[FT] + b[YI] * a[VM] + b[LO] * a[XQ] + a[EN] * b[XQ];
		final float r15 = a[FT] * b[CG] + b[UZ] * a[VM] + b[DP] * a[XQ] + a[EN] * b[EN];
		a[CP] = r0;
		a[BR] = r1;
		a[SB] = r2;
		a[CG] = r3;
		a[MZ] = r4;
		a[LQ] = r5;
		a[YI] = r6;
		a[UZ] = r7;
		a[TG] = r8;
		a[LH] = r9;
		a[LO] = r10;
		a[DP] = r11;
		a[FT] = r12;
		a[VM] = r13;
		a[XQ] = r14;
		a[EN] = r15;
	}

	// vw.ap: a point through the matrix, each of x, y and z its own expression
	static float x(float[] m, float x, float y, float z)
	{
		return m[FT] + (z * m[TG] + (m[MZ] * y + x * m[CP]));
	}

	static float y(float[] m, float x, float y, float z)
	{
		return x * m[BR] + y * m[LQ] + m[LH] * z + m[VM];
	}

	static float z(float[] m, float x, float y, float z)
	{
		return m[XQ] + (m[SB] * x + y * m[YI] + m[LO] * z);
	}

	/** The model origin's depth the client sorts by (fo.mi: (int) var9.ac(0, 0, 0)). */
	static int originDepth(float[] m)
	{
		return (int) (m[XQ] + (0.0F * m[LO] + (m[SB] * 0.0F + m[YI] * 0.0F)));
	}

	/**
	 * A model's corners through its matrix (fo.mi: each corner cast to a whole unit, then a float, then vw.ap) into
	 * ox, oy, oz. Returns whether every corner is at least 50 in front of the camera.
	 */
	static boolean corners(float[] m, float[] vx, float[] vy, float[] vz, int count, float[] ox, float[] oy, float[] oz)
	{
		boolean front = true;
		for (int v = 0; v < count; v++)
		{
			final float x = (int) vx[v], y = (int) vy[v], z = (int) vz[v];
			ox[v] = x(m, x, y, z);
			oy[v] = y(m, x, y, z);
			final float d = z(m, x, y, z);
			oz[v] = d;
			if (!(d >= 50.0F))
			{
				front = false;
			}
		}
		return front;
	}
}
