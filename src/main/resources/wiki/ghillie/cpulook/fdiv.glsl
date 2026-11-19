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

#include fdiv_config

#ifdef FDIV_FMA
// a - q * b exactly, on a card whose fma() rounds once (11/19/2026: CpuLookPlugin asks the card at start, FmaProbe):
// a - q b is a float wherever it's relied on (below), so the fused multiply-add's single rounding leaves it exact; one
// step where Dekker's product takes fifteen, each waiting on the last
float fdiv_gap(float a, float q, float b) {
  precise float g = fma(-q, b, a);
  return g;
}
#else
// a - q * b, worked out exactly with float multiplies and adds alone (Dekker's product: q and b each split into two
// halves of 12 bits, whose products a float holds exactly), exact whenever the answer is itself a float, as it is for
// the two floats either side of a / b. No fused multiply-add: not every card fuses one (Mesa's llvmpipe doesn't), and
// GLSL rounds every multiply and add exactly as Java does. precise keeps the compiler from fusing or reordering.
float fdiv_gap(float a, float q, float b) {
  precise float cq = 4097.0 * q;
  precise float qh = cq - (cq - q);
  precise float ql = q - qh;
  precise float cb = 4097.0 * b;
  precise float bh = cb - (cb - b);
  precise float bl = b - bh;
  precise float p = q * b;
  precise float err = ((qh * bh - p) + qh * bl + ql * bh) + ql * bl;
  precise float g = (a - p) - err;
  return g;
}
#endif

// Java's float division, correctly rounded, in floats (11/18/2026; until then through doubles, exact but slow: a
// consumer card runs doubles at a small fraction of its speed, and the geometry stage divides about 13 times a
// triangle). The card's own quotient (which may be a rounding step or two off) is refined once toward the true one;
// then the exact gap a - q b (fdiv_gap) says which side of q the true quotient lies and how far: past half the step
// to the neighbouring float on that side, the neighbour is nearer. Half that step times |b| is exact (a power of two
// times b), so the comparison is exact, and the true quotient of two floats is never exactly halfway between two
// floats, so the nearer is the correctly rounded quotient: the same bits as Java's a / b (ShaderCheck compares
// millions, those a hair from halfway too). (Until 11/18/2026 evening the gap was worked out for q and both its
// neighbours and the smallest kept: the same answer for two gaps fewer.)
float fdiv(float a, float b) {
  precise float q = a / b;
  if (q == 0.0 || isinf(q) || isnan(q)) return q;
#ifdef FDIV_SKEW
  // ShaderCheck only: the card's quotient pushed a few rounding steps off, as a card's own division may be (GLSL
  // allows 2.5), to show the rest still lands on Java's
  q = uintBitsToFloat(uint(int(floatBitsToUint(q)) + FDIV_SKEW));
#endif
  precise float r = 1.0 / b;
  precise float step = fdiv_gap(a, q, b) * r;
  q = q + step;
  float g = fdiv_gap(a, q, b);
  if (g == 0.0) return q;
  // the true quotient is q + g / b: above q when g and b have the same sign
  bool up = (g > 0.0) == (b > 0.0);
  uint u = floatBitsToUint(q);
  // the neighbouring float on that side (the bits of a negative float grow away from zero)
  float nb = uintBitsToFloat(up == (q > 0.0) ? u + 1u : u - 1u);
  precise float halfStep = abs(nb - q) * 0.5;
  precise float limit = halfStep * abs(b);
  return abs(g) > limit ? nb : q;
}
