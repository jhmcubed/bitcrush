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

// A triangle's edges as today's client walks them (fc.av, fc.ay and their kin; claude/cpu-look-stage-b.md, "the
// client walks a triangle's edges in floats"), shared by the setup (setup.glsl) and the compute pass's skip of faces
// that surely draw nothing (priority_render.glsl). The includer has fdiv (fdiv.glsl) and viewSize.

#include "cpu_acc.glsl"

// From the rounded corners: the top corner (the least row), the middle one (the other two clamped to the screen's
// bottom first, as the client picks it), which edge is on the left by the client's own tests, and the slopes of the
// long edge (top to bottom), the top-to-middle and the middle-to-bottom ones: float divisions of the rounded corners
// (fdiv: Java's), the same whichever way an edge is walked.
void triangleEdges(int x0, int y0, int x1, int y1, int x2, int y2, out int t, out int m, out int b, out bool longLeft,
    out float sTB, out float sTM, out float sMB) {
  float s01 = y0 != y1 ? fdiv(float(x1 - x0), float(y1 - y0)) : 0.0;
  float s12 = y1 != y2 ? fdiv(float(x2 - x1), float(y2 - y1)) : 0.0;
  float s02 = y0 != y2 ? fdiv(float(x2 - x0), float(y2 - y0)) : 0.0;
  int H = viewSize.y;
  if (y0 <= y1 && y0 <= y2) {
    t = 0;
    int c1 = min(y1, H), c2 = min(y2, H);
    if (c1 < c2) { m = 1; b = 2; longLeft = !((y0 == c1 || !(s02 < s01)) && (y0 != c1 || !(s02 > s12))); }
    else { m = 2; b = 1; longLeft = (y0 == c2 || !(s02 < s01)) && (y0 != c2 || !(s12 > s01)); }
  } else if (y1 <= y2) {
    t = 1;
    int c2 = min(y2, H), c0 = min(y0, H);
    if (c2 < c0) { m = 2; b = 0; longLeft = !((y1 == c2 || !(s01 < s12)) && (y1 != c2 || !(s01 > s02))); }
    else { m = 0; b = 2; longLeft = !(s01 < s12); }
  } else {
    t = 2;
    int c0 = min(y0, H), c1 = min(y1, H);
    if (c0 < c1) { m = 0; b = 1; longLeft = s12 < s02; }
    else { m = 1; b = 0; longLeft = !(s12 < s02); }
  }
  sTB = (t + b) == 1 ? s01 : (t + b) == 3 ? s12 : s02;
  sTM = (t + m) == 1 ? s01 : (t + m) == 3 ? s12 : s02;
  sMB = (m + b) == 1 ? s01 : (m + b) == 3 ? s12 : s02;
}

// Whether the client's flat routine (fc.ay) lights any pixel of a triangle whose rounded corners lie in a line (a flat
// face's: the other routines skip it; 11/20/2026). Its three slopes are one line's, the same float, so the top part's
// two edges, both from the top corner, are the same sums and light nothing; in the bottom part the long edge carried
// on and the middle-to-bottom edge started afresh from the middle corner can truncate apart. Those rows walked as the
// fragment stage walks them (frag.glsl), each end clamped to the screen as there.
bool flatLineDraws(int x0, int y0, int x1, int y1, int x2, int y2) {
  int t, m, b;
  bool longLeft;
  float sTB, sTM, sMB;
  triangleEdges(x0, y0, x1, y1, x2, y2, t, m, b, longLeft, sTB, sTM, sMB);
  int H = viewSize.y;
  int xt = t == 0 ? x0 : t == 1 ? x1 : x2, xm = m == 0 ? x0 : m == 1 ? x1 : x2;
  int yt = t == 0 ? y0 : t == 1 ? y1 : y2, ym = min(m == 0 ? y0 : m == 1 ? y1 : y2, H), yb = min(b == 0 ? y0 : b == 1 ? y1 : y2, H);
  if (yt >= H) {
    return false;
  }
  precise float eL = float(xt), eM = float(xm);
  if (yt < 0) {
    eL = float(xt) - sTB * float(yt);
    yt = 0;
  }
  if (ym < 0) {
    eM = float(xm) - sMB * float(ym);
    ym = 0;
  }
  for (int py = ym; py < yb; py++) {
    float l = cpu_acc(eL, sTB, py - yt), o = cpu_acc(eM, sMB, py - ym);
    int x1 = max(int(longLeft ? l : o), 0), x2 = min(int(longLeft ? o : l), viewSize.x);
    if (x1 < x2) {
      return true;
    }
  }
  return false;
}
