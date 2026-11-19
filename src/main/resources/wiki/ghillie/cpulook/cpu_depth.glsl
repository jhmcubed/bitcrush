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

// The depth the CPU sorts a model's faces by (the game's Model.draw and draw0): each corner's camera depth, turned
// in floats as the CPU turns it (cpu_turn.glsl) and cast to an int, less the model centre's own depth (still in
// whole units, 65536ths and >> 16); a face's is the three summed and divided by 3 in Java's way (toward zero). The farthest
// is drawn first, faces at the same depth in their own order. Needs cameraPos and cameraRot (yaw sin, yaw cos,
// pitch sin, pitch cos) from the includer.

int cpu_tdiv(int a, int b) {
  int q = int(uint(abs(a)) / uint(abs(b)));
  return (a < 0) != (b < 0) ? -q : q;
}

// floor(a / b) for b > 0: the CPU averages bucket numbers that are never negative (depth + the model's radius);
// without the radius the same comparisons need flooring
int cpu_fdiv(int a, int b) {
  int q = cpu_tdiv(a, b);
  return q * b > a ? q - 1 : q;
}

// a corner relative to the camera as the client's model draw adds the model's place (floats, the place's ints)
vec3 cpu_rel(vec3 v, ivec3 pos) {
  precise vec3 rel = vec3(v.x + float(pos.x - cameraPos.x), v.y + float(pos.y - cameraPos.y), v.z + float(pos.z - cameraPos.z));
  return rel;
}

// a corner's depth: (int) of its float camera z (the corner already turned for the object's orientation)
int cpu_depth(vec4 v, ivec3 pos) {
  return int(cpu_turn(cpu_rel(v.xyz, pos)).z);
}

// the model centre's own depth, in ints (bo's var18)
int cpu_origin(ivec3 pos) {
  int x0 = pos.x - cameraPos.x, y0 = pos.y - cameraPos.y, z0 = pos.z - cameraPos.z;
  return (y0 * cameraRot.z + ((z0 * cameraRot.y - x0 * cameraRot.x) >> 16) * cameraRot.w) >> 16;
}

int cpu_face_distance(vec4 vA, vec4 vB, vec4 vC, ivec3 pos) {
  int c = cpu_origin(pos);
  return cpu_tdiv((cpu_depth(vA, pos) - c) + (cpu_depth(vB, pos) - c) + (cpu_depth(vC, pos) - c), 3);
}
