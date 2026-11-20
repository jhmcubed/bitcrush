/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

// The CPU look's per-triangle maths, shared by the geometry stage (geom.glsl) and the compute pass that sets up the
// triangles for the record path (setup_comp.glsl), so both give the very same setup: every corner turned and projected
// as the client does, the face's culling and near-plane cut, and for each triangle the CPU's own setup (geom.glsl's
// header says what). The includer declares the uniforms block (zoom, centerX, centerY, cameraRotF) and viewSize.

#include "cpu_turn.glsl"

// Java's division: toward zero
int idiv(int a, int b) {
  int q = int(uint(abs(a)) / uint(abs(b)));
  return (a < 0) != (b < 0) ? -q : q;
}

// Java's float division (correctly rounded), in floats: fdiv.glsl
#include "fdiv.glsl"

// the client's edges and its running sums (the walk), and a flat face's line (cpu_edges.glsl)
#include "cpu_edges.glsl"

// a corner as the client's Model.draw leaves it: turned (floats), its screen point (floats), its ints
struct corner {
  vec3 cam;     // camera space, floats
  vec2 screen;  // mid + x * zoom / z, floats (z >= 50)
  ivec3 ci;     // (int) of the camera floats: the textured routine's points, the near cut
};

// rel: the corner relative to the camera, turned here as the client turns it; or, with cam (0x40000 on the face's
// corners: a model or the ground on a boat), already in camera space from the client's own matrix (BoatMath)
corner toCorner(vec3 rel, bool cam) {
  corner k;
  k.cam = cam ? rel : cpu_turn(rel);
  precise float px = k.cam.x * float(zoom);
  precise float py = k.cam.y * float(zoom);
  precise float sx = float(centerX) + fdiv(px, k.cam.z);
  precise float sy = float(centerY) + fdiv(py, k.cam.z);
  k.screen = vec2(sx, sy);
  k.ci = ivec3(int(k.cam.x), int(k.cam.y), int(k.cam.z));
  return k;
}

// the CPU's depth key for a corner at camera depth z (the face's bias already taken off)
float depthKey(float z) {
  precise float a = (z - 75.0) * 1.0100503;
  precise float k = fdiv(150.75377, a) + 1.0100503;
  return k;
}

// ((long) v << sh) / zoom, then (int), exactly as the CPU's Java does it: the true quotient's low 32 bits (q << sh
// wraps just as the cast does)
int ldiv(int v, int sh) {
  int q = idiv(v, zoom);
  int r = v - q * zoom;
  return q * (1 << sh) + idiv(r * (1 << sh), zoom);
}

// the textured routines' per-face terms (Rasterizer3D's textured triangle; for ground tiles its drawTexturedTile,
// whose steps across the screen are << 14 where the triangle's are << 3 << 14), in the CPU's own 32-bit arithmetic:
// products and << 14 wrap as Java's do, and each pixel's own value comes out right through the wrap, as the CPU's
// does. (Until 11/18/2026 big texture triangles had their points halved here until nothing wrapped, which moved
// the texture by a few units: the tree canopies' texels.)
void textureTerms(ivec3 p, ivec3 m, ivec3 n, bool tile, out ivec4 t0, out ivec4 t1, out int t40) {
  int across = tile ? 14 : 17;
  int a10 = p.x - m.x, a13 = p.y - m.y, a16 = p.z - m.z;  // P - M
  int a11 = n.x - p.x, a14 = n.y - p.y, a17 = n.z - p.z;  // N - P
  t0.x = (a11 * p.y - p.x * a14) << 14;
  t0.y = ldiv(p.z * a14 - a17 * p.y, across);
  t0.z = ldiv(a17 * p.x - a11 * p.z, 14);
  t0.w = (a10 * p.y - a13 * p.x) << 14;
  t1.x = ldiv(a13 * p.z - a16 * p.y, across);
  t1.y = ldiv(a16 * p.x - a10 * p.z, 14);
  t1.z = (a13 * a11 - a10 * a14) << 14;
  t1.w = ldiv(a16 * a14 - a13 * a17, across);
  t40 = ldiv(a17 * a10 - a11 * a16, 14);
}

// A triangle set up for the fragment stage: what the geometry stage hands each pixel (as flat varyings) or the record
// path stores (setup_comp.glsl), and its box on the screen.
struct Rec {
  ivec4 rows;   // first row, middle row, bottom row (each clamped to the screen), flags (1 long edge on the left, 2 textured, 4/8 marks, 16 tile, 32 flat, texture << 8)
  ivec4 edge;   // floats' bits: the long edge's and the top-to-middle edge's x at the first row, their slopes
  ivec4 col;    // the middle-to-bottom edge's slope (a float's bits); textured faces' int shade where the first row meets x = 0, its step across, its step down
  vec4 colF;    // plain faces: the float palette index where the first row meets x = 0, its step across, its step down
  ivec4 t0;     // the texture's terms: u, u per 8 pixels, u per row, v
  ivec4 t1;     //                      v per 8 pixels, v per row, w, w per 8 pixels
  ivec4 t2;     //                      w per row; then the face's alpha, its colour, its bias
  vec4 key;     // the depth key where the first drawn row meets x = 0, its step across, its step down, that row
  vec4 depth;   // 1/z as a plane over the screen (the depth-buffer diagnostic and the old ground stopgap); w: the
                // middle-to-bottom edge's x at the middle row
  ivec4 box;    // the box the fragment stage is run over: min x, min y, max x + 1, max y + 1
  ivec3 px;     // the rounded corners (the plain-triangles diagnostic)
  ivec3 py;
};

// Whether a triangle on these screen corners (floats, unrounded) draws anything: its corners rounded as the triangle
// routines round them, some area on whole pixels, some of its box on the screen. The one decision setupTriangle makes,
// asked first there, and all the record path's count asks (11/18/2026), so both decide alike by construction. Out: the
// rounded corners, twice the area (the routines' determinant), and the box the fragment stage is run over (min x, min
// y, max x + 1, max y + 1: the top corner's row is the least y, the bottom's the greatest).
// (11/19/2026: no arrays anywhere in the setup. NVIDIA's compiler left the loops over a face's three corners rolled
// and so put every per-corner array in local memory, indexed at run time: 51 four-wide slots a thread in the count,
// 68 in the write (their binaries from his card), the card's slow memory, and the count and write's time. Each corner
// is now its own variables and the near cut's points four named slots; the very same arithmetic.)
bool coverage(vec2 f0, vec2 f1, vec2 f2, bool flatFace, out int x0, out int x1, out int x2, out int y0, out int y1, out int y2, out int det, out ivec4 box) {
  precise float rx0 = f0.x + 0.5;
  precise float ry0 = f0.y + 0.5;
  precise float rx1 = f1.x + 0.5;
  precise float ry1 = f1.y + 0.5;
  precise float rx2 = f2.x + 0.5;
  precise float ry2 = f2.y + 0.5;
  x0 = int(rx0);
  y0 = int(ry0);
  x1 = int(rx1);
  y1 = int(ry1);
  x2 = int(rx2);
  y2 = int(ry2);
  box = ivec4(0);
  det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
  // no area once on whole pixels: nothing drawn, but by the client's flat routine (fc.ay, a flat face's: 11/20/2026),
  // which tests only the unrounded corners, so its rows may still leave a pixel here and there along the line
  if (det == 0 && !flatFace) return false;
  // nor on the unrounded corners (the client's second test, before its depth key's steps: 11/19/2026)
  precise float kd = (f1.x - f0.x) * (f2.y - f0.y) - (f2.x - f0.x) * (f1.y - f0.y);
  if (kd == 0.0) return false;
  int minX = max(0, min(x0, min(x1, x2)));
  int maxX = min(viewSize.x - 1, max(x0, max(x1, x2)));
  int minY = max(0, min(y0, min(y1, y2)));
  int maxY = min(viewSize.y, max(y0, max(y1, y2))) - 1;
  if (minX > maxX || minY > maxY) return false;
  box = ivec4(minX, minY, maxX + 1, maxY + 1);
  // a flat face's line (its rounded corners in a line): something only where a row of its walk lights a pixel
  // (11/20/2026: most light none, and zoomed out there are tens of thousands of them a frame)
  if (det == 0 && !flatLineDraws(x0, y0, x1, y1, x2, y2)) return false;
  return true;
}

// the i-th of three by selects (0, 1 or 2)
int pick3(int a0, int a1, int a2, int i) {
  return i == 0 ? a0 : i == 1 ? a1 : a2;
}

float pick3(float a0, float a1, float a2, int i) {
  return i == 0 ? a0 : i == 1 ? a1 : a2;
}

vec2 pick3(vec2 a0, vec2 a1, vec2 a2, int i) {
  return i == 0 ? a0 : i == 1 ? a1 : a2;
}

bool covers(vec2 f0, vec2 f1, vec2 f2, bool flatFace) {
  int x0, x1, x2, y0, y1, y2, det;
  ivec4 box;
  return coverage(f0, f1, f2, flatFace, x0, x1, x2, y0, y1, y2, det, box);
}

// One triangle: f the screen corners as floats (unrounded), s their depth keys, c their colours (hsl, or a textured
// face's lightness). The triangle routines round the corners; the depth key's steps come from the unrounded ones.
// False if nothing of it is drawn (no area on whole pixels, or off the screen: coverage).
bool setupTriangle(vec2 f0, vec2 f1, vec2 f2, int c0, int c1, int c2, float s0, float s1, float s2, vec3 zc, int flags, ivec4 t0, ivec4 t1, int t40, int alpha, int bias, out Rec r) {
  int x0, x1, x2, y0, y1, y2, det;
  ivec4 box;
  bool flatFace = (flags & 32) != 0;
  if (!coverage(f0, f1, f2, flatFace, x0, x1, x2, y0, y1, y2, det, box)) return false;

  // the edges: which corner is the top, the middle and the bottom, which edge is on the left, the slopes (cpu_edges.glsl)
  int t, m, b;
  bool longLeft;
  float sTB, sTM, sMB;
  triangleEdges(x0, y0, x1, y1, x2, y2, t, m, b, longLeft, sTB, sTM, sMB);
  int H = viewSize.y;
  // the top, middle and bottom corners' values
  int xt = pick3(x0, x1, x2, t), xm = pick3(x0, x1, x2, m), yt = pick3(y0, y1, y2, t), ym = pick3(y0, y1, y2, m);
  int yb = pick3(y0, y1, y2, b), Ct = pick3(c0, c1, c2, t);
  vec2 ft = pick3(f0, f1, f2, t);
  float st = pick3(s0, s1, s2, t);
  // the colour: textured faces the client's int fixed point (<< 9) from float steps; plain faces a float palette index
  float X0 = float(x0), X1 = float(x1), X2 = float(x2), Y0 = float(y0), Y1 = float(y1), Y2 = float(y2);
  float fdet = float(det);
  float dc1 = float(c1 - c0), dc2 = float(c2 - c0);
  precise float ax = dc1 * (Y2 - Y0) - dc2 * (Y1 - Y0);
  precise float ay = dc2 * (X1 - X0) - dc1 * (X2 - X0);
  // (a top above the screen: the client moves the row seed down to row 0, as it does the edges below)
  ivec4 col = ivec4(floatBitsToInt(sMB), 0, 0, 0);
  vec4 colF = vec4(0.0);
  if ((flags & 2) != 0) {
    precise float axs = ax * 512.0;
    precise float ays = ay * 512.0;
    int gxI = int(fdiv(axs, fdet));
    int gyI = int(fdiv(ays, fdet));
    int seedI = (Ct << 9) - gxI * xt + gxI;
    if (yt < 0) {
      seedI -= gyI * yt;
    }
    col = ivec4(floatBitsToInt(sMB), seedI, gxI, gyI);
  } else {
    // (a flat face: one colour, no steps; its rounded corners may line up, det 0)
    float gx = flatFace ? 0.0 : fdiv(ax, fdet), gy = flatFace ? 0.0 : fdiv(ay, fdet);
    precise float seed = float(Ct) - gx * float(xt) + gx;
    if (yt < 0) {
      seed -= gy * float(yt);
    }
    colF = vec4(seed, gx, gy, 0.0);
  }

  // the depth key: steps from the unrounded corners, seeded at the top corner (the client's cj)
  precise float kd = (f1.x - f0.x) * (f2.y - f0.y) - (f2.x - f0.x) * (f1.y - f0.y);
  precise float kax = (s1 - s0) * (f2.y - f0.y) - (s2 - s0) * (f1.y - f0.y);
  precise float kay = (s2 - s0) * (f1.x - f0.x) - (s1 - s0) * (f2.x - f0.x);
  float sx = fdiv(kax, kd), sy = fdiv(kay, kd);
  precise float key = st - sx * ft.x + sx;
  if (ft.y < 0.0) {
    key -= sy * float(int(ft.y));
  }
  precise float frac = ft.y - float(yt);
  key -= sy * frac;
  vec4 keyF = vec4(key, sx, sy, float(max(yt, 0)));

  // the rows and the edges' starts (the long one and the top-to-middle one at the top row, the middle-to-bottom one at the
  // middle row): the middle and bottom rows clamped to the screen's bottom, and a top or a middle above the screen moved
  // down to row 0 along its edges, x - slope * y in floats, as the client does; the fragment stage adds the slopes on
  // from there a row at a time (cpu_acc.glsl)
  int ymC = min(ym, H), ybC = min(yb, H), ytC = yt;
  precise float eL = float(xt), eS = float(xt), eM = float(xm);
  if (ytC < 0) {
    eL = float(xt) - sTB * float(ytC);
    eS = float(xt) - sTM * float(ytC);
    ytC = 0;
  }
  if (ymC < 0) {
    eM = float(xm) - sMB * float(ymC);
    ymC = 0;
  }
  ivec4 rows = ivec4(ytC, ymC, ybC, (longLeft ? 1 : 0) | flags);
  ivec4 edge = ivec4(floatBitsToInt(eL), floatBitsToInt(eS), floatBitsToInt(sTB), floatBitsToInt(sTM));
  ivec4 tt2 = ivec4(t40, alpha, (flags & 2) != 0 ? c0 & 127 : c0, bias);  // w: the face's bias (the bias view)

  // 1/z over the screen (the depth-buffer diagnostic and the old ground stopgap)
  vec3 iz = vec3(1.0 / max(zc.x, 1.0), 1.0 / max(zc.y, 1.0), 1.0 / max(zc.z, 1.0));
  float dax = float(x1 - x0), day = float(y1 - y0), dbx = float(x2 - x0), dby = float(y2 - y0);
  float ad = iz.y - iz.x, bd = iz.z - iz.x;
  float dA = det != 0 ? (ad * dby - bd * day) / fdet : 0.0, dB = det != 0 ? (bd * dax - ad * dbx) / fdet : 0.0;
  vec4 depth = vec4(dA, dB, iz.x - dA * float(x0) - dB * float(y0), eM);  // w: the middle-to-bottom edge's start

  r.rows = rows;
  r.edge = edge;
  r.col = col;
  r.colF = colF;
  r.t0 = t0;
  r.t1 = t1;
  r.t2 = tt2;
  r.key = keyF;
  r.depth = depth;
  r.box = box;
  r.px = ivec3(x0, x1, x2);
  r.py = ivec3(y0, y1, y2);
  return true;
}

// a point of a face cut by the near plane: its screen point, colour, depth key and camera depth
struct Pt {
  vec2 sp;
  int sc;
  float sk;
  float sz;
};

// the n-th of four points set by selects (no array: kept in registers)
void putPt(int n, Pt p, inout Pt q0, inout Pt q1, inout Pt q2, inout Pt q3) {
  if (n == 0) {
    q0 = p;
  } else if (n == 1) {
    q1 = p;
  } else if (n == 2) {
    q2 = p;
  } else if (n == 3) {
    q3 = p;
  }
}

Pt cornerPt(corner k, int col, float key) {
  Pt p;
  p.sp = k.screen;
  p.sc = col;
  p.sk = key;
  p.sz = k.cam.z;
  return p;
}

// where the edge from an outside corner i to an inside corner o crosses z = 50 (the client's ints)
Pt cutPt(ivec3 ci, ivec3 co, int coli, int colo, float keyCut) {
  int fct = (65536 / (co.z - ci.z)) * (50 - ci.z);
  int cx = ci.x + ((co.x - ci.x) * fct >> 16);
  int cy = ci.y + ((co.y - ci.y) * fct >> 16);
  Pt p;
  p.sp = vec2(float(centerX + idiv(zoom * cx, 50)), float(centerY + idiv(zoom * cy, 50)));
  p.sc = coli + ((colo - coli) * fct >> 16);
  p.sk = keyCut;
  p.sz = 50.0;
  return p;
}

// A face: its three corners relative to the camera, their colour words (alpha << 24 | marks | colour), its texture's
// number + 1 (0 none) and bias, its texture's P, M, N relative to the camera. Up to two triangles (a face cut by the
// near plane can leave four corners): how many, in r0 and r1. The callers have already left out fully see-through faces
// and those of other passes. full false: only how many (the record path's count), every decision made by the same
// code (the corners, the facing tests, the near cut, coverage) and the rest of the setup left out; r0 and r1 unset.
int setupFace(vec3 v0, vec3 v1, vec3 v2, int a0, int a1, int a2, int texPlus1, int bias, vec3 tp0, vec3 tp1, vec3 tp2, bool full, out Rec r0, out Rec r1) {
  bool cam = (a0 & 0x40000) != 0;
  corner k0 = toCorner(v0, cam);
  corner k1 = toCorner(v1, cam);
  corner k2 = toCorner(v2, cam);
  int col0 = a0 & 0xffff, col1 = a1 & 0xffff, col2 = a2 & 0xffff;
  int alpha = (a0 >> 24) & 0xff;
  float bias2 = float(bias * 2);

  int texId = texPlus1 - 1;
  int flags = 0;
  ivec4 t0 = ivec4(0), t1 = ivec4(0);
  int t40 = 0;
  bool tile = (a0 & 0x100000) != 0;
  if (full && texId >= 0) {
    textureTerms(toCorner(tp0, cam).ci, toCorner(tp1, cam).ci, toCorner(tp2, cam).ci, tile, t0, t1, t40);
    flags = 2 | (texId << 8);
  }
  flags |= ((a0 >> 20) & 12) | (tile ? 16 : 0);  // 4 the ground, 8 never hidden by it, 16 a ground tile
  flags |= (a0 & 0x10000) != 0 && (flags & 2) == 0 ? 32 : 0;  // 32 a flat face (the client's flat routine)

  bool in0 = k0.cam.z >= 50.0, in1 = k1.cam.z >= 50.0, in2 = k2.cam.z >= 50.0;
  float key0 = 0.0, key1 = 0.0, key2 = 0.0;
  if (full) {
    precise float zb0 = k0.cam.z - bias2;
    precise float zb1 = k1.cam.z - bias2;
    precise float zb2 = k2.cam.z - bias2;
    key0 = depthKey(zb0);
    key1 = depthKey(zb1);
    key2 = depthKey(zb2);
  }
  if (in0 && in1 && in2) {
    // the client's test for a face turned away, on its float screen corners
    precise float facing = (k0.screen.x - k1.screen.x) * (k2.screen.y - k1.screen.y) - (k2.screen.x - k1.screen.x) * (k0.screen.y - k1.screen.y);
    if (!(facing > 0.0)) return 0;
    if (!full) return covers(k0.screen, k1.screen, k2.screen, (flags & 32) != 0) ? 1 : 0;
    return setupTriangle(k0.screen, k1.screen, k2.screen, col0, col1, col2, key0, key1, key2, vec3(k0.cam.z, k1.cam.z, k2.cam.z), flags, t0, t1, t40, alpha, bias, r0) ? 1 : 0;
  }
  if (!in0 && !in1 && !in2) return 0;

  // a face across the plane z = 50: the client's test in camera space (its ints), then its cut at z = 50: each corner
  // in turn, an outside one giving where its edges to the previous and the next corner cross the plane
  ivec3 c0 = k0.ci, c1 = k1.ci, c2 = k2.ci;
  int ax = c0.x - c1.x, bx = c2.x - c1.x, ay = c0.y - c1.y, by = c2.y - c1.y, az = c0.z - c1.z, bz = c2.z - c1.z;
  int nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
  if (!(c1.x * nx + c1.y * ny + c1.z * nz > 0)) return 0;
  float keyCut = full ? depthKey(50.0 - bias2) : 0.0;
  Pt q0 = Pt(vec2(0.0), 0, 0.0, 0.0), q1 = q0, q2 = q0, q3 = q0;
  int n = 0;
  // corner 0: its previous corner 2, its next 1
  if (in0) {
    putPt(n, cornerPt(k0, col0, key0), q0, q1, q2, q3);
    n++;
  } else {
    if (in2 && n < 4) {
      putPt(n, cutPt(c0, c2, col0, col2, keyCut), q0, q1, q2, q3);
      n++;
    }
    if (in1 && n < 4) {
      putPt(n, cutPt(c0, c1, col0, col1, keyCut), q0, q1, q2, q3);
      n++;
    }
  }
  // corner 1: its previous 0, its next 2
  if (in1) {
    putPt(n, cornerPt(k1, col1, key1), q0, q1, q2, q3);
    n++;
  } else {
    if (in0 && n < 4) {
      putPt(n, cutPt(c1, c0, col1, col0, keyCut), q0, q1, q2, q3);
      n++;
    }
    if (in2 && n < 4) {
      putPt(n, cutPt(c1, c2, col1, col2, keyCut), q0, q1, q2, q3);
      n++;
    }
  }
  // corner 2: its previous 1, its next 0
  if (in2) {
    putPt(n, cornerPt(k2, col2, key2), q0, q1, q2, q3);
    n++;
  } else {
    if (in1 && n < 4) {
      putPt(n, cutPt(c2, c1, col2, col1, keyCut), q0, q1, q2, q3);
      n++;
    }
    if (in0 && n < 4) {
      putPt(n, cutPt(c2, c0, col2, col0, keyCut), q0, q1, q2, q3);
      n++;
    }
  }
  // the two triangles in the order the client draws them; whichever of them has something to draw, in that order
  if (!full) {
    return (n >= 3 && covers(q0.sp, q1.sp, q2.sp, (flags & 32) != 0) ? 1 : 0) + (n == 4 && covers(q0.sp, q2.sp, q3.sp, (flags & 32) != 0) ? 1 : 0);
  }
  int count = 0;
  if (n >= 3 && setupTriangle(q0.sp, q1.sp, q2.sp, q0.sc, q1.sc, q2.sc, q0.sk, q1.sk, q2.sk, vec3(q0.sz, q1.sz, q2.sz), flags, t0, t1, t40, alpha, bias, r0)) {
    count = 1;
  }
  if (n == 4) {
    Rec second;
    if (setupTriangle(q0.sp, q2.sp, q3.sp, q0.sc, q2.sc, q3.sc, q0.sk, q2.sk, q3.sk, vec3(q0.sz, q2.sz, q3.sz), flags, t0, t1, t40, alpha, bias, second)) {
      if (count == 0) {
        r0 = second;
      } else {
        r1 = second;
      }
      count++;
    }
  }
  return count;
}
