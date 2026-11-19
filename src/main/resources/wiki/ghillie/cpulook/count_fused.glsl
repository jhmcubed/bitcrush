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

// The record path's count inside the compute pass (11/19/2026; claude/cpu-look-stage-b.md, "The card's passes"): the
// compute pass has each face's corners and texture slots in hand as it writes them, so it counts the face's triangles
// there, exactly as setup_comp.glsl would from what it wrote (setup_records.glsl's count run: the same setupFace, the
// same lists, a render mode 2 model's solid faces left to the geometry stage), and the count pass isn't run. With
// skipEmpty a face that draws nothing isn't written at all (about four faces in five zoomed out): only the record path
// reads the written faces then, and only those with records, besides the render mode 2 models' solid faces, which
// the geometry stage draws whole and which are always written.
#ifdef FUSED_COUNT
uniform ivec2 viewSize;
uniform int twoLists;   // as setup_comp.glsl's
uniform int skipEmpty;  // 1: the record path draws this frame, so a face without triangles needn't be written

layout(std430, binding = 8) writeonly buffer fused_counts_out {
  uvec2 counts[];
};

#include "setup.glsl"

// face f's count, from what's about to be written for it; whether it must be written
bool countFace(int f, vec3 a, vec3 b, vec3 c, int ahA, int ahB, int ahC, vec4 ua, vec4 ub, vec4 uc) {
  int alpha = (ahA >> 24) & 0xff;
  int n = 0;
  if (alpha < 255) {
    vec3 v[3] = vec3[3](a, b, c);
    int ah[3] = int[3](ahA, ahB, ahC);
    vec3 tp[3] = vec3[3](ua.yzw, ub.yzw, uc.yzw);
    int tx = int(ua.x);
    Rec r0, r1;
    n = setupFace(v, ah, tx & 511, (((tx >> 9) & 255) ^ 128) - 128, tp, false, r0, r1);
  }
  bool listB = twoLists == 1 && alpha > 0;
  // a render mode 2 model (with the CPU's depth test): its solid faces are in neither list, and the geometry stage draws
  // the model's whole range (drawPainters), so every face of it is written
  bool marked = twoLists == 1 && (ahA & 0x200000) != 0;
  if (marked && alpha == 0) {
    n = 0;
  }
  counts[f] = listB ? uvec2(0u, uint(n)) : uvec2(uint(n), 0u);
  return skipEmpty == 0 || n > 0 || marked;
}
#endif
