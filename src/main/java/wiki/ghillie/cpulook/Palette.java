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
 * The CPU renderer's palette: every 16-bit colour (hue 6 bits, saturation 3, lightness 7) as RGB,
 * each channel through the client's brightness (its pow on the channel, in 256ths), black as 1.
 */
final class Palette
{
	private Palette()
	{
	}

	static int[] build(double brightness)
	{
		final int[] out = new int[65536];
		for (int i = 0; i < 65536; i++)
		{
			double hue = (double) (i >> 10) / 64.0D + 0.0078125D;
			double sat = (double) (i >> 7 & 7) / 8.0D + 0.0625D;
			double lum = (double) (i & 127) / 128.0D;
			double r = lum, g = lum, b = lum;
			if (sat != 0.0D)
			{
				double q = lum < 0.5D ? lum * (1.0D + sat) : lum + sat - lum * sat;
				double p = 2.0D * lum - q;
				double tr = hue + 0.3333333333333333D;
				if (tr > 1.0D)
				{
					--tr;
				}
				double tb = hue - 0.3333333333333333D;
				if (tb < 0.0D)
				{
					++tb;
				}
				r = channel(p, q, tr);
				g = channel(p, q, hue);
				b = channel(p, q, tb);
			}
			int rgb = brighten(((int) (r * 256.0D) << 16) + ((int) (g * 256.0D) << 8) + (int) (b * 256.0D), brightness);
			out[i] = rgb == 0 ? 1 : rgb;
		}
		return out;
	}

	private static double channel(double p, double q, double t)
	{
		if (6.0D * t < 1.0D)
		{
			return p + (q - p) * 6.0D * t;
		}
		if (2.0D * t < 1.0D)
		{
			return q;
		}
		if (3.0D * t < 2.0D)
		{
			return p + (q - p) * (0.6666666666666666D - t) * 6.0D;
		}
		return p;
	}

	static int brighten(int rgb, double brightness)
	{
		int r = (int) (Math.pow((double) (rgb >> 16) / 256.0D, brightness) * 256.0D);
		int g = (int) (Math.pow((double) (rgb >> 8 & 255) / 256.0D, brightness) * 256.0D);
		int b = (int) (Math.pow((double) (rgb & 255) / 256.0D, brightness) * 256.0D);
		return b + (g << 8) + (r << 16);
	}
}
