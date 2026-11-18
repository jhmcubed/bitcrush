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

// The CPU look, per triangle: the CPU renderer's own maths, as today's client does it (claude/cpu-look.md, section 9,
// "Read from today's client" and its follow-up).
//  - each corner comes in relative to the camera (the compute pass, or SceneUploader, put it there the client's
//    way) and is turned by the camera in floats (cpu_turn.glsl), then projected in floats (mid + x * zoom / z);
//  - the face is kept if it faces the camera, by the client's test on those float screen corners;
//  - the triangle routines round the corners with (int)(v + 0.5f) and set up from there: the top corner, which edge
//    is on the left (its own tie rules), the edges' slopes in 1/16384ths, the colour's steps (a float palette index
//    for plain faces; for textured ones the client's int fixed point built from floats), the texture's terms (P, M,
//    N as ints of their float camera positions);
//  - every corner's depth key, 150.75377 / ((z - 2 bias - 75) * 1.0100503) + 1.0100503, its steps across and down
//    taken from the unrounded corners: the CPU's own per-pixel depth test (the fragment stage keeps the greatest);
//  - one quad over the triangle's box; the fragment stage decides every pixel.
// Written from the 2022 reading of the rasterizer (OpenOSRS) and what today's client was read to do, not copied.
#version 400

#include texture_config

layout(triangles) in;
layout(triangle_strip, max_vertices = 8) out;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  int centerX;
  int centerY;
  int zoom;
  float cameraX;
  float cameraY;
  float cameraZ;
  ivec4 cameraPos;   // the camera in whole units
  ivec4 cameraRot;   // yaw sin, yaw cos, pitch sin, pitch cos at 16,384ths of a turn, in 65536ths (the model centre)
  vec4 cameraRotF;   // the same as the client's floats (Perspective.SINEF14): every corner's turn
};

uniform ivec2 viewSize;  // the game view, in game pixels

in vec3 gVertex[3];       // relative to the camera
flat in int gAhsl[3];     // alpha << 24 | marks (20 tile, 22 ground, 23 never hidden) | colour
flat in int gTextureId[3];
flat in int gBias[3];     // the face's bias (Model.getFaceBias, 0 to 255), carried in the texture slot
in vec3 gTexPos[3];       // the texture's P, M, N, relative to the camera
flat in int gFace[3];     // the face's place in the game's order

flat out ivec4 fRows;  // top row, middle row, bottom row, flags (1 long edge on the left, 2 textured, 4/8 marks, 16 tile, texture << 8)
flat out ivec4 fEdge;  // top corner's x, middle corner's x, long edge's slope, top-to-middle slope
flat out ivec4 fCol;   // middle-to-bottom slope; for textured faces their int shade where the top row meets x = 0, its step across, its step down
flat out vec4 fColF;   // plain faces: the float palette index where the top row meets x = 0, its step across, its step down
flat out ivec4 fT0;    // the texture's terms: u, u per 8 pixels, u per row, v
flat out ivec4 fT1;    //                      v per 8 pixels, v per row, w, w per 8 pixels
flat out ivec4 fT2;    //                      w per row; then the face's alpha, its colour (for plain triangles)
flat out vec4 fKey;    // the depth key where the first drawn row meets x = 0, its step across, its step down, that row
flat out vec4 fDepth;  // 1/z as a plane over the screen, for the depth-buffer diagnostic
flat out int fFace;    // the face's place in the game's order (frag.glsl: see-through faces after the pixel's winner only)

uniform int cpuFlags;  // 1 bricks, 2 bands, 4 texture spans, 8 depth buffer, 16 plain triangles (diagnostics), 32 the CPU's depth key
uniform int groundPass;  // "the ground hides what's under it" (the old stopgap): 1 the ground alone, into the depth buffer; 2 everything
uniform int alphaPass;   // with the CPU's depth test: 1 solid faces only (tested, writing the key), 2 see-through faces only (tested, not writing); 0 both

#include "setup.glsl"

// a triangle out to the fragment stage: one quad over its box (or, with the plain-triangles diagnostic, the triangle
// itself on its whole-pixel corners, filled by the hardware)
void emitRec(Rec r) {
  if ((cpuFlags & 16) != 0) {
    for (int k = 0; k < 3; k++) {
      gl_Position = vec4((float(r.px[k]) + 0.5) / float(viewSize.x) * 2.0 - 1.0, 1.0 - (float(r.py[k]) + 0.5) / float(viewSize.y) * 2.0, 0.0, 1.0);
      fRows = r.rows; fEdge = r.edge; fCol = r.col; fColF = r.colF; fT0 = r.t0; fT1 = r.t1; fT2 = r.t2; fKey = r.key; fDepth = r.depth;
      fFace = gFace[0];
      EmitVertex();
    }
    EndPrimitive();
    return;
  }
  vec2 lo = vec2(float(r.box.x), float(r.box.y)), hi = vec2(float(r.box.z), float(r.box.w));
  for (int k = 0; k < 4; k++) {
    vec2 p = vec2((k & 1) == 1 ? hi.x : lo.x, (k & 2) == 2 ? hi.y : lo.y);
    gl_Position = vec4(p.x / float(viewSize.x) * 2.0 - 1.0, 1.0 - p.y / float(viewSize.y) * 2.0, 0.0, 1.0);
    fRows = r.rows; fEdge = r.edge; fCol = r.col; fColF = r.colF; fT0 = r.t0; fT1 = r.t1; fT2 = r.t2; fKey = r.key; fDepth = r.depth;
    fFace = gFace[0];
    EmitVertex();
  }
  EndPrimitive();
}

void main() {
  if (groundPass == 1 && (gAhsl[0] & 0x400000) == 0) return;
  // which pass the face belongs to, before any of its maths (the second pass skips every solid face at once)
  int alpha = (gAhsl[0] >> 24) & 0xff;
  // a fully see-through face draws nothing and leaves the depth key alone (11/18/2026: an invisible NPC's box round a
  // bloodwood tree's bucket hid the bucket in CPU Look, where the CPU draws it)
  if (alpha >= 255) return;
  // the CPU's see-through faces are tested against the depth key but never write it (11/18/2026: an invisible NPC's
  // box round a bloodwood tree's bucket, partly see-through, hid the bucket and the trunk's foot whenever it came
  // first in the order, where the CPU draws them): solid faces in one pass, then see-through ones over them
  if ((alphaPass == 1 && alpha > 0) || (alphaPass == 2 && alpha == 0)) return;
  vec3 v[3] = vec3[3](gVertex[0], gVertex[1], gVertex[2]);
  int a[3] = int[3](gAhsl[0], gAhsl[1], gAhsl[2]);
  vec3 tp[3] = vec3[3](gTexPos[0], gTexPos[1], gTexPos[2]);
  Rec r0, r1;
  int n = setupFace(v, a, gTextureId[0], gBias[0], tp, true, r0, r1);
  if (n >= 1) emitRec(r0);
  if (n >= 2) emitRec(r1);
}
