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
package wiki.ghillie.cpulook;

import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.IntProjection;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Projectile;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import wiki.ghillie.cpulook.config.UIScalingMode;
import wiki.ghillie.cpulook.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL10GL;
import org.lwjgl.opencl.CL12;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;

@PluginDescriptor(
	name = "Bitcrush",
	description = "The GPU plugin's draw distance and frame rate, with every pixel drawn the way the game's CPU renderer draws it",
	enabledByDefault = false,
	tags = {"gpu", "cpu", "renderer", "draw distance", "fps", "pixels", "retro", "cpu look"},
	conflicts = {"GPU", "117 HD"},
	loadInSafeMode = false
)
@Slf4j
public class CpuLookPlugin extends Plugin implements DrawCallbacks
{
	// This is the maximum number of triangles the compute shaders support
	static final int MAX_TRIANGLE = 6144;
	static final int SMALL_TRIANGLE_COUNT = 512;
	// smaller workgroups for smaller models (11/18/2026; OpenGL compute only): every model under 512 faces had a
	// workgroup of 512 threads, a clump of grass of a dozen faces too, most of them idle. The faces' order is the same
	// whatever the workgroup's size (ShaderCheck's order check runs every tier against the GPU plugin's count).
	static final int TINY_TRIANGLE_COUNT = 64;
	static final int MID_TRIANGLE_COUNT = 256;
	// larger models in workgroups nearer their size (11/18/2026 evening; OpenGL compute only): {threads, faces a thread},
	// each for models of up to threads x faces faces, past 512. Jace's timings zoomed out: the 798 models over 512 faces,
	// all in workgroups of 1,024 threads x 6 faces, took 5.0 of the compute pass's 5.4 ms. The rest stay in 1024 x 6.
	static final int[][] BIG_TIERS = {{512, 2}, {1024, 2}, {1024, 4}};
	private static final int FLAG_SCENE_BUFFER = Integer.MIN_VALUE;
	// the CPU look, "the ground hides what's under it": a record's ground (its corners marked 0x400000 by the compute
	// pass) and the models the ground never hides (players, NPCs, items, projectiles; marked 0x800000)
	private static final int FLAG_GROUND = 1 << 27;
	private static final int FLAG_FREE = 1 << 28;
	private static final int MARK_GROUND = 0x400000;
	private static final int MARK_FREE = 0x800000;
	// a ground tile, always (the CPU textures tiles with its own routine; the compute pass marks them itself)
	private static final int MARK_TILE = 0x100000;
	// the CPU look's integer camera maths stays inside an int out to here (claude/cpu-look.md)
	static final int MAX_DISTANCE = 128;
	// fog, as the GPU plugin's (11/18/2026): up to this many tiles in from the draw distance's edge
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int GROUND_MIN_Y = 350; // how far below the ground models extend

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private OpenCLManager openCLManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CpuLookConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	enum ComputeMode
	{
		NONE,
		OPENGL,
		OPENCL
	}

	private ComputeMode computeMode = ComputeMode.NONE;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private GLCapabilities glCapabilities;

	static final String LINUX_VERSION_HEADER =
		"#version 420\n" +
			"#extension GL_ARB_compute_shader : require\n" +
			"#extension GL_ARB_shader_storage_buffer_object : require\n" +
			"#extension GL_ARB_explicit_attrib_location : require\n";
	static final String WINDOWS_VERSION_HEADER = "#version 430\n";

	static final Shader PROGRAM = new Shader()
		.add(GL43C.GL_VERTEX_SHADER, "vert.glsl")
		.add(GL43C.GL_GEOMETRY_SHADER, "geom.glsl")
		.add(GL43C.GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader COMPUTE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "comp.glsl");

	static final Shader SMALL_COMPUTE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "comp.glsl");

	static final Shader TINY_COMPUTE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "comp.glsl");

	// the record path (stage A3, 11/18/2026; setup_comp.glsl says what): each face's triangles set up once in compute,
	// in the game's order, and drawn as boxes whose fragment stage reads the setup, not handed on by a geometry shader
	static final Shader SETUP_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "setup_comp.glsl");

	// the record path's write, a program of its own (11/18/2026 evening), so the count isn't held to its registers
	static final Shader SETUP_WRITE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "setup_write_comp.glsl");

	static final Shader SCAN_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "scan_comp.glsl");

	static final Shader RECORD_PROGRAM = new Shader()
		.add(GL43C.GL_VERTEX_SHADER, "vert_rec.glsl")
		.add(GL43C.GL_FRAGMENT_SHADER, "frag.glsl");

	// faces the record path takes in a frame: the scan's two levels of 4,096 and 2,048 (past it, the geometry stage)
	static final int MAX_RECORD_FACES = 4096 * 2048;
	// records a frame past one a face: the second triangles of faces cut by the near plane (grown if ever exceeded)
	static final int RECORD_EXTRA = 65536;

	static final Shader MID_COMPUTE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "comp.glsl");

	static final Shader UNORDERED_COMPUTE_PROGRAM = new Shader()
		.add(GL43C.GL_COMPUTE_SHADER, "comp_unordered.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL43C.GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL43C.GL_FRAGMENT_SHADER, "fragui.glsl");

	private int glProgram;
	private int glComputeProgram;
	private int glSmallComputeProgram;
	private int glTinyComputeProgram = -1;
	private int glSetupProgram = -1;
	private int glSetupWriteProgram = -1;
	// the fog's uniform locations (fogDepth, drawDistance, expandedChunks, fogColor), in the geometry stage's program
	// and the record path's; this frame's fog depth and Extended map loading, read once in the frame's drawing
	private final int[] uniFog = new int[4], uniRecFog = new int[4];
	private int fogDepth, fogChunks;
	private int glScanProgram = -1;
	private int glRecordProgram = -1;
	private int vaoRecords = -1;
	private int recordExtra = RECORD_EXTRA;
	private boolean recordWarned;
	// the overflow check's readback (11/18/2026, after Jace's first timings with stage A): the scan's totals copied each
	// frame into one of a few small buffers, each with its fence, and read once the card is past it. Nothing later
	// writes a copy being waited on, so reading it never waits. (Reading the totals in place, as the first build did,
	// waited for whatever later frames had queued that wrote them: the processor stalled on the card every other frame,
	// 11.0 ms frames became 14.4 zoomed out on his RTX 3070.)
	private static final int OVERFLOW_SLOTS = 4;
	private final int[] overflowCopies = new int[OVERFLOW_SLOTS];
	private final long[] overflowFences = new long[OVERFLOW_SLOTS];
	private int overflowSlot;
	private final IntBuffer overflowTotals = BufferUtils.createIntBuffer(4);
	private int uniSetupViewSize, uniSetupFaceCount, uniSetupTwoLists, uniSetupBlock, uniSetupWriteViewSize, uniSetupWriteBlock;
	private int uniScanPhase, uniScanFaceCount, uniScanBlockCount, uniScanCapacity;
	private int uniRecViewSize, uniRecListB, uniRecCpuFlags, uniRecGroundPass, uniRecGroundAllowance, uniRecPalette, uniRecTick,
		uniRecBlock, uniRecTextures, uniRecTextureAnimations;
	private int glMidComputeProgram = -1;
	private final int[] glBigComputePrograms = {-1, -1, -1};
	private final int[] uniBlockBig = new int[BIG_TIERS.length];
	private final GLBuffer[] tmpModelBufferBig = {new GLBuffer("model buffer 1024"), new GLBuffer("model buffer 2048"),
		new GLBuffer("model buffer 4096")};
	private final GpuIntBuffer[] modelBufferBig = new GpuIntBuffer[BIG_TIERS.length];
	private final int[] bigModels = new int[BIG_TIERS.length];
	// each tier's models and faces this frame, for the frame timings (FrameTimer.TIERS' order)
	private final int[] tierModels = new int[FrameTimer.TIERS.length], tierFaces = new int[FrameTimer.TIERS.length];
	private int glUnorderedComputeProgram;
	private int glUiProgram;

	private int vaoCompute;
	private int vaoTemp;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private int rboColorBuffer;

	private final GLBuffer sceneVertexBuffer = new GLBuffer("scene vertex buffer");
	private final GLBuffer sceneUvBuffer = new GLBuffer("scene tex buffer");
	private final GLBuffer tmpVertexBuffer = new GLBuffer("tmp vertex buffer");
	private final GLBuffer tmpUvBuffer = new GLBuffer("tmp tex buffer");
	private final GLBuffer tmpModelBufferLarge = new GLBuffer("model buffer large");
	private final GLBuffer tmpModelBufferSmall = new GLBuffer("model buffer small");
	private final GLBuffer tmpModelBufferTiny = new GLBuffer("model buffer tiny");
	private final GLBuffer recCounts = new GLBuffer("record counts");
	private final GLBuffer recBlocks = new GLBuffer("record blocks");
	private final GLBuffer recRecords = new GLBuffer("records");
	private final GLBuffer recCommands = new GLBuffer("record draws");
	private final GLBuffer recFaces = new GLBuffer("record faces");
	private final GLBuffer tmpModelBufferMid = new GLBuffer("model buffer mid");
	private final GLBuffer tmpModelBufferUnordered = new GLBuffer("model buffer unordered");
	private final GLBuffer tmpOutBuffer = new GLBuffer("out vertex buffer");
	private final GLBuffer tmpOutUvBuffer = new GLBuffer("out tex buffer");

	private int textureArrayId;
	private int tileHeightTex;

	private final GLBuffer uniformBuffer = new GLBuffer("uniform buffer");

	private GpuIntBuffer vertexBuffer;
	private GpuFloatBuffer uvBuffer;

	private GpuIntBuffer modelBufferUnordered;
	private GpuIntBuffer modelBufferSmall;
	private GpuIntBuffer modelBufferTiny;
	private GpuIntBuffer modelBufferMid;
	private GpuIntBuffer modelBuffer;

	private int unorderedModels;

	/**
	 * number of models in small buffer
	 */
	private int smallModels;
	private int tinyModels;
	private int midModels;

	/**
	 * number of models in large buffer
	 */
	private int largeModels;

	/**
	 * offset in the target buffer for model
	 */
	private int targetBufferOffset;

	/**
	 * offset into the temporary scene vertex buffer
	 */
	private int tempOffset;

	/**
	 * offset into the temporary scene uv buffer
	 */
	private int tempUvOffset;

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	// the CPU look: the game view drawn at the game's own size, then scaled up a whole game pixel at a time
	private int fboCpu = -1;
	private int texCpu = -1;
	private int rboCpuDepth = -1;
	// see-through faces exactly as the CPU (stage A4): each pixel's solid winner's place in the order (+ 1), kept by the
	// solid pass in a second target, read by the see-through pass, which draws to a framebuffer without it
	private int texWinner = -1;
	private int fboCpuSee = -1;
	private int uniSeeThroughPass, uniWinners, uniRecSeeThroughPass, uniRecWinners;
	private int lastCpuWidth = -1;
	private int lastCpuHeight = -1;
	// the CPU's palette (65536 colours at the client's brightness) as a 256 × 256 texture
	private int paletteTexture = -1;
	private double paletteBrightness = -1;
	// the client's sine and cosine tables, for the compute pass's turning of models
	private final GLBuffer sinCosBuffer = new GLBuffer("sin cos table");
	// the CPU's draw order (CpuOrder): what the client hands over in a frame, noted as it comes, re-sorted before it's drawn
	private final CpuOrder cpuOrder = new CpuOrder();
	// the same order, worked out faster (stage B's step 1, claude/cpu-look-stage-b.md); every 30th frame checked
	// against cpuOrder, entry for entry
	private final FastOrder fastOrder = new FastOrder();
	// the developer build's log of world views (Sailing's boats; WorldViewLog.java)
	private final WorldViewLog worldViewLog = new WorldViewLog();
	private int orderChecks;
	private boolean orderDiffWarned;
	private Object[] drawKeys = new Object[4096];
	private int[] drawX = new int[4096], drawZ = new int[4096], drawPos = new int[4096], drawLen = new int[4096], drawStart = new int[4096];
	private GpuIntBuffer[] drawBuf = new GpuIntBuffer[4096];
	private int draws;
	private final int[][][] visibleStamp = new int[4][Constants.EXTENDED_SCENE_SIZE][Constants.EXTENDED_SCENE_SIZE];
	private int frameStamp = 1;
	private boolean anyVisible;
	private boolean orderWarned;
	// this frame's switches, read once in drawScene
	private boolean groundHides;
	private boolean orderTest;
	private Scene frameScene;
	// the camera in whole units, this frame
	private int camXi, camYi, camZi;
	// the camera written to the log once a start, to compare what's handed over with the client's own fields
	private boolean cameraLogged;
	// Diagnostics, "Log frame timings": where a frame's time goes (FrameTimer)
	private final FrameTimer timer = new FrameTimer();
	private boolean timing;
	// the stretches of this frame's triangles that belong to models with see-through faces: the depth test's second
	// pass draws only these (vertex offsets and counts, in the order handed over), not every triangle again
	private int[] seeFirst = new int[256], seeCount = new int[256];
	private int seeRanges;
	private boolean drawnSeeThrough;
	// the hide switches (diagnostics), read once a frame: RuneLite's config proxy is slow to read for every one of the
	// tens of thousands of objects a frame hands over (11/18/2026)
	// the client's zoom and clip edges for isVisible, read at its first object each frame (they don't change mid-frame)
	private boolean clipRead;
	private int clipZoom, clipMidX2, clipNegMidX, clipNegMidY, clipMidY2;
	private IntBuffer seeFirstBuf, seeCountBuf;
	private int[] orderNext = new int[0];
	private boolean[] orderClaimed = new boolean[0];
	private long[] orderSort = new long[0];
	private final IdentityHashMap<Object, Integer> orderHead = new IdentityHashMap<>();
	// the draw-order diagnostic
	private final DrawOrderLog drawOrderLog = new DrawOrderLog();
	private volatile boolean logDrawOrderNext;

	private double cameraX, cameraY, cameraZ;
	private double cameraYaw, cameraPitch;

	private int viewportOffsetX;
	private int viewportOffsetY;
	private int viewportWidth;
	private int viewportHeight;

	// Uniforms
	private int uniUiColorBlindMode;
	private int uniViewSize;
	private int uniCpuFlags;
	private int uniGroundPass;
	private int uniAlphaPass;
	private int uniGroundAllowance;
	private int uniPalette;
	private int uniTex;
	private int uniTexSamplingMode;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniBlockSmall;
	private int uniBlockTiny;
	private int uniBlockMid;
	private int uniBlockLarge;
	private int uniBlockMain;
	private int uniTick;

	private boolean lwjglInitted = false;

	private int sceneId;
	private int nextSceneId;
	private GpuIntBuffer nextSceneVertexBuffer;
	private GpuFloatBuffer nextSceneTexBuffer;

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				fboScene = rboColorBuffer = -1;
				targetBufferOffset = 0;
				cameraLogged = false;
				unorderedModels = smallModels = largeModels = tinyModels = midModels = 0;
				Arrays.fill(bigModels, 0);

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				computeMode = config.useComputeShaders()
					? (OSType.getOSType() == OSType.MacOS ? ComputeMode.OPENCL : ComputeMode.OPENGL)
					: ComputeMode.NONE;

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", GL43C.glGetString(GL43C.GL_RENDERER));
				log.info("Using driver: {}", GL43C.glGetString(GL43C.GL_VERSION));

				if (!glCapabilities.OpenGL31)
				{
					throw new RuntimeException("OpenGL 3.1 is required but not available");
				}

				if (!glCapabilities.OpenGL43 && computeMode == ComputeMode.OPENGL)
				{
					log.info("disabling compute shaders because OpenGL 4.3 is not available");
					computeMode = ComputeMode.NONE;
				}

				if (computeMode == ComputeMode.NONE)
				{
					sceneUploader.initSortingBuffers();
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						//	GLDebugEvent[ id 0x20071
						//		type Warning: generic
						//		severity Unknown (0x826b)
						//		source GL API
						//		msg Buffer detailed info: Buffer object 11 (bound to GL_ARRAY_BUFFER_ARB, and GL_SHADER_STORAGE_BUFFER (4), usage hint is GL_STREAM_DRAW) will use VIDEO memory as the source for buffer object operations.
						GL43C.glDebugMessageControl(GL43C.GL_DEBUG_SOURCE_API, GL43C.GL_DEBUG_TYPE_OTHER,
							GL43C.GL_DONT_CARE, 0x20071, false);

						//	GLDebugMessageHandler: GLDebugEvent[ id 0x20052
						//		type Warning: implementation dependent performance
						//		severity Medium: Severe performance/deprecation/other warnings
						//		source GL API
						//		msg Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						GL43C.glDebugMessageControl(GL43C.GL_DEBUG_SOURCE_API, GL43C.GL_DEBUG_TYPE_PERFORMANCE,
							GL43C.GL_DONT_CARE, 0x20052, false);
					}
				}

				vertexBuffer = new GpuIntBuffer();
				uvBuffer = new GpuFloatBuffer();

				modelBufferUnordered = new GpuIntBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBufferTiny = new GpuIntBuffer();
				modelBufferMid = new GpuIntBuffer();
				for (int i = 0; i < BIG_TIERS.length; i++)
				{
					modelBufferBig[i] = new GpuIntBuffer();
				}
				modelBuffer = new GpuIntBuffer();

				setupSyncMode();

				initBuffers();
				initVao();
				try
				{
					initProgram();
				}
				catch (ShaderException ex)
				{
					throw new RuntimeException(ex);
				}
				initInterfaceTexture();
				initUniformBuffer();

				client.setDrawCallbacks(this);
				// no HILLSKEW: the client bends scenery to its slope itself (the game's contourGround, every one of
				// its ways), as it does for the CPU, instead of handing it over straight for the compute pass to bend
				client.setGpuFlags(DrawCallbacks.GPU
					| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
				);
				client.setExpandedMapLoading(config.expandedMapLoadingChunks());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastCpuWidth = lastCpuHeight = -1;
				paletteBrightness = -1;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					Scene scene = client.getScene();
					loadScene(scene);
					swapScene(scene);
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			sceneUploader.releaseSortingBuffers();

			if (lwjglInitted)
			{
				timer.reset();
				timing = false;

				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				if (tileHeightTex != 0)
				{
					GL43C.glDeleteTextures(tileHeightTex);
					tileHeightTex = 0;
				}

				destroyGlBuffer(uniformBuffer);

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownFbo();
			}

			// this must shutdown after the clgl buffers are freed
			openCLManager.cleanup();

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			glCapabilities = null;

			vertexBuffer = null;
			uvBuffer = null;

			modelBufferSmall = null;
			modelBufferTiny = null;
			modelBufferMid = null;
			Arrays.fill(modelBufferBig, null);
			modelBuffer = null;
			modelBufferUnordered = null;

			if (paletteTexture != -1)
			{
				GL43C.glDeleteTextures(paletteTexture);
				paletteTexture = -1;
			}
			paletteBrightness = -1;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	CpuLookConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CpuLookConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(CpuLookConfig.GROUP))
		{
			if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingChunks());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("logDrawOrder"))
			{
				logDrawOrderNext = Build.DEVELOPER && config.logDrawOrder();
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				// no HILLSKEW: the client bends scenery to its slope itself (the game's contourGround, every one of
				// its ways), as it does for the CPU, instead of handing it over straight for the compute pass to bend
				client.setGpuFlags(DrawCallbacks.GPU
					| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
				);
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		CpuLookConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: CpuLookConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	private Template createTemplate(int threadCount, int facesPerThread)
	{
		return createTemplate(threadCount, facesPerThread, false);
	}

	// records: the fragment stage for the record path (frag.glsl's RECORDS), which reads storage buffers (GLSL 4.30)
	private Template createTemplate(int threadCount, int facesPerThread, boolean records)
	{
		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		Template template = new Template();
		template.add(key ->
		{
			if ("version_header".equals(key))
			{
				return versionHeader;
			}
			if ("thread_config".equals(key))
			{
				return "#define THREAD_COUNT " + threadCount + "\n" +
					"#define FACES_PER_THREAD " + facesPerThread + "\n";
			}
			if ("texture_config".equals(key))
			{
				return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
			}
			if ("frag_header".equals(key))
			{
				return records ? versionHeader + "#define RECORDS\n" : "#version 400\n";
			}
			return null;
		});
		template.addInclude(CpuLookPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		Template template = createTemplate(-1, -1);
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);

		if (computeMode == ComputeMode.OPENGL)
		{
			glComputeProgram = COMPUTE_PROGRAM.compile(createTemplate(1024, 6));
			glSmallComputeProgram = SMALL_COMPUTE_PROGRAM.compile(createTemplate(512, 1));
			glTinyComputeProgram = TINY_COMPUTE_PROGRAM.compile(createTemplate(TINY_TRIANGLE_COUNT, 1));
			glMidComputeProgram = MID_COMPUTE_PROGRAM.compile(createTemplate(MID_TRIANGLE_COUNT, 1));
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				glBigComputePrograms[i] = COMPUTE_PROGRAM.compile(createTemplate(BIG_TIERS[i][0], BIG_TIERS[i][1]));
			}
			glUnorderedComputeProgram = UNORDERED_COMPUTE_PROGRAM.compile(template);
			try
			{
				glSetupProgram = SETUP_PROGRAM.compile(template);
				glSetupWriteProgram = SETUP_WRITE_PROGRAM.compile(template);
				glScanProgram = SCAN_PROGRAM.compile(template);
				glRecordProgram = RECORD_PROGRAM.compile(createTemplate(-1, -1, true));
			}
			catch (ShaderException ex)
			{
				// the geometry stage draws instead (the same pixels, slower)
				log.warn("CPU Look: the record path didn't compile; drawing by the geometry stage", ex);
				glSetupProgram = glSetupWriteProgram = glScanProgram = glRecordProgram = -1;
			}
		}
		else if (computeMode == ComputeMode.OPENCL)
		{
			openCLManager.init(awtContext);
		}

		initUniforms();
	}

	private void initUniforms()
	{
		uniViewSize = GL43C.glGetUniformLocation(glProgram, "viewSize");
		uniFog[0] = GL43C.glGetUniformLocation(glProgram, "fogDepth");
		uniFog[1] = GL43C.glGetUniformLocation(glProgram, "drawDistance");
		uniFog[2] = GL43C.glGetUniformLocation(glProgram, "expandedChunks");
		uniFog[3] = GL43C.glGetUniformLocation(glProgram, "fogColor");
		uniCpuFlags = GL43C.glGetUniformLocation(glProgram, "cpuFlags");
		uniGroundPass = GL43C.glGetUniformLocation(glProgram, "groundPass");
		uniAlphaPass = GL43C.glGetUniformLocation(glProgram, "alphaPass");
		uniGroundAllowance = GL43C.glGetUniformLocation(glProgram, "groundAllowance");
		uniPalette = GL43C.glGetUniformLocation(glProgram, "palette");
		uniTick = GL43C.glGetUniformLocation(glProgram, "tick");
		uniBlockMain = GL43C.glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = GL43C.glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = GL43C.glGetUniformLocation(glProgram, "textureAnimations");
		uniSeeThroughPass = GL43C.glGetUniformLocation(glProgram, "seeThroughPass");
		uniWinners = GL43C.glGetUniformLocation(glProgram, "winners");

		uniTex = GL43C.glGetUniformLocation(glUiProgram, "tex");
		uniTexSamplingMode = GL43C.glGetUniformLocation(glUiProgram, "samplingMode");
		uniTexTargetDimensions = GL43C.glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = GL43C.glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiColorBlindMode = GL43C.glGetUniformLocation(glUiProgram, "colorBlindMode");
		uniUiAlphaOverlay = GL43C.glGetUniformLocation(glUiProgram, "alphaOverlay");

		if (computeMode == ComputeMode.OPENGL)
		{
			uniBlockSmall = GL43C.glGetUniformBlockIndex(glSmallComputeProgram, "uniforms");
			uniBlockTiny = GL43C.glGetUniformBlockIndex(glTinyComputeProgram, "uniforms");
			uniBlockMid = GL43C.glGetUniformBlockIndex(glMidComputeProgram, "uniforms");
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				uniBlockBig[i] = GL43C.glGetUniformBlockIndex(glBigComputePrograms[i], "uniforms");
			}
			uniBlockLarge = GL43C.glGetUniformBlockIndex(glComputeProgram, "uniforms");
		}
		if (glRecordProgram != -1)
		{
			uniSetupViewSize = GL43C.glGetUniformLocation(glSetupProgram, "viewSize");
			uniSetupFaceCount = GL43C.glGetUniformLocation(glSetupProgram, "faceCount");
			uniSetupTwoLists = GL43C.glGetUniformLocation(glSetupProgram, "twoLists");
			uniSetupBlock = GL43C.glGetUniformBlockIndex(glSetupProgram, "uniforms");
			uniSetupWriteViewSize = GL43C.glGetUniformLocation(glSetupWriteProgram, "viewSize");
			uniSetupWriteBlock = GL43C.glGetUniformBlockIndex(glSetupWriteProgram, "uniforms");
			uniScanPhase = GL43C.glGetUniformLocation(glScanProgram, "phase");
			uniScanFaceCount = GL43C.glGetUniformLocation(glScanProgram, "faceCount");
			uniScanBlockCount = GL43C.glGetUniformLocation(glScanProgram, "blockCount");
			uniScanCapacity = GL43C.glGetUniformLocation(glScanProgram, "capacity");
			uniRecViewSize = GL43C.glGetUniformLocation(glRecordProgram, "viewSize");
			uniRecFog[0] = GL43C.glGetUniformLocation(glRecordProgram, "fogDepth");
			uniRecFog[1] = GL43C.glGetUniformLocation(glRecordProgram, "drawDistance");
			uniRecFog[2] = GL43C.glGetUniformLocation(glRecordProgram, "expandedChunks");
			uniRecFog[3] = GL43C.glGetUniformLocation(glRecordProgram, "fogColor");
			uniRecListB = GL43C.glGetUniformLocation(glRecordProgram, "listB");
			uniRecCpuFlags = GL43C.glGetUniformLocation(glRecordProgram, "cpuFlags");
			uniRecGroundPass = GL43C.glGetUniformLocation(glRecordProgram, "groundPass");
			uniRecGroundAllowance = GL43C.glGetUniformLocation(glRecordProgram, "groundAllowance");
			uniRecPalette = GL43C.glGetUniformLocation(glRecordProgram, "palette");
			uniRecTick = GL43C.glGetUniformLocation(glRecordProgram, "tick");
			uniRecBlock = GL43C.glGetUniformBlockIndex(glRecordProgram, "uniforms");
			uniRecTextures = GL43C.glGetUniformLocation(glRecordProgram, "textures");
			uniRecTextureAnimations = GL43C.glGetUniformLocation(glRecordProgram, "textureAnimations");
			uniRecSeeThroughPass = GL43C.glGetUniformLocation(glRecordProgram, "seeThroughPass");
			uniRecWinners = GL43C.glGetUniformLocation(glRecordProgram, "winners");
		}
	}

	private void shutdownProgram()
	{
		GL43C.glDeleteProgram(glProgram);
		glProgram = -1;

		GL43C.glDeleteProgram(glComputeProgram);
		glComputeProgram = -1;

		GL43C.glDeleteProgram(glSmallComputeProgram);
		glSmallComputeProgram = -1;

		for (int p : new int[]{glSetupProgram, glSetupWriteProgram, glScanProgram, glRecordProgram})
		{
			if (p != -1)
			{
				GL43C.glDeleteProgram(p);
			}
		}
		glSetupProgram = glSetupWriteProgram = glScanProgram = glRecordProgram = -1;

		if (glTinyComputeProgram != -1)
		{
			GL43C.glDeleteProgram(glTinyComputeProgram);
			glTinyComputeProgram = -1;
		}
		if (glMidComputeProgram != -1)
		{
			GL43C.glDeleteProgram(glMidComputeProgram);
			glMidComputeProgram = -1;
		}
		for (int i = 0; i < BIG_TIERS.length; i++)
		{
			if (glBigComputePrograms[i] != -1)
			{
				GL43C.glDeleteProgram(glBigComputePrograms[i]);
				glBigComputePrograms[i] = -1;
			}
		}

		GL43C.glDeleteProgram(glUnorderedComputeProgram);
		glUnorderedComputeProgram = -1;

		GL43C.glDeleteProgram(glUiProgram);
		glUiProgram = -1;
	}

	private void initVao()
	{
		// the record path's draws read everything from storage buffers: a vertex array with nothing in it
		vaoRecords = GL43C.glGenVertexArrays();

		// Create compute VAO
		vaoCompute = GL43C.glGenVertexArrays();
		GL43C.glBindVertexArray(vaoCompute);

		GL43C.glEnableVertexAttribArray(0);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpOutBuffer.glBufferId);
		GL43C.glVertexAttribPointer(0, 3, GL43C.GL_FLOAT, false, 16, 0);

		GL43C.glEnableVertexAttribArray(1);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpOutBuffer.glBufferId);
		GL43C.glVertexAttribIPointer(1, 1, GL43C.GL_INT, 16, 12);

		GL43C.glEnableVertexAttribArray(2);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpOutUvBuffer.glBufferId);
		GL43C.glVertexAttribPointer(2, 4, GL43C.GL_FLOAT, false, 0, 0);

		// Create temp VAO
		vaoTemp = GL43C.glGenVertexArrays();
		GL43C.glBindVertexArray(vaoTemp);

		GL43C.glEnableVertexAttribArray(0);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpVertexBuffer.glBufferId);
		GL43C.glVertexAttribPointer(0, 3, GL43C.GL_FLOAT, false, 16, 0);

		GL43C.glEnableVertexAttribArray(1);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpVertexBuffer.glBufferId);
		GL43C.glVertexAttribIPointer(1, 1, GL43C.GL_INT, 16, 12);

		GL43C.glEnableVertexAttribArray(2);
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, tmpUvBuffer.glBufferId);
		GL43C.glVertexAttribPointer(2, 4, GL43C.GL_FLOAT, false, 0, 0);

		// Create UI VAO
		vaoUiHandle = GL43C.glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = GL43C.glGenBuffers();
		GL43C.glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0.0f, 1.0f, 0f, // top right
			1f, -1f, 0.0f, 1.0f, 1f, // bottom right
			-1f, -1f, 0.0f, 0.0f, 1f, // bottom left
			-1f, 1f, 0.0f, 0.0f, 0f  // top left
		});
		vboUiBuf.rewind();
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, vboUiHandle);
		GL43C.glBufferData(GL43C.GL_ARRAY_BUFFER, vboUiBuf, GL43C.GL_STATIC_DRAW);

		// position attribute
		GL43C.glVertexAttribPointer(0, 3, GL43C.GL_FLOAT, false, 5 * Float.BYTES, 0);
		GL43C.glEnableVertexAttribArray(0);

		// texture coord attribute
		GL43C.glVertexAttribPointer(1, 2, GL43C.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		GL43C.glEnableVertexAttribArray(1);

		// unbind VBO
		GL43C.glBindBuffer(GL43C.GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		GL43C.glDeleteVertexArrays(vaoCompute);
		vaoCompute = -1;

		if (vaoRecords != -1)
		{
			GL43C.glDeleteVertexArrays(vaoRecords);
			vaoRecords = -1;
		}
		for (int s = 0; s < OVERFLOW_SLOTS; s++)
		{
			if (overflowFences[s] != 0)
			{
				GL43C.glDeleteSync(overflowFences[s]);
				overflowFences[s] = 0;
			}
			if (overflowCopies[s] != 0)
			{
				GL43C.glDeleteBuffers(overflowCopies[s]);
				overflowCopies[s] = 0;
			}
		}
		overflowSlot = 0;

		GL43C.glDeleteVertexArrays(vaoTemp);
		vaoTemp = -1;

		GL43C.glDeleteBuffers(vboUiHandle);
		vboUiHandle = -1;

		GL43C.glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = -1;
	}

	private void initBuffers()
	{
		initGlBuffer(sceneVertexBuffer);
		initGlBuffer(sceneUvBuffer);
		initGlBuffer(tmpVertexBuffer);
		initGlBuffer(tmpUvBuffer);
		initGlBuffer(tmpModelBufferLarge);
		initGlBuffer(tmpModelBufferSmall);
		initGlBuffer(tmpModelBufferTiny);
		initGlBuffer(recCounts);
		initGlBuffer(recBlocks);
		initGlBuffer(recRecords);
		initGlBuffer(recCommands);
		initGlBuffer(recFaces);
		for (GLBuffer b : tmpModelBufferBig)
		{
			initGlBuffer(b);
		}
		initGlBuffer(tmpModelBufferMid);
		initGlBuffer(tmpModelBufferUnordered);
		initGlBuffer(tmpOutBuffer);
		initGlBuffer(tmpOutUvBuffer);
		initGlBuffer(sinCosBuffer);

		// the client's float sines and cosines for an object's orientation (2,048 steps): models turned as the CPU turns them
		FloatBuffer sinCos = BufferUtils.createFloatBuffer(2048 * 2);
		for (int i = 0; i < 2048; i++)
		{
			sinCos.put(Perspective.SINEF[i]).put(Perspective.COSINEF[i]);
		}
		sinCos.flip();
		updateBuffer(sinCosBuffer, GL43C.GL_SHADER_STORAGE_BUFFER, sinCos, GL43C.GL_STATIC_DRAW, 0L);
		GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = GL43C.glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(sceneVertexBuffer);
		destroyGlBuffer(sceneUvBuffer);

		destroyGlBuffer(tmpVertexBuffer);
		destroyGlBuffer(tmpUvBuffer);
		destroyGlBuffer(tmpModelBufferLarge);
		destroyGlBuffer(tmpModelBufferSmall);
		destroyGlBuffer(tmpModelBufferTiny);
		destroyGlBuffer(recCounts);
		destroyGlBuffer(recBlocks);
		destroyGlBuffer(recRecords);
		destroyGlBuffer(recCommands);
		destroyGlBuffer(recFaces);
		for (GLBuffer b : tmpModelBufferBig)
		{
			destroyGlBuffer(b);
		}
		destroyGlBuffer(tmpModelBufferMid);
		destroyGlBuffer(tmpModelBufferUnordered);
		destroyGlBuffer(tmpOutBuffer);
		destroyGlBuffer(tmpOutUvBuffer);
		destroyGlBuffer(sinCosBuffer);
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			GL43C.glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;

		if (glBuffer.clBuffer != -1)
		{
			CL12.clReleaseMemObject(glBuffer.clBuffer);
			glBuffer.clBuffer = -1;
		}
	}

	private void initInterfaceTexture()
	{
		interfacePbo = GL43C.glGenBuffers();

		interfaceTexture = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, interfaceTexture);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_LINEAR);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_LINEAR);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		GL43C.glDeleteBuffers(interfacePbo);
		GL43C.glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initUniformBuffer()
	{
		initGlBuffer(uniformBuffer);

		updateBuffer(uniformBuffer, GL43C.GL_UNIFORM_BUFFER, 16 * Integer.BYTES, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		GL43C.glBindBuffer(GL43C.GL_UNIFORM_BUFFER, 0);
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			GL43C.glEnable(GL43C.GL_MULTISAMPLE);
		}
		else
		{
			GL43C.glDisable(GL43C.GL_MULTISAMPLE);
		}

		// Create and bind the FBO
		fboScene = GL43C.glGenFramebuffers();
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, fboScene);

		// Create color render buffer
		rboColorBuffer = GL43C.glGenRenderbuffers();
		GL43C.glBindRenderbuffer(GL43C.GL_RENDERBUFFER, rboColorBuffer);
		GL43C.glRenderbufferStorageMultisample(GL43C.GL_RENDERBUFFER, aaSamples, GL43C.GL_RGBA, width, height);
		GL43C.glFramebufferRenderbuffer(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_RENDERBUFFER, rboColorBuffer);

		int status = GL43C.glCheckFramebufferStatus(GL43C.GL_FRAMEBUFFER);
		if (status != GL43C.GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		// Reset
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		GL43C.glBindRenderbuffer(GL43C.GL_RENDERBUFFER, 0);
	}

	private void shutdownFbo()
	{
		if (fboScene != -1)
		{
			GL43C.glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != -1)
		{
			GL43C.glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = -1;
		}

		shutdownCpuTarget();
	}

	// what the ground never hides: players, NPCs, items on the ground, projectiles and spot animations
	private static boolean free(Renderable r)
	{
		return r instanceof Actor || r instanceof TileItem || r instanceof Projectile || r instanceof GraphicsObject;
	}

	// grass and the other ground decorations count as the ground: they hide the scenery behind them (as the CPU's
	// picture has the grass in front of a log over the log) and are always drawn themselves
	private boolean groundDecoration(Scene scene, Renderable r, int x, int z, long hash)
	{
		Scene sc = scene != null ? scene : frameScene;
		if (sc == null)
		{
			return false;
		}
		Tile[][][] tiles = sc.getExtendedTiles();
		int plane = (int) ((hash >> TileObject.HASH_PLANE_SHIFT) & 3);
		int ex = (x >> Perspective.LOCAL_COORD_BITS) + SCENE_OFFSET, ez = (z >> Perspective.LOCAL_COORD_BITS) + SCENE_OFFSET;
		if (tiles == null || plane >= tiles.length || ex < 0 || ez < 0 || ex >= tiles[plane].length || ez >= tiles[plane][ex].length)
		{
			return false;
		}
		Tile t = tiles[plane][ex][ez];
		GroundObject g = t != null ? t.getGroundObject() : null;
		return g != null && g.getRenderable() == r;
	}

	// what the ground pass makes of a model: the ground (grass), never hidden (players and the rest), or tested (0)
	private int markOf(Scene scene, Renderable r, int x, int z, long hash)
	{
		if (!groundHides)
		{
			return 0;
		}
		if (free(r))
		{
			return MARK_FREE;
		}
		return groundDecoration(scene, r, x, z, hash) ? MARK_GROUND : 0;
	}

	// without compute shaders: the ground's corners just pushed (and its texture's points) made relative to the camera,
	// as the compute pass makes every corner (whole units: exact in floats)
	private void relativeToCamera(int start)
	{
		IntBuffer vb = vertexBuffer.getBuffer();
		FloatBuffer ub = uvBuffer.getBuffer();
		int end = vb.position() >> 2;
		for (int v = Math.max(0, end - (targetBufferOffset - start)); v < end; v++)
		{
			int i = v * 4;
			vb.put(i, Float.floatToIntBits(Float.intBitsToFloat(vb.get(i)) - camXi));
			vb.put(i + 1, Float.floatToIntBits(Float.intBitsToFloat(vb.get(i + 1)) - camYi));
			vb.put(i + 2, Float.floatToIntBits(Float.intBitsToFloat(vb.get(i + 2)) - camZi));
			if (i + 3 < ub.position() && ub.get(i) != 0)
			{
				ub.put(i + 1, ub.get(i + 1) - camXi);
				ub.put(i + 2, ub.get(i + 2) - camYi);
				ub.put(i + 3, ub.get(i + 3) - camZi);
			}
		}
	}

	// without compute shaders: mark the corners just pushed (from start to targetBufferOffset), as the compute pass does
	private void mark(int start, int bit, boolean on)
	{
		if (!on)
		{
			return;
		}
		IntBuffer vb = vertexBuffer.getBuffer();
		int end = vb.position() >> 2;
		for (int v = Math.max(0, end - (targetBufferOffset - start)); v < end; v++)
		{
			int i = v * 4 + 3;
			vb.put(i, vb.get(i) | bit);
		}
	}

	// one draw as the client hands it over: what it is, where, and where its record or vertices sit
	private void noteDraw(Object key, int x, int z, GpuIntBuffer buf, int pos, int len, int start)
	{
		if (!orderTest)
		{
			return;
		}
		if (draws == drawKeys.length)
		{
			int m = draws * 2;
			drawKeys = Arrays.copyOf(drawKeys, m);
			drawX = Arrays.copyOf(drawX, m);
			drawZ = Arrays.copyOf(drawZ, m);
			drawPos = Arrays.copyOf(drawPos, m);
			drawLen = Arrays.copyOf(drawLen, m);
			drawStart = Arrays.copyOf(drawStart, m);
			drawBuf = Arrays.copyOf(drawBuf, m);
		}
		drawKeys[draws] = key;
		drawX[draws] = x;
		drawZ[draws] = z;
		drawBuf[draws] = buf;
		drawPos[draws] = pos;
		drawLen[draws] = len;
		drawStart[draws] = start;
		draws++;
	}

	// The frame re-sorted into the CPU renderer's order (CpuOrder). Each draw takes the first place in the CPU's order
	// for the same thing (at the same spot, if the same model is used twice); anything the CPU's order doesn't have
	// (a boat's world, say) stays right after whatever the client handed over before it.
	private void reorder()
	{
		final Scene scene = client.getTopLevelWorldView().getScene();
		if (scene == null)
		{
			return;
		}
		final boolean all = !anyVisible;
		final int now = frameStamp;
		final CpuOrder.Visible visible = (p, x, y) -> all || visibleStamp[p][x][y] == now;
		final int camX = (int) cameraX, camZ = (int) cameraZ, distance = getDrawDistance();
		final long orderStart = timing ? System.nanoTime() : 0;
		fastOrder.build(scene, camX, camZ, distance, visible);
		final boolean reference = fastOrder.overflowed;
		if (reference)
		{
			cpuOrder.build(scene, camX, camZ, distance, visible);
		}
		if (timing)
		{
			// what working out the CPU's order costs CPU Look itself (stage B's question: the zone design would need it)
			timer.order(System.nanoTime() - orderStart, reference ? cpuOrder.n : fastOrder.n, fastOrder.readNs,
				fastOrder.setupNs - fastOrder.readNs, fastOrder.sweepNs);
		}
		if (!reference && ++orderChecks % 30 == 0)
		{
			// the check: the reference walk on the same frame, entry for entry
			cpuOrder.build(scene, camX, camZ, distance, visible);
			boolean same = cpuOrder.n == fastOrder.n && cpuOrder.gaveUp == fastOrder.gaveUp;
			for (int k = 0; same && k < cpuOrder.n; k++)
			{
				same = cpuOrder.keys[k] == fastOrder.keys[k] && cpuOrder.kx[k] == fastOrder.kx[k] && cpuOrder.kz[k] == fastOrder.kz[k];
			}
			if (timing)
			{
				timer.orderChecked(same);
			}
			if (!same && !orderDiffWarned)
			{
				log.warn("CPU Look: the fast walk's order differed from the reference walk's ({} against {} entries)", fastOrder.n, cpuOrder.n);
				orderDiffWarned = true;
			}
		}
		final boolean gaveUp = reference ? cpuOrder.gaveUp : fastOrder.gaveUp;
		final Object[] orderKeys = reference ? cpuOrder.keys : fastOrder.keys;
		final int[] orderX = reference ? cpuOrder.kx : fastOrder.kx, orderZ = reference ? cpuOrder.kz : fastOrder.kz;
		if (gaveUp)
		{
			if (!orderWarned)
			{
				log.warn("the CPU's order took too long to work out; drawing the frame as handed over");
				orderWarned = true;
			}
			return;
		}

		final int n = reference ? cpuOrder.n : fastOrder.n;
		if (orderNext.length < n)
		{
			orderNext = new int[n * 2];
			orderClaimed = new boolean[n * 2];
		}
		if (orderSort.length < draws)
		{
			orderSort = new long[draws * 2];
		}
		final int[] next = orderNext;
		final boolean[] claimed = orderClaimed;
		final IdentityHashMap<Object, Integer> head = orderHead;
		head.clear();
		for (int k = n - 1; k >= 0; k--)
		{
			Integer h = head.put(orderKeys[k], k);
			next[k] = h != null ? h : -1;
			claimed[k] = false;
		}
		final long[] sort = orderSort;
		long prev = -1;
		for (int i = 0; i < draws; i++)
		{
			int e = -1, free = -1;
			Integer h = head.get(drawKeys[i]);
			for (int k = h != null ? h : -1; k >= 0; k = next[k])
			{
				if (claimed[k])
				{
					continue;
				}
				if (free < 0)
				{
					free = k;
				}
				if (orderX[k] == drawX[i] && orderZ[k] == drawZ[i])
				{
					e = k;
					break;
				}
			}
			if (e < 0)
			{
				e = free;
			}
			long key;
			if (e >= 0)
			{
				claimed[e] = true;
				key = (long) e << 20;
			}
			else
			{
				key = prev + 1;
			}
			prev = key;
			sort[i] = key << 20 | i;
		}
		Arrays.sort(sort, 0, draws);
		head.clear();

		if (computeMode != ComputeMode.NONE)
		{
			// each model's place in the output, in the new order
			int off = 0;
			for (int j = 0; j < draws; j++)
			{
				int i = (int) (sort[j] & 0xFFFFF);
				drawBuf[i].getBuffer().put(drawPos[i] + 3, off);
				off += drawLen[i];
			}
			return;
		}

		// without compute shaders the vertices themselves move
		final IntBuffer vb = vertexBuffer.getBuffer();
		final FloatBuffer ub = uvBuffer.getBuffer();
		final int total = targetBufferOffset * 4;
		if (vb.position() != total || ub.position() != total)
		{
			return;
		}
		final int[] v = new int[total];
		final float[] u = new float[total];
		for (int j = 0; j < total; j++)
		{
			v[j] = vb.get(j);
			u[j] = ub.get(j);
		}
		int w = 0;
		for (int q = 0; q < draws; q++)
		{
			int i = (int) (sort[q] & 0xFFFFF);
			for (int j = drawStart[i] * 4, end = (drawStart[i] + drawLen[i]) * 4; j < end; j++, w++)
			{
				vb.put(w, v[j]);
				ub.put(w, u[j]);
			}
		}
	}

	// the CPU look's own target: the game view, one texel a game pixel
	private void ensureCpuTarget(int width, int height)
	{
		if (fboCpu != -1 && width == lastCpuWidth && height == lastCpuHeight)
		{
			return;
		}
		shutdownCpuTarget();

		texCpu = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texCpu);
		GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA8, width, height, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, 0);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);

		fboCpu = GL43C.glGenFramebuffers();
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, fboCpu);
		GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, texCpu, 0);
		// a depth buffer, for the depth-buffer diagnostic only
		rboCpuDepth = GL43C.glGenRenderbuffers();
		GL43C.glBindRenderbuffer(GL43C.GL_RENDERBUFFER, rboCpuDepth);
		GL43C.glRenderbufferStorage(GL43C.GL_RENDERBUFFER, GL43C.GL_DEPTH_COMPONENT32F, width, height);
		GL43C.glFramebufferRenderbuffer(GL43C.GL_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT, GL43C.GL_RENDERBUFFER, rboCpuDepth);
		GL43C.glBindRenderbuffer(GL43C.GL_RENDERBUFFER, 0);
		// each pixel's solid winner (stage A4), an int a pixel, read by texelFetch only
		texWinner = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texWinner);
		GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_R32I, width, height, 0, GL43C.GL_RED_INTEGER, GL43C.GL_INT, 0);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
		GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT1, GL43C.GL_TEXTURE_2D, texWinner, 0);
		GL43C.glDrawBuffers(new int[]{GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_COLOR_ATTACHMENT1});
		int status = GL43C.glCheckFramebufferStatus(GL43C.GL_FRAMEBUFFER);
		if (status != GL43C.GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("CPU look FBO is incomplete. status: " + status);
		}
		// the see-through pass's: the same picture and depth, without the winners it reads
		fboCpuSee = GL43C.glGenFramebuffers();
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, fboCpuSee);
		GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_TEXTURE_2D, texCpu, 0);
		GL43C.glFramebufferRenderbuffer(GL43C.GL_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT, GL43C.GL_RENDERBUFFER, rboCpuDepth);
		status = GL43C.glCheckFramebufferStatus(GL43C.GL_FRAMEBUFFER);
		if (status != GL43C.GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("CPU look see-through FBO is incomplete. status: " + status);
		}
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		lastCpuWidth = width;
		lastCpuHeight = height;
	}

	private void shutdownCpuTarget()
	{
		if (fboCpu != -1)
		{
			GL43C.glDeleteFramebuffers(fboCpu);
			fboCpu = -1;
		}
		if (fboCpuSee != -1)
		{
			GL43C.glDeleteFramebuffers(fboCpuSee);
			fboCpuSee = -1;
		}
		if (texWinner != -1)
		{
			GL43C.glDeleteTextures(texWinner);
			texWinner = -1;
		}
		if (texCpu != -1)
		{
			GL43C.glDeleteTextures(texCpu);
			texCpu = -1;
		}
		if (rboCpuDepth != -1)
		{
			GL43C.glDeleteRenderbuffers(rboCpuDepth);
			rboCpuDepth = -1;
		}
		lastCpuWidth = lastCpuHeight = -1;
	}

	// The CPU's palette at the client's brightness, as a 256 × 256 texture (Palette)
	private void buildPalette(double brightness)
	{
		final int[] rgb = Palette.build(brightness);
		ByteBuffer pal = BufferUtils.createByteBuffer(65536 * 4);
		for (int c : rgb)
		{
			pal.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) -1);
		}
		pal.flip();

		if (paletteTexture == -1)
		{
			paletteTexture = GL43C.glGenTextures();
		}
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, paletteTexture);
		GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA8, 256, 256, 0, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, pal);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		final boolean timings = config.logTimings();
		if (timing && !timings)
		{
			timer.reset();
		}
		timing = timings;
		if (timing)
		{
			timer.sceneStart();
		}
		seeRanges = 0;
		clipRead = false;

		// The camera as the CPU renderer has it (claude/cpu-look.md, "Read from today's client", question 5): the
		// client's own whole-unit position and its angles in 16,384ths of a turn, read with the float tables RuneLite
		// keeps in step with the client's (Perspective.SINEF14). RuneLite's axes: getCameraX and getCameraY run along
		// the ground (Y north), getCameraZ is the height; CPU Look's are x, height, north, so Y and Z swap (11/18/2026:
		// read unswapped, the camera sat under the ground and the view was black). The client's whole units aren't the
		// handed-over position cut down (his log: 6130.73 and 7959.36 handed over, 6131 and 7960 in the client), so
		// they're the ones the CPU's arithmetic gets. Should they ever be far from the handed-over camera (not kept up
		// to date), the handed-over one is used instead; the same for the angles. Both written to the log once a start.
		final int fieldX = client.getCameraX(), fieldY = client.getCameraZ(), fieldZ = client.getCameraY();
		// (they differ from the handed-over camera by up to 5 units on a still camera in his log: the CPU's camera is
		// worked out its own way; only a camera far off, say not kept up to date, falls back)
		final boolean fieldsOff = Math.abs(fieldX - cameraX) > 64 || Math.abs(fieldY - cameraY) > 64 || Math.abs(fieldZ - cameraZ) > 64;
		final int camX = fieldsOff ? (int) Math.round(cameraX) : fieldX;
		final int camY = fieldsOff ? (int) Math.round(cameraY) : fieldY;
		final int camZ = fieldsOff ? (int) Math.round(cameraZ) : fieldZ;
		final int givenYaw = (int) Math.round(cameraYaw * 8192 / Math.PI) & 0x3fff;
		final int givenPitch = (int) Math.round(cameraPitch * 8192 / Math.PI) & 0x3fff;
		int yaw = client.getCameraYaw() & 0x3fff;
		int pitch = client.getCameraPitch() & 0x3fff;
		final boolean yawOff = Math.abs(((yaw - givenYaw + 8192) & 0x3fff) - 8192) > 16;
		final boolean pitchOff = Math.abs(((pitch - givenPitch + 8192) & 0x3fff) - 8192) > 16;
		if (!cameraLogged)
		{
			cameraLogged = true;
			log.info("CPU Look camera: handed over x {} height {} north {} yaw {} pitch {} (in 16,384ths); the client's x {} height {} north {} yaw {} pitch {}; using the {}",
				cameraX, cameraY, cameraZ, givenYaw, givenPitch, fieldX, fieldY, fieldZ, yaw, pitch,
				fieldsOff || yawOff || pitchOff ? "handed-over camera (the client's is off)" : "client's");
		}
		if (yawOff || pitchOff)
		{
			yaw = givenYaw;
			pitch = givenPitch;
		}

		if (logDrawOrderNext && client.getLocalPlayer() != null && client.getLocalPlayer().getLocalLocation() != null)
		{
			logDrawOrderNext = false;
			drawOrderLog.start(client.getTopLevelWorldView().getScene(), camX >> 7, camZ >> 7,
				client.getLocalPlayer().getLocalLocation().getX() >> 7, client.getLocalPlayer().getLocalLocation().getY() >> 7,
				client.getPlane());
		}

		camXi = camX;
		camYi = camY;
		camZi = camZ;
		if (Build.DEVELOPER)
		{
			worldViewLog.frame(client, camX, camY, camZ, yaw, pitch);
		}
		this.cameraX = camX;
		this.cameraY = camY;
		this.cameraZ = camZ;
		draws = 0;
		// (the developer's diagnostics: never on in the Plugin Hub's build, Build.java)
		groundHides = Build.DEVELOPER && config.groundHides() && !config.depthBuffer() && !config.cpuDepthTest();
		orderTest = Build.DEVELOPER && config.cpuOrderTest();
		frameScene = client.getTopLevelWorldView().getScene();
		this.cameraPitch = pitch * Perspective.UNIT14;
		this.cameraYaw = yaw * Perspective.UNIT14;
		viewportOffsetX = client.getViewportXOffset();
		viewportOffsetY = client.getViewportYOffset();
		viewportWidth = client.getViewportWidth();
		viewportHeight = client.getViewportHeight();

		final Scene scene = client.getScene();
		scene.setDrawDistance(getDrawDistance());

		// Only reset the target buffer offset right before drawing the scene. That way if there are frames
		// after this that don't involve a scene draw, like during LOADING/HOPPING/CONNECTION_LOST, we can
		// still redraw the previous frame's scene to emulate the client behavior of not painting over the
		// viewport buffer.
		targetBufferOffset = 0;

		// UBO.
		// We can reuse the vertex buffer since it isn't used yet.
		vertexBuffer.clear();
		vertexBuffer.ensureCapacity(48);
		IntBuffer uniformBuf = vertexBuffer.getBuffer();
		uniformBuf
			.put(Float.floatToIntBits((float) this.cameraYaw))
			.put(Float.floatToIntBits((float) this.cameraPitch))
			.put(client.getCenterX())
			.put(client.getCenterY())
			.put(client.getScale())
			.put(Float.floatToIntBits((float) camX))
			.put(Float.floatToIntBits((float) camY))
			.put(Float.floatToIntBits((float) camZ))
			// the CPU look: the camera in whole units, its angles' 65536ths (the model centre's depth, all int), and
			// its angles' float sines and cosines (every corner's turn)
			.put(camX).put(camY).put(camZ).put(0)
			.put(Perspective.SINE14[yaw]).put(Perspective.COSINE14[yaw])
			.put(Perspective.SINE14[pitch]).put(Perspective.COSINE14[pitch])
			.put(Float.floatToIntBits(Perspective.SINEF14[yaw])).put(Float.floatToIntBits(Perspective.COSINEF14[yaw]))
			.put(Float.floatToIntBits(Perspective.SINEF14[pitch])).put(Float.floatToIntBits(Perspective.COSINEF14[pitch]));
		uniformBuf.flip();
		sceneUploader.setCamera(camX, camY, camZ, yaw, pitch);

		updateBuffer(uniformBuffer, GL43C.GL_UNIFORM_BUFFER, uniformBuf, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		GL43C.glBindBuffer(GL43C.GL_UNIFORM_BUFFER, 0);

		GL43C.glBindBufferBase(GL43C.GL_UNIFORM_BUFFER, 0, uniformBuffer.glBufferId);
		uniformBuf.clear();

		checkGLErrors();
	}

	@Override
	public void postDrawScene()
	{
		if (!timing)
		{
			postDrawScene0();
			return;
		}
		timer.postStart();
		postDrawScene0();
		timer.postEnd();
	}

	private void postDrawScene0()
	{
		if (drawOrderLog.recording())
		{
			drawOrderLog.finish();
		}

		if (orderTest && draws > 1)
		{
			try
			{
				reorder();
			}
			catch (RuntimeException ex)
			{
				if (!orderWarned)
				{
					log.warn("couldn't re-sort the frame into the CPU's order; drawing it as handed over", ex);
					orderWarned = true;
				}
			}
		}
		draws = 0;
		frameStamp++;
		anyVisible = false;

		if (computeMode == ComputeMode.NONE)
		{
			// Upload buffers
			vertexBuffer.flip();
			uvBuffer.flip();

			IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
			FloatBuffer uvBuffer = this.uvBuffer.getBuffer();

			updateBuffer(tmpVertexBuffer, GL43C.GL_ARRAY_BUFFER, vertexBuffer, GL43C.GL_DYNAMIC_DRAW, 0L);
			updateBuffer(tmpUvBuffer, GL43C.GL_ARRAY_BUFFER, uvBuffer, GL43C.GL_DYNAMIC_DRAW, 0L);

			checkGLErrors();
			return;
		}

		// Upload buffers
		vertexBuffer.flip();
		uvBuffer.flip();
		modelBuffer.flip();
		modelBufferSmall.flip();
		modelBufferTiny.flip();
		modelBufferMid.flip();
		for (GpuIntBuffer b : modelBufferBig)
		{
			b.flip();
		}
		modelBufferUnordered.flip();

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();
		IntBuffer modelBuffer = this.modelBuffer.getBuffer();
		IntBuffer modelBufferSmall = this.modelBufferSmall.getBuffer();
		IntBuffer modelBufferTiny = this.modelBufferTiny.getBuffer();
		IntBuffer modelBufferMid = this.modelBufferMid.getBuffer();
		IntBuffer modelBufferUnordered = this.modelBufferUnordered.getBuffer();

		// temp buffers
		updateBuffer(tmpVertexBuffer, GL43C.GL_ARRAY_BUFFER, vertexBuffer, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		updateBuffer(tmpUvBuffer, GL43C.GL_ARRAY_BUFFER, uvBuffer, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);

		// model buffers
		updateBuffer(tmpModelBufferLarge, GL43C.GL_ARRAY_BUFFER, modelBuffer, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		updateBuffer(tmpModelBufferSmall, GL43C.GL_ARRAY_BUFFER, modelBufferSmall, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		if (computeMode == ComputeMode.OPENGL)
		{
			updateBuffer(tmpModelBufferTiny, GL43C.GL_ARRAY_BUFFER, modelBufferTiny, GL43C.GL_DYNAMIC_DRAW, 0L);
			updateBuffer(tmpModelBufferMid, GL43C.GL_ARRAY_BUFFER, modelBufferMid, GL43C.GL_DYNAMIC_DRAW, 0L);
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				updateBuffer(tmpModelBufferBig[i], GL43C.GL_ARRAY_BUFFER, this.modelBufferBig[i].getBuffer(), GL43C.GL_DYNAMIC_DRAW, 0L);
			}
		}
		updateBuffer(tmpModelBufferUnordered, GL43C.GL_ARRAY_BUFFER, modelBufferUnordered, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);

		// Output buffers
		updateBuffer(tmpOutBuffer,
			GL43C.GL_ARRAY_BUFFER,
			targetBufferOffset * 16, // each element is an ivec4, which is 16 bytes
			GL43C.GL_STREAM_DRAW,
			CL12.CL_MEM_WRITE_ONLY);
		updateBuffer(tmpOutUvBuffer,
			GL43C.GL_ARRAY_BUFFER,
			targetBufferOffset * 16, // each element is a vec4, which is 16 bytes
			GL43C.GL_STREAM_DRAW,
			CL12.CL_MEM_WRITE_ONLY);

		if (computeMode == ComputeMode.OPENCL)
		{
			// The docs for clEnqueueAcquireGLObjects say all pending GL operations must be completed before calling
			// clEnqueueAcquireGLObjects, and recommends calling glFinish() as the only portable way to do that.
			// However no issues have been observed from not calling it, and so will leave disabled for now.
			// GL43C.glFinish();

			openCLManager.compute(
				unorderedModels, smallModels, largeModels,
				sceneVertexBuffer, sceneUvBuffer,
				tmpVertexBuffer, tmpUvBuffer,
				tmpModelBufferUnordered, tmpModelBufferSmall, tmpModelBufferLarge,
				tmpOutBuffer, tmpOutUvBuffer,
				uniformBuffer);

			checkGLErrors();
			return;
		}

		/*
		 * Compute is split into three separate programs: 'unordered', 'small', and 'large'
		 * to save on GPU resources. Small will sort <= 512 faces, large will do <= 6144.
		 */

		// Bind UBO to compute programs
		GL43C.glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0);
		GL43C.glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0);

		if (timing)
		{
			tierModels[0] = unorderedModels;
			tierModels[1] = smallModels;
			tierModels[2] = tinyModels;
			tierModels[3] = midModels;
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				tierModels[4 + i] = bigModels[i];
			}
			tierModels[7] = largeModels;
			timer.tiers(tierModels, tierFaces);
			timer.mark(FrameTimer.COMPUTE_START);
		}

		// unordered
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 7, sinCosBuffer.glBufferId);

		GL43C.glUseProgram(glUnorderedComputeProgram);

		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferUnordered.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, sceneVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tmpVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, sceneUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBuffer.glBufferId);

		GL43C.glDispatchCompute(unorderedModels, 1, 1);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END);
		}

		// small
		GL43C.glUseProgram(glSmallComputeProgram);

		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferSmall.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, sceneVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tmpVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, sceneUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBuffer.glBufferId);

		GL43C.glDispatchCompute(smallModels, 1, 1);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END + 1);
		}

		// tiny and mid: the same program, smaller workgroups for smaller models
		dispatchTier(glTinyComputeProgram, uniBlockTiny, tmpModelBufferTiny, tinyModels);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END + 2);
		}
		dispatchTier(glMidComputeProgram, uniBlockMid, tmpModelBufferMid, midModels);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END + 3);
		}
		for (int i = 0; i < BIG_TIERS.length; i++)
		{
			dispatchTier(glBigComputePrograms[i], uniBlockBig[i], tmpModelBufferBig[i], bigModels[i]);
			if (timing)
			{
				timer.mark(FrameTimer.TIER_END + 4 + i);
			}
		}

		// large
		GL43C.glUseProgram(glComputeProgram);

		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferLarge.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, sceneVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tmpVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, sceneUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBuffer.glBufferId);

		GL43C.glDispatchCompute(largeModels, 1, 1);

		if (timing)
		{
			timer.mark(FrameTimer.COMPUTE_END);
		}

		checkGLErrors();
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
	{
		if (Build.DEVELOPER && scene != null && scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			worldViewLog.paint(scene);
		}
		final long t0 = timing ? System.nanoTime() : 0;
		drawScenePaint0(scene, paint, plane, tileX, tileY);
		if (timing)
		{
			timer.callback(t0);
		}
	}

	private void drawScenePaint0(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
	{
		if (drawOrderLog.recording())
		{
			drawOrderLog.ground(false, tileX, tileY, plane);
		}
		if (computeMode == ComputeMode.NONE)
		{
			int start = targetBufferOffset;
			targetBufferOffset += sceneUploader.upload(scene, paint,
				plane, tileX, tileY,
				vertexBuffer, uvBuffer,
				tileX << Perspective.LOCAL_COORD_BITS,
				tileY << Perspective.LOCAL_COORD_BITS,
				true
			);
			mark(start, MARK_TILE | (groundHides ? MARK_GROUND : 0), true);
			relativeToCamera(start);
			noteDraw(paint, tileX << Perspective.LOCAL_COORD_BITS, tileY << Perspective.LOCAL_COORD_BITS, null, -1, targetBufferOffset - start, start);
		}
		else if (paint.getBufferLen() > 0)
		{
			final int localX = tileX << Perspective.LOCAL_COORD_BITS;
			final int localY = 0;
			final int localZ = tileY << Perspective.LOCAL_COORD_BITS;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;
			tierFaces[0] += 2;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			int pos = buffer.position();
			buffer.put(paint.getBufferOffset());
			buffer.put(paint.getUvBufferOffset());
			buffer.put(2);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER | (groundHides ? FLAG_GROUND : 0));
			buffer.put(localX).put(localY).put(localZ);

			targetBufferOffset += 2 * 3;
			noteDraw(paint, localX, localZ, b, pos, 2 * 3, -1);
		}
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileY)
	{
		if (Build.DEVELOPER && scene != null && scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			worldViewLog.tileModel(scene);
		}
		final long t0 = timing ? System.nanoTime() : 0;
		drawSceneTileModel0(scene, model, tileX, tileY);
		if (timing)
		{
			timer.callback(t0);
		}
	}

	private void drawSceneTileModel0(Scene scene, SceneTileModel model, int tileX, int tileY)
	{
		if (drawOrderLog.recording())
		{
			drawOrderLog.ground(true, tileX, tileY, 0);
		}
		if (computeMode == ComputeMode.NONE)
		{
			int start = targetBufferOffset;
			targetBufferOffset += sceneUploader.upload(model,
				0, 0,
				vertexBuffer, uvBuffer,
				true);
			mark(start, MARK_TILE | (groundHides ? MARK_GROUND : 0), true);
			relativeToCamera(start);
			noteDraw(model, tileX << Perspective.LOCAL_COORD_BITS, tileY << Perspective.LOCAL_COORD_BITS, null, -1, targetBufferOffset - start, start);
		}
		else if (model.getBufferLen() > 0)
		{
			final int localX = tileX << Perspective.LOCAL_COORD_BITS;
			final int localY = 0;
			final int localZ = tileY << Perspective.LOCAL_COORD_BITS;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;
			tierFaces[0] += model.getBufferLen() / 3;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			int pos = buffer.position();
			buffer.put(model.getBufferOffset());
			buffer.put(model.getUvBufferOffset());
			buffer.put(model.getBufferLen() / 3);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER | (groundHides ? FLAG_GROUND : 0));
			buffer.put(localX).put(localY).put(localZ);

			targetBufferOffset += model.getBufferLen();
			noteDraw(model, localX, localZ, b, pos, model.getBufferLen(), -1);
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, interfacePbo);
			GL43C.glBufferData(GL43C.GL_PIXEL_UNPACK_BUFFER, canvasWidth * canvasHeight * 4L, GL43C.GL_STREAM_DRAW);
			GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, 0);

			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, interfaceTexture);
			GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_RGBA, canvasWidth, canvasHeight, 0, GL43C.GL_BGRA, GL43C.GL_UNSIGNED_BYTE, 0);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer interfaceBuf = GL43C.glMapBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, GL43C.GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			GL43C.glUnmapBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER);
		}
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, interfaceTexture);
		GL43C.glTexSubImage2D(GL43C.GL_TEXTURE_2D, 0, 0, 0, width, height, GL43C.GL_BGRA, GL43C.GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		GL43C.glBindBuffer(GL43C.GL_PIXEL_UNPACK_BUFFER, 0);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		if (!timing)
		{
			drawFrame(overlayColor);
			return;
		}
		timer.drawStart();
		final int triangles = targetBufferOffset / 3;
		drawFrame(overlayColor);
		timer.drawEnd(triangles);
	}

	private void drawFrame(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		prepareInterfaceTexture(canvasWidth, canvasHeight);
		if (timing)
		{
			timer.uploaded();
		}

		// The scene's frame buffer: the canvas (stretched, if it is). No anti-aliasing: the CPU has none.
		{
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight)
			{
				shutdownFbo();
				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, 0);
				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
			}

			GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboScene);
		}

		// Clear scene
		int sky = client.getSkyboxColor();
		GL43C.glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		GL43C.glClear(GL43C.GL_COLOR_BUFFER_BIT);

		// Draw 3d scene
		if (gameState.getState() >= GameState.LOADING.getState() && viewportWidth > 0 && viewportHeight > 0)
		{
			final TextureProvider textureProvider = client.getTextureProvider();

			// the palette and the textures at the client's own brightness, as the CPU has them
			final double brightness = textureProvider.getBrightness();
			if (brightness != paletteBrightness || textureManager.solidTextures != config.solidTextures())
			{
				buildPalette(brightness);
				paletteBrightness = brightness;
				textureManager.solidTextures = config.solidTextures();
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}
			}

			if (textureArrayId == -1)
			{
				// lazy init textures as they may not be loaded at plugin start.
				// this will return -1 and retry if not all textures are loaded yet, too.
				textureArrayId = textureManager.initTextureArray(textureProvider);
				if (textureArrayId > -1)
				{
					// if texture upload is successful, compute and set texture animations
					float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
					GL43C.glUseProgram(glProgram);
					GL43C.glUniform2fv(uniTextureAnimations, texAnims);
					if (glRecordProgram != -1)
					{
						GL43C.glUseProgram(glRecordProgram);
						GL43C.glUniform2fv(uniRecTextureAnimations, texAnims);
					}
					GL43C.glUseProgram(0);
				}
			}

			// the game view at the game's own size: one texel a game pixel
			ensureCpuTarget(viewportWidth, viewportHeight);
			GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboCpu);
			GL43C.glViewport(0, 0, viewportWidth, viewportHeight);
			GL43C.glClearDepth(0d);
			// the picture and depth cleared (the winners' int target apart: a float clear leaves an int one undefined)
			GL43C.glDrawBuffers(new int[]{GL43C.GL_COLOR_ATTACHMENT0});
			GL43C.glClear(GL43C.GL_COLOR_BUFFER_BIT | GL43C.GL_DEPTH_BUFFER_BIT);
			GL43C.glDrawBuffers(new int[]{GL43C.GL_COLOR_ATTACHMENT0, GL43C.GL_COLOR_ATTACHMENT1});
			GL43C.glClearBufferiv(GL43C.GL_COLOR, 1, new int[]{0, 0, 0, 0});
			if (timing)
			{
				timer.mark(FrameTimer.SCENE_START);
			}
			final boolean depthBuffer = Build.DEVELOPER && config.depthBuffer();
			final boolean plainTriangles = Build.DEVELOPER && config.plainTriangles();
			final boolean ground = groundHides && !depthBuffer;
			// the CPU's own depth test: each pixel's key, the greater kept (frag.glsl writes it as a depth)
			final boolean keyTest = config.cpuDepthTest() && !depthBuffer;
			if (keyTest)
			{
				GL43C.glEnable(GL43C.GL_DEPTH_TEST);
				GL43C.glDepthFunc(GL43C.GL_GREATER);
			}
			if (depthBuffer)
			{
				// the diagnostic: nearer (larger 1/z) wins, pixel by pixel
				GL43C.glEnable(GL43C.GL_DEPTH_TEST);
				GL43C.glDepthFunc(GL43C.GL_GREATER);
			}

			final int cpuFlags = (config.bricks() ? 1 : 0) | (config.bands() ? 2 : 0) | (config.textureSpans() ? 4 : 0)
				| (depthBuffer ? 8 : 0) | (plainTriangles ? 16 : 0) | (keyTest ? 32 : 0) | (Build.DEVELOPER && config.showBias() ? 64 : 0);
			// the record path (stage A3): for the CPU look as it's normally drawn; the diagnostics that draw otherwise (a
			// depth buffer, plain triangles, the old ground stopgap, the re-sort) keep the geometry stage
			final boolean records = computeMode == ComputeMode.OPENGL && glRecordProgram != -1 && config.recordPath() && !depthBuffer
				&& !ground && !plainTriangles && !orderTest && targetBufferOffset / 3 <= MAX_RECORD_FACES;
			GL43C.glUseProgram(glProgram);
			GL43C.glUniform2i(uniViewSize, viewportWidth, viewportHeight);
			fogDepth = Math.max(0, Math.min(MAX_FOG_DEPTH, config.fogDepth()));
			fogChunks = config.expandedMapLoadingChunks();
			setFog(uniFog, sky);
			GL43C.glUniform1i(uniCpuFlags, cpuFlags);
			GL43C.glUniform1i(uniGroundPass, 0);
			GL43C.glUniform1i(uniAlphaPass, 0);
			GL43C.glUniform1f(uniGroundAllowance, config.groundAllowance());
			if (gameState == GameState.LOGGED_IN)
			{
				// avoid textures animating during loading
				GL43C.glUniform1i(uniTick, client.getGameCycle() & 127);
			}

			// Bind uniforms
			GL43C.glUniformBlockBinding(glProgram, uniBlockMain, 0);
			GL43C.glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1
			GL43C.glUniform1i(uniPalette, 3);
			GL43C.glUniform1i(uniSeeThroughPass, 0);
			GL43C.glUniform1i(uniWinners, 4);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE3);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, paletteTexture);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE0);

			// Faces turned away are dropped in the geometry stage, on whole pixels, as the CPU drops them.
			// Enable blending for alpha
			GL43C.glEnable(GL43C.GL_BLEND);
			GL43C.glBlendFuncSeparate(GL43C.GL_SRC_ALPHA, GL43C.GL_ONE_MINUS_SRC_ALPHA, GL43C.GL_ONE, GL43C.GL_ONE);

			// Draw buffers
			if (computeMode != ComputeMode.NONE)
			{
				if (computeMode == ComputeMode.OPENGL)
				{
					// Before reading the SSBOs written to from postDrawScene() we must insert a barrier
					GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
				}
				else
				{
					// Wait for the command queue to finish, so that we know the compute is done
					openCLManager.finish();
				}

				// Draw using the output buffer of the compute
				GL43C.glBindVertexArray(vaoCompute);
			}
			else
			{
				// Only use the temporary buffers, which will contain the full scene
				GL43C.glBindVertexArray(vaoTemp);
			}

			if (records)
			{
				drawRecords(keyTest, cpuFlags, gameState == GameState.LOGGED_IN);
			}
			else
			{
			if (ground)
			{
				// the ground hides what's under it: first the ground alone, its depth only (nearer wins) ...
				GL43C.glEnable(GL43C.GL_DEPTH_TEST);
				GL43C.glDepthFunc(GL43C.GL_GREATER);
				GL43C.glColorMask(false, false, false, false);
				GL43C.glUniform1i(uniGroundPass, 1);
				GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, targetBufferOffset);
				// ... then everything in the game's order, scenery only where it isn't behind that ground; nothing
				// writes depth, so scenery still covers scenery by the order alone
				GL43C.glColorMask(true, true, true, true);
				GL43C.glDepthMask(false);
				GL43C.glDepthFunc(GL43C.GL_GEQUAL);
				GL43C.glUniform1i(uniGroundPass, 2);
			}

			if (keyTest)
			{
				// the CPU's depth test: solid faces first, in the game's order, testing and writing the key; then the
				// see-through ones, in the same order, tested against it but never writing it, as the CPU's are
				GL43C.glUniform1i(uniAlphaPass, 1);
				GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, targetBufferOffset);
				if (timing)
				{
					timer.mark(FrameTimer.AFTER_SOLID);
				}
				GL43C.glDepthMask(false);
				GL43C.glUniform1i(uniAlphaPass, 2);
				seeThroughTarget(true);
				GL43C.glUniform1i(uniSeeThroughPass, 1);
				if (orderTest)
				{
					// the re-sort diagnostic moved the triangles: every one again
					GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, targetBufferOffset);
				}
				else if (seeRanges > 0)
				{
					// only the models with see-through faces, in the order handed over (11/18/2026: going over all 1.5
					// million triangles a frame again cost about 5 frames a second zoomed out)
					if (seeFirstBuf == null || seeFirstBuf.capacity() < seeRanges)
					{
						seeFirstBuf = BufferUtils.createIntBuffer(Math.max(256, seeRanges * 2));
						seeCountBuf = BufferUtils.createIntBuffer(Math.max(256, seeRanges * 2));
					}
					seeFirstBuf.clear();
					seeCountBuf.clear();
					seeFirstBuf.put(seeFirst, 0, seeRanges).flip();
					seeCountBuf.put(seeCount, 0, seeRanges).flip();
					GL43C.glMultiDrawArrays(GL43C.GL_TRIANGLES, seeFirstBuf, seeCountBuf);
				}
				GL43C.glUniform1i(uniAlphaPass, 0);
				GL43C.glUniform1i(uniSeeThroughPass, 0);
				seeThroughTarget(false);
			}
			else
			{
				GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, targetBufferOffset);
				if (timing)
				{
					timer.mark(FrameTimer.AFTER_SOLID);
				}
			}
			}

			if (timing)
			{
				timer.mark(FrameTimer.SCENE_END);
				timer.sceneDone();
			}
			GL43C.glDepthMask(true);
			GL43C.glDisable(GL43C.GL_BLEND);
			GL43C.glDisable(GL43C.GL_DEPTH_TEST);
			GL43C.glUseProgram(0);

			// into the frame where the game view sits, each game pixel a whole block (nearest), stretched or not
			int x0 = viewportOffsetX, x1 = viewportOffsetX + viewportWidth;
			int y0 = viewportOffsetY, y1 = viewportOffsetY + viewportHeight;
			int frameHeight = canvasHeight;
			if (client.isStretchedEnabled())
			{
				final Dimension dim = client.getStretchedDimensions();
				final double sx = dim.getWidth() / canvasWidth, sy = dim.getHeight() / canvasHeight;
				x0 = (int) Math.round(x0 * sx);
				x1 = (int) Math.round(x1 * sx);
				y0 = (int) Math.round(y0 * sy);
				y1 = (int) Math.round(y1 * sy);
				frameHeight = dim.height;
			}
			final AffineTransform t = clientUI.getGraphicsConfiguration().getDefaultTransform();
			GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fboCpu);
			GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboScene);
			GL43C.glBlitFramebuffer(0, 0, viewportWidth, viewportHeight,
				getScaledValue(t.getScaleX(), x0), getScaledValue(t.getScaleY(), frameHeight - y1),
				getScaledValue(t.getScaleX(), x1), getScaledValue(t.getScaleY(), frameHeight - y0),
				GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);
		}

		// Blit FBO
		{
			int width = lastStretchedCanvasWidth;
			int height = lastStretchedCanvasHeight;

			final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
			final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

			width = getScaledValue(transform.getScaleX(), width);
			height = getScaledValue(transform.getScaleY(), height);

			GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, fboScene);
			GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
			GL43C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
				GL43C.GL_COLOR_BUFFER_BIT, GL43C.GL_NEAREST);

			// Reset
			GL43C.glBindFramebuffer(GL43C.GL_READ_FRAMEBUFFER, awtContext.getFramebuffer(false));
		}

		vertexBuffer.clear();
		uvBuffer.clear();
		modelBuffer.clear();
		modelBufferSmall.clear();
		modelBufferTiny.clear();
		modelBufferMid.clear();
		for (GpuIntBuffer b : modelBufferBig)
		{
			b.clear();
		}
		modelBufferUnordered.clear();

		smallModels = largeModels = unorderedModels = tinyModels = midModels = 0;
		Arrays.fill(bigModels, 0);
		Arrays.fill(tierFaces, 0);
		tempOffset = 0;
		tempUvOffset = 0;

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		try
		{
			final long swapStart = System.nanoTime();
			awtContext.swapBuffers();
			if (timing)
			{
				timer.swapped(swapStart);
			}
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		GL43C.glEnable(GL43C.GL_BLEND);
		GL43C.glBlendFunc(GL43C.GL_ONE, GL43C.GL_ONE_MINUS_SRC_ALPHA);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		GL43C.glUseProgram(glUiProgram);
		GL43C.glUniform1i(uniTex, 0);
		GL43C.glUniform1i(uniTexSamplingMode, uiScalingMode.getMode());
		GL43C.glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		GL43C.glUniform1i(uniUiColorBlindMode, 0);
		GL43C.glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			GL43C.glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			GL43C.glUniform2i(uniTexTargetDimensions, canvasWidth, canvasHeight);
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR ? GL43C.GL_LINEAR : GL43C.GL_NEAREST;
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, function);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		GL43C.glBindVertexArray(vaoUiHandle);
		GL43C.glDrawArrays(GL43C.GL_TRIANGLE_FAN, 0, 4);

		// Reset
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
		GL43C.glBindVertexArray(0);
		GL43C.glUseProgram(0);
		GL43C.glBlendFunc(GL43C.GL_SRC_ALPHA, GL43C.GL_ONE_MINUS_SRC_ALPHA);
		GL43C.glDisable(GL43C.GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		GL43C.glReadBuffer(awtContext.getBufferMode());
		GL43C.glReadPixels(0, 0, width, height, GL43C.GL_RGBA, GL43C.GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		// texture animation happens on gpu
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (timing)
		{
			timer.gameTick();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Avoid drawing the last frame's buffer during LOADING after LOGIN_SCREEN
			targetBufferOffset = 0;
		}
		if (gameStateChanged.getGameState() == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
		}
	}

	@Override
	public void loadScene(Scene scene)
	{
		if (computeMode == ComputeMode.NONE)
		{
			return;
		}

		GpuIntBuffer vertexBuffer = new GpuIntBuffer();
		GpuFloatBuffer uvBuffer = new GpuFloatBuffer();

		sceneUploader.upload(scene, vertexBuffer, uvBuffer);

		vertexBuffer.flip();
		uvBuffer.flip();

		nextSceneVertexBuffer = vertexBuffer;
		nextSceneTexBuffer = uvBuffer;
		nextSceneId = sceneUploader.sceneId;
	}

	private void uploadTileHeights(Scene scene)
	{
		if (tileHeightTex != 0)
		{
			GL43C.glDeleteTextures(tileHeightTex);
			tileHeightTex = 0;
		}

		final int TILEHEIGHT_BUFFER_SIZE = Constants.MAX_Z * Constants.EXTENDED_SCENE_SIZE * Constants.EXTENDED_SCENE_SIZE * Short.BYTES;
		ShortBuffer tileBuffer = ByteBuffer
			.allocateDirect(TILEHEIGHT_BUFFER_SIZE)
			.order(ByteOrder.nativeOrder())
			.asShortBuffer();

		int[][][] tileHeights = scene.getTileHeights();
		for (int z = 0; z < Constants.MAX_Z; ++z)
		{
			for (int y = 0; y < Constants.EXTENDED_SCENE_SIZE; ++y)
			{
				for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x)
				{
					int h = tileHeights[z][x][y];
					assert (h & 0b111) == 0;
					h >>= 3;
					tileBuffer.put((short) h);
				}
			}
		}
		tileBuffer.flip();

		tileHeightTex = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, tileHeightTex);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_3D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_3D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_3D, GL43C.GL_TEXTURE_WRAP_S, GL43C.GL_CLAMP_TO_EDGE);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_3D, GL43C.GL_TEXTURE_WRAP_T, GL43C.GL_CLAMP_TO_EDGE);
		GL43C.glTexImage3D(GL43C.GL_TEXTURE_3D, 0, GL43C.GL_R16I,
			Constants.EXTENDED_SCENE_SIZE, Constants.EXTENDED_SCENE_SIZE, Constants.MAX_Z,
			0, GL43C.GL_RED_INTEGER, GL43C.GL_SHORT, tileBuffer);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, 0);

		// bind to texture 2
		GL43C.glActiveTexture(GL43C.GL_TEXTURE2);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_3D, tileHeightTex); // binding = 2 in the shader
		GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (Build.DEVELOPER)
		{
			worldViewLog.loaded(worldView, scene);
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		if (Build.DEVELOPER)
		{
			worldViewLog.despawned(worldView);
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (computeMode == ComputeMode.NONE)
		{
			return;
		}

		if (computeMode == ComputeMode.OPENCL)
		{
			openCLManager.uploadTileHeights(scene);
		}
		else
		{
			assert computeMode == ComputeMode.OPENGL;
			uploadTileHeights(scene);
		}

		sceneId = nextSceneId;
		updateBuffer(sceneVertexBuffer, GL43C.GL_ARRAY_BUFFER, nextSceneVertexBuffer.getBuffer(), GL43C.GL_STATIC_COPY, CL12.CL_MEM_READ_ONLY);
		updateBuffer(sceneUvBuffer, GL43C.GL_ARRAY_BUFFER, nextSceneTexBuffer.getBuffer(), GL43C.GL_STATIC_COPY, CL12.CL_MEM_READ_ONLY);

		nextSceneVertexBuffer = null;
		nextSceneTexBuffer = null;
		nextSceneId = -1;

		checkGLErrors();
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		boolean in = inFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
		if (in && plane >= 0 && plane < 4 && msx >= 0 && msy >= 0 && msx < Constants.EXTENDED_SCENE_SIZE && msy < Constants.EXTENDED_SCENE_SIZE)
		{
			// the tiles in view, for the CPU's order
			visibleStamp[plane][msx][msy] = frameStamp;
			anyVisible = true;
		}
		return in;
	}

	private boolean inFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		int[][][] tileHeights = scene.getTileHeights();
		int x = ((msx - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64 - cameraX;
		int z = ((msy - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + 64 - cameraZ;
		int y = Math.max(
			Math.max(tileHeights[plane][msx][msy], tileHeights[plane][msx][msy + 1]),
			Math.max(tileHeights[plane][msx + 1][msy], tileHeights[plane][msx + 1][msy + 1])
		) + GROUND_MIN_Y - cameraY;

		int radius = 96; // ~ 64 * sqrt(2)

		int zoom = client.get3dZoom();
		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();

		float var11 = yawCos * z - yawSin * x;
		float var12 = pitchSin * y + pitchCos * var11;
		float var13 = pitchCos * radius;
		float depth = var12 + var13;
		if (depth > 50)
		{
			float rx = z * yawSin + yawCos * x;
			float var16 = (rx - radius) * zoom;
			float var17 = (rx + radius) * zoom;
			// left && right
			if (var16 < Rasterizer3D_clipMidX2 * depth && var17 > Rasterizer3D_clipNegativeMidX * depth)
			{
				float ry = pitchCos * y - var11 * pitchSin;
				float ybottom = pitchSin * radius;
				float var20 = (ry + ybottom) * zoom;
				// top
				if (var20 > Rasterizer3D_clipNegativeMidY * depth)
				{
					// we don't test the bottom so we don't have to find the height of all the models on the tile
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isVisible(Model model, float pitchSin, float pitchCos, float yawSin, float yawCos, int x, int y, int z)
	{
		final int xzMag = model.getXYZMag();
		final int bottomY = model.getBottomY();
		if (!clipRead)
		{
			clipZoom = client.get3dZoom();
			clipMidX2 = client.getRasterizer3D_clipMidX2();
			clipNegMidX = client.getRasterizer3D_clipNegativeMidX();
			clipNegMidY = client.getRasterizer3D_clipNegativeMidY();
			clipMidY2 = client.getRasterizer3D_clipMidY2();
			clipRead = true;
		}
		final int zoom = clipZoom;
		final int modelHeight = model.getModelHeight();

		int Rasterizer3D_clipMidX2 = clipMidX2; // width / 2
		int Rasterizer3D_clipNegativeMidX = clipNegMidX; // -width / 2
		int Rasterizer3D_clipNegativeMidY = clipNegMidY; // -height / 2
		int Rasterizer3D_clipMidY2 = clipMidY2; // height / 2

		float var11 = yawCos * z - yawSin * x;
		float var12 = pitchSin * y + pitchCos * var11;
		float var13 = pitchCos * xzMag;
		float depth = var12 + var13;
		if (depth > 50)
		{
			float rx = z * yawSin + yawCos * x;
			float var16 = (rx - xzMag) * zoom;
			if (var16 / depth < Rasterizer3D_clipMidX2)
			{
				float var17 = (rx + xzMag) * zoom;
				if (var17 / depth > Rasterizer3D_clipNegativeMidX)
				{
					float ry = pitchCos * y - var11 * pitchSin;
					float yheight = pitchSin * xzMag;
					float ybottom = (pitchCos * bottomY) + yheight; // use bottom height instead of y pos for height
					float var20 = (ry + ybottom) * zoom;
					if (var20 / depth > Rasterizer3D_clipNegativeMidY)
					{
						float ytop = (pitchCos * modelHeight) + yheight;
						float var22 = (ry - ytop) * zoom;
						return var22 / depth < Rasterizer3D_clipMidY2;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Draw a renderable in the scene
	 */
	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (Build.DEVELOPER)
		{
			worldViewLog.draw(scene, projection, renderable, x, y, z);
		}
		final long t0 = timing ? System.nanoTime() : 0;
		final int before = targetBufferOffset;
		drawnSeeThrough = false;
		drawRenderable(projection, scene, renderable, orientation, x, y, z, hash);
		if (drawnSeeThrough && targetBufferOffset > before)
		{
			seeRange(before, targetBufferOffset - before);
		}
		if (timing)
		{
			timer.callback(t0);
		}
	}

	// a stretch of see-through faces' model, for the depth test's second pass (joined to the last when they touch)
	private void seeRange(int first, int count)
	{
		if (seeRanges > 0 && seeFirst[seeRanges - 1] + seeCount[seeRanges - 1] == first)
		{
			seeCount[seeRanges - 1] += count;
			return;
		}
		if (seeRanges == seeFirst.length)
		{
			seeFirst = Arrays.copyOf(seeFirst, seeRanges * 2);
			seeCount = Arrays.copyOf(seeCount, seeRanges * 2);
		}
		seeFirst[seeRanges] = first;
		seeCount[seeRanges] = count;
		seeRanges++;
	}

	private void drawRenderable(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (drawOrderLog.recording())
		{
			drawOrderLog.draw(scene, renderable, x, y, z, hash);
		}

		Model model, offsetModel;
		if (renderable instanceof Model)
		{
			model = (Model) renderable;
			offsetModel = model.getUnskewedModel();
			if (offsetModel == null)
			{
				offsetModel = model;
			}
		}
		else
		{
			model = renderable.getModel();
			if (model == null)
			{
				return;
			}
			offsetModel = model;
		}
		drawnSeeThrough = model.getFaceTransparencies() != null || model.getTransparency() != 0;

		if (computeMode == ComputeMode.NONE)
		{
			// Apply height to renderable from the model
			if (model != renderable)
			{
				renderable.setModelHeight(model.getModelHeight());
			}

			model.calculateBoundsCylinder();

			if (projection instanceof IntProjection)
			{
				IntProjection p = (IntProjection) projection;
				if (!isVisible(model, p.getPitchSin(), p.getPitchCos(), p.getYawSin(), p.getYawCos(), x - p.getCameraX(), y - p.getCameraY(), z - p.getCameraZ()))
				{
					if (drawOrderLog.recording())
					{
						drawOrderLog.skipped();
					}
					return;
				}
			}

			client.checkClickbox(projection, model, orientation, x, y, z, hash);

			int start = targetBufferOffset;
			targetBufferOffset += sceneUploader.pushSortedModel(
				projection,
				model, orientation,
				x, y, z,
				vertexBuffer, uvBuffer);
			int m = markOf(scene, renderable, x, z, hash);
			mark(start, m, m != 0);
			noteDraw(renderable, x, z, null, -1, targetBufferOffset - start, start);
		}
		// Model may be in the scene buffer
		else if (offsetModel.getSceneId() == sceneId)
		{
			assert model == renderable;

			model.calculateBoundsCylinder();

			if (projection instanceof IntProjection)
			{
				IntProjection p = (IntProjection) projection;
				if (!isVisible(model, p.getPitchSin(), p.getPitchCos(), p.getYawSin(), p.getYawCos(), x - p.getCameraX(), y - p.getCameraY(), z - p.getCameraZ()))
				{
					if (drawOrderLog.recording())
					{
						drawOrderLog.skipped();
					}
					return;
				}
			}

			client.checkClickbox(projection, model, orientation, x, y, z, hash);

			int tc = Math.min(MAX_TRIANGLE, offsetModel.getFaceCount());
			int uvOffset = offsetModel.getUvBufferOffset();
			int plane = (int) ((hash >> TileObject.HASH_PLANE_SHIFT) & 3);
			boolean hillskew = offsetModel != model;

			GpuIntBuffer b = bufferForTriangles(tc);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			int pos = buffer.position();
			buffer.put(offsetModel.getBufferOffset());
			buffer.put(uvOffset);
			buffer.put(tc);
			buffer.put(targetBufferOffset);
			int m = markOf(scene, renderable, x, z, hash);
			buffer.put(FLAG_SCENE_BUFFER | (hillskew ? (1 << 26) : 0) | (plane << 24) | orientation
				| (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0));
			buffer.put(x).put(y).put(z);

			targetBufferOffset += tc * 3;
			noteDraw(renderable, x, z, b, pos, tc * 3, -1);
		}
		else
		{
			// Temporary model (animated or otherwise not a static Model on the scene)

			// Apply height to renderable from the model
			if (model != renderable)
			{
				renderable.setModelHeight(model.getModelHeight());
			}

			model.calculateBoundsCylinder();

			if (projection instanceof IntProjection)
			{
				IntProjection p = (IntProjection) projection;
				if (!isVisible(model, p.getPitchSin(), p.getPitchCos(), p.getYawSin(), p.getYawCos(), x - p.getCameraX(), y - p.getCameraY(), z - p.getCameraZ()))
				{
					if (drawOrderLog.recording())
					{
						drawOrderLog.skipped();
					}
					return;
				}
			}

			client.checkClickbox(projection, model, orientation, x, y, z, hash);

			// texture slots for textures, and for the faces' bias (SceneUploader.hasUv)
			boolean hasUv = SceneUploader.hasUv(model);

			int len = sceneUploader.pushModel(model, vertexBuffer, uvBuffer);

			GpuIntBuffer b = bufferForTriangles(len / 3);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			int pos = buffer.position();
			buffer.put(tempOffset);
			buffer.put(hasUv ? tempUvOffset : -1);
			buffer.put(len / 3);
			buffer.put(targetBufferOffset);
			int m = markOf(scene, renderable, x, z, hash);
			buffer.put(orientation | (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0));
			buffer.put(x).put(y).put(z);
			noteDraw(renderable, x, z, b, pos, len, -1);

			tempOffset += len;
			if (hasUv)
			{
				tempUvOffset += len;
			}

			targetBufferOffset += len;
		}
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 *
	 * @param triangles
	 * @return
	 */
	private GpuIntBuffer bufferForTriangles(int triangles)
	{
		if (computeMode == ComputeMode.OPENGL && triangles <= TINY_TRIANGLE_COUNT)
		{
			++tinyModels;
			tierFaces[2] += triangles;
			return modelBufferTiny;
		}
		if (computeMode == ComputeMode.OPENGL && triangles <= MID_TRIANGLE_COUNT)
		{
			++midModels;
			tierFaces[3] += triangles;
			return modelBufferMid;
		}
		if (triangles <= SMALL_TRIANGLE_COUNT)
		{
			++smallModels;
			tierFaces[1] += triangles;
			return modelBufferSmall;
		}
		if (computeMode == ComputeMode.OPENGL)
		{
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				if (triangles <= BIG_TIERS[i][0] * BIG_TIERS[i][1])
				{
					++bigModels[i];
					tierFaces[4 + i] += triangles;
					return modelBufferBig[i];
				}
			}
		}
		++largeModels;
		tierFaces[7] += triangles;
		return modelBuffer;
	}

	/**
	 * The record path's frame (stage A3; setup_comp.glsl and scan_comp.glsl say what): each face's triangles counted,
	 * placed in the game's order and written as records, then drawn as boxes, the solid list first and, with the CPU's
	 * depth test, the see-through list over it without writing the key. The same pixels as the geometry stage's draws
	 * (ShaderCheck draws its scenes both ways).
	 */
	private void drawRecords(boolean keyTest, int cpuFlags, boolean loggedIn)
	{
		final int faces = targetBufferOffset / 3;
		if (faces == 0)
		{
			return;
		}
		readRecordOverflow();
		final int blocks = (faces + 4095) / 4096;
		final int capacity = faces + recordExtra;
		// only the card writes these, so they're grown when needed but never invalidated: invalidating asks the driver
		// for fresh memory while earlier frames may still use the old (a quarter of a gigabyte of records zoomed out)
		growBuffer(recCounts, GL43C.GL_SHADER_STORAGE_BUFFER, faces * 8, GL43C.GL_DYNAMIC_DRAW);
		growBuffer(recBlocks, GL43C.GL_SHADER_STORAGE_BUFFER, 16 + blocks * 8, GL43C.GL_DYNAMIC_DRAW);
		growBuffer(recRecords, GL43C.GL_SHADER_STORAGE_BUFFER, capacity * 128, GL43C.GL_DYNAMIC_DRAW);
		growBuffer(recFaces, GL43C.GL_SHADER_STORAGE_BUFFER, capacity * 4, GL43C.GL_DYNAMIC_DRAW);
		growBuffer(recCommands, GL43C.GL_SHADER_STORAGE_BUFFER, 48, GL43C.GL_DYNAMIC_DRAW);

		// count each face's triangles, by the setup's decisions alone
		GL43C.glUseProgram(glSetupProgram);
		GL43C.glUniformBlockBinding(glSetupProgram, uniSetupBlock, 0);
		GL43C.glUniform2i(uniSetupViewSize, viewportWidth, viewportHeight);
		GL43C.glUniform1i(uniSetupFaceCount, faces);
		GL43C.glUniform1i(uniSetupTwoLists, keyTest ? 1 : 0);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, recCounts.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, recBlocks.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, recRecords.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, recCommands.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, recFaces.glBufferId);
		GL43C.glDispatchCompute((faces + 255) / 256, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_COUNT);
		}

		// their places in their lists, the draw commands and the write's dispatch
		GL43C.glUseProgram(glScanProgram);
		GL43C.glUniform1i(uniScanFaceCount, faces);
		GL43C.glUniform1i(uniScanBlockCount, blocks);
		GL43C.glUniform1i(uniScanCapacity, capacity);
		GL43C.glUniform1i(uniScanPhase, 0);
		GL43C.glDispatchCompute(blocks, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		GL43C.glUniform1i(uniScanPhase, 1);
		GL43C.glDispatchCompute(1, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT
			| GL43C.GL_COMMAND_BARRIER_BIT);
		copyRecordTotals();
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_PLACE);
		}

		// each record's face
		GL43C.glUniform1i(uniScanPhase, 2);
		GL43C.glDispatchCompute((faces + 1023) / 1024, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_SCATTER);
		}

		// the records, a thread each (only faces that draw are set up in full)
		GL43C.glUseProgram(glSetupWriteProgram);
		GL43C.glUniformBlockBinding(glSetupWriteProgram, uniSetupWriteBlock, 0);
		GL43C.glUniform2i(uniSetupWriteViewSize, viewportWidth, viewportHeight);
		GL43C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, recCommands.glBufferId);
		GL43C.glDispatchComputeIndirect(32L);
		GL43C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, 0);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_COMMAND_BARRIER_BIT);
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_WRITE);
		}

		// the boxes: the solid list (or, without the depth test, every face) in the game's order, then the see-through
		GL43C.glUseProgram(glRecordProgram);
		GL43C.glUniformBlockBinding(glRecordProgram, uniRecBlock, 0);
		GL43C.glUniform2i(uniRecViewSize, viewportWidth, viewportHeight);
		setFog(uniRecFog, client.getSkyboxColor());
		GL43C.glUniform1i(uniRecCpuFlags, cpuFlags);
		GL43C.glUniform1i(uniRecGroundPass, 0);
		GL43C.glUniform1f(uniRecGroundAllowance, config.groundAllowance());
		if (loggedIn)
		{
			GL43C.glUniform1i(uniRecTick, client.getGameCycle() & 127);
		}
		GL43C.glUniform1i(uniRecTextures, 1);
		GL43C.glUniform1i(uniRecPalette, 3);
		GL43C.glUniform1i(uniRecSeeThroughPass, 0);
		GL43C.glUniform1i(uniRecWinners, 4);
		GL43C.glBindVertexArray(vaoRecords);
		GL43C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, recCommands.glBufferId);
		GL43C.glUniform1i(uniRecListB, 0);
		GL43C.glDrawArraysIndirect(GL43C.GL_TRIANGLES, 0L);
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_SOLID);
		}
		if (keyTest)
		{
			GL43C.glDepthMask(false);
			seeThroughTarget(true);
			GL43C.glUniform1i(uniRecSeeThroughPass, 1);
			GL43C.glUniform1i(uniRecListB, 1);
			GL43C.glDrawArraysIndirect(GL43C.GL_TRIANGLES, 16L);
			GL43C.glUniform1i(uniRecSeeThroughPass, 0);
			seeThroughTarget(false);
		}
		GL43C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);
	}

	// the see-through pass's target (stage A4): the picture and depth without the winners, which it reads instead
	private void seeThroughTarget(boolean on)
	{
		GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, on ? fboCpuSee : fboCpu);
		GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, on ? texWinner : 0);
		GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
	}

	// this frame's totals (solid records, see-through records, the overflow flag) copied out for the overflow check, into
	// a slot no earlier frame is still waiting on (if every slot is, this frame goes unchecked)
	private void copyRecordTotals()
	{
		final int s = overflowSlot;
		if (overflowFences[s] != 0)
		{
			return;
		}
		if (overflowCopies[s] == 0)
		{
			overflowCopies[s] = GL43C.glGenBuffers();
			GL43C.glBindBuffer(GL43C.GL_COPY_WRITE_BUFFER, overflowCopies[s]);
			GL43C.glBufferData(GL43C.GL_COPY_WRITE_BUFFER, 16, GL43C.GL_STREAM_READ);
		}
		GL43C.glBindBuffer(GL43C.GL_COPY_READ_BUFFER, recBlocks.glBufferId);
		GL43C.glBindBuffer(GL43C.GL_COPY_WRITE_BUFFER, overflowCopies[s]);
		GL43C.glCopyBufferSubData(GL43C.GL_COPY_READ_BUFFER, GL43C.GL_COPY_WRITE_BUFFER, 0, 0, 16);
		GL43C.glBindBuffer(GL43C.GL_COPY_READ_BUFFER, 0);
		GL43C.glBindBuffer(GL43C.GL_COPY_WRITE_BUFFER, 0);
		overflowFences[s] = GL43C.glFenceSync(GL43C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		overflowSlot = (s + 1) % OVERFLOW_SLOTS;
	}

	// whether an earlier frame's records outgrew their buffer, from the copies the card has finished (never waiting): if
	// so that frame left some triangles out, so the buffer grows for the frames after
	private void readRecordOverflow()
	{
		for (int i = 0; i < OVERFLOW_SLOTS; i++)
		{
			final int s = (overflowSlot + i) % OVERFLOW_SLOTS;  // the oldest first
			if (overflowFences[s] == 0)
			{
				continue;
			}
			final int w = GL43C.glClientWaitSync(overflowFences[s], 0, 0L);
			if (w != GL43C.GL_ALREADY_SIGNALED && w != GL43C.GL_CONDITION_SATISFIED)
			{
				break;  // the card does them in order: the later ones aren't done either
			}
			GL43C.glDeleteSync(overflowFences[s]);
			overflowFences[s] = 0;
			overflowTotals.clear();
			GL43C.glBindBuffer(GL43C.GL_COPY_READ_BUFFER, overflowCopies[s]);
			GL43C.glGetBufferSubData(GL43C.GL_COPY_READ_BUFFER, 0, overflowTotals);
			GL43C.glBindBuffer(GL43C.GL_COPY_READ_BUFFER, 0);
			final int solid = overflowTotals.get(0), see = overflowTotals.get(1);
			if (timing)
			{
				timer.records(solid, see);
			}
			if (overflowTotals.get(2) != 0)
			{
				recordExtra = Math.max(recordExtra * 2, solid + see);
				if (!recordWarned)
				{
					log.warn("CPU Look: a frame's records outgrew their buffer ({} and {}); growing it", solid, see);
					recordWarned = true;
				}
			}
		}
	}

	// a compute tier's dispatch, bound as the small one is
	private void dispatchTier(int program, int uniBlock, GLBuffer models, int count)
	{
		if (count == 0)
		{
			return;
		}
		GL43C.glUniformBlockBinding(program, uniBlock, 0);
		GL43C.glUseProgram(program);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, models.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, sceneVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tmpVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, sceneUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBuffer.glBufferId);
		GL43C.glDispatchCompute(count, 1, 1);
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		GL43C.glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull IntBuffer data, int usage, long clFlags)
	{
		int size = data.remaining() << 2;
		updateBuffer(glBuffer, target, size, usage, clFlags);
		GL43C.glBufferSubData(target, 0, data);
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, @Nonnull FloatBuffer data, int usage, long clFlags)
	{
		int size = data.remaining() << 2;
		updateBuffer(glBuffer, target, size, usage, clFlags);
		GL43C.glBufferSubData(target, 0, data);
	}

	// the fog's uniforms for the program in use (its locations: fog depth, draw distance, Extended map loading, colour)
	private void setFog(int[] at, int sky)
	{
		GL43C.glUniform1i(at[0], fogDepth);
		GL43C.glUniform1i(at[1], getDrawDistance());
		GL43C.glUniform1i(at[2], fogChunks);
		GL43C.glUniform3f(at[3], (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f);
	}

	// a buffer only the card writes: bound, and grown when too small, never invalidated
	private void growBuffer(@Nonnull GLBuffer glBuffer, int target, int size, int usage)
	{
		GL43C.glBindBuffer(target, glBuffer.glBufferId);
		if (size > glBuffer.size)
		{
			int newSize = Math.max(1024, nextPowerOfTwo(size));
			log.trace("Buffer resize: {} {} -> {}", glBuffer.name, glBuffer.size, newSize);
			glBuffer.size = newSize;
			GL43C.glBufferData(target, newSize, usage);
		}
	}

	private void updateBuffer(@Nonnull GLBuffer glBuffer, int target, int size, int usage, long clFlags)
	{
		GL43C.glBindBuffer(target, glBuffer.glBufferId);
		if (glCapabilities.glInvalidateBufferData != 0L)
		{
			// https://www.khronos.org/opengl/wiki/Buffer_Object_Streaming suggests buffer re-specification is useful
			// to avoid implicit synching. We always need to trash the whole buffer anyway so this can't hurt.
			GL43C.glInvalidateBufferData(glBuffer.glBufferId);
		}
		if (size > glBuffer.size)
		{
			int newSize = Math.max(1024, nextPowerOfTwo(size));
			log.trace("Buffer resize: {} {} -> {}", glBuffer.name, glBuffer.size, newSize);

			glBuffer.size = newSize;
			GL43C.glBufferData(target, newSize, usage);
			recreateCLBuffer(glBuffer, clFlags);
		}
	}

	private static int nextPowerOfTwo(int v)
	{
		v--;
		v |= v >> 1;
		v |= v >> 2;
		v |= v >> 4;
		v |= v >> 8;
		v |= v >> 16;
		v++;
		return v;
	}

	private void recreateCLBuffer(GLBuffer glBuffer, long clFlags)
	{
		if (computeMode == ComputeMode.OPENCL)
		{
			if (glBuffer.clBuffer != -1)
			{
				CL10.clReleaseMemObject(glBuffer.clBuffer);
			}
			if (glBuffer.size == 0)
			{
				glBuffer.clBuffer = -1;
			}
			else
			{
				glBuffer.clBuffer = CL10GL.clCreateFromGLBuffer(openCLManager.context, clFlags, glBuffer.glBufferId, (int[]) null);
			}
		}
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = GL43C.glGetError();
			if (err == GL43C.GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL43C.GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL43C.GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL43C.GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL43C.GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}
}
