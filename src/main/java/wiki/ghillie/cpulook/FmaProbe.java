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

import java.nio.FloatBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL43C;

/**
 * Whether this graphics card's fma() rounds once (11/19/2026). The exact division (fdiv.glsl) twice works out a - q b
 * exactly; where fma() is a true fused multiply-add, one fma does that (FDIV_FMA), where not, Dekker's product in
 * fifteen plain steps does (every card). GLSL leaves it to the card whether fma() fuses, so it's asked: random a, b,
 * and c = -(a b rounded), whose fused a b + c is a b's rounding error (almost never 0) and whose unfused one is 0,
 * and random triples of every size besides, each against Java's own Math.fma, bit for bit. Only when every one is
 * Java's is the short division used. Needs a current OpenGL 4.3 context with compute shaders.
 */
final class FmaProbe
{
	private FmaProbe()
	{
	}

	static boolean fused()
	{
		final int n = 1 << 14;
		final String src = "#version 430\n"
			+ "layout(local_size_x = 64) in;\n"
			+ "layout(std430, binding = 0) readonly buffer In { vec4 v[]; };\n"
			+ "layout(std430, binding = 1) writeonly buffer Out { float o[]; };\n"
			+ "void main() { uint i = gl_GlobalInvocationID.x; precise float r = fma(v[i].x, v[i].y, v[i].z); o[i] = r; }\n";
		final int sh = GL43C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		GL43C.glShaderSource(sh, src);
		GL43C.glCompileShader(sh);
		if (GL43C.glGetShaderi(sh, GL43C.GL_COMPILE_STATUS) == 0)
		{
			GL43C.glDeleteShader(sh);
			return false;
		}
		final int prog = GL43C.glCreateProgram();
		GL43C.glAttachShader(prog, sh);
		GL43C.glLinkProgram(prog);
		GL43C.glDeleteShader(sh);
		if (GL43C.glGetProgrami(prog, GL43C.GL_LINK_STATUS) == 0)
		{
			GL43C.glDeleteProgram(prog);
			return false;
		}
		final Random r = new Random(17);
		final FloatBuffer in = BufferUtils.createFloatBuffer(n * 4);
		final float[] want = new float[n];
		for (int i = 0; i < n; i++)
		{
			float a, b, c;
			if ((i & 1) == 0)
			{
				a = 1 + r.nextFloat();
				b = 1 + r.nextFloat();
				c = -(a * b);
			}
			else
			{
				a = (float) ((r.nextBoolean() ? 1 : -1) * Math.scalb(1 + r.nextDouble(), r.nextInt(40) - 20));
				b = (float) ((r.nextBoolean() ? 1 : -1) * Math.scalb(1 + r.nextDouble(), r.nextInt(40) - 20));
				c = (float) ((r.nextBoolean() ? 1 : -1) * Math.scalb(1 + r.nextDouble(), r.nextInt(40) - 20));
			}
			in.put(a).put(b).put(c).put(0f);
			want[i] = Math.fma(a, b, c);
		}
		in.flip();
		final int[] bufs = new int[2];
		GL43C.glGenBuffers(bufs);
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufs[0]);
		GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, in, GL43C.GL_STATIC_DRAW);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, bufs[0]);
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufs[1]);
		GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) n * 4, GL43C.GL_STATIC_READ);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, bufs[1]);
		GL43C.glUseProgram(prog);
		GL43C.glDispatchCompute(n / 64, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
		final FloatBuffer out = BufferUtils.createFloatBuffer(n);
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufs[1]);
		GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, out);
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
		GL43C.glUseProgram(0);
		GL43C.glDeleteBuffers(bufs);
		GL43C.glDeleteProgram(prog);
		for (int i = 0; i < n; i++)
		{
			if (Float.floatToIntBits(out.get(i)) != Float.floatToIntBits(want[i]))
			{
				return false;
			}
		}
		return GL43C.glGetError() == GL43C.GL_NO_ERROR;
	}
}
