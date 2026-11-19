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
package wiki.ghillie.cpulook;

import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import wiki.ghillie.cpulook.regions.Regions;

@Singleton
@Slf4j
class SceneUploader
{
	private final Client client;
	private final CpuLookConfig gpuConfig;

	private final Regions regions;

	int sceneId = (int) System.nanoTime();
	private int offset;
	private int uvoffset;
	private int uniqueModels;

	@Inject
	SceneUploader(
		Client client,
		CpuLookConfig config
	)
	{
		this.client = client;
		this.gpuConfig = config;

		try (var in = SceneUploader.class.getResourceAsStream("regions/regions.txt"))
		{
			regions = new Regions(in, "regions.txt");
		}
		catch (IOException ex)
		{
			throw new RuntimeException(ex);
		}
	}

	void upload(Scene scene, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		++sceneId;
		offset = 0;
		uvoffset = 0;
		uniqueModels = 0;
		vertexBuffer.clear();
		uvBuffer.clear();

		Stopwatch stopwatch = Stopwatch.createStarted();
		prepare(scene);
		stopwatch.stop();
		log.debug("Scene preparation time: {}", stopwatch);

		stopwatch = Stopwatch.createStarted();
		for (int z = 0; z < Constants.MAX_Z; ++z)
		{
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x)
			{
				for (int y = 0; y < Constants.EXTENDED_SCENE_SIZE; ++y)
				{
					Tile tile = scene.getExtendedTiles()[z][x][y];
					if (tile != null)
					{
						upload(scene, tile, vertexBuffer, uvBuffer);
					}
				}
			}
		}

		stopwatch.stop();
		log.debug("Scene upload time: {} unique models: {} length: {}KB", stopwatch, uniqueModels, (offset * 16) / 1024);
	}

	private void upload(Scene scene, Tile tile, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		Tile bridge = tile.getBridge();
		if (bridge != null)
		{
			upload(scene, bridge, vertexBuffer, uvBuffer);
		}

		SceneTilePaint sceneTilePaint = tile.getSceneTilePaint();
		if (sceneTilePaint != null)
		{
			sceneTilePaint.setBufferOffset(offset);
			if (sceneTilePaint.getTexture() != -1)
			{
				sceneTilePaint.setUvBufferOffset(uvoffset);
			}
			else
			{
				sceneTilePaint.setUvBufferOffset(-1);
			}
			Point tilePoint = tile.getSceneLocation();
			int len = upload(scene, sceneTilePaint,
				tile.getRenderLevel(), tilePoint.getX(), tilePoint.getY(),
				vertexBuffer, uvBuffer,
				0, 0, false);
			sceneTilePaint.setBufferLen(len);
			offset += len;
			if (sceneTilePaint.getTexture() != -1)
			{
				uvoffset += len;
			}
		}

		SceneTileModel sceneTileModel = tile.getSceneTileModel();
		if (sceneTileModel != null)
		{
			sceneTileModel.setBufferOffset(offset);
			if (sceneTileModel.getTriangleTextureId() != null)
			{
				sceneTileModel.setUvBufferOffset(uvoffset);
			}
			else
			{
				sceneTileModel.setUvBufferOffset(-1);
			}
			Point tilePoint = tile.getSceneLocation();
			int len = upload(sceneTileModel,
				tilePoint.getX() << Perspective.LOCAL_COORD_BITS, tilePoint.getY() << Perspective.LOCAL_COORD_BITS,
				vertexBuffer, uvBuffer, false);
			sceneTileModel.setBufferLen(len);
			offset += len;
			if (sceneTileModel.getTriangleTextureId() != null)
			{
				uvoffset += len;
			}
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null)
		{
			Renderable renderable1 = wallObject.getRenderable1();
			if (renderable1 instanceof Model)
			{
				uploadSceneModel((Model) renderable1, vertexBuffer, uvBuffer);
			}

			Renderable renderable2 = wallObject.getRenderable2();
			if (renderable2 instanceof Model)
			{
				uploadSceneModel((Model) renderable2, vertexBuffer, uvBuffer);
			}
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null)
		{
			Renderable renderable = groundObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadSceneModel((Model) renderable, vertexBuffer, uvBuffer);
			}
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null)
		{
			Renderable renderable = decorativeObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadSceneModel((Model) renderable, vertexBuffer, uvBuffer);
			}

			Renderable renderable2 = decorativeObject.getRenderable2();
			if (renderable2 instanceof Model)
			{
				uploadSceneModel((Model) renderable2, vertexBuffer, uvBuffer);
			}
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			if (renderable instanceof Model)
			{
				uploadSceneModel((Model) gameObject.getRenderable(), vertexBuffer, uvBuffer);
			}
		}
	}

	int upload(Scene scene, SceneTilePaint tile, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer,
		int lx, int lz, boolean stream)
	{
		tileX += CpuLookPlugin.SCENE_OFFSET;
		tileY += CpuLookPlugin.SCENE_OFFSET;

		final int[][][] tileHeights = scene.getTileHeights();
		final int swHeight = tileHeights[tileZ][tileX][tileY];
		final int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		final int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		final int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		final int swColor = tile.getSwColor();
		final int seColor = tile.getSeColor();
		final int neColor = tile.getNeColor();
		final int nwColor = tile.getNwColor();

		if (neColor == 12345678)
		{
			return 0;
		}

		vertexBuffer.ensureCapacity(24);
		uvBuffer.ensureCapacity(24);

		// 0,0
		final int lx0 = lx;
		final int ly0 = swHeight;
		final int lz0 = lz;
		final int hsl0 = swColor;

		// 1,0
		final int lx1 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly1 = seHeight;
		final int lz1 = lz;
		final int hsl1 = seColor;

		// 1,1
		final int lx2 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly2 = neHeight;
		final int lz2 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl2 = neColor;

		// 0,1
		final int lx3 = lx;
		final int ly3 = nwHeight;
		final int lz3 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl3 = nwColor;

		vertexBuffer.put((float) lx2, ly2, lz2, hsl2);
		vertexBuffer.put((float) lx3, ly3, lz3, hsl3);
		vertexBuffer.put((float) lx1, ly1, lz1, hsl1);

		vertexBuffer.put((float) lx0, ly0, lz0, hsl0);
		vertexBuffer.put((float) lx1, ly1, lz1, hsl1);
		vertexBuffer.put((float) lx3, ly3, lz3, hsl3);

		if (stream || tile.getTexture() != -1)
		{
			int tex = tile.getTexture() + 1;
			if (tile.isFlat())
			{
				uvBuffer.put(tex, lx0, ly0, lz0);
				uvBuffer.put(tex, lx1, ly1, lz1);
				uvBuffer.put(tex, lx3, ly3, lz3);
			}
			else
			{
				uvBuffer.put(tex, lx2, ly2, lz2);
				uvBuffer.put(tex, lx3, ly3, lz3);
				uvBuffer.put(tex, lx1, ly1, lz1);
			}

			uvBuffer.put(tex, lx0, ly0, lz0);
			uvBuffer.put(tex, lx1, ly1, lz1);
			uvBuffer.put(tex, lx3, ly3, lz3);
		}

		return 6;
	}

	int upload(SceneTileModel sceneTileModel, int lx, int lz,
		GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, boolean stream)
	{
		final int[] faceX = sceneTileModel.getFaceX();
		final int[] faceY = sceneTileModel.getFaceY();
		final int[] faceZ = sceneTileModel.getFaceZ();

		final int[] vertexX = sceneTileModel.getVertexX();
		final int[] vertexY = sceneTileModel.getVertexY();
		final int[] vertexZ = sceneTileModel.getVertexZ();

		final int[] triangleColorA = sceneTileModel.getTriangleColorA();
		final int[] triangleColorB = sceneTileModel.getTriangleColorB();
		final int[] triangleColorC = sceneTileModel.getTriangleColorC();

		final int[] triangleTextures = sceneTileModel.getTriangleTextureId();

		final int faceCount = faceX.length;

		vertexBuffer.ensureCapacity(faceCount * 12);
		uvBuffer.ensureCapacity(faceCount * 12);

		int cnt = 0;
		for (int i = 0; i < faceCount; ++i)
		{
			final int vertex0 = faceX[i];
			final int vertex1 = faceY[i];
			final int vertex2 = faceZ[i];

			final int hsl0 = triangleColorA[i];
			final int hsl1 = triangleColorB[i];
			final int hsl2 = triangleColorC[i];

			if (hsl0 == 12345678)
			{
				continue;
			}

			cnt += 3;

			// vertexes are stored in scene local, convert to tile local
			int lx0 = vertexX[vertex0] - lx;
			int ly0 = vertexY[vertex0];
			int lz0 = vertexZ[vertex0] - lz;

			int lx1 = vertexX[vertex1] - lx;
			int ly1 = vertexY[vertex1];
			int lz1 = vertexZ[vertex1] - lz;

			int lx2 = vertexX[vertex2] - lx;
			int ly2 = vertexY[vertex2];
			int lz2 = vertexZ[vertex2] - lz;

			vertexBuffer.put((float) lx0, ly0, lz0, hsl0);
			vertexBuffer.put((float) lx1, ly1, lz1, hsl1);
			vertexBuffer.put((float) lx2, ly2, lz2, hsl2);

			if (stream || triangleTextures != null)
			{
				if (triangleTextures != null && triangleTextures[i] != -1)
				{
					int tex = triangleTextures[i] + 1;
					if (sceneTileModel.isFlat())
					{
						uvBuffer.put(tex, vertexX[0] - lx, vertexY[0], vertexZ[0] - lz);
						uvBuffer.put(tex, vertexX[1] - lx, vertexY[1], vertexZ[1] - lz);
						uvBuffer.put(tex, vertexX[3] - lx, vertexY[3], vertexZ[3] - lz);
					}
					else
					{
						uvBuffer.put(tex, vertexX[vertex0] - lx, vertexY[vertex0], vertexZ[vertex0] - lz);
						uvBuffer.put(tex, vertexX[vertex1] - lx, vertexY[vertex1], vertexZ[vertex1] - lz);
						uvBuffer.put(tex, vertexX[vertex2] - lx, vertexY[vertex2], vertexZ[vertex2] - lz);
					}
				}
				else
				{
					uvBuffer.put(0, 0, 0, 0);
					uvBuffer.put(0, 0, 0, 0);
					uvBuffer.put(0, 0, 0, 0);
				}
			}
		}

		return cnt;
	}

	private void uploadSceneModel(Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		// deduplicate hillskewed models
		if (model.getUnskewedModel() != null)
		{
			model = model.getUnskewedModel();
		}

		if (model.getSceneId() == sceneId)
		{
			return; // model has already been uploaded
		}

		model.setBufferOffset(offset);
		if (hasUv(model))
		{
			model.setUvBufferOffset(uvoffset);
		}
		else
		{
			model.setUvBufferOffset(-1);
		}
		model.setSceneId(sceneId);
		++uniqueModels;

		int len = pushModel(model, vertexBuffer, uvBuffer);

		offset += len;
		if (hasUv(model))
		{
			uvoffset += len;
		}
	}

	public int pushModel(Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		return pushModel(model, vertexBuffer, uvBuffer, model.getVerticesX(), model.getVerticesY(), model.getVerticesZ());
	}

	/**
	 * A model with its corners given (11/19/2026: a boat's, already through the boat's matrix in camera space,
	 * BoatMath.corners): the same faces, colours and texture slots, each corner and texture point taken from these
	 * arrays by its index.
	 */
	public int pushModel(Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, float[] vertexX, float[] vertexY,
		float[] vertexZ)
	{
		return pushModel(model, vertexBuffer, uvBuffer, vertexX, vertexY, vertexZ, 0, null);
	}

	/**
	 * ... and with a scene's colour override (11/19/2026: a boat the game hides for overlapping another, which it draws
	 * flattened onto the water and tinted; Scene.getOverrideHue and the rest, packed by sceneTint: amount << 24, hue
	 * << 16, saturation << 8, lightness, each a byte). While its amount is above 0 the CPU tints every face of every
	 * model in that scene that isn't textured (fo.bv: the face count in place of the model's own override's count;
	 * fr.am and fr.ad: the client's interpolation, interpolateHSL here), in place of the model's own override; a flat
	 * face (one colour) from its colour already through the palette (fr.am is handed the palette's RGB and treats it as
	 * a colour number: the client's own way, kept), so palette is the CPU's palette for it.
	 */
	public int pushModel(Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, float[] vertexX, float[] vertexY,
		float[] vertexZ, int sceneTint, int[] palette)
	{
		final byte tintAmount = (byte) (sceneTint >> 24), tintHue = (byte) (sceneTint >> 16), tintSat = (byte) (sceneTint >> 8),
			tintLum = (byte) sceneTint;
		final int triangleCount = Math.min(model.getFaceCount(), CpuLookPlugin.MAX_TRIANGLE);

		vertexBuffer.ensureCapacity(triangleCount * 12);
		uvBuffer.ensureCapacity(triangleCount * 12);

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		final byte[] transparencies = model.getFaceTransparencies();
		final byte[] facePriorities = model.getFaceRenderPriorities();
		// the CPU look: each face's bias, for the CPU's depth key, rides in its texture slot (uv.x's bits 9-16, the
		// texture's number + 1 below), so a model with any bias gets texture slots even without textures
		final byte[] faceBias = model.getFaceBias();
		final boolean uv = faceTextures != null || anyBias(faceBias);

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();
		final byte modelTransparency = model.getTransparency();

		// (11/19/2026: written into plain arrays and put in the buffers in one go, the same numbers as one put at a
		// time; the copying of the players and NPCs the client hands over was a sixth of its thread)
		final int[] vs = vertexBuffer.scratch(triangleCount * 12);
		final float[] us = uv ? uvBuffer.scratch(triangleCount * 12) : null;
		int vi = 0, ui = 0;
		for (int face = 0; face < triangleCount; ++face)
		{
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];
			final boolean flat = color3 == -1;

			if (color3 == -1)
			{
				color2 = color3 = color1;
			}
			else if (color3 == -2)
			{
				for (int k = 0; k < 12; k++)
				{
					vs[vi++] = 0;
				}
				if (uv)
				{
					for (int k = 0; k < 12; k++)
					{
						us[ui++] = 0;
					}
				}
				continue;
			}

			// HSL override is not applied to textured faces
			if (faceTextures == null || faceTextures[face] == -1)
			{
				if (tintAmount > 0)
				{
					if (flat && palette != null)
					{
						color1 = color2 = color3 = interpolateHSL(palette[color1 & 0xffff], tintHue, tintSat, tintLum, tintAmount);
					}
					else
					{
						color1 = interpolateHSL(color1, tintHue, tintSat, tintLum, tintAmount);
						color2 = interpolateHSL(color2, tintHue, tintSat, tintLum, tintAmount);
						color3 = interpolateHSL(color3, tintHue, tintSat, tintLum, tintAmount);
					}
				}
				else if (overrideAmount > 0)
				{
					color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
					color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
					color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
				}
			}

			int packAlphaPriority = packAlphaPriority(modelTransparency, transparencies, facePriorities, face);
			final int biasBits = bias(faceBias, face) << 9;

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];

			vs[vi] = Float.floatToIntBits(vertexX[triangleA]);
			vs[vi + 1] = Float.floatToIntBits(vertexY[triangleA]);
			vs[vi + 2] = Float.floatToIntBits(vertexZ[triangleA]);
			vs[vi + 3] = packAlphaPriority | color1;
			vs[vi + 4] = Float.floatToIntBits(vertexX[triangleB]);
			vs[vi + 5] = Float.floatToIntBits(vertexY[triangleB]);
			vs[vi + 6] = Float.floatToIntBits(vertexZ[triangleB]);
			vs[vi + 7] = packAlphaPriority | color2;
			vs[vi + 8] = Float.floatToIntBits(vertexX[triangleC]);
			vs[vi + 9] = Float.floatToIntBits(vertexY[triangleC]);
			vs[vi + 10] = Float.floatToIntBits(vertexZ[triangleC]);
			vs[vi + 11] = packAlphaPriority | color3;
			vi += 12;

			if (uv)
			{
				if (faceTextures != null && faceTextures[face] != -1)
				{
					int texA, texB, texC;

					if (textureFaces != null && textureFaces[face] != -1)
					{
						int tface = textureFaces[face] & 0xff;
						texA = texIndices1[tface];
						texB = texIndices2[tface];
						texC = texIndices3[tface];
					}
					else
					{
						texA = triangleA;
						texB = triangleB;
						texC = triangleC;
					}

					float texture = (faceTextures[face] + 1) | biasBits;
					us[ui] = texture;
					us[ui + 1] = vertexX[texA];
					us[ui + 2] = vertexY[texA];
					us[ui + 3] = vertexZ[texA];
					us[ui + 4] = texture;
					us[ui + 5] = vertexX[texB];
					us[ui + 6] = vertexY[texB];
					us[ui + 7] = vertexZ[texB];
					us[ui + 8] = texture;
					us[ui + 9] = vertexX[texC];
					us[ui + 10] = vertexY[texC];
					us[ui + 11] = vertexZ[texC];
				}
				else
				{
					float b = biasBits;
					us[ui] = b;
					us[ui + 1] = 0;
					us[ui + 2] = 0;
					us[ui + 3] = 0;
					us[ui + 4] = b;
					us[ui + 5] = 0;
					us[ui + 6] = 0;
					us[ui + 7] = 0;
					us[ui + 8] = b;
					us[ui + 9] = 0;
					us[ui + 10] = 0;
					us[ui + 11] = 0;
				}
				ui += 12;
			}
		}
		vertexBuffer.getBuffer().put(vs, 0, vi);
		if (uv)
		{
			uvBuffer.getBuffer().put(us, 0, ui);
		}

		return triangleCount * 3;
	}

	/**
	 * A boat's tile paint (11/19/2026; the client's ea.al): its four corners (x << 7, the corner's height, z << 7, the
	 * tile's place in the boat's own scene) through the boat's matrix, south-west, south-east, north-east, north-west;
	 * the whole tile left out if any is nearer than 50 (as the client leaves it), else the paint's two triangles as
	 * upload(SceneTilePaint) lays them out, every corner and texture point in camera space. Returns the corners
	 * written (0: nothing written).
	 */
	int pushBoatPaint(Scene scene, SceneTilePaint tile, int plane, int tileX, int tileY, float[] m, GpuIntBuffer vertexBuffer,
		GpuFloatBuffer uvBuffer)
	{
		final int[][][] heights = scene.getTileHeights();
		if (tile.getNeColor() == 12345678 || heights == null || plane < 0 || plane >= heights.length || tileX < 0 || tileY < 0
			|| tileX + 1 >= heights[plane].length || tileY + 1 >= heights[plane][tileX].length)
		{
			return 0;
		}
		final float x0 = tileX << 7, z0 = tileY << 7;
		final float x1 = x0 + 128.0F, z1 = 128.0F + z0;
		final float hsw = heights[plane][tileX][tileY], hse = heights[plane][tileX + 1][tileY];
		final float hne = heights[plane][tileX + 1][tileY + 1], hnw = heights[plane][tileX][tileY + 1];
		final float[] c = paintCorners;
		final float[][] at = {{x0, hsw, z0}, {x1, hse, z0}, {x1, hne, z1}, {x0, hnw, z1}};
		for (int k = 0; k < 4; k++)
		{
			final float x = at[k][0], y = at[k][1], z = at[k][2];
			final float d = BoatMath.z(m, x, y, z);
			if (d < 50.0F)
			{
				return 0;
			}
			c[k * 3] = BoatMath.x(m, x, y, z);
			c[k * 3 + 1] = BoatMath.y(m, x, y, z);
			c[k * 3 + 2] = d;
		}
		final int[] hsl = {tile.getSwColor(), tile.getSeColor(), tile.getNeColor(), tile.getNwColor()};
		vertexBuffer.ensureCapacity(24);
		// 0 south-west, 1 south-east, 2 north-east, 3 north-west: (2, 3, 1) and (0, 1, 3), as upload(SceneTilePaint)
		final int[] order = {2, 3, 1, 0, 1, 3};
		for (int k : order)
		{
			vertexBuffer.put(c[k * 3], c[k * 3 + 1], c[k * 3 + 2], hsl[k]);
		}
		if (tile.getTexture() != -1)
		{
			uvBuffer.ensureCapacity(24);
			final float tex = tile.getTexture() + 1;
			final int[] tp = tile.isFlat() ? new int[]{0, 1, 3, 0, 1, 3} : new int[]{2, 3, 1, 0, 1, 3};
			for (int k : tp)
			{
				uvBuffer.put(tex, c[k * 3], c[k * 3 + 1], c[k * 3 + 2]);
			}
		}
		return 6;
	}

	private final float[] paintCorners = new float[12];

	/**
	 * A boat's shaped tile (the client's ea.av): every corner through the boat's matrix, the whole tile left out if any
	 * is nearer than 50, else its faces as upload(SceneTileModel) lays them out, in camera space. cx, cy, cz: room for
	 * the corners. Returns the corners written (0: nothing written).
	 */
	int pushBoatTileModel(SceneTileModel model, float[] m, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, float[] cx,
		float[] cy, float[] cz)
	{
		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();
		final int count = vertexX.length;
		if (cx.length < count)
		{
			return -count;  // (the caller makes room and asks again)
		}
		for (int v = 0; v < count; v++)
		{
			final float x = vertexX[v], y = vertexY[v], z = vertexZ[v];
			final float d = BoatMath.z(m, x, y, z);
			if (d < 50.0F)
			{
				return 0;
			}
			cx[v] = BoatMath.x(m, x, y, z);
			cy[v] = BoatMath.y(m, x, y, z);
			cz[v] = d;
		}
		final int[] faceX = model.getFaceX();
		final int[] faceY = model.getFaceY();
		final int[] faceZ = model.getFaceZ();
		final int[] colorA = model.getTriangleColorA();
		final int[] colorB = model.getTriangleColorB();
		final int[] colorC = model.getTriangleColorC();
		final int[] textures = model.getTriangleTextureId();
		final int faceCount = faceX.length;
		vertexBuffer.ensureCapacity(faceCount * 12);
		if (textures != null)
		{
			uvBuffer.ensureCapacity(faceCount * 12);
		}
		int cnt = 0;
		for (int i = 0; i < faceCount; i++)
		{
			if (colorA[i] == 12345678)
			{
				continue;
			}
			cnt += 3;
			final int a = faceX[i], b = faceY[i], c = faceZ[i];
			vertexBuffer.put(cx[a], cy[a], cz[a], colorA[i]);
			vertexBuffer.put(cx[b], cy[b], cz[b], colorB[i]);
			vertexBuffer.put(cx[c], cy[c], cz[c], colorC[i]);
			if (textures != null)
			{
				if (textures[i] != -1)
				{
					final float tex = textures[i] + 1;
					final int p = model.isFlat() ? 0 : a, q = model.isFlat() ? 1 : b, r = model.isFlat() ? 3 : c;
					uvBuffer.put(tex, cx[p], cy[p], cz[p]);
					uvBuffer.put(tex, cx[q], cy[q], cz[q]);
					uvBuffer.put(tex, cx[r], cy[r], cz[r]);
				}
				else
				{
					uvBuffer.put(0, 0, 0, 0);
					uvBuffer.put(0, 0, 0, 0);
					uvBuffer.put(0, 0, 0, 0);
				}
			}
		}
		return cnt;
	}

	/** Whether a model needs texture slots: textures, or any face's bias (carried in the slot, uv.x's bits 9-16). */
	static boolean hasUv(Model model)
	{
		return model.getFaceTextures() != null || anyBias(model.getFaceBias());
	}

	private static boolean anyBias(byte[] bias)
	{
		if (bias != null)
		{
			for (byte b : bias)
			{
				if (b != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static int[] distances;
	private static char[] distanceFaceCount;
	private static char[][] distanceToFaces;

	private static float[] modelCanvasX;
	private static float[] modelCanvasY;
	// each corner's camera-space ints, for the client's facing test of a face with a corner nearer than z = 50
	private static int[] modelCamX, modelCamY, modelCamZ;

	private static float[] modelLocalX;
	private static float[] modelLocalY;
	private static float[] modelLocalZ;

	private static int[] numOfPriority;
	private static int[] eq10;
	private static int[] eq11;
	private static int[] lt10;
	private static int[][] orderedFaces;

	void initSortingBuffers()
	{
		int MAX_VERTEX_COUNT = 6500;
		int MAX_DIAMETER = 6000;
		int ZSORT_GROUP_SIZE = 1024; // was 512
		int MAX_FACES_PER_PRIORITY = 4000; // was 2000

		distances = new int[MAX_VERTEX_COUNT];
		distanceFaceCount = new char[MAX_DIAMETER];
		distanceToFaces = new char[MAX_DIAMETER][ZSORT_GROUP_SIZE];

		modelCanvasX = new float[MAX_VERTEX_COUNT];
		modelCanvasY = new float[MAX_VERTEX_COUNT];
		modelCamX = new int[MAX_VERTEX_COUNT];
		modelCamY = new int[MAX_VERTEX_COUNT];
		modelCamZ = new int[MAX_VERTEX_COUNT];

		modelLocalX = new float[MAX_VERTEX_COUNT];
		modelLocalY = new float[MAX_VERTEX_COUNT];
		modelLocalZ = new float[MAX_VERTEX_COUNT];

		numOfPriority = new int[12];
		eq10 = new int[MAX_FACES_PER_PRIORITY];
		eq11 = new int[MAX_FACES_PER_PRIORITY];
		lt10 = new int[12];
		orderedFaces = new int[12][MAX_FACES_PER_PRIORITY];
	}

	void releaseSortingBuffers()
	{
		distances = null;
		distanceFaceCount = null;
		distanceToFaces = null;

		modelCanvasX = null;
		modelCanvasY = null;
		modelCamX = null;
		modelCamY = null;
		modelCamZ = null;

		modelLocalX = null;
		modelLocalY = null;
		modelLocalZ = null;

		numOfPriority = null;
		eq10 = null;
		eq11 = null;
		lt10 = null;
		orderedFaces = null;
	}

	// the CPU look: the camera as the CPU has it (claude/cpu-look.md, "Read from today's client"): whole units, its
	// angles' 65536ths for the model centre's depth, and their floats for every corner's turn (16,384ths of a turn)
	private int camX, camY, camZ, yawSin, yawCos = 65536, pitchSin, pitchCos = 65536;
	private float yawSinF, yawCosF = 1, pitchSinF, pitchCosF = 1;

	void setCamera(int x, int y, int z, int yaw, int pitch)
	{
		camX = x;
		camY = y;
		camZ = z;
		yawSin = Perspective.SINE14[yaw];
		yawCos = Perspective.COSINE14[yaw];
		pitchSin = Perspective.SINE14[pitch];
		pitchCos = Perspective.COSINE14[pitch];
		yawSinF = Perspective.SINEF14[yaw];
		yawCosF = Perspective.COSINEF14[yaw];
		pitchSinF = Perspective.SINEF14[pitch];
		pitchCosF = Perspective.COSINEF14[pitch];
	}

	int pushSortedModel(Projection proj, Model model, int orientation, int x, int y, int z, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		final int vertexCount = model.getVerticesCount();
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int faceCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors3 = model.getFaceColors3();
		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();

		final int centerX = client.getCenterX();
		final int centerY = client.getCenterY();
		final int zoom = client.get3dZoom();

		// Each corner as the client's Model.draw has it (claude/cpu-look.md, "Read from today's client", follow-up 1):
		// the stored vertex cast to an int, turned for the object's orientation in floats (Perspective.SINEF), the
		// object's place less the camera's (ints) added in floats: written out relative to the camera, as the
		// compute pass writes them; then turned by the camera in floats for the face sort and the screen.
		float orientSine = 0;
		float orientCosine = 1;
		if ((orientation & 2047) != 0)
		{
			orientSine = Perspective.SINEF[orientation & 2047];
			orientCosine = Perspective.COSINEF[orientation & 2047];
		}
		final int ox = x - camX, oy = y - camY, oz = z - camZ;
		// the model centre's depth, all int, as the client's (follow-up 2)
		final int zero = (oy * pitchSin + ((oz * yawCos - ox * yawSin) >> 16) * pitchCos) >> 16;

		for (int v = 0; v < vertexCount; ++v)
		{
			final int ix = (int) verticesX[v], iy = (int) verticesY[v], iz = (int) verticesZ[v];
			float vx = ix, vz = iz;
			if ((orientation & 2047) != 0)
			{
				float nx = iz * orientSine + ix * orientCosine;
				float nz = iz * orientCosine - ix * orientSine;
				vx = nx;
				vz = nz;
			}
			vx += ox;
			vz += oz;
			float vy = iy;
			vy += oy;

			modelLocalX[v] = vx;
			modelLocalY[v] = vy;
			modelLocalZ[v] = vz;

			final float tx = vz * yawSinF + vx * yawCosF;
			final float tz = vz * yawCosF - vx * yawSinF;
			final float ty = vy * pitchCosF - tz * pitchSinF;
			final float d = vy * pitchSinF + tz * pitchCosF;
			distances[v] = (int) d - zero;
			modelCamX[v] = (int) tx;
			modelCamY[v] = (int) ty;
			modelCamZ[v] = (int) d;
			if (d >= 50.0f)
			{
				modelCanvasX[v] = centerX + tx * zoom / d;
				modelCanvasY[v] = centerY + ty * zoom / d;
			}
			else
			{
				modelCanvasX[v] = -5000.0f;
				modelCanvasY[v] = centerY + ty * zoom / d;
			}
		}

		final int diameter = model.getDiameter();
		final int radius = model.getRadius();
		if (diameter >= 6000)
		{
			return 0;
		}

		Arrays.fill(distanceFaceCount, 0, diameter, (char) 0);

		for (char i = 0; i < faceCount; ++i)
		{
			if (faceColors3[i] != -2)
			{
				final int v1 = indices1[i];
				final int v2 = indices2[i];
				final int v3 = indices3[i];

				final float
					aX = modelCanvasX[v1],
					aY = modelCanvasY[v1],
					bX = modelCanvasX[v2],
					bY = modelCanvasY[v2],
					cX = modelCanvasX[v3],
					cY = modelCanvasY[v3];

				// the client's facing test (fo.bv's am): with a corner nearer than z = 50 (its -5000 mark), in camera
				// space on the corners' ints; else on the float screen points (11/19/2026: the -5000s went into the
				// screen test, which isn't the client's)
				final boolean facing;
				if (aX == -5000.0f || bX == -5000.0f || cX == -5000.0f)
				{
					final int x1 = modelCamX[v2], y1 = modelCamY[v2], z1 = modelCamZ[v2];
					final int ax = modelCamX[v1] - x1, ay = modelCamY[v1] - y1, az = modelCamZ[v1] - z1;
					final int bx = modelCamX[v3] - x1, by = modelCamY[v3] - y1, bz = modelCamZ[v3] - z1;
					facing = x1 * (ay * bz - az * by) + y1 * (az * bx - ax * bz) + z1 * (ax * by - ay * bx) > 0;
				}
				else
				{
					facing = (aX - bX) * (cY - bY) - (cX - bX) * (aY - bY) > 0;
				}
				if (facing)
				{
					int distance = radius + (distances[v1] + distances[v2] + distances[v3]) / 3;
					// whole-unit rounding can put a face a unit past the model's reach: keep it in the buckets
					distance = Math.max(0, Math.min(diameter - 1, distance));
					distanceToFaces[distance][distanceFaceCount[distance]++] = i;
				}
			}
		}

		vertexBuffer.ensureCapacity(12 * faceCount);
		uvBuffer.ensureCapacity(12 * faceCount);

		int len = 0;
		if (faceRenderPriorities == null)
		{
			for (int i = diameter - 1; i >= 0; --i)
			{
				final int cnt = distanceFaceCount[i];
				if (cnt > 0)
				{
					final char[] faces = distanceToFaces[i];

					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx)
					{
						final int face = faces[faceIdx];
						len += pushFace(model, face, vertexBuffer, uvBuffer);
					}
				}
			}
		}
		else
		{
			Arrays.fill(numOfPriority, 0);
			Arrays.fill(lt10, 0);

			for (int i = diameter - 1; i >= 0; --i)
			{
				final int cnt = distanceFaceCount[i];
				if (cnt > 0)
				{
					final char[] faces = distanceToFaces[i];

					for (int faceIdx = 0; faceIdx < cnt; ++faceIdx)
					{
						final int face = faces[faceIdx];
						final byte pri = faceRenderPriorities[face];
						final int distIdx = numOfPriority[pri]++;

						orderedFaces[pri][distIdx] = face;
						if (pri < 10)
						{
							lt10[pri] += i;
						}
						else if (pri == 10)
						{
							eq10[distIdx] = i;
						}
						else
						{
							eq11[distIdx] = i;
						}
					}
				}
			}

			int avg12 = 0;
			if (numOfPriority[1] > 0 || numOfPriority[2] > 0)
			{
				avg12 = (lt10[1] + lt10[2]) / (numOfPriority[1] + numOfPriority[2]);
			}

			int avg34 = 0;
			if (numOfPriority[3] > 0 || numOfPriority[4] > 0)
			{
				avg34 = (lt10[3] + lt10[4]) / (numOfPriority[3] + numOfPriority[4]);
			}

			int avg68 = 0;
			if (numOfPriority[6] > 0 || numOfPriority[8] > 0)
			{
				avg68 = (lt10[8] + lt10[6]) / (numOfPriority[8] + numOfPriority[6]);
			}

			int drawnFaces = 0;
			int numDynFaces = numOfPriority[10];
			int[] dynFaces = orderedFaces[10];
			int[] dynFaceDistances = eq10;
			if (drawnFaces == numDynFaces)
			{
				drawnFaces = 0;
				numDynFaces = numOfPriority[11];
				dynFaces = orderedFaces[11];
				dynFaceDistances = eq11;
			}

			int currFaceDistance;
			if (drawnFaces < numDynFaces)
			{
				currFaceDistance = dynFaceDistances[drawnFaces];
			}
			else
			{
				currFaceDistance = -1000;
			}

			for (int pri = 0; pri < 10; ++pri)
			{
				while (pri == 0 && currFaceDistance > avg12)
				{
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, face, vertexBuffer, uvBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				while (pri == 3 && currFaceDistance > avg34)
				{
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, face, vertexBuffer, uvBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				while (pri == 5 && currFaceDistance > avg68)
				{
					final int face = dynFaces[drawnFaces++];
					len += pushFace(model, face, vertexBuffer, uvBuffer);

					if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
					{
						drawnFaces = 0;
						numDynFaces = numOfPriority[11];
						dynFaces = orderedFaces[11];
						dynFaceDistances = eq11;
					}

					if (drawnFaces < numDynFaces)
					{
						currFaceDistance = dynFaceDistances[drawnFaces];
					}
					else
					{
						currFaceDistance = -1000;
					}
				}

				final int priNum = numOfPriority[pri];
				final int[] priFaces = orderedFaces[pri];

				for (int faceIdx = 0; faceIdx < priNum; ++faceIdx)
				{
					final int face = priFaces[faceIdx];
					len += pushFace(model, face, vertexBuffer, uvBuffer);
				}
			}

			while (currFaceDistance != -1000)
			{
				final int face = dynFaces[drawnFaces++];
				len += pushFace(model, face, vertexBuffer, uvBuffer);

				if (drawnFaces == numDynFaces && dynFaces != orderedFaces[11])
				{
					drawnFaces = 0;
					dynFaces = orderedFaces[11];
					numDynFaces = numOfPriority[11];
					dynFaceDistances = eq11;
				}

				if (drawnFaces < numDynFaces)
				{
					currFaceDistance = dynFaceDistances[drawnFaces];
				}
				else
				{
					currFaceDistance = -1000;
				}
			}
		}

		return len;
	}

	private int pushFace(Model model, int face, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer)
	{
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] faceColors1 = model.getFaceColors1();
		final int[] faceColors2 = model.getFaceColors2();
		final int[] faceColors3 = model.getFaceColors3();

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		final byte[] faceRenderPriorities = model.getFaceRenderPriorities();
		final byte[] transparencies = model.getFaceTransparencies();

		// the compute-off path's colour word is what the geometry stage reads: alpha and colour (the face's bias rides
		// in its texture slot, uv.x's bits 9-16)
		final int packAlphaPriority = faceTransparency(model.getTransparency(), transparencies != null ? transparencies[face] & 0xff : 0) << 24;
		final int biasBits = bias(model.getFaceBias(), face) << 9;

		final int triangleA = indices1[face];
		final int triangleB = indices2[face];
		final int triangleC = indices3[face];

		int color1 = faceColors1[face];
		int color2 = faceColors2[face];
		int color3 = faceColors3[face];

		if (color3 == -1)
		{
			color2 = color3 = color1;
		}

		// HSL override is not applied to textured faces
		if (faceTextures == null || faceTextures[face] == -1)
		{
			if (overrideAmount > 0)
			{
				color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
				color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
				color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}
		}

		vertexBuffer.put(modelLocalX[triangleA], modelLocalY[triangleA], modelLocalZ[triangleA], packAlphaPriority | color1);
		vertexBuffer.put(modelLocalX[triangleB], modelLocalY[triangleB], modelLocalZ[triangleB], packAlphaPriority | color2);
		vertexBuffer.put(modelLocalX[triangleC], modelLocalY[triangleC], modelLocalZ[triangleC], packAlphaPriority | color3);

		if (faceTextures != null && faceTextures[face] != -1)
		{
			int texA, texB, texC;

			if (textureFaces != null && textureFaces[face] != -1)
			{
				int tfaceIdx = textureFaces[face] & 0xff;
				texA = texIndices1[tfaceIdx];
				texB = texIndices2[tfaceIdx];
				texC = texIndices3[tfaceIdx];
			}
			else
			{
				texA = triangleA;
				texB = triangleB;
				texC = triangleC;
			}

			int texture = faceTextures[face] + 1;
			uvBuffer.put(texture | biasBits, modelLocalX[texA], modelLocalY[texA], modelLocalZ[texA]);
			uvBuffer.put(texture | biasBits, modelLocalX[texB], modelLocalY[texB], modelLocalZ[texB]);
			uvBuffer.put(texture | biasBits, modelLocalX[texC], modelLocalY[texC], modelLocalZ[texC]);
		}
		else
		{
			uvBuffer.put(biasBits, 0, 0, 0);
			uvBuffer.put(biasBits, 0, 0, 0);
			uvBuffer.put(biasBits, 0, 0, 0);
		}

		return 3;
	}

	// a face's bias (Model.getFaceBias, a whole unsigned byte as RuneLite's own GPU plugin reads it), for the CPU's
	// depth key (until 11/18/2026 cut to 15, which sank things that lie on the ground)
	// the face's bias as its eight bits; the shaders sign-extend them: the CPU takes the byte signed (pa: the byte times 2
	// off the corner's depth), so a bias of -1 puts the face 2 units farther, not 510 nearer (11/19/2026, Jace's boots)
	private static int bias(byte[] bias, int face)
	{
		return bias == null || face >= bias.length ? 0 : bias[face] & 0xff;
	}

	private static int packAlphaPriority(byte modelTransparency, byte[] faceTransparencies, byte[] facePriorities, int face)
	{
		int alpha = faceTransparency(modelTransparency, faceTransparencies != null ? faceTransparencies[face] & 0xff : 0) << 24;
		int priority = 0;
		if (facePriorities != null)
		{
			priority = (facePriorities[face] & 0xff) << 16;
		}
		return alpha | priority;
	}

	/**
	 * A face's transparency with the model's own (Model.getTransparency, newer than the GPU plugin CPU Look is built
	 * on) folded in, as RuneLite 1.13.7's GPU plugin folds it (ModelUploader.faceTransparency): a model transparency of
	 * -1 makes every face fully see-through (255). 11/18/2026: an invisible NPC's box round a bloodwood tree's bucket.
	 */
	static int faceTransparency(byte modelTransparency, int faceTransparency)
	{
		if (modelTransparency == -1)
		{
			return 255;
		}
		// a face's transparency of 255 is drawn at 253, nearly invisible but not quite (the client's face draw, fo.pa,
		// 1.13.7: "cu[face] == -1 ? 253 : cu[face]"; 11/19/2026); only a whole model's -1 draws nothing
		if (faceTransparency == 255)
		{
			faceTransparency = 253;
		}
		int t = modelTransparency & 255;
		if (t > 0 && faceTransparency < 253)
		{
			return faceTransparency + ((253 - faceTransparency) * t >> 8);
		}
		return faceTransparency;
	}

	// a scene's colour override packed for pushModel (each a byte: amount, hue, saturation, lightness)
	static int sceneTint(Scene scene)
	{
		return (scene.getOverrideAmount() & 0xff) << 24 | (scene.getOverrideHue() & 0xff) << 16
			| (scene.getOverrideSaturation() & 0xff) << 8 | scene.getOverrideLuminance() & 0xff;
	}

	static int interpolateHSL(int hsl, byte hue2, byte sat2, byte lum2, byte lerp)
	{
		int hue = hsl >> 10 & 63;
		int sat = hsl >> 7 & 7;
		int lum = hsl & 127;
		int var9 = lerp & 255;
		if (hue2 != -1)
		{
			hue += var9 * (hue2 - hue) >> 7;
		}

		if (sat2 != -1)
		{
			sat += var9 * (sat2 - sat) >> 7;
		}

		if (lum2 != -1)
		{
			lum += var9 * (lum2 - lum) >> 7;
		}

		return (hue << 10 | sat << 7 | lum) & 65535;
	}

	// remove tiles from the scene that are outside the current region
	private void prepare(Scene scene)
	{
		if (scene.isInstance() || !gpuConfig.hideUnrelatedMaps())
		{
			return;
		}

		int baseX = scene.getBaseX() / 8;
		int baseY = scene.getBaseY() / 8;
		int centerX = baseX + 6;
		int centerY = baseY + 6;
		int centerId = regions.getRegionId(centerX, centerY);

		int r = Constants.EXTENDED_SCENE_SIZE / 16;
		for (int offx = -r; offx <= r; ++offx)
		{
			for (int offy = -r; offy <= r; ++offy)
			{
				int cx = centerX + offx;
				int cy = centerY + offy;
				int id = regions.getRegionId(cx, cy);
				if (id != centerId)
				{
					removeChunk(scene, cx, cy);
				}
			}
		}
	}

	private static void removeChunk(Scene scene, int cx, int cy)
	{
		int wx = cx * 8;
		int wy = cy * 8;
		int sx = wx - scene.getBaseX();
		int sy = wy - scene.getBaseY();
		int cmsx = sx + CpuLookPlugin.SCENE_OFFSET;
		int cmsy = sy + CpuLookPlugin.SCENE_OFFSET;
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int x = 0; x < 8; ++x)
		{
			for (int y = 0; y < 8; ++y)
			{
				int msx = cmsx + x;
				int msy = cmsy + y;
				if (msx >= 0 && msx < Constants.EXTENDED_SCENE_SIZE && msy >= 0 && msy < Constants.EXTENDED_SCENE_SIZE)
				{
					for (int z = 0; z < Constants.MAX_Z; ++z)
					{
						Tile tile = tiles[z][msx][msy];
						if (tile != null)
						{
							scene.removeTile(tile);
						}
					}
				}
			}
		}
	}
}
