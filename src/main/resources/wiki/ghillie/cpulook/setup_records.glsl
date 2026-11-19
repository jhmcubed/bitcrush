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

// The record path's setup (11/18/2026; stage A3 of the frame-rate plan, claude/cpu-look.md section 9): each face's
// triangles set up once, here, with the geometry stage's own maths (setup.glsl), instead of in a geometry shader whose
// every corner carried the whole setup to the fragment stage (most of the scene's drawing time zoomed out). Two
// runs: first, one invocation a face, each face counts the triangles it draws (0, 1 or 2) into its list, the solid
// list or (with the CPU's depth test) the see-through one, by the setup's decisions alone (setupFace with full false:
// the corners, the facing tests, the near cut, coverage; no colour, texture or depth key); the scan (scan_comp.glsl)
// turns the counts into places and notes each record's face; then, one invocation a record, the full setup of its
// face, written at its place, so every list stays in the faces' own order, the CPU's. (11/18/2026 evening: the first
// build set every face up in full twice, though zoomed out only about one face in five draws anything: 2.5 + 2.75 ms
// of Jace's RTX 3070's 5.7 for the scene.)
// Since the evening of 11/18/2026 the two runs are two programs (setup_comp.glsl, setup_write_comp.glsl include this
// file, the second with WRITE_RECORDS), so the count, which needs little, isn't held to the register allocation of the
// full setup (one program's registers are the most any of its paths needs: Jace's timings had the count at 3.1 ms).
// A record: 8 ivec4 (rows; edge; colour: a textured face's ints, a plain face's floats as bits after the slope; the
// texture's terms three; the depth key as bits; the box, min and max + 1 packed in 16 bits each, then the face's number).

#ifndef RECORD_GROUP
#define RECORD_GROUP 256
#endif
layout(local_size_x = RECORD_GROUP) in;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  int centerX;
  int centerY;
  int zoom;
  float cameraX;
  float cameraY;
  float cameraZ;
  ivec4 cameraPos;
  ivec4 cameraRot;
  vec4 cameraRotF;
};

uniform ivec2 viewSize;
uniform int faceCount;
uniform int twoLists;      // 1: solid faces in list A, see-through in list B (the CPU's depth test); 0: all in A, in order

struct vert {
  vec3 pos;
  int ahsl;
};

layout(std430, binding = 0) readonly buffer faces_in {
  vert vin[];
};
layout(std430, binding = 1) readonly buffer uvs_in {
  vec4 uvin[];
};
layout(std430, binding = 2) buffer counts_buffer {
  uvec2 counts[];  // count run: each face's triangles in list A and B; then the scan's places within the face's block
};
layout(std430, binding = 3) readonly buffer blocks_buffer {
  uvec4 totals;          // list A's records, list B's, whether they didn't fit, and how many are written
  uvec2 blockPlaces[];   // each block's first place in list A and in list B
};
layout(std430, binding = 4) writeonly buffer records_out {
  ivec4 recs[];
};

layout(std430, binding = 6) readonly buffer faces_of {
  uint faceOf[];  // each record's face (scan_comp.glsl's phase 2), bit 31 set for the second of a face's two
};

#define SCAN_BLOCK 4096

#include "setup.glsl"

void writeRec(int at, Rec r, int face) {
  int b = at * 8;
  recs[b] = r.rows;
  recs[b + 1] = r.edge;
  recs[b + 2] = (r.rows.w & 2) != 0 ? r.col : ivec4(r.col.x, floatBitsToInt(r.colF.x), floatBitsToInt(r.colF.y), floatBitsToInt(r.colF.z));
  recs[b + 3] = r.t0;
  recs[b + 4] = r.t1;
  recs[b + 5] = r.t2;
  recs[b + 6] = floatBitsToInt(r.key);
  recs[b + 7] = ivec4(r.box.x | (r.box.z << 16), r.box.y | (r.box.w << 16), face, 0);
}

// the face's triangles: full false, only how many (setup.glsl's setupFace)
int faceSetup(int f, bool full, out int alpha, out Rec r0, out Rec r1) {
  // the first corner's colour word first: alpha 255 is also the compute pass's mark for a face it skipped (SKIP_EMPTY),
  // whose other corners it didn't write
  alpha = (vin[f * 3].ahsl >> 24) & 0xff;
  if (alpha >= 255) {
    return 0;
  }
  vert a = vin[f * 3], b = vin[f * 3 + 1], c = vin[f * 3 + 2];
  // the texture's points and number, and the bias: only the full setup's (11/19/2026: the count read them too, half
  // of its memory traffic, his GPU Trace 3: the count bound by memory), and only a face that has them (0x20000: the
  // compute pass wrote none)
  vec4 ua = vec4(0.0), ub = vec4(0.0), uc = vec4(0.0);
#ifdef WRITE_RECORDS
  if ((a.ahsl & 0x20000) == 0) {
    ua = uvin[f * 3];
    ub = uvin[f * 3 + 1];
    uc = uvin[f * 3 + 2];
  }
#endif
  vec3 v[3] = vec3[3](a.pos, b.pos, c.pos);
  int ah[3] = int[3](a.ahsl, b.ahsl, c.ahsl);
  vec3 tp[3] = vec3[3](ua.yzw, ub.yzw, uc.yzw);
  // the texture's number + 1 and the face's bias, as vert.glsl reads them from the first corner's texture slot
  int tx = int(ua.x);
  return setupFace(v, ah, tx & 511, (((tx >> 9) & 255) ^ 128) - 128, tp, full, r0, r1);  // the bias signed, as the CPU's
}

void main() {
  int i = int(gl_GlobalInvocationID.x);
  int alpha;
  Rec r0, r1;
#ifndef WRITE_RECORDS
  if (i >= faceCount) {
    return;
  }
  int n = faceSetup(i, false, alpha, r0, r1);
  bool listB = twoLists == 1 && alpha > 0;
  // a render mode 2 model's solid faces (0x200000: players, most NPCs) are drawn by the geometry stage, a model at a
  // time, after the solid list (geom.glsl's painterPass says how); their see-through faces stay in list B
  // what the CPU draws in its solid pass only (0x80000: under a bridge, CpuLookPlugin.FLAG_SOLID): never a see-through face
  if (alpha > 0 && (vin[i * 3].ahsl & 0x80000) != 0) {
    n = 0;
  }
  if (twoLists == 1 && alpha == 0 && (vin[i * 3].ahsl & 0x200000) != 0) {
    n = 0;
  }
  counts[i] = listB ? uvec2(0u, uint(n)) : uvec2(uint(n), 0u);
#else
  // a record (only those that fit: the scan cut the lists there and flagged it, and the plugin grows the buffer)
  if (uint(i) >= totals.w) {
    return;
  }
  uint fo = faceOf[i];
  int f = int(fo & 0x7fffffffu);
#if defined(WRITE_EXPERIMENT) && WRITE_EXPERIMENT == 1
  // the developer's experiment (Diagnostics -> Records: the write's experiment, Memory only): the record's reads and
  // writes, the same bytes, without its setup (the picture wrong)
  vert a = vin[f * 3], b = vin[f * 3 + 1], c = vin[f * 3 + 2];
  vec4 ua = vec4(0.0), ub = vec4(0.0), uc = vec4(0.0);
  if ((a.ahsl & 0x20000) == 0) {
    ua = uvin[f * 3];
    ub = uvin[f * 3 + 1];
    uc = uvin[f * 3 + 2];
  }
  int k = i * 8;
  recs[k] = ivec4(floatBitsToInt(a.pos), a.ahsl);
  recs[k + 1] = ivec4(floatBitsToInt(b.pos), b.ahsl);
  recs[k + 2] = ivec4(floatBitsToInt(c.pos), c.ahsl);
  recs[k + 3] = floatBitsToInt(ua);
  recs[k + 4] = floatBitsToInt(ub);
  recs[k + 5] = floatBitsToInt(uc);
  recs[k + 6] = ivec4(f, int(fo), 0, 0);
  recs[k + 7] = ivec4(0, 0, f, 0);
#elif defined(WRITE_EXPERIMENT) && WRITE_EXPERIMENT == 2
  // ... Setup only: the record's setup without its writes (a write behind a test that never passes keeps the setup
  // from being left out; the picture wrong)
  faceSetup(f, true, alpha, r0, r1);
  Rec r = (fo >> 31) != 0u ? r1 : r0;
  if (r.rows.x == 0x7fffffff && r.key.x == 12345.0) {
    writeRec(i, r, f);
  }
#else
  faceSetup(f, true, alpha, r0, r1);
  writeRec(i, (fo >> 31) != 0u ? r1 : r0, f);
#endif
#endif
}
