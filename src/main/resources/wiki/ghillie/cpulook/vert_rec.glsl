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

// The record path's vertex stage (setup_comp.glsl says what for): six corners a record, its box's two triangles in
// the game view's pixels (as the geometry stage's quad), handing on only the record's number: the fragment stage reads
// the record itself (frag.glsl, RECORDS).
#include version_header

uniform ivec2 viewSize;
uniform int listB;  // 1: list B's records, which start after list A's

layout(std430, binding = 3) readonly buffer blocks_buffer {
  uvec4 totals;
};
layout(std430, binding = 4) readonly buffer records_in {
  ivec4 recs[];
};

flat out int fRec;

void main() {
  int rec = gl_VertexID / 6 + (listB == 1 ? int(totals.x) : 0);
  int corner = gl_VertexID % 6;
  // the quad's two triangles, 0 1 2 and 2 1 3, as the geometry stage's strip
  int k = corner < 3 ? corner : corner == 3 ? 2 : corner == 4 ? 1 : 3;
  ivec4 box = recs[rec * 8 + 7];
  vec2 lo = vec2(float(box.x & 0xffff), float(box.y & 0xffff));
  vec2 hi = vec2(float((box.x >> 16) & 0xffff), float((box.y >> 16) & 0xffff));
  vec2 p = vec2((k & 1) == 1 ? hi.x : lo.x, (k & 2) == 2 ? hi.y : lo.y);
  gl_Position = vec4(p.x / float(viewSize.x) * 2.0 - 1.0, 1.0 - p.y / float(viewSize.y) * 2.0, 0.0, 1.0);
  fRec = rec;
}
