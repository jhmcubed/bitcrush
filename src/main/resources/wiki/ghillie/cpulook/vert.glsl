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

// The CPU look: each corner as the compute pass left it (in painter's order), handed on whole.
// The geometry stage does the CPU's own maths on the three corners together.
#version 400

layout(location = 0) in vec3 vert;   // scene units, already turned and placed
layout(location = 1) in int ahsl;    // alpha << 24 | colour (hsl; for textured faces, the lightness)
layout(location = 2) in vec4 uv;     // x: texture id + 1 (bits 0-8) and the face's bias (bits 9-16); yzw: this corner's point of the texture's triangle (P, M or N)

out vec3 gVertex;
flat out int gAhsl;
flat out int gTextureId;
flat out int gBias;
out vec3 gTexPos;
flat out int gFace;  // the face's place in the game's order (its triangles' first vertex / 3): see-through faces' rule

void main() {
  gVertex = vert;
  gAhsl = ahsl;
  gTextureId = int(uv.x) & 511;
  gBias = (int(uv.x) >> 9) & 255;
  gTexPos = uv.yzw;
  gFace = gl_VertexID / 3;
}
