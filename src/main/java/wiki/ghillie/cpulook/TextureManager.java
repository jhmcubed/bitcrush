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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;

@Singleton
@Slf4j
class TextureManager
{
	static final int TEXTURE_COUNT = 256;
	private static final int TEXTURE_SIZE = 128;

	/**
	 * Whether texels of 0 are drawn (black) in solid textures, as the CPU renderer draws them. Off: every
	 * texel of 0 is a hole, as RuneLite's GPU plugin has it.
	 */
	boolean solidTextures = true;

	/** What the last lookup found, for the log. */
	private String solidFound;

	int initTextureArray(TextureProvider textureProvider)
	{
		if (!allTexturesLoaded(textureProvider))
		{
			return -1;
		}

		Texture[] textures = textureProvider.getTextures();

		int textureArrayId = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D_ARRAY, textureArrayId);
		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			GL43C.glTexStorage3D(GL43C.GL_TEXTURE_2D_ARRAY, 8, GL43C.GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, textures.length);
		}
		else
		{
			int size = TEXTURE_SIZE;
			for (int i = 0; i < 8; i++)
			{
				GL43C.glTexImage3D(GL43C.GL_TEXTURE_2D_ARRAY, i, GL43C.GL_RGBA8, size, size, textures.length, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, 0);
				size /= 2;
			}
		}

		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D_ARRAY, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D_ARRAY, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);

		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D_ARRAY, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);

		// The CPU look: the textures at the client's own brightness, as the CPU renderer has them
		// (CpuLookPlugin rebuilds this array when the brightness changes)
		updateTextures(textureProvider, textureArrayId);

		GL43C.glActiveTexture(GL43C.GL_TEXTURE1);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D_ARRAY, textureArrayId);
		GL43C.glGenerateMipmap(GL43C.GL_TEXTURE_2D_ARRAY);
		GL43C.glActiveTexture(GL43C.GL_TEXTURE0);

		return textureArrayId;
	}

	void setAnisotropicFilteringLevel(int textureArrayId, int level)
	{
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D_ARRAY, textureArrayId);

		//level = 0 means no mipmaps and no anisotropic filtering
		if (level == 0)
		{
			GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D_ARRAY, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		}
		//level = 1 means with mipmaps but without anisotropic filtering GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT defaults to 1.0 which is off
		//level > 1 enables anisotropic filtering. It's up to the vendor what the values mean
		//Even if anisotropic filtering isn't supported, mipmaps will be enabled with any level >= 1
		else
		{
			// Set on GL_NEAREST_MIPMAP_LINEAR (bilinear filtering with mipmaps) since the pixel nature of the game means that nearest filtering
			// looks best for objects up close but allows linear filtering to resolve possible aliasing and noise with mipmaps from far away objects.
			GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D_ARRAY, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST_MIPMAP_LINEAR);
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic)
		{
			final float maxSamples = GL43C.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			//Clamp from 1 to max GL says it supports.
			final float anisoLevel = Math.max(1, Math.min(maxSamples, level));
			GL43C.glTexParameterf(GL43C.GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoLevel);
		}
	}

	void freeTextureArray(int textureArrayId)
	{
		GL43C.glDeleteTextures(textureArrayId);
	}

	/**
	 * Check if all textures have been loaded and cached yet.
	 *
	 * @param textureProvider
	 * @return
	 */
	private boolean allTexturesLoaded(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();
		if (textures == null || textures.length == 0)
		{
			return false;
		}

		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] pixels = textureProvider.load(textureId);
				if (pixels == null)
				{
					return false;
				}
			}
		}

		return true;
	}

	private void updateTextures(TextureProvider textureProvider, int textureArrayId)
	{
		Texture[] textures = textureProvider.getTextures();
		boolean[] solid = solidTextures ? solidFlags(textures) : new boolean[textures.length];

		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D_ARRAY, textureArrayId);

		int cnt = 0;
		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] srcPixels = textureProvider.load(textureId);
				if (srcPixels == null)
				{
					log.warn("No pixels for texture {}!", textureId);
					continue; // this can't happen
				}

				++cnt;

				if (srcPixels.length != TEXTURE_SIZE * TEXTURE_SIZE)
				{
					// The texture storage is 128x128 bytes, and will only work correctly with the
					// 128x128 textures from high detail mode
					log.warn("Texture size for {} is {}!", textureId, srcPixels.length);
					continue;
				}

				byte[] pixels = convertPixels(srcPixels, solid[textureId]);
				ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(pixels.length);
				pixelBuffer.put(pixels);
				pixelBuffer.flip();
				GL43C.glTexSubImage3D(GL43C.GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureId, TEXTURE_SIZE, TEXTURE_SIZE,
					1, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, pixelBuffer);
			}
		}

		log.debug("Uploaded textures {}", cnt);
	}

	/**
	 * A 128 x 128 texture as RGBA bytes. The CPU renderer skips a texel of 0 unless its texture is solid, when
	 * it draws it, black: alpha 0 marks a skipped texel for the fragment shader.
	 */
	static byte[] convertPixels(int[] srcPixels, boolean solid)
	{
		byte[] pixels = new byte[srcPixels.length * 4];
		int pixelIdx = 0;
		for (int rgb : srcPixels)
		{
			if (rgb != 0 || solid)
			{
				pixels[pixelIdx++] = (byte) (rgb >> 16);
				pixels[pixelIdx++] = (byte) (rgb >> 8);
				pixels[pixelIdx++] = (byte) rgb;
				pixels[pixelIdx++] = (byte) -1;
			}
			else
			{
				pixelIdx += 4;
			}
		}
		return pixels;
	}

	/**
	 * The CPU renderer's per-texture "solid" flag (the texture definition's first byte is 1): in a solid texture a
	 * texel of 0 is drawn black, in the others it leaves a hole. RuneLite's API has no such flag and the Plugin Hub
	 * allows no reflection, so the flags come from a list in the plugin, solid-textures.txt, read from Jace's game
	 * (11/18/2026) by the developer build's probe (src/test: SolidProbe, which also says when a game update makes the
	 * list out of date). A texture the list doesn't know counts as not solid: a hole, as RuneLite's GPU plugin has it.
	 */
	boolean[] solidFlags(Texture[] textures)
	{
		boolean[] list = solidList();
		boolean[] solid = new boolean[textures.length];
		int count = 0, total = 0;
		for (int id = 0; id < textures.length; id++)
		{
			if (textures[id] == null)
			{
				continue;
			}
			total++;
			solid[id] = id < list.length && list[id];
			count += solid[id] ? 1 : 0;
		}
		report(String.format("solid textures: %d of %d, from the built-in list", count, total));
		return solid;
	}

	private static boolean[] solidList;

	// solid-textures.txt: the ids of the solid textures, separated by commas, spaces or new lines; # starts a comment
	static synchronized boolean[] solidList()
	{
		if (solidList != null)
		{
			return solidList;
		}
		boolean[] list = new boolean[TEXTURE_COUNT];
		try (InputStream in = TextureManager.class.getResourceAsStream("solid-textures.txt"))
		{
			if (in != null)
			{
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null)
				{
					int hash = line.indexOf('#');
					for (String id : (hash >= 0 ? line.substring(0, hash) : line).split("[,\\s]+"))
					{
						if (!id.isEmpty())
						{
							int n = Integer.parseInt(id);
							if (n >= 0 && n < list.length)
							{
								list[n] = true;
							}
						}
					}
				}
			}
		}
		catch (IOException | NumberFormatException ex)
		{
			log.warn("CPU Look: couldn't read solid-textures.txt; every texel of 0 left a hole", ex);
			list = new boolean[TEXTURE_COUNT];
		}
		solidList = list;
		return list;
	}

	private void report(String found)
	{
		if (!found.equals(solidFound))
		{
			log.info("CPU Look: {}", found);
			solidFound = found;
		}
	}

	float[] computeTextureAnimations(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();

		if (textures.length > TEXTURE_COUNT)
		{
			log.warn("texture limit exceeded: {} > {}", textures.length, TEXTURE_COUNT);
		}

		float[] anims = new float[TEXTURE_COUNT * 2];
		for (int i = 0; i < Math.min(TEXTURE_COUNT, textures.length); ++i)
		{
			Texture texture = textures[i];
			if (texture == null)
			{
				continue;
			}

			float u = 0f, v = 0f;
			switch (texture.getAnimationDirection())
			{
				case 1:
					v = -1f;
					break;
				case 3:
					v = 1f;
					break;
				case 2:
					u = -1f;
					break;
				case 4:
					u = 1f;
					break;
			}

			int speed = texture.getAnimationSpeed();
			u *= speed;
			v *= speed;

			anims[i * 2] = u;
			anims[i * 2 + 1] = v;
		}
		return anims;
	}
}
