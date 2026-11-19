/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
import java.nio.IntBuffer;

class GpuIntBuffer
{
	private IntBuffer buffer = allocateDirect(65536);

	void put(float x, float y, float z, int w)
	{
		buffer.put(Float.floatToIntBits(x))
			.put(Float.floatToIntBits(y))
			.put(Float.floatToIntBits(z))
			.put(w);
	}

	// zone mode's prepared draws (11/19/2026): eight ints at a time into a plain array, put in the buffer in one go when
	// it's flipped (each entry carries its own place in the output, so their order in the buffer doesn't matter)
	private int[] staged = new int[1024];
	private int nStaged;

	void stage(int a, int b, int c, int d, int e, int f, int g, int h)
	{
		if (nStaged + 8 > staged.length)
		{
			staged = java.util.Arrays.copyOf(staged, staged.length * 2);
		}
		final int[] s = staged;
		final int n = nStaged;
		s[n] = a;
		s[n + 1] = b;
		s[n + 2] = c;
		s[n + 3] = d;
		s[n + 4] = e;
		s[n + 5] = f;
		s[n + 6] = g;
		s[n + 7] = h;
		nStaged = n + 8;
	}

	// the ints staged so far (zone mode's own thread: the frame's output marked before its draw list, and put back)
	int stagedCount()
	{
		return nStaged;
	}

	void restore(int position, int staged)
	{
		buffer.position(position);
		nStaged = staged;
	}

	void flip()
	{
		if (nStaged > 0)
		{
			ensureCapacity(nStaged);
			buffer.put(staged, 0, nStaged);
			nStaged = 0;
		}
		buffer.flip();
	}

	void clear()
	{
		nStaged = 0;
		buffer.clear();
	}

	void ensureCapacity(int size)
	{
		int capacity = buffer.capacity();
		final int position = buffer.position();
		if ((capacity - position) < size)
		{
			do
			{
				capacity *= 2;
			}
			while ((capacity - position) < size);

			IntBuffer newB = allocateDirect(capacity);
			buffer.flip();
			newB.put(buffer);
			buffer = newB;
		}
	}

	IntBuffer getBuffer()
	{
		return buffer;
	}

	// a plain array of at least size, for writing a model whole before putting it in the buffer (SceneUploader.pushModel;
	// a buffer is written on one thread at a time)
	private int[] scratch = new int[0];

	int[] scratch(int size)
	{
		if (scratch.length < size)
		{
			scratch = new int[Math.max(size, scratch.length * 2)];
		}
		return scratch;
	}

	static IntBuffer allocateDirect(int size)
	{
		return ByteBuffer.allocateDirect(size * Integer.BYTES)
			.order(ByteOrder.nativeOrder())
			.asIntBuffer();
	}
}
