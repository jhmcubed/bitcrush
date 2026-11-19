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

// x after n additions of s, each rounded as Java's floats round (to the nearest, ties to even): how today's client
// steps along a triangle's edges, a row's colour and its depth key, a row or a pixel at a time (fc.av, fc.ay and the
// textured routines; claude/cpu-look-stage-b.md, "the client walks a triangle's edges in floats"). Exact, without n
// additions: inside one binade (one sign and exponent) adding s to any of its values rounds by the same rule, so once
// three additions in a row have stayed in one binade (the last two settled even on a tie) the rest of the run inside
// it is a multiplication in the binade's own units; then on into the next. n >= 0.
float cpu_acc(float x, float s, int n) {
  for (int guard = 0; guard < 128 && n > 0; guard++) {
    precise float y = x + s;
    if (n == 1) {
      return y;
    }
    precise float z = y + s;
    if (n == 2) {
      return z;
    }
    precise float w = z + s;
    n -= 3;
    if (n == 0) {
      return w;
    }
    uint by = floatBitsToUint(y), bz = floatBitsToUint(z), bw = floatBitsToUint(w);
    uint e = bw >> 23u;  // sign and exponent
    if ((by >> 23u) == e && (bz >> 23u) == e && (e & 0xffu) != 0u && (e & 0xffu) != 0xffu) {
      // the binade's units: the mantissas with their leading bit, 2^23 to 2^24 - 1
      int mz = int(bz & 0x7fffffu) | 0x800000, mw = int(bw & 0x7fffffu) | 0x800000;
      int d = mw - mz;
      if (d == 0) {
        return w;  // s rounds away to nothing here: w for good
      }
      // further steps that stay inside, a unit clear of its bottom (where a sum's rounding would be the next binade's)
      int m = min(d > 0 ? (0xffffff - mw) / d : (mw - 0x800001) / (-d), n);
      mw += m * d;
      n -= m;
      w = uintBitsToFloat((bw & 0xff800000u) | uint(mw & 0x7fffff));
    }
    x = w;
  }
  return x;
}
