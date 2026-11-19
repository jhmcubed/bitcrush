/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

#define PI 3.1415926535897932384626433832795f
#define UNIT PI / 1024.0f
// a model's flag (bit 23, 11/19/2026: Sailing's boats): its corners and texture points come already in camera space,
// turned by the client's own float matrix on the processor (BoatMath), so neither the compute pass nor the setup turns
// them; y holds the model origin's (int) camera depth for the face depths
#define CAM_SPACE (1 << 23)
// a model's flag (bit 22, 11/19/2026): drawn in its faces' own order, so the compute pass needn't sort it (comp.glsl).
// The CPU draws a model that isn't render mode 2 solid faces first, in their order, and see-through faces after by
// depth (face_key); one with no see-through face at all is then simply in its faces' order: CpuLookPlugin sets this
// for a model with no transparency of its own or per face that isn't render mode 2
#define FACE_ORDER (1 << 22)

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  int centerX;
  int centerY;
  int zoom;
  float cameraX;
  float cameraY;
  float cameraZ;
  ivec4 cameraPos;   // the CPU look: the camera in whole units
  ivec4 cameraRot;   // yaw sin, yaw cos, pitch sin, pitch cos at 16,384ths of a turn, in 65536ths (the model centre)
  vec4 cameraRotF;   // the same as the client's floats (Perspective.SINEF14): every corner's turn
};

struct modelinfo {
  int offset;   // offset into vertex buffer
  int toffset;  // offset into texture buffer
  int size;     // length in faces
  int idx;      // write idx in target buffer
  int flags;    // buffer, hillskew, plane, orientation
  int x;        // scene position x
  int y;        // scene position y
  int z;        // scene position z
};

struct vert {
  vec3 pos;
  int ahsl;
};

layout(std430, binding = 0) readonly buffer modelbuffer_in {
  modelinfo ol[];
};

layout(std430, binding = 1) readonly buffer vertexbuffer_in {
  vert vb[];
};

layout(std430, binding = 2) readonly buffer tempvertexbuffer_in {
  vert tempvb[];
};

layout(std430, binding = 3) writeonly buffer vertex_out {
  vert vout[];
};

layout(std430, binding = 4) writeonly buffer uv_out {
  vec4 uvout[];
};

layout(std430, binding = 5) readonly buffer texturebuffer_in {
  vec4 texb[];
};

layout(std430, binding = 6) readonly buffer temptexturebuffer_in {
  vec4 temptexb[];
};