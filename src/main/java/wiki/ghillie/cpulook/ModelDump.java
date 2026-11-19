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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.Deflater;
import net.runelite.api.Model;

/**
 * The developer build's dump of one model as the client hands it over (11/19/2026: for working out a rule that keeps
 * the CPU's trousers over the boots without its log basket over the torso; claude/cpu-look.md, section 9, "Players
 * and NPCs: render mode 2"): the camera, the draw's place and turn, and the model's corners, faces, colours, priorities,
 * alphas, biases and textures, little-endian, deflated, in base64, for one log line. tools/cpu-look/research reads it.
 * Never in the Plugin Hub's build (Build.DEVELOPER).
 */
final class ModelDump
{
	static final int VERSION = 1;

	private ModelDump()
	{
	}

	static String dump(Model m, int renderMode, int orientation, int x, int y, int z, int camX, int camY, int camZ, int yaw,
		int pitch, int zoom, int centerX, int centerY, int viewW, int viewH)
	{
		final int nv = m.getVerticesCount(), nf = m.getFaceCount();
		final float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		final int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
		final int[] c1 = m.getFaceColors1(), c2 = m.getFaceColors2(), c3 = m.getFaceColors3();
		final byte[] pri = m.getFaceRenderPriorities(), alpha = m.getFaceTransparencies(), bias = m.getFaceBias();
		final short[] tex = m.getFaceTextures();
		ByteBuffer b = ByteBuffer.allocate(4 * 20 + nv * 12 + nf * (24 + 3 + 2)).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(VERSION).putInt(nv).putInt(nf).putInt(renderMode).putInt(orientation).putInt(x).putInt(y).putInt(z)
			.putInt(camX).putInt(camY).putInt(camZ).putInt(yaw).putInt(pitch).putInt(zoom).putInt(centerX).putInt(centerY)
			.putInt(viewW).putInt(viewH).putInt(m.getTransparency()).putInt(m.getRadius());
		for (int i = 0; i < nv; i++)
		{
			b.putFloat(vx[i]).putFloat(vy[i]).putFloat(vz[i]);
		}
		for (int f = 0; f < nf; f++)
		{
			b.putInt(i1[f]).putInt(i2[f]).putInt(i3[f]).putInt(c1[f]).putInt(c2[f]).putInt(c3[f]);
			b.put(pri != null && f < pri.length ? pri[f] : 0).put(alpha != null && f < alpha.length ? alpha[f] : 0)
				.put(bias != null && f < bias.length ? bias[f] : 0).putShort(tex != null && f < tex.length ? tex[f] : -1);
		}
		Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
		d.setInput(b.array(), 0, b.position());
		d.finish();
		byte[] out = new byte[b.position() + 1024];
		int n = 0;
		while (!d.finished())
		{
			if (n == out.length)
			{
				out = java.util.Arrays.copyOf(out, out.length * 2);
			}
			n += d.deflate(out, n, out.length - n);
		}
		d.end();
		return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(out, n));
	}
}
