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

// The general way (any run): x after n additions of s, each rounded as Java's floats round (to the nearest, ties to even): how today's client
// steps along a triangle's edges, a row's colour and its depth key, a row or a pixel at a time (fc.av, fc.ay and the
// textured routines; claude/cpu-look-stage-b.md, "the client walks a triangle's edges in floats"). Exact, without n
// additions: inside one binade (one sign and exponent) adding s to any of its values rounds by the same rule, so once
// three additions in a row have stayed in one binade (the last two settled even on a tie) the rest of the run inside
// it is a multiplication in the binade's own units; then on into the next. n >= 0.
float cpu_acc_runs(float x, float s, int n) {
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

// x after n additions of s (as cpu_acc_runs), the usual run quickly (11/20/2026: the walk had doubled the fragment
// stage's time on his card): no step (a flat face's colour) leaves x as it is; and where x and x + s share a binade and
// the step isn't a tie (half a unit of the binade left over, which rounds by parity), every further step inside the
// binade adds the very same number of its units, so n of them that stay inside, a unit clear of its bottom, are one
// multiplication, with no division and no loop. Anything else goes the general way.
// (cpu_acc's usual run alone, for the compute pass's skip: ok false where it doesn't apply, and then x + s is returned,
// one step taken)
float cpu_acc_fast(float x, float s, int n, out bool ok) {
  ok = true;
  if (n <= 0 || s == 0.0) {
    return x;
  }
  precise float y = x + s;
  if (n == 1) {
    return y;
  }
  uint bx = floatBitsToUint(x), by = floatBitsToUint(y);
  uint ex = bx >> 23u;  // sign and exponent
  uint e = ex & 0xffu;
  if ((by >> 23u) == ex && e > 24u && e < 0xffu && n < 4096) {
    int mx = int(bx & 0x7fffffu) | 0x800000;
    int d = (int(by & 0x7fffffu) | 0x800000) - mx;  // the step in the binade's units
    precise float r = s - (y - x);  // what the rounding left of s (exact: y - x is, and r is s's low bits)
    float halfUnit = uintBitsToFloat((e - 24u) << 23u);  // half a unit of the binade
    if (abs(r) != halfUnit && abs(d) < 0x80000) {
      int t = mx + n * d;
      if (t >= 0x800001 && t <= 0xffffff) {
        return uintBitsToFloat((bx & 0xff800000u) | uint(t & 0x7fffff));
      }
    }
  }
  ok = false;
  return y;
}

float cpu_acc(float x, float s, int n) {
  bool ok;
  float v = cpu_acc_fast(x, s, n, ok);
  return ok ? v : cpu_acc_runs(v, s, n - 1);
}

// How far n additions of a step can end from one multiplication (g = x + s * n, Java's two roundings): each addition
// rounds by at most half a unit in the last place of its sum, so the walk ends within n units in the 24th bit of the
// largest value on the way of x + n s exactly, and g within two of it; the largest value is under |x| + |g| + 1. Four
// times that, so a guess and its reach settle most whole numbers without the walk (ShaderCheck's accCheck checks the
// bound on every run). 2^-24 is 5.96e-8.
float accTol(float x, float g, int n) {
  return float(n + 4) * (abs(x) + abs(g) + 1.0) * 2.4e-7;
}

// whether int(v) is certain for every v within tol of g: all of it above 0 and no whole number inside
bool sureInt(float g, float tol) {
  return g - tol > 0.0 && floor(g - tol) == floor(g + tol);
}
