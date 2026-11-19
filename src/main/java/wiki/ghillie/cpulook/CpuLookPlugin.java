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
import net.runelite.api.GameObject;
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
	// render mode 2, "sorted, no depth" (players and most NPCs): its corners marked 0x200000 by the compute pass, its solid
	// faces drawn a model at a time as the CPU draws them (geom.glsl's painterPass)
	private static final int FLAG_PAINTER = 1 << 29;
	// drawn in the CPU's solid pass only (FastOrder.K_SOLID: what lies under a bridge beyond its ground, first wall half
	// and objects): its corners marked 0x80000 by the compute pass, its see-through faces never drawn
	private static final int FLAG_SOLID = 1 << 30;
	// zoneEntry's draw of such an entry in full (drawRenderable's flags)
	private boolean drawSolidOnly;
	private static final int MARK_PAINTER = 0x200000;
	private static final int MARK_SOLID = 0x80000;
	private static final int MARK_GROUND = 0x400000;
	private static final int MARK_FREE = 0x800000;
	// a ground tile, always (the CPU textures tiles with its own routine; the compute pass marks them itself)
	private static final int MARK_TILE = 0x100000;
	// the CPU look's integer camera maths stays inside an int out to here (claude/cpu-look.md)
	static final int MAX_DISTANCE = 128;
	// fog, as the GPU plugin's (11/18/2026): up to this many tiles in from the draw distance's edge
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	static final int GROUND_MIN_Y = 350; // how far below the ground models extend

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

	// RuneLite's hooks for hiding tiles and objects (other plugins'): applied to zone mode's own draws, as RuneLite's zone
	// plugin applies them
	@Inject
	private net.runelite.client.callback.RenderCallbackManager renderCallbackManager;

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
	// faces that surely draw nothing not written by the compute pass, only marked (priority_render.glsl's SKIP_EMPTY)
	private boolean skipEmpty;
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
	// "Players' own faces: Both fixed": a render mode 2 model's own greatest key at each pixel, drawn alone (a depth
	// texture in a framebuffer of its own, read by the colour pass)
	private int fboPainter = -1;
	private int texPainterDepth = -1;
	// (stage A4's winners, each pixel's solid winner kept for the see-through pass, went 11/19/2026: the CPU draws all its
	// solid faces before any see-through one, so a see-through face blends wherever its key beats the solid one's)
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
	// stage B's B1 check (with the re-sort diagnostic): every draw the client hands over, in full, against the walk's
	// own (FastOrder.draw), every 30th frame; the answer in the log at most every five seconds
	private Object[] callR = new Object[8192];
	private int[] callO = new int[8192], callX = new int[8192], callY = new int[8192], callZ = new int[8192];
	private long[] callH = new long[8192];
	private int calls;
	private long lastFullCheckLog;
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
	// The CPU look's "Players' own faces", read once a frame: render mode 2 models drawn as the CPU draws them (unless
	// "Nearest wins"); with "Both fixed" the band in game units (else 0)
	private boolean painterModels;
	private int painterBand;
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
	// the stretches of this frame's triangles that belong to render mode 2 models (players, most NPCs), one a model, never
	// joined: the CPU draws each such model's solid faces with colour only, tested against the depth from before it (so a
	// later face covers an earlier one nearer the camera: the trousers over the boots), then writes its keys
	// (claude/cpu-look.md, section 9, "Players and NPCs: render mode 2")
	private int[] painterFirst = new int[64], painterCount = new int[64];
	private int painterRanges;
	// each one's place and own bounds (PainterBatch), the frame's camera for its screen box, and the batches
	private int[] painterX = new int[64], painterY = new int[64], painterZ = new int[64];
	private float[] painterM = new float[64], painterY0 = new float[64], painterY1 = new float[64];
	private final PainterBatch painterBatch = new PainterBatch();
	private IntBuffer painterFirstBuf = BufferUtils.createIntBuffer(256), painterCountBuf = BufferUtils.createIntBuffer(256);
	private boolean drawnPainter;
	private int uniPainterPass, uniPainterBand, uniPainterDepth;
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
	// the developer build's "Log my player's model": the local player's next draw dumped to the log (ModelDump)
	private volatile boolean logPlayerModelNext;
	// Diagnostics -> Log the ground around me: on the next frame (GroundDump)
	private volatile boolean logGroundNext;

	private double cameraX, cameraY, cameraZ;
	// the frame's camera turn in 16,384ths, as the uniforms carry it (the developer build's model dump)
	private int cameraYawUnits, cameraPitchUnits;

	// Stage B, zone mode (Diagnostics; claude/cpu-look-stage-b.md, 5b): the client in its zone mode (ZBUF), handing over
	// only what moves (drawDynamic, drawTemp); the scene drawn from CPU Look's own walk, in the CPU's order, at
	// postSceneDraw. Set when the plugin starts (the client's flag).
	private boolean zoneMode;
	private int zoneThreads;
	// drawScene's plane: the highest render level the client draws this frame
	private int framePlane;
	// the developer build's count of why zone mode left tiles out (level, hidden roof, frustum, in view), logged every
	// five seconds with Log frame timings on
	private boolean zoneWhyNext;
	private long lastZoneWhyLog;
	private volatile boolean zoneFrame;
	// the "Zone mode: draw nothing" measurement, read once a frame
	private volatile boolean zoneIdle;
	// drawDynamic comes from the client's render threads, drawTemp from its own thread beside them: their copying one
	// at a time
	private float frameYawSin, frameYawCos, framePitchSin, framePitchCos;
	private int frameCamX, frameCamY, frameCamZ;
	private int zoneLevel, zoneMaxLevel;
	private java.util.Set<Integer> zoneHideRoofs = java.util.Collections.emptySet();
	// this frame's dynamic objects (animated scenery), by renderable, and temporary objects (players, NPCs, projectiles,
	// graphics), by game object: each one's upload (below). The model the client hands over is its own scratch, made
	// again for the next object (11/19/2026, Jace: every NPC drawn as the same NPC, walking in place, and moles where the
	// portals were), so it is copied into the frame's temporary buffers there and then, as RuneLite's zone plugin does,
	// and put in the walk's order later
	private final IdentityHashMap<Renderable, Integer> zoneDyn = new IdentityHashMap<>();
	private final IdentityHashMap<GameObject, Integer> zoneTemp = new IdentityHashMap<>();
	private GameObject[] tempG = new GameObject[256];
	private int temps;
	// the uploads: where each went in the temporary buffers, its length, its model entry's flags and place, whether it
	// has see-through faces and whether it's render mode 2
	private int[] upOff = new int[512], upUv = new int[512], upLen = new int[512], upFlags = new int[512], upX = new int[512],
		upY = new int[512], upZ = new int[512];
	private boolean[] upSee = new boolean[512], upPainter = new boolean[512];
	// a render mode 2 model's own bounds (PainterBatch): the greatest distance of a corner from its axis, its lowest and
	// highest corner (the model's y, down positive)
	private float[] upPm = new float[512], upPy0 = new float[512], upPy1 = new float[512];
	private int ups;
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

						// the pass names' own groups (Name the card's passes for a profiler): every push and pop would
						// otherwise be printed (his GPU Trace: 33.7 MB of them in two minutes)
						GL43C.glDebugMessageControl(GL43C.GL_DONT_CARE, GL43C.GL_DEBUG_TYPE_PUSH_GROUP, GL43C.GL_DONT_CARE,
							(IntBuffer) null, false);
						GL43C.glDebugMessageControl(GL43C.GL_DONT_CARE, GL43C.GL_DEBUG_TYPE_POP_GROUP, GL43C.GL_DONT_CARE,
							(IntBuffer) null, false);
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
				// zone mode, the normal path since 11/19/2026 (claude/cpu-look-stage-b.md): wherever the compute shaders run
				// on OpenGL; the old path (the client's old callbacks, in its order) on the Mac's OpenCL and with compute
				// off, and in the developer build when Zone mode is switched off, to compare
				zoneMode = (!Build.DEVELOPER || config.zoneMode()) && computeMode == ComputeMode.OPENGL;
				// the client's own zone-mode work (its picking) over render threads, as RuneLite's GPU plugin has it
				// (the common pool's parallelism: the processor's cores less one)
				zoneThreads = Math.max(0, Math.min(java.util.concurrent.ForkJoinPool.getCommonPoolParallelism(), config.zoneThreads()));
				if (zoneMode)
				{
					// a stage for the client's own thread, one for each render thread, and one spare
					Stage[] st = new Stage[zoneThreads + 2];
					for (int k = 0; k < st.length; k++)
					{
						st[k] = new Stage();
					}
					stages = st;
					zoneWorker = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
					{
						Thread t = new Thread(r, "Bitcrush zone walk");
						t.setDaemon(true);
						return t;
					});
					zoneLister = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
					{
						Thread t = new Thread(r, "Bitcrush zone draw list");
						t.setDaemon(true);
						return t;
					});
				}
				else
				{
					stages = null;
				}
				client.setGpuFlags(gpuFlags());
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
			waitZone();
			zonePostPending = false;
			if (zoneWorker != null)
			{
				zoneWorker.shutdown();
				zoneWorker = null;
			}
			if (zoneLister != null)
			{
				zoneLister.shutdown();
				zoneLister = null;
			}
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
			else if (configChanged.getKey().equals("skipEmpty"))
			{
				// compiled in or out (the comparison is with the programs as they were)
				clientThread.invokeLater(() ->
				{
					if (computeMode != ComputeMode.OPENGL || glProgram == -1)
					{
						return;
					}
					shutdownProgram();
					try
					{
						initProgram();
					}
					catch (ShaderException ex)
					{
						log.error("CPU Look: the programs didn't compile again", ex);
					}
				});
			}
			else if (configChanged.getKey().equals("logDrawOrder"))
			{
				logDrawOrderNext = Build.DEVELOPER && config.logDrawOrder();
			}
			else if (configChanged.getKey().equals("logPlayerModel"))
			{
				logPlayerModelNext = Build.DEVELOPER && config.logPlayerModel();
			}
			else if (configChanged.getKey().equals("logGround"))
			{
				logGroundNext = Build.DEVELOPER && config.logGround();
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				// no HILLSKEW: the client bends scenery to its slope itself (the game's contourGround, every one of
				// its ways), as it does for the CPU, instead of handing it over straight for the compute pass to bend
				client.setGpuFlags(gpuFlags());
			}
		}
	}

	// the client's flags: GPU; vertex snapping off if asked; in zone mode ZBUF, with no render threads (CPU Look's
	// uploads aren't made for several threads at once)
	private int gpuFlags()
	{
		return DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| (zoneMode ? DrawCallbacks.ZBUF | DrawCallbacks.ZBUF_ZONE_FRUSTUM_CHECK | DrawCallbacks.RENDER_THREADS(zoneThreads) : 0);
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
			if ("cull_config".equals(key))
			{
				return skipEmpty ? "#define SKIP_EMPTY\n" : "";
			}
			return null;
		});
		template.addInclude(CpuLookPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		skipEmpty = !Build.DEVELOPER || config.skipEmpty();
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

		if (Build.DEVELOPER && computeMode == ComputeMode.OPENGL)
		{
			nameProgram(glProgram, "geometry stage");
			nameProgram(glUiProgram, "interface");
			nameProgram(glComputeProgram, "compute 1024 x 6");
			nameProgram(glSmallComputeProgram, "compute 512");
			nameProgram(glTinyComputeProgram, "compute 64");
			nameProgram(glMidComputeProgram, "compute 256");
			for (int i = 0; i < BIG_TIERS.length; i++)
			{
				nameProgram(glBigComputePrograms[i], "compute " + BIG_TIERS[i][0] + " x " + BIG_TIERS[i][1]);
			}
			nameProgram(glUnorderedComputeProgram, "compute unordered");
			nameProgram(glSetupProgram, "records count");
			nameProgram(glSetupWriteProgram, "records write");
			nameProgram(glScanProgram, "records place");
			nameProgram(glRecordProgram, "records draw");
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
		uniPainterPass = GL43C.glGetUniformLocation(glProgram, "painterPass");
		uniPainterBand = GL43C.glGetUniformLocation(glProgram, "painterBand");
		uniPainterDepth = GL43C.glGetUniformLocation(glProgram, "painterDepth");
		uniGroundAllowance = GL43C.glGetUniformLocation(glProgram, "groundAllowance");
		uniPalette = GL43C.glGetUniformLocation(glProgram, "palette");
		uniTick = GL43C.glGetUniformLocation(glProgram, "tick");
		uniBlockMain = GL43C.glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = GL43C.glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = GL43C.glGetUniformLocation(glProgram, "textureAnimations");

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
		// the tiles the client drew: those its frustum test passed (tileInFrustum), at a level no higher than the one
		// shown (drawScene's plane; with roof removal on, every level, as the client has it); a tile's level is the
		// client's own rule (drawLevel)
		final byte[][][] settings = scene.getExtendedTileSettings();
		final int shown = framePlane;
		final boolean roofRemoval = scene.getRoofRemovalMode() != 0;
		final CpuOrder.Visible visible = (p, x, y) -> (all || visibleStamp[p][x][y] == now)
			&& (roofRemoval || drawLevel(settings, p, x, y) <= shown);
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
		if (!reference && orderChecks % 30 == 0)
		{
			fullCheck();
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
		GL43C.glDrawBuffers(new int[]{GL43C.GL_COLOR_ATTACHMENT0});
		int status = GL43C.glCheckFramebufferStatus(GL43C.GL_FRAMEBUFFER);
		if (status != GL43C.GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("CPU look FBO is incomplete. status: " + status);
		}
		// "Both fixed"'s scratch: depth only
		texPainterDepth = GL43C.glGenTextures();
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texPainterDepth);
		GL43C.glTexImage2D(GL43C.GL_TEXTURE_2D, 0, GL43C.GL_DEPTH_COMPONENT32F, width, height, 0, GL43C.GL_DEPTH_COMPONENT, GL43C.GL_FLOAT, 0);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MIN_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_MAG_FILTER, GL43C.GL_NEAREST);
		GL43C.glTexParameteri(GL43C.GL_TEXTURE_2D, GL43C.GL_TEXTURE_COMPARE_MODE, GL43C.GL_NONE);
		GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
		fboPainter = GL43C.glGenFramebuffers();
		GL43C.glBindFramebuffer(GL43C.GL_FRAMEBUFFER, fboPainter);
		GL43C.glFramebufferTexture2D(GL43C.GL_FRAMEBUFFER, GL43C.GL_DEPTH_ATTACHMENT, GL43C.GL_TEXTURE_2D, texPainterDepth, 0);
		GL43C.glDrawBuffer(GL43C.GL_NONE);
		GL43C.glReadBuffer(GL43C.GL_NONE);
		status = GL43C.glCheckFramebufferStatus(GL43C.GL_FRAMEBUFFER);
		if (status != GL43C.GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("CPU look's players' depth FBO is incomplete. status: " + status);
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
		if (texCpu != -1)
		{
			GL43C.glDeleteTextures(texCpu);
			texCpu = -1;
		}
		if (fboPainter != -1)
		{
			GL43C.glDeleteFramebuffers(fboPainter);
			fboPainter = -1;
		}
		if (texPainterDepth != -1)
		{
			GL43C.glDeleteTextures(texPainterDepth);
			texPainterDepth = -1;
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
		// (a frame whose draw never came: its thread's work finished, uploaded, before this frame starts)
		finishZone();
		zoneThreadOn = zoneMode && config.zoneThread();
		framePlane = plane;
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
		painterRanges = 0;
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
		this.cameraYawUnits = yaw;
		this.cameraPitchUnits = pitch;
		zoneFrame = zoneMode;
		zoneIdle = zoneMode && Build.DEVELOPER && config.zoneIdle();
		zoneCheck = zoneMode && Build.DEVELOPER && config.zoneCheck();
		frameYawSin = (float) Math.sin(cameraYaw);
		frameYawCos = (float) Math.cos(cameraYaw);
		framePitchSin = (float) Math.sin(cameraPitch);
		framePitchCos = (float) Math.cos(cameraPitch);
		frameCamX = camX;
		frameCamY = camY;
		frameCamZ = camZ;
		zoneDyn.clear();
		zoneTemp.clear();
		temps = 0;
		ups = 0;
		stagedV = stagedUv = 0;
		if (zoneMode && stages != null)
		{
			for (Stage stage : stages)
			{
				stage.clear();
			}
			// (read here, before the render threads cull with them)
			readClip();
		}
		this.cameraY = camY;
		this.cameraZ = camZ;
		draws = 0;
		calls = 0;
		// (the developer's diagnostics: never on in the Plugin Hub's build, Build.java)
		groundHides = Build.DEVELOPER && config.groundHides() && !config.depthBuffer() && !config.cpuDepthTest();
		final CpuLookConfig.PlayerFaces faces = config.playerFaces();
		painterModels = faces != CpuLookConfig.PlayerFaces.DEPTH;
		painterBand = faces == CpuLookConfig.PlayerFaces.BOTH ? Math.max(1, Math.min(64, config.playerFacesBand())) : 0;
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
		painterBatch.camera(camX, camY, camZ, Perspective.SINEF14[yaw], Perspective.COSINEF14[yaw], Perspective.SINEF14[pitch],
			Perspective.COSINEF14[pitch], client.getCenterX(), client.getCenterY(), client.getScale());

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
		// (debug groups are OpenGL 4.3's, as the compute pass is: not on the Mac's OpenCL path)
		passNames = Build.DEVELOPER && config.passNames() && computeMode == ComputeMode.OPENGL;
		if (drawOrderLog.recording())
		{
			drawOrderLog.finish();
		}

		if (orderTest && draws > 1 && !zoneFrame)
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
		calls = 0;
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

		// temp buffers (zone mode's stages at their places in them)
		pass("uploads");
		final long ut0 = timing ? System.nanoTime() : 0;
		final long tempBytes = ((long) vertexBuffer.remaining() + uvBuffer.remaining() + (stagedV + stagedUv) * 4L) * 4;
		if (stagedV > 0 || stagedUv > 0)
		{
			uploadStaged(tmpVertexBuffer, vertexBuffer, splitV, stagedV, false);
			uploadStaged(tmpUvBuffer, uvBuffer, splitUv, stagedUv);
			stagedV = stagedUv = 0;
		}
		else
		{
			updateBuffer(tmpVertexBuffer, GL43C.GL_ARRAY_BUFFER, vertexBuffer, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
			updateBuffer(tmpUvBuffer, GL43C.GL_ARRAY_BUFFER, uvBuffer, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		}

		// model buffers
		final long ut1 = timing ? System.nanoTime() : 0;
		long modelBytes = (long) modelBuffer.remaining() + modelBufferSmall.remaining() + modelBufferTiny.remaining()
			+ modelBufferMid.remaining() + modelBufferUnordered.remaining();
		for (GpuIntBuffer b : this.modelBufferBig)
		{
			modelBytes += b.getBuffer().remaining();
		}
		modelBytes *= 4;
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
		if (timing)
		{
			timer.uploadParts(tempBytes, ut1 - ut0, modelBytes, System.nanoTime() - ut1);
		}

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
		if (skipEmpty)
		{
			// the screen for surelyEmpty's coverage test
			viewSizeOf(glSmallComputeProgram);
			viewSizeOf(glTinyComputeProgram);
			viewSizeOf(glMidComputeProgram);
			viewSizeOf(glComputeProgram);
			for (int p : glBigComputePrograms)
			{
				viewSizeOf(p);
			}
		}

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
		pass("compute, the ground (unordered)");
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
		pass("compute, 512 threads");
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
		pass("compute, 64 threads");
		dispatchTier(glTinyComputeProgram, uniBlockTiny, tmpModelBufferTiny, tinyModels);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END + 2);
		}
		pass("compute, 256 threads");
		dispatchTier(glMidComputeProgram, uniBlockMid, tmpModelBufferMid, midModels);
		if (timing)
		{
			timer.mark(FrameTimer.TIER_END + 3);
		}
		for (int i = 0; i < BIG_TIERS.length; i++)
		{
			pass("compute, " + BIG_TIERS[i][0] + " x " + BIG_TIERS[i][1]);
			dispatchTier(glBigComputePrograms[i], uniBlockBig[i], tmpModelBufferBig[i], bigModels[i]);
			if (timing)
			{
				timer.mark(FrameTimer.TIER_END + 4 + i);
			}
		}

		// large
		pass("compute, 1024 x 6");
		GL43C.glUseProgram(glComputeProgram);

		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferLarge.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, sceneVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, tmpVertexBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, sceneUvBuffer.glBufferId);
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBuffer.glBufferId);

		// (an empty tier isn't dispatched, as dispatchTier doesn't: his GPU Traces had this one at 0.18-0.19 ms with no
		// models, the card waiting for itself)
		if (largeModels > 0)
		{
			GL43C.glDispatchCompute(largeModels, 1, 1);
		}
		passEnd();

		if (timing)
		{
			timer.mark(FrameTimer.COMPUTE_END);
		}

		checkGLErrors();
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileY)
	{
		if (zoneFrame)
		{
			return;  // zone mode: every tile's ground comes from CPU Look's own walk
		}
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
		if (zoneFrame)
		{
			return;
		}
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
		// mapped with its old contents invalidated (11/19/2026): a plain glMapBuffer waits for the card to finish last
		// frame's copy out of it, about 1 ms a frame once the card was busy (his station: "the interface's upload" 1.9 ms
		// with zone mode's own thread, 0.18 without); the driver now hands fresh storage instead, the same pixels
		final long pboSize = (long) lastCanvasWidth * lastCanvasHeight * 4;
		final int ints = (int) Math.min((long) width * height, pboSize / 4);
		ByteBuffer interfaceBuf = pboSize > 0 ? GL43C.glMapBufferRange(GL43C.GL_PIXEL_UNPACK_BUFFER, 0, pboSize,
			GL43C.GL_MAP_WRITE_BIT | GL43C.GL_MAP_INVALIDATE_BUFFER_BIT) : null;
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, ints);
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
		if (Build.DEVELOPER && logGroundNext)
		{
			logGroundNext = false;
			logGround();
		}
		if (!timing)
		{
			finishZone();
			drawFrame(overlayColor);
			return;
		}
		timer.drawStart();
		finishZone();
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

		pass("the interface's upload");
		prepareInterfaceTexture(canvasWidth, canvasHeight);
		passEnd();
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
			pass("clear");
			GL43C.glClear(GL43C.GL_COLOR_BUFFER_BIT | GL43C.GL_DEPTH_BUFFER_BIT);
			passEnd();
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
				// the CPU's depth test, in its own two passes (claude/cpu-look.md, section 9, "The CPU's two passes"): every
				// solid face first, testing and writing the key; then the see-through ones in the game's order, tested
				// against it but never writing it
				GL43C.glUniform1i(uniAlphaPass, 1);
				final boolean painters = painterRanges > 0 && !orderTest;
				GL43C.glUniform1i(uniPainterPass, painters ? 1 : 0);
				GL43C.glDrawArrays(GL43C.GL_TRIANGLES, 0, targetBufferOffset);
				if (painters)
				{
					drawPainters();
				}
				if (timing)
				{
					timer.mark(FrameTimer.AFTER_SOLID);
				}
				GL43C.glDepthMask(false);
				GL43C.glUniform1i(uniAlphaPass, 2);
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
		pass("the interface");
		drawUi(overlayColor, canvasHeight, canvasWidth);
		passEnd();

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
		waitZone();
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
		waitZone();
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
		waitZone();
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
		waitZone();
		if (computeMode == ComputeMode.NONE || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		// zone mode's walk reads the new scene whole before its first frame
		fastOrder.invalidateAll();

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

	// ---- zone mode (stage B): the client's zone callbacks ----

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ,
		float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, java.util.Set<Integer> hideRoofIds)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			zoneLevel = level;
			zoneMaxLevel = maxLevel;
			zoneHideRoofs = hideRoofIds != null ? hideRoofIds : java.util.Collections.emptySet();
			zoneScene = scene;
			if (zoneFrame)
			{
				// the view's test for the client's zoneInFrustum, which comes next (ei.jz, then ei.oi)
				fillFrustum(scene);
			}
		}
	}

	private Scene zoneScene;

	// the frame's view test: the camera, the screen (readClip at drawScene), the client's levels and hidden roofs
	private void fillFrustum(Scene scene)
	{
		final FastOrder.Frustum fr = zoneFrustum;
		fr.pitchSin = framePitchSin;
		fr.pitchCos = framePitchCos;
		fr.yawSin = frameYawSin;
		fr.yawCos = frameYawCos;
		fr.camX = frameCamX;
		fr.camY = frameCamY;
		fr.camZ = frameCamZ;
		fr.zoom = client.get3dZoom();
		fr.clipMidX2 = client.getRasterizer3D_clipMidX2();
		fr.clipNegMidX = client.getRasterizer3D_clipNegativeMidX();
		fr.clipNegMidY = client.getRasterizer3D_clipNegativeMidY();
		fr.level = zoneLevel;
		fr.maxLevel = zoneMaxLevel;
		fr.roofRemoval = scene.getRoofRemovalMode() != 0;
		fr.roofs = scene.getRoofs();
		fr.hide = zoneHideRoofs;
	}

	// zone mode: which zones the client works on this frame (their moving models, temporary objects and items handed
	// over): every zone with a tile the walk may draw (the client's flag ZBUF_ZONE_FRUSTUM_CHECK)
	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY)
	{
		final Scene scene = zoneScene;
		if (!zoneFrame || scene == null)
		{
			return true;
		}
		return fastOrder.zoneInView(scene, zoneFrustum, zx, zz);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r,
		Model m, int orient, int x, int y, int z)
	{
		if (!zoneFrame || zoneIdle || scene.getWorldViewId() != WorldView.TOPLEVEL || m == null
			|| !renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}
		final Stage[] st = stages;
		if (st == null)
		{
			return;
		}
		// the client's own thread is -1, its render threads 0 and up: each its own stage, so none waits for another
		final Stage stage = st[Math.max(0, Math.min(st.length - 1, renderThreadId + 1))];
		synchronized (stage)
		{
			final long t0 = timing ? System.nanoTime() : 0;
			stageUpload(stage, scene, r, m, orient, x, y, z, tileObject.getHash(), r, false);
			// (an item pile's items come this way in zone mode: their pile kept for the walk)
			stage.owner[stage.n - 1] = tileObject;
			if (timing)
			{
				timer.zoneCopy(System.nanoTime() - t0, renderThreadId < 0);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		if (!zoneFrame || zoneIdle || scene.getWorldViewId() != WorldView.TOPLEVEL || m == null
			|| !renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}
		final Stage[] st = stages;
		if (st == null)
		{
			return;
		}
		// (the client hands the temporary objects over on its own thread, ei.hw, in its order for them: kept, for the
		// order of a tile's objects)
		final Stage stage = st[0];
		synchronized (stage)
		{
			final long t0 = timing ? System.nanoTime() : 0;
			if (temps == tempG.length)
			{
				tempG = Arrays.copyOf(tempG, temps * 2);
			}
			tempG[temps++] = gameObject;
			stageUpload(stage, scene, gameObject.getRenderable(), m, orient, x, y, z, gameObject.getHash(), gameObject, true);
			if (timing)
			{
				timer.zoneCopy(System.nanoTime() - t0, true);
			}
		}
	}

	/**
	 * Zone mode's copies of the moving models the client hands over (stage B's speed work, 11/19/2026): a stage for each
	 * thread that hands them over (0 the client's own, 1 and up its render threads, by their number), each copied into
	 * on its own thread without waiting for the others (before, one lock: the render threads took turns). When the frame
	 * is drawn the stages are put in place (zoneMerge: their copies numbered for zoneQueue, after whatever the client's
	 * own thread had put in the frame's temporary buffer by then) and uploaded straight into the card's temporary buffer
	 * at their places (uploadStaged), so nothing is copied twice.
	 */
	private static final class Stage
	{
		final GpuIntBuffer vb = new GpuIntBuffer();
		final GpuFloatBuffer ub = new GpuFloatBuffer();
		// its vertices and texture points so far, its copies
		int v, uv, n;
		// each copy: the dynamic object's renderable or the temporary object, whether temporary, its place in the
		// stage (-1 length: out of view), its draw's flags and place, whether see-through, render mode 2
		Object[] key = new Object[64], owner = new Object[64];
		boolean[] temp = new boolean[64], see = new boolean[64], painter = new boolean[64];
		int[] off = new int[64], uvOff = new int[64], len = new int[64], flags = new int[64], x = new int[64], y = new int[64], z = new int[64];
		// a render mode 2 copy's own bounds (PainterBatch.bounds)
		float[] pm = new float[64], py0 = new float[64], py1 = new float[64];
		// where its vertices and texture points start in the frame's temporary buffers (set by zoneMerge)
		int base, uvBase;

		void clear()
		{
			vb.clear();
			ub.clear();
			v = uv = n = 0;
			Arrays.fill(key, null);
			Arrays.fill(owner, null);
		}

		int add()
		{
			if (n == key.length)
			{
				int k = n * 2;
				key = Arrays.copyOf(key, k);
				owner = Arrays.copyOf(owner, k);
				temp = Arrays.copyOf(temp, k);
				see = Arrays.copyOf(see, k);
				painter = Arrays.copyOf(painter, k);
				off = Arrays.copyOf(off, k);
				uvOff = Arrays.copyOf(uvOff, k);
				len = Arrays.copyOf(len, k);
				flags = Arrays.copyOf(flags, k);
				x = Arrays.copyOf(x, k);
				y = Arrays.copyOf(y, k);
				z = Arrays.copyOf(z, k);
				pm = Arrays.copyOf(pm, k);
				py0 = Arrays.copyOf(py0, k);
				py1 = Arrays.copyOf(py1, k);
			}
			return n++;
		}
	}

	private Stage[] stages;
	// this frame's item piles, as handed over (the walk puts them on their tiles; FastOrder.putItems)
	private net.runelite.api.ItemLayer[] itemLayers = new net.runelite.api.ItemLayer[64];
	private int nItemLayers;
	// this frame's stages in the temporary buffers: where they go (the client's own thread's content before them), and
	// their vertices and texture points in all
	private int splitV, splitUv, stagedV, stagedUv;

	// a moving model copied into its thread's stage now (the drawRenderable's temporary branch, its first half)
	private void stageUpload(Stage s, Scene scene, Renderable r, Model model, int orientation, int x, int y, int z, long hash,
		Object key, boolean temp)
	{
		final int i = s.add();
		s.key[i] = key;
		s.owner[i] = null;
		s.temp[i] = temp;
		model.calculateBoundsCylinder();
		if (!visibleModel(null, model, x, y, z))
		{
			s.len[i] = -1;
			return;
		}
		if (Build.DEVELOPER && logPlayerModelNext && r == client.getLocalPlayer())
		{
			logPlayerModelNext = false;
			log.info("Bitcrush player model: {}", ModelDump.dump(model, r.getRenderMode(), orientation, x, y, z,
				(int) cameraX, (int) cameraY, (int) cameraZ, cameraYawUnits, cameraPitchUnits, client.getScale(), client.getCenterX(),
				client.getCenterY(), client.getViewportWidth(), client.getViewportHeight()));
		}
		final boolean painter = painterModels && r != null && r.getRenderMode() == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		final boolean hasUv = SceneUploader.hasUv(model);
		final int len = sceneUploader.pushModel(model, s.vb, s.ub);
		final int m = markOf(scene, r, x, z, hash);
		s.off[i] = s.v;
		s.uvOff[i] = hasUv ? s.uv : -1;
		s.len[i] = len;
		s.flags[i] = orientation | (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0) | (painter ? FLAG_PAINTER : 0);
		s.x[i] = x;
		s.y[i] = y;
		s.z[i] = z;
		s.see[i] = model.getFaceTransparencies() != null || model.getTransparency() != 0;
		s.painter[i] = painter;
		if (painter)
		{
			PainterBatch.bounds(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(), model.getVerticesCount(), s.pm, s.py0, s.py1, i);
		}
		s.v += len;
		if (hasUv)
		{
			s.uv += len;
		}
	}

	// the stages put in place (the client and its render threads have handed everything over by postSceneDraw): each
	// copy numbered for zoneQueue, the dynamic and temporary objects' maps filled, the client's own thread's further
	// copies after them
	private void zoneMerge()
	{
		final Stage[] st = stages;
		splitV = tempOffset;
		splitUv = tempUvOffset;
		stagedV = stagedUv = 0;
		nItemLayers = 0;
		if (st == null)
		{
			return;
		}
		int v = 0, uv = 0;
		for (Stage s : st)
		{
			s.base = v;
			s.uvBase = uv;
			v += s.v;
			uv += s.uv;
		}
		stagedV = v;
		stagedUv = uv;
		for (Stage s : st)
		{
			for (int k = 0; k < s.n; k++)
			{
				int idx = -1;
				if (s.len[k] >= 0)
				{
					if (ups == upOff.length)
					{
						int c = ups * 2;
						upOff = Arrays.copyOf(upOff, c);
						upUv = Arrays.copyOf(upUv, c);
						upLen = Arrays.copyOf(upLen, c);
						upFlags = Arrays.copyOf(upFlags, c);
						upX = Arrays.copyOf(upX, c);
						upY = Arrays.copyOf(upY, c);
						upZ = Arrays.copyOf(upZ, c);
						upSee = Arrays.copyOf(upSee, c);
						upPainter = Arrays.copyOf(upPainter, c);
						upPm = Arrays.copyOf(upPm, c);
						upPy0 = Arrays.copyOf(upPy0, c);
						upPy1 = Arrays.copyOf(upPy1, c);
					}
					idx = ups++;
					upOff[idx] = splitV + s.base + s.off[k];
					upUv[idx] = s.uvOff[k] < 0 ? -1 : splitUv + s.uvBase + s.uvOff[k];
					upLen[idx] = s.len[k];
					upFlags[idx] = s.flags[k];
					upX[idx] = s.x[k];
					upY[idx] = s.y[k];
					upZ[idx] = s.z[k];
					upSee[idx] = s.see[k];
					upPainter[idx] = s.painter[k];
					upPm[idx] = s.pm[k];
					upPy0[idx] = s.py0[k];
					upPy1[idx] = s.py1[k];
				}
				if (s.temp[k])
				{
					zoneTemp.put((GameObject) s.key[k], idx);
				}
				else
				{
					zoneDyn.put((Renderable) s.key[k], idx);
					final Object owner = s.owner[k];
					if (owner instanceof net.runelite.api.ItemLayer && (nItemLayers == 0 || itemLayers[nItemLayers - 1] != owner))
					{
						if (nItemLayers == itemLayers.length)
						{
							itemLayers = Arrays.copyOf(itemLayers, nItemLayers * 2);
						}
						itemLayers[nItemLayers++] = (net.runelite.api.ItemLayer) owner;
					}
				}
			}
		}
		tempOffset = splitV + stagedV;
		tempUvOffset = splitUv + stagedUv;
	}

	// a temporary buffer with this frame's stages: what the client's own thread put there before them (to split), the
	// stages at their places, the rest after them
	private void uploadStaged(GLBuffer glBuffer, IntBuffer main, int split, int staged, boolean texture)
	{
		final int total = (main.remaining() + staged * 4) << 2;
		updateBuffer(glBuffer, GL43C.GL_ARRAY_BUFFER, total, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		final int lim = main.limit();
		if (split > 0)
		{
			main.limit(split * 4);
			GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, 0, main);
		}
		main.limit(lim);
		main.position(split * 4);
		if (main.remaining() > 0)
		{
			GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, (long) (split + staged) << 4, main);
		}
		main.position(0);
		for (Stage s : stages)
		{
			if ((texture ? s.uv : s.v) == 0)
			{
				continue;
			}
			final long at = (long) (split + (texture ? s.uvBase : s.base)) << 4;
			if (texture)
			{
				s.ub.flip();
				GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, at, s.ub.getBuffer());
			}
			else
			{
				s.vb.flip();
				GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, at, s.vb.getBuffer());
			}
		}
	}

	private void uploadStaged(GLBuffer glBuffer, FloatBuffer main, int split, int staged)
	{
		final int total = (main.remaining() + staged * 4) << 2;
		updateBuffer(glBuffer, GL43C.GL_ARRAY_BUFFER, total, GL43C.GL_DYNAMIC_DRAW, CL12.CL_MEM_READ_ONLY);
		final int lim = main.limit();
		if (split > 0)
		{
			main.limit(split * 4);
			GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, 0, main);
		}
		main.limit(lim);
		main.position(split * 4);
		if (main.remaining() > 0)
		{
			GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, (long) (split + staged) << 4, main);
		}
		main.position(0);
		for (Stage s : stages)
		{
			if (s.uv == 0)
			{
				continue;
			}
			s.ub.flip();
			GL43C.glBufferSubData(GL43C.GL_ARRAY_BUFFER, (long) (split + s.uvBase) << 4, s.ub.getBuffer());
		}
	}

	// an upload put in the model list at this point of the order (the temporary branch's second half)
	private void zoneQueue(int i)
	{
		final int len = upLen[i];
		final int before = targetBufferOffset;
		GpuIntBuffer b = bufferForTriangles(len / 3);
		b.ensureCapacity(8);
		IntBuffer buffer = b.getBuffer();
		buffer.put(upOff[i]);
		buffer.put(upUv[i]);
		buffer.put(len / 3);
		buffer.put(targetBufferOffset);
		buffer.put(upFlags[i]);
		buffer.put(upX[i]).put(upY[i]).put(upZ[i]);
		targetBufferOffset += len;
		if (upSee[i] && len > 0)
		{
			seeRange(before, len);
		}
		if (upPainter[i] && len > 0)
		{
			// hill-skewed models (not players or NPCs) are bent on the card: their bounds unknown here
			if ((upFlags[i] & (1 << 26)) == 0)
			{
				painterRange(before, len, upX[i], upY[i], upZ[i], upPm[i], upPy0[i], upPy1[i]);
			}
			else
			{
				painterRange(before, len);
			}
		}
	}

	// zone mode: the client says a zone of a scene changed (an object, wall, decoration, ground object or item put there
	// or taken away, an animated object come to rest; extended tiles >> 3): the walk reads it again before the next frame
	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		if (zoneMode && scene != null && scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			fastOrder.invalidate(zx, zz);
		}
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (!zoneFrame || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return;
		}
		try
		{
			if (!zoneIdle)
			{
				zoneDraw(scene);
			}
		}
		catch (RuntimeException ex)
		{
			if (!zoneWarned)
			{
				log.warn("CPU Look's zone mode couldn't draw the frame", ex);
				zoneWarned = true;
			}
		}
		if (zonePostPending)
		{
			// (the thread has the frame: the scene's part ends here, the uploads wait for draw)
			if (timing)
			{
				timer.postStart();
				timer.postEnd();
			}
			return;
		}
		postDrawScene();
	}

	private boolean zoneWarned;
	private final FastOrder.Frustum zoneFrustum = new FastOrder.Frustum();
	// zone mode's reading since the last log: whole scenes, zones and tiles read again
	private int zoneFullReads, zoneReadZones;
	private long zoneReadSlots;
	// the developer check (Zone mode: check the walk): every 30th frame, the walk from the kept scene against CpuOrder
	// reading the scene afresh, entry for entry
	private volatile boolean zoneCheck;
	private int zoneChecks, zoneCheckFrames, zoneCheckDiffer;
	private long lastZoneCheckLog;
	private String zoneCheckExample;

	private void checkZoneWalk(Scene scene, int cx, int cz, int distance, CpuOrder.Visible visible)
	{
		cpuOrder.setTemps(tempG, temps);
		cpuOrder.itemShown = il -> renderCallbackManager.drawObject(scene, il);
		cpuOrder.build(scene, cx, cz, distance, visible);
		cpuOrder.setTemps(null, 0);
		cpuOrder.itemShown = null;
		final FastOrder f = fastOrder;
		final CpuOrder c = cpuOrder;
		boolean same = f.n == c.n && f.gaveUp == c.gaveUp;
		int at = -1;
		for (int k = 0, n = Math.min(f.n, c.n); k < n; k++)
		{
			if (f.keys[k] != c.keys[k] || f.kx[k] != c.kx[k] || f.kz[k] != c.kz[k])
			{
				same = false;
				at = k;
				break;
			}
		}
		zoneCheckFrames++;
		if (!same)
		{
			zoneCheckDiffer++;
			if (zoneCheckExample == null)
			{
				zoneCheckExample = at < 0 ? String.format("%d entries against %d", f.n, c.n)
					: String.format("%d entries against %d, first at %d: %s at %d %d against %s at %d %d", f.n, c.n, at,
					f.keys[at] == null ? "nothing" : f.keys[at].getClass().getSimpleName(), f.kx[at], f.kz[at],
					c.keys[at] == null ? "nothing" : c.keys[at].getClass().getSimpleName(), c.kx[at], c.kz[at]);
			}
		}
		final long now = System.nanoTime();
		if (now - lastZoneCheckLog > 5_000_000_000L)
		{
			lastZoneCheckLog = now;
			log.info("Bitcrush zone mode's check: {} frames' walks from the kept scene against the scene read afresh, {} differing{}",
				zoneCheckFrames, zoneCheckDiffer, zoneCheckExample != null ? " (the first: " + zoneCheckExample + ")" : "");
			zoneCheckFrames = zoneCheckDiffer = 0;
			zoneCheckExample = null;
		}
	}

	// The frame in zone mode: the CPU's order from CPU Look's own walk (the static scene from its tiles, this frame's
	// temporary objects put on theirs), each draw in full from the client's getters (FastOrder.draw, checked against the
	// old mode's draws by B1), dynamic objects' and temporary objects' models as the client made them this frame
	private void zoneDraw(Scene scene)
	{
		final Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null)
		{
			return;
		}
		zoneMerge();
		final int[][][] roofs = scene.getRoofs();
		final byte[][][] settings = scene.getExtendedTileSettings();
		final boolean roofRemoval = scene.getRoofRemovalMode() != 0;
		final int level = zoneLevel, maxLevel = zoneMaxLevel;
		final java.util.Set<Integer> hide = zoneHideRoofs;
		final float ps = framePitchSin, pc = framePitchCos, ys = frameYawSin, yc = frameYawCos;
		final int cx = frameCamX, cy = frameCamY, cz = frameCamZ;
		// which tiles the client would draw: its render level within the levels shown, a hidden roof's tiles left out,
		// and the frustum (the old mode's tileInFrustum). The walk tests them itself (FastOrder.viewZone, from what it
		// worked out when it read each tile); this, reading the scene, is for CpuOrder (the reference walk, the check)
		final boolean count = Build.DEVELOPER && timing;
		final CpuOrder.Visible visible = (p, x, y) ->
		{
			Tile t = tiles[p][x][y];
			if (t == null)
			{
				return false;
			}
			// its level by the client's own rule; above the highest level drawn it's left out, unless roof removal is on
			// (the client's walk, ei.iv, then leaves out only hidden roofs' tiles)
			int rl = drawLevel(settings, p, x, y);
			if (rl > maxLevel && !roofRemoval)
			{
				return false;
			}
			if (roofRemoval && level < rl && roofs != null)
			{
				int roof = roofs[level][x][y];
				if (roof != 0 && hide.contains(roof))
				{
					return false;
				}
			}
			return x > 0 && y > 0 && x < Constants.EXTENDED_SCENE_SIZE - 1 && y < Constants.EXTENDED_SCENE_SIZE - 1
				&& inFrustum(scene, ps, pc, ys, yc, cx, cy, cz, p, x, y);
		};
		final int distance = getDrawDistance();
		final long t0 = timing ? System.nanoTime() : 0;
		if (count && System.nanoTime() - lastZoneWhyLog > 5_000_000_000L)
		{
			zoneWhyNext = true;
		}
		final FastOrder.Frustum fr = zoneFrustum;
		fillFrustum(scene);
		fr.count = count;
		// the static draws from their prepared records (made the first time each is drawn, kept until its zone is read
		// again or the scene changes); the rest, and every draw while a diagnostic needs each call, as before. The
		// records are dropped before the walk when the settings in them change (the walk notes entries by them)
		final boolean prepared = !orderTest && !drawOrderLog.recording();
		if (prepared)
		{
			final int key = (groundHides ? 1 : 0) | (painterModels ? 2 : 0) | sceneId << 2;
			if (key != recsKey || nRecs > 400_000)
			{
				// (also past 400,000: records left by zones read again pile up) the scene read again, every record made
				// again as it's read
				fastOrder.invalidateAll();
				recsKey = key;
			}
		}
		lookAtEmpty();
		fastOrder.setTemps(tempG, temps);
		fastOrder.setItems(itemLayers, nItemLayers);
		// (entries with a prepared draw noted by it alone, unless a check or diagnostic needs every entry in full)
		fastOrder.lean = prepared && !zoneCheck;
		prepScene = scene;
		fastOrder.preparer = preparer;
		final boolean begun = fastOrder.beginCached(scene, cx, cz, distance);
		fastOrder.setTemps(null, 0);
		fastOrder.setItems(null, 0);
		zoneReadZones += fastOrder.zonesRead;
		zoneFullReads += fastOrder.fullRead ? 1 : 0;
		zoneReadSlots += fastOrder.slotsRead;
		final boolean lean = fastOrder.lean;
		final boolean reference = fastOrder.overflowed;
		if (!begun && !reference)
		{
			fastOrder.lean = false;
			return;
		}
		// the rest on zone mode's own thread, beside the client's interface and RuneLite's overlays, when nothing this
		// frame needs the client's objects there (no check or diagnostic; the developer's tile log done here)
		if (begun && zoneWorker != null && zoneThreadOn && lean && !zoneCheck && !zoneWhyNext)
		{
			markOutput();
			zoneAborted = false;
			zoneDrawScene = scene;
			zoneStart = System.nanoTime();
			if (zoneLister != null)
			{
				// the sweep on one thread, the draw list on another following its entries as they're made
				zoneTask = zoneWorker.submit(() -> zoneSweep(scene, visible, fr));
				zoneListTask = zoneLister.submit(this::zoneListFollowing);
			}
			else
			{
				zoneTask = zoneWorker.submit(() -> zoneWork(scene, visible, fr, true, true, true));
			}
			zonePostPending = true;
			return;
		}
		if (begun)
		{
			fastOrder.finishCached(scene, visible, fr);
		}
		fastOrder.lean = false;
		if (reference)
		{
			cpuOrder.setTemps(tempG, temps);
			cpuOrder.itemShown = il -> renderCallbackManager.drawObject(scene, il);
			cpuOrder.build(scene, cx, cz, distance, visible);
			cpuOrder.setTemps(null, 0);
			cpuOrder.itemShown = null;
		}
		if (timing)
		{
			timer.order(System.nanoTime() - t0, reference ? cpuOrder.n : fastOrder.n, fastOrder.readNs,
				fastOrder.setupNs - fastOrder.readNs, fastOrder.sweepNs);
		}
		if (Build.DEVELOPER && zoneCheck && !reference && ++zoneChecks % 30 == 0)
		{
			checkZoneWalk(scene, cx, cz, distance, visible);
		}
		if (zoneWhyNext)
		{
			zoneWhyNext = false;
			lastZoneWhyLog = System.nanoTime();
			log.info("Bitcrush zone mode's reading: the whole scene {} times, zones read again {} ({} tiles with the objects reaching out of them)",
				zoneFullReads, zoneReadZones, zoneReadSlots - zoneFullReads * 4 * Constants.EXTENDED_SCENE_SIZE * Constants.EXTENDED_SCENE_SIZE);
			zoneFullReads = zoneReadZones = 0;
			zoneReadSlots = 0;
			final int[] why = zoneFrustum.why;
			log.info("Bitcrush zone mode's tiles: levels from the client: lowest {}, the player's {}, highest drawn {}; roof removal mode {}, roofs hidden {}; the old mode's level shown would be {}; tiles tested {}: left out by their level {}, by a hidden roof {}, by the frustum {}, in view {}",
				scene.getMinLevel(), level, maxLevel, scene.getRoofRemovalMode(), hide, framePlane,
				why[0] + why[1] + why[2] + why[3], why[0], why[1], why[2], why[3]);
		}
		if (reference)
		{
			// the reference walk keeps no sources: its entries drawn by what they are (rare: a tile with over 8 objects)
			for (int k = 0; k < cpuOrder.n; k++)
			{
				zoneEntry(scene, cpuOrder.keys[k], null, (byte) -1, k, null);
			}
			return;
		}
		final int[] params = zoneParams;
		final long loop0 = timing ? System.nanoTime() : 0, apart0 = timing ? timer.callbacksSoFar() : 0;
		drawLoop(scene, lean, prepared, false);
		if (timing)
		{
			timer.callbacks(System.nanoTime() - loop0 - (timer.callbacksSoFar() - apart0), zoneFast);
		}
		if (Build.DEVELOPER && zoneCheck && prepared && zoneChecks % 30 == 0)
		{
			checkRecords(scene, params);
		}
	}

	// the static draws from their prepared records; the rest as the old mode's callbacks drew them. safe (zone mode's
	// own thread): nothing of the client's read, the temporary objects, items and moving models as copied when handed
	// over; any other draw stops it (zoneAborted), the frame's draw list then made again on the client's thread
	private void drawLoop(Scene scene, boolean lean, boolean prepared, boolean safe)
	{
		final int[] params = zoneParams;
		final FastOrder f = fastOrder;
		final int[] krec = f.krec;
		int fast = 0;
		for (int k = 0, n = f.n; k < n; k++)
		{
			if (lean && krec[k] >= 0)
			{
				drawRecord(krec[k]);
				fast++;
				continue;
			}
			if (safe)
			{
				if (!zoneEntrySafe(f.keys[k], f.ksrc[k]))
				{
					zoneAborted = true;
					if (Build.DEVELOPER)
					{
						final int ref = f.kref[k];
						zoneStopper = String.format("kind %d, %s, record %s", f.kkind[k],
							f.keys[k] == null ? "nothing" : f.keys[k].getClass().getSimpleName(),
							ref == -1 ? "none" : String.valueOf(ref >= 0 ? f.tileRec[ref] : f.oRec[-ref - 2]));
					}
					return;
				}
				continue;
			}
			if (prepared)
			{
				int r = record(scene, k, params);
				if (r >= 0)
				{
					drawRecord(r);
					fast++;
					continue;
				}
			}
			zoneEntry(scene, f.keys[k], f.ksrc[k], f.kkind[k], k, params);
		}
		zoneFast = fast;
	}

	// a temporary object, item or moving model as copied when the client handed it over; false for anything else
	private boolean zoneEntrySafe(Object key, Object src)
	{
		if (!(key instanceof Renderable))
		{
			return false;
		}
		Integer up = src instanceof GameObject ? zoneTemp.get(src) : null;
		if (up == null)
		{
			up = zoneDyn.get(key);
		}
		if (up == null)
		{
			return false;
		}
		if (up >= 0)
		{
			zoneQueue(up);
		}
		return true;
	}

	/**
	 * Zone mode's own thread (11/19/2026): after postSceneDraw the client draws its interface and RuneLite its overlays
	 * before calling draw; the walk's second half (which tiles are in view, the sweep) and the draw list run there
	 * meanwhile, on a thread of CPU Look's own, reading only CPU Look's arrays and copies; draw waits for it, then
	 * uploads and dispatches. Anything else of CPU Look's that touches the same state waits for it first (the next
	 * frame's drawScene, a scene's load or swap, shutting down).
	 */
	private java.util.concurrent.ExecutorService zoneWorker;
	private java.util.concurrent.Future<?> zoneTask;
	private volatile boolean zoneAborted;
	private boolean zonePostPending, zoneThreadOn;
	private Scene zoneDrawScene;
	private final int[] zoneParams = new int[4];
	private int zoneFast;
	// the thread's last frame: the walk's second half and the draw list (ns), draws from records
	private long zoneWalkNs, zoneListNs;
	// the developer log of the frames the thread had to stop in, and the first entry it stopped at
	private volatile String zoneStopper;
	private String zoneStopFirst;
	private int zoneStops, zoneThreadFrames;
	private long lastZoneStopLog;

	// the draw list's thread (11/19/2026): the sweep's entries drawn as it gives them out (FastOrder.published), so the
	// two run side by side instead of one after the other; the same drawing as drawLoop's safe path, in the same order
	private java.util.concurrent.ExecutorService zoneLister;
	private java.util.concurrent.Future<?> zoneListTask;
	private long zoneStart;
	private volatile long zoneSweepEnd, zoneListEnd;

	private void zoneSweep(Scene scene, CpuOrder.Visible visible, FastOrder.Frustum fr)
	{
		final long t0 = System.nanoTime();
		fastOrder.finishCached(scene, visible, fr);
		zoneWalkNs = System.nanoTime() - t0;
		zoneSweepEnd = System.nanoTime();
	}

	private void zoneListFollowing()
	{
		final FastOrder f = fastOrder;
		int fast = 0;
		int k = 0;
		try
		{
			while (true)
			{
				final boolean fin = f.done;
				final int pub = f.published;
				// (the arrays read after published: they may have grown)
				final int[] krec = f.krec;
				final Object[] keys = f.keys, src = f.ksrc;
				for (; k < pub; k++)
				{
					if (krec[k] >= 0)
					{
						drawRecord(krec[k]);
						fast++;
						continue;
					}
					if (!zoneEntrySafe(keys[k], src[k]))
					{
						zoneAborted = true;
						if (Build.DEVELOPER)
						{
							final int ref = f.kref[k];
							zoneStopper = String.format("kind %d, %s, record %s", f.kkind[k],
								keys[k] == null ? "nothing" : keys[k].getClass().getSimpleName(),
								ref == -1 ? "none" : String.valueOf(ref >= 0 ? f.tileRec[ref] : f.oRec[-ref - 2]));
						}
						return;
					}
				}
				if (fin)
				{
					break;
				}
				Thread.onSpinWait();
			}
		}
		finally
		{
			zoneFast = fast;
			zoneListEnd = System.nanoTime();
		}
	}

	private void zoneWork(Scene scene, CpuOrder.Visible visible, FastOrder.Frustum fr, boolean lean, boolean prepared, boolean safe)
	{
		final long t0 = System.nanoTime();
		fastOrder.finishCached(scene, visible, fr);
		final long t1 = System.nanoTime();
		drawLoop(scene, lean, prepared, safe);
		zoneWalkNs = t1 - t0;
		zoneListNs = System.nanoTime() - t1;
	}

	// wait for the thread's frame, if it has one going
	private void waitZone()
	{
		waitFor(zoneTask);
		zoneTask = null;
		waitFor(zoneListTask);
		zoneListTask = null;
	}

	private void waitFor(java.util.concurrent.Future<?> t)
	{
		if (t == null)
		{
			return;
		}
		try
		{
			t.get();
		}
		catch (InterruptedException | java.util.concurrent.ExecutionException ex)
		{
			if (!zoneWarned)
			{
				log.warn("CPU Look's zone thread couldn't finish the frame; doing it on the client's thread", ex);
				zoneWarned = true;
			}
			zoneAborted = true;
		}
	}

	// the frame's end in draw: the thread waited for (its draw list made again here if it had to stop), then the
	// uploads and the compute dispatch postSceneDraw left
	private void finishZone()
	{
		if (!zonePostPending)
		{
			return;
		}
		zonePostPending = false;
		final long w0 = timing ? System.nanoTime() : 0;
		final boolean zoneListTaskUsed = zoneListTask != null;
		waitZone();
		final long waited = timing ? System.nanoTime() - w0 : 0;
		fastOrder.lean = false;
		boolean redone = false;
		if (zoneAborted)
		{
			resetOutput();
			drawLoop(zoneDrawScene, true, true, false);
			redone = true;
			zoneStops++;
			if (zoneStopFirst == null)
			{
				zoneStopFirst = zoneStopper;
			}
		}
		zoneThreadFrames++;
		if (Build.DEVELOPER && timing && System.nanoTime() - lastZoneStopLog > 5_000_000_000L)
		{
			lastZoneStopLog = System.nanoTime();
			log.info("Bitcrush zone mode's own thread: done again on the client's thread in {} of {} frames{}", zoneStops,
				zoneThreadFrames, zoneStopFirst != null ? " (the first entry it couldn't draw: " + zoneStopFirst
					+ "; kinds: 0 ground, 1-2 wall, 3-4 decoration, 5 ground decoration, 6 item, 7 object)" : "");
			zoneStops = zoneThreadFrames = 0;
			zoneStopFirst = null;
		}
		zoneDrawScene = null;
		final long u0 = timing ? System.nanoTime() : 0;
		postDrawScene0();
		if (timing)
		{
			timer.order(fastOrder.readNs + zoneWalkNs, fastOrder.n, fastOrder.readNs, fastOrder.setupNs - fastOrder.readNs, fastOrder.sweepNs);
			final long work = zoneListTaskUsed ? Math.max(zoneSweepEnd, zoneListEnd) - zoneStart : zoneWalkNs + zoneListNs;
			timer.zoneThread(work, waited, System.nanoTime() - u0, redone);
		}
	}

	// the frame's output as it stood before the draw list (put back if the thread had to stop)
	private int markTarget, markTemp, markTempUv, markUv, markSee, markPainter;
	private int markSmall, markLarge, markUnordered, markTiny, markMid;
	private final int[] markBig = new int[BIG_TIERS.length], markTierFaces = new int[FrameTimer.TIERS.length];
	private final int[] markPos = new int[6 + BIG_TIERS.length], markStaged = new int[6 + BIG_TIERS.length];

	private GpuIntBuffer[] modelBuffers()
	{
		GpuIntBuffer[] all = new GpuIntBuffer[6 + BIG_TIERS.length];
		all[0] = modelBuffer;
		all[1] = modelBufferSmall;
		all[2] = modelBufferTiny;
		all[3] = modelBufferMid;
		all[4] = modelBufferUnordered;
		all[5] = vertexBuffer;
		System.arraycopy(modelBufferBig, 0, all, 6, BIG_TIERS.length);
		return all;
	}

	private void markOutput()
	{
		markTarget = targetBufferOffset;
		markTemp = tempOffset;
		markTempUv = tempUvOffset;
		markUv = uvBuffer.getBuffer().position();
		markSee = seeRanges;
		markPainter = painterRanges;
		markSmall = smallModels;
		markLarge = largeModels;
		markUnordered = unorderedModels;
		markTiny = tinyModels;
		markMid = midModels;
		System.arraycopy(bigModels, 0, markBig, 0, markBig.length);
		System.arraycopy(tierFaces, 0, markTierFaces, 0, markTierFaces.length);
		final GpuIntBuffer[] all = modelBuffers();
		for (int i = 0; i < all.length; i++)
		{
			markPos[i] = all[i].getBuffer().position();
			markStaged[i] = all[i].stagedCount();
		}
	}

	private void resetOutput()
	{
		targetBufferOffset = markTarget;
		tempOffset = markTemp;
		tempUvOffset = markTempUv;
		uvBuffer.getBuffer().position(markUv);
		seeRanges = markSee;
		painterRanges = markPainter;
		smallModels = markSmall;
		largeModels = markLarge;
		unorderedModels = markUnordered;
		tinyModels = markTiny;
		midModels = markMid;
		System.arraycopy(markBig, 0, bigModels, 0, markBig.length);
		System.arraycopy(markTierFaces, 0, tierFaces, 0, markTierFaces.length);
		final GpuIntBuffer[] all = modelBuffers();
		for (int i = 0; i < all.length; i++)
		{
			all[i].restore(markPos[i], markStaged[i]);
		}
	}

	// ---- zone mode's prepared draws (stage B's speed work, 11/19/2026) ----
	// A static draw's record, REC ints: its buffer offset, texture offset, faces, flags, x, y, z; then for a model its
	// bounds for the culling (xz magnitude, bottom, height), for the ground the place it takes; and what it is (RB_*).
	// The same numbers the old path's callbacks put in the draw buffers (drawRenderable's scene-buffer branch,
	// drawScenePaint0, drawSceneTileModel0), worked out once; RuneLite's hide hooks asked then too, as RuneLite's own
	// zone renderer asks them when it uploads a zone (SceneUploader.uploadZoneTile).
	private static final int REC = 12, RB_MODEL = 1, RB_SEE = 2, RB_PAINTER = 4, RB_GROUND = 8, RB_NONE = 16, RB_PUSHED = 32;
	// RB_PUSHED (11/19/2026): animated scenery at rest (a DynamicObject the client doesn't hand over, drawn from its
	// getModelZbuf, which pushModel copied every frame): copied once when prepared, its numbers kept here (recVerts,
	// recUvs; the record's last int their index) and put in the frame's temporary buffers in one go; recKeys, the
	// object, for the frames the client hands it over moving (zoneDyn), which then win, as in zoneEntry
	private int[][] recVerts = new int[256][];
	private float[][] recUvs = new float[256][];
	private Object[] recKeys = new Object[256];
	private int nRecData;
	private int[] checkVerts;
	private float[] checkUvs;
	private final GpuIntBuffer prepVB = new GpuIntBuffer();
	private final GpuFloatBuffer prepUB = new GpuFloatBuffer();
	private int[] recs = new int[REC * 16384];
	// the scene the walk is reading (for the records made as it reads: FastOrder.Preparer)
	private Scene prepScene;
	private final int[] prepParams = new int[4];
	private final FastOrder.Preparer preparer = new FastOrder.Preparer()
	{
		@Override
		public void reset()
		{
			nRecs = 0;
			dropRecData();
			recsSeen = fastOrder.records;
		}

		@Override
		public int prepare(Object key, Object src, byte kind)
		{
			final Scene scene = prepScene;
			if (scene == null)
			{
				return -1;
			}
			if ((nRecs + 1) * REC > recs.length)
			{
				recs = Arrays.copyOf(recs, recs.length * 2);
			}
			return CpuLookPlugin.this.prepare(scene, key, src, kind, prepParams, recs, nRecs * REC, true) ? nRecs++ : -2;
		}

		@Override
		public boolean redo(int rec)
		{
			// a record that draws nothing at rest (prepareEmpty) for an animated object that has a model at rest now
			final int b = rec * REC;
			if (b + REC > recs.length || (recs[b + 10] & RB_PUSHED) == 0)
			{
				return false;
			}
			final int data = recs[b + 11];
			return data >= 0 && data < nRecData && recVerts[data] == null && recKeys[data] instanceof net.runelite.api.DynamicObject
				&& ((net.runelite.api.DynamicObject) recKeys[data]).getModelZbuf() != null;
		}
	};
	private int nRecs, recsSeen = -1, recsKey = -1;
	private final int[] recCheck = new int[REC];
	private int recChecked, recDiffer;
	private String recExample;

	// entry k's record (made the first time it's drawn): its index, or -2 when it isn't a static draw
	private int record(Scene scene, int k, int[] params)
	{
		final FastOrder f = fastOrder;
		final int ref = f.kref[k];
		if (ref == -1)
		{
			return -2;
		}
		int r = ref >= 0 ? f.tileRec[ref] : f.oRec[-ref - 2];
		if (r != -1)
		{
			return r;
		}
		if ((nRecs + 1) * REC > recs.length)
		{
			recs = Arrays.copyOf(recs, recs.length * 2);
		}
		r = prepare(scene, f.keys[k], f.ksrc[k], f.kkind[k], params, recs, nRecs * REC, true) ? nRecs++ : -2;
		if (ref >= 0)
		{
			f.tileRec[ref] = r;
		}
		else
		{
			f.oRec[-ref - 2] = r;
		}
		return r;
	}

	// entry k's record into out from at; false when it isn't a static draw (one for drawRenderable's other branches);
	// keep: an at-rest animated object's copied numbers kept with the records (else into checkVerts, for the check)
	private boolean prepare(Scene scene, Object key, Object src, byte kind, int[] params, int[] out, int at, boolean keep)
	{
		if (kind == FastOrder.K_GROUND)
		{
			if (!(src instanceof Tile))
			{
				return false;
			}
			final Tile tile = (Tile) src;
			final boolean shown = renderCallbackManager.drawTile(scene, tile);
			final net.runelite.api.Point loc = tile.getSceneLocation();
			int offset, uv, faces, len;
			if (key instanceof SceneTilePaint)
			{
				SceneTilePaint paint = (SceneTilePaint) key;
				offset = paint.getBufferOffset();
				uv = paint.getUvBufferOffset();
				faces = 2;
				len = paint.getBufferLen() > 0 ? 6 : 0;
			}
			else if (key instanceof SceneTileModel)
			{
				SceneTileModel model = (SceneTileModel) key;
				offset = model.getBufferOffset();
				uv = model.getUvBufferOffset();
				faces = model.getBufferLen() / 3;
				len = model.getBufferLen() > 0 ? model.getBufferLen() : 0;
			}
			else
			{
				return false;
			}
			out[at] = offset;
			out[at + 1] = uv;
			out[at + 2] = faces;
			out[at + 3] = FLAG_SCENE_BUFFER | (groundHides ? FLAG_GROUND : 0);
			out[at + 4] = loc.getX() << Perspective.LOCAL_COORD_BITS;
			out[at + 5] = 0;
			out[at + 6] = loc.getY() << Perspective.LOCAL_COORD_BITS;
			out[at + 7] = len;
			out[at + 8] = out[at + 9] = 0;
			out[at + 10] = RB_GROUND | (shown && len > 0 ? 0 : RB_NONE);
			out[at + 11] = 0;
			return true;
		}
		if (key instanceof net.runelite.api.DynamicObject && src instanceof TileObject && kind != FastOrder.K_ITEM)
		{
			final net.runelite.api.DynamicObject d = (net.runelite.api.DynamicObject) key;
			final Model still = d.getModelZbuf();
			if (still == null)
			{
				// nothing at rest (zoneEntry draws nothing for it unless the client hands it over moving): a record that
				// draws nothing but what's handed over (11/19/2026: a ground decoration of this kind at the GE stopped
				// zone mode's own thread every frame); kept to look at again, in case a model comes for it later
				return prepareEmpty(scene, key, src, kind, params, out, at, keep, d);
			}
			if (still.getSceneId() == sceneId)
			{
				return false;
			}
			return preparePushed(scene, key, src, kind, params, out, at, keep, d, still, (TileObject) src);
		}
		if (!(key instanceof Model))
		{
			return false;
		}
		final Model model = (Model) key;
		Model offsetModel = model.getUnskewedModel();
		if (offsetModel == null)
		{
			offsetModel = model;
		}
		if (offsetModel.getSceneId() != sceneId)
		{
			// a model not in the scene's buffer (its object put there after the scene loaded): copied once, as
			// drawRenderable's temporary branch copies it every frame (11/19/2026: zone mode's own thread stopped at them)
			return preparePushed(scene, key, src, kind, params, out, at, keep, model, model,
				src instanceof TileObject ? (TileObject) src : null);
		}
		final boolean shown = !(src instanceof TileObject) || renderCallbackManager.drawObject(scene, (TileObject) src);
		final long hash = FastOrder.draw(key, src, kind, params);
		if (params[0] < 0)
		{
			return false;
		}
		final int x = params[1], y = params[2], z = params[3];
		model.calculateBoundsCylinder();
		final boolean solid = (kind & FastOrder.K_SOLID) != 0;
		final boolean see = !solid && (model.getFaceTransparencies() != null || model.getTransparency() != 0);
		final boolean painter = painterModels && model.getRenderMode() == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		final int plane = (int) ((hash >> TileObject.HASH_PLANE_SHIFT) & 3);
		final boolean hillskew = offsetModel != model;
		final int m = markOf(scene, model, x, z, hash);
		out[at] = offsetModel.getBufferOffset();
		out[at + 1] = offsetModel.getUvBufferOffset();
		out[at + 2] = Math.min(MAX_TRIANGLE, offsetModel.getFaceCount());
		out[at + 3] = FLAG_SCENE_BUFFER | (hillskew ? (1 << 26) : 0) | (plane << 24) | params[0]
			| (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0) | (painter ? FLAG_PAINTER : 0) | (solid ? FLAG_SOLID : 0);
		out[at + 4] = x;
		out[at + 5] = y;
		out[at + 6] = z;
		out[at + 7] = model.getXYZMag();
		out[at + 8] = model.getBottomY();
		out[at + 9] = model.getModelHeight();
		out[at + 10] = RB_MODEL | (see ? RB_SEE : 0) | (painter ? RB_PAINTER : 0) | (shown ? 0 : RB_NONE);
		out[at + 11] = 0;
		return true;
	}

	// an animated object with no model at rest: drawn only when the client hands it over (drawPushed with no copy)
	private boolean prepareEmpty(Scene scene, Object key, Object src, byte kind, int[] params, int[] out, int at, boolean keep,
		net.runelite.api.DynamicObject d)
	{
		FastOrder.draw(key, src, kind, params);
		if (params[0] < 0)
		{
			return false;
		}
		int data = -1;
		if (keep)
		{
			if (nRecData == recVerts.length)
			{
				int c = nRecData * 2;
				recVerts = Arrays.copyOf(recVerts, c);
				recUvs = Arrays.copyOf(recUvs, c);
				recKeys = Arrays.copyOf(recKeys, c);
			}
			data = nRecData++;
			recVerts[data] = null;
			recUvs[data] = null;
			recKeys[data] = d;
			// its zone, to read again if a model at rest comes for it
			emptyObjects.add(d);
			emptyZones.add(((params[1] >> Perspective.LOCAL_COORD_BITS) + SCENE_OFFSET) >> 3 << 16
				| ((params[3] >> Perspective.LOCAL_COORD_BITS) + SCENE_OFFSET) >> 3);
		}
		else
		{
			checkVerts = null;
			checkUvs = null;
		}
		Arrays.fill(out, at, at + REC, 0);
		out[at + 4] = params[1];
		out[at + 5] = params[2];
		out[at + 6] = params[3];
		out[at + 10] = RB_PUSHED;
		out[at + 11] = data;
		return true;
	}

	// animated objects prepared with no model at rest, and their zones (x << 16 | z): looked at again now and then
	private final java.util.ArrayList<net.runelite.api.DynamicObject> emptyObjects = new java.util.ArrayList<>();
	private final java.util.ArrayList<Integer> emptyZones = new java.util.ArrayList<>();
	private int emptyLook;

	// (the client's thread, every 30th frame) any of them with a model at rest now: its zone read again, its record made
	// again with the model
	private void lookAtEmpty()
	{
		if (emptyObjects.isEmpty() || ++emptyLook % 30 != 0)
		{
			return;
		}
		for (int i = emptyObjects.size() - 1; i >= 0; i--)
		{
			if (emptyObjects.get(i).getModelZbuf() != null)
			{
				final int zone = emptyZones.get(i);
				fastOrder.invalidate(zone >>> 16, zone & 0xFFFF);
				emptyObjects.remove(i);
				emptyZones.remove(i);
			}
		}
	}

	// a record whose model is copied now: drawRenderable's temporary branch for d drawn with the model still (an at-rest
	// animated object's getModelZbuf, or a model not in the scene's buffer, d itself)
	private boolean preparePushed(Scene scene, Object key, Object srcObject, byte kind, int[] params, int[] out, int at, boolean keep,
		Renderable d, Model still, TileObject src)
	{
		final boolean shown = src == null || renderCallbackManager.drawObject(scene, src);
		final long hash = FastOrder.draw(key, srcObject, kind, params);
		if (params[0] < 0)
		{
			return false;
		}
		final int x = params[1], y = params[2], z = params[3];
		if (still != d)
		{
			d.setModelHeight(still.getModelHeight());
		}
		still.calculateBoundsCylinder();
		final boolean see = still.getFaceTransparencies() != null || still.getTransparency() != 0;
		final boolean painter = painterModels && d.getRenderMode() == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		final boolean hasUv = SceneUploader.hasUv(still);
		prepVB.clear();
		prepUB.clear();
		final int len = sceneUploader.pushModel(still, prepVB, prepUB);
		prepVB.flip();
		prepUB.flip();
		final int[] verts = new int[prepVB.getBuffer().remaining()];
		prepVB.getBuffer().get(verts);
		final float[] uvs = hasUv ? new float[prepUB.getBuffer().remaining()] : null;
		if (uvs != null)
		{
			prepUB.getBuffer().get(uvs);
		}
		final int m = markOf(scene, d, x, z, hash);
		int data = -1;
		if (keep)
		{
			if (nRecData == recVerts.length)
			{
				int c = nRecData * 2;
				recVerts = Arrays.copyOf(recVerts, c);
				recUvs = Arrays.copyOf(recUvs, c);
				recKeys = Arrays.copyOf(recKeys, c);
			}
			data = nRecData++;
			recVerts[data] = verts;
			recUvs[data] = uvs;
			recKeys[data] = d;
		}
		else
		{
			checkVerts = verts;
			checkUvs = uvs;
		}
		out[at] = len;
		out[at + 1] = hasUv ? 1 : 0;
		out[at + 2] = 0;
		out[at + 3] = params[0] | (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0) | (painter ? FLAG_PAINTER : 0)
			| ((kind & FastOrder.K_SOLID) != 0 ? FLAG_SOLID : 0);
		out[at + 4] = x;
		out[at + 5] = y;
		out[at + 6] = z;
		out[at + 7] = still.getXYZMag();
		out[at + 8] = still.getBottomY();
		out[at + 9] = still.getModelHeight();
		out[at + 10] = RB_PUSHED | (see && (kind & FastOrder.K_SOLID) == 0 ? RB_SEE : 0) | (painter ? RB_PAINTER : 0) | (shown ? 0 : RB_NONE);
		out[at + 11] = data;
		return true;
	}

	// a prepared draw into the frame's draw buffers, as its callback would have put it
	private void drawRecord(int r)
	{
		final int[] R = recs;
		final int b = r * REC, bits = R[b + 10];
		if ((bits & RB_NONE) != 0)
		{
			return;
		}
		if ((bits & RB_PUSHED) != 0)
		{
			drawPushed(R, b, bits);
			return;
		}
		if ((bits & RB_GROUND) != 0)
		{
			++unorderedModels;
			tierFaces[0] += R[b + 2];
			modelBufferUnordered.stage(R[b], R[b + 1], R[b + 2], targetBufferOffset, R[b + 3], R[b + 4], R[b + 5], R[b + 6]);
			targetBufferOffset += R[b + 7];
			return;
		}
		if (!visibleFast(R[b + 7], R[b + 8], R[b + 9], R[b + 4] - frameCamX, R[b + 5] - frameCamY, R[b + 6] - frameCamZ))
		{
			return;
		}
		final int tc = R[b + 2];
		bufferForTriangles(tc).stage(R[b], R[b + 1], tc, targetBufferOffset, R[b + 3], R[b + 4], R[b + 5], R[b + 6]);
		final int before = targetBufferOffset;
		targetBufferOffset += tc * 3;
		if (tc > 0)
		{
			if ((bits & RB_SEE) != 0)
			{
				seeRange(before, tc * 3);
			}
			if ((bits & RB_PAINTER) != 0)
			{
				painterRange(before, tc * 3);
			}
		}
	}

	/**
	 * isVisible with the frame's camera, the same answer with fewer divisions: each of its tests "a / depth against c"
	 * (depth over 50, so positive) is first made as "a against c * depth", and only when the two sides are within a
	 * hundred-thousandth of each other (where the roundings of the two ways could differ) by the division itself.
	 */
	private boolean visibleFast(int xzMag, int bottomY, int modelHeight, int x, int y, int z)
	{
		final float pitchSin = framePitchSin, pitchCos = framePitchCos, yawSin = frameYawSin, yawCos = frameYawCos;
		final int zoom = clipZoom;
		float var11 = yawCos * z - yawSin * x;
		float var12 = pitchSin * y + pitchCos * var11;
		float var13 = pitchCos * xzMag;
		float depth = var12 + var13;
		if (depth > 50)
		{
			float rx = z * yawSin + yawCos * x;
			float var16 = (rx - xzMag) * zoom;
			if (below(var16, depth, clipMidX2))
			{
				float var17 = (rx + xzMag) * zoom;
				if (below(-var17, depth, -clipNegMidX))
				{
					float ry = pitchCos * y - var11 * pitchSin;
					float yheight = pitchSin * xzMag;
					float ybottom = (pitchCos * bottomY) + yheight;
					float var20 = (ry + ybottom) * zoom;
					if (below(-var20, depth, -clipNegMidY))
					{
						float ytop = (pitchCos * modelHeight) + yheight;
						float var22 = (ry - ytop) * zoom;
						return below(var22, depth, clipMidY2);
					}
				}
			}
		}
		return false;
	}

	// a / depth < c (depth positive), exactly as the division compares, mostly without it
	private static boolean below(float a, float depth, int c)
	{
		final float m = c * depth;
		final float e = Math.abs(m) * 1e-5f;
		if (a < m - e)
		{
			return true;
		}
		if (a > m + e)
		{
			return false;
		}
		return a / depth < c;
	}

	private void drawPushed(int[] R, int b, int bits)
	{
		final int d = R[b + 11];
		final Integer up = zoneDyn.get(recKeys[d]);
		if (up != null)
		{
			// moving this frame: the client's model, as handed over
			if (up >= 0)
			{
				zoneQueue(up);
			}
			return;
		}
		if (!visibleFast(R[b + 7], R[b + 8], R[b + 9], R[b + 4] - frameCamX, R[b + 5] - frameCamY, R[b + 6] - frameCamZ))
		{
			return;
		}
		final int len = R[b];
		final int[] verts = recVerts[d];
		if (verts == null)
		{
			// no model at rest (prepareEmpty): nothing, as zoneEntry
			return;
		}
		vertexBuffer.ensureCapacity(verts.length);
		vertexBuffer.getBuffer().put(verts);
		int uvAt = -1;
		final float[] uvs = recUvs[d];
		if (uvs != null)
		{
			uvBuffer.ensureCapacity(uvs.length);
			uvBuffer.getBuffer().put(uvs);
			uvAt = tempUvOffset;
			tempUvOffset += len;
		}
		bufferForTriangles(len / 3).stage(tempOffset, uvAt, len / 3, targetBufferOffset, R[b + 3], R[b + 4], R[b + 5], R[b + 6]);
		final int before = targetBufferOffset;
		tempOffset += len;
		targetBufferOffset += len;
		if (len > 0)
		{
			if ((bits & RB_SEE) != 0)
			{
				seeRange(before, len);
			}
			if ((bits & RB_PAINTER) != 0)
			{
				painterRange(before, len);
			}
		}
	}

	// the developer check (Zone mode: check the walk): every prepared draw this frame worked out again from the client's
	// getters, against its record
	private void checkRecords(Scene scene, int[] params)
	{
		final FastOrder f = fastOrder;
		for (int k = 0; k < f.n; k++)
		{
			final int ref = f.kref[k];
			if (ref == -1)
			{
				continue;
			}
			final int r = ref >= 0 ? f.tileRec[ref] : f.oRec[-ref - 2];
			if (r < 0)
			{
				continue;
			}
			recChecked++;
			boolean same = prepare(scene, f.keys[k], f.ksrc[k], f.kkind[k], params, recCheck, 0, false);
			final boolean pushed = (recs[r * REC + 10] & RB_PUSHED) != 0;
			for (int j = 0; same && j < (pushed ? REC - 1 : REC); j++)
			{
				same = recCheck[j] == recs[r * REC + j];
			}
			if (same && pushed)
			{
				final int d = recs[r * REC + 11];
				same = Arrays.equals(checkVerts, recVerts[d]) && Arrays.equals(checkUvs, recUvs[d]);
			}
			if (!same)
			{
				recDiffer++;
				if (recExample == null)
				{
					recExample = String.format("%s at %d %d: kept %s, now %s", f.keys[k].getClass().getSimpleName(), f.kx[k], f.kz[k],
						Arrays.toString(Arrays.copyOfRange(recs, r * REC, r * REC + REC)), Arrays.toString(recCheck));
				}
			}
		}
		final long now = System.nanoTime();
		if (now - lastRecCheckLog > 5_000_000_000L)
		{
			lastRecCheckLog = now;
			log.info("Bitcrush zone mode's prepared draws: {} checked against the client's getters, {} differing{}; {} kept",
				recChecked, recDiffer, recExample != null ? " (the first: " + recExample + ")" : "", nRecs);
			recChecked = recDiffer = 0;
			recExample = null;
		}
	}

	private long lastRecCheckLog;

	// the at-rest objects' copied numbers dropped with the records
	private void dropRecData()
	{
		emptyObjects.clear();
		emptyZones.clear();
		Arrays.fill(recVerts, 0, nRecData, null);
		Arrays.fill(recUvs, 0, nRecData, null);
		Arrays.fill(recKeys, 0, nRecData, null);
		nRecData = 0;
	}

	// one entry of the walk, drawn as the old mode's callbacks would have drawn it
	private void zoneEntry(Scene scene, Object key, Object src, byte kind, int k, int[] params)
	{
		final long t0 = timing ? System.nanoTime() : 0;
		if (key instanceof SceneTilePaint || key instanceof SceneTileModel)
		{
			if (src instanceof Tile)
			{
				Tile tile = (Tile) src;
				if (!renderCallbackManager.drawTile(scene, tile))
				{
					return;
				}
				net.runelite.api.Point loc = tile.getSceneLocation();
				if (key instanceof SceneTilePaint)
				{
					drawScenePaint0(scene, (SceneTilePaint) key, tile.getPlane(), loc.getX(), loc.getY());
				}
				else
				{
					drawSceneTileModel0(scene, (SceneTileModel) key, loc.getX(), loc.getY());
				}
			}
			if (timing)
			{
				timer.callback(t0);
			}
			return;
		}
		if (!(key instanceof Renderable))
		{
			return;
		}
		final Renderable r = (Renderable) key;
		int o, x, y, z;
		long hash;
		Integer up = src instanceof GameObject ? zoneTemp.get(src) : null;
		if (up == null)
		{
			up = zoneDyn.get(r);
		}
		if (up != null)
		{
			// a temporary or dynamic object, copied when the client handed it over (-1: out of view)
			if (up >= 0)
			{
				zoneQueue(up);
			}
			if (timing)
			{
				timer.callback(t0);
			}
			return;
		}
		Model still = null;
		if (params == null)
		{
			// the reference walk's entry, without its source: not drawn (rare: a tile with over 8 objects)
			return;
		}
		if (r instanceof net.runelite.api.DynamicObject)
		{
			// one the client didn't hand over this frame: in zone mode only the ones animating come through drawDynamic;
			// the rest (POH furniture, farming patches, objects whose look follows the game's state) are the zone's static
			// scenery, drawn from their model without animation (11/19/2026, Jace: his POH jewellery box and a building
			// missing), as RuneLite's zone plugin uploads them
			still = ((net.runelite.api.DynamicObject) r).getModelZbuf();
			if (still == null)
			{
				return;
			}
		}
		{
			if (src instanceof TileObject && !renderCallbackManager.drawObject(scene, (TileObject) src))
			{
				return;
			}
			hash = fastOrder.draw(k, params);
			if (params[0] < 0)
			{
				return;
			}
			o = params[0];
			x = params[1];
			y = params[2];
			z = params[3];
		}
		final int before = targetBufferOffset;
		drawnSeeThrough = false;
		drawnPainter = false;
		drawSolidOnly = (kind & FastOrder.K_SOLID) != 0;
		try
		{
			drawRenderable(null, scene, r, still, o, x, y, z, hash);
		}
		finally
		{
			drawSolidOnly = false;
		}
		if (drawnSeeThrough && (kind & FastOrder.K_SOLID) == 0 && targetBufferOffset > before)
		{
			seeRange(before, targetBufferOffset - before);
		}
		if (drawnPainter && targetBufferOffset > before)
		{
			painterRange(before, targetBufferOffset - before);
		}
		if (timing)
		{
			timer.callback(t0);
		}
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
	// the model's culling: by the projection the client handed over, or (zone mode's own draws) the frame's camera
	private boolean visibleModel(Projection projection, Model model, int x, int y, int z)
	{
		if (projection instanceof IntProjection)
		{
			IntProjection p = (IntProjection) projection;
			return isVisible(model, p.getPitchSin(), p.getPitchCos(), p.getYawSin(), p.getYawCos(), x - p.getCameraX(), y - p.getCameraY(), z - p.getCameraZ());
		}
		if (projection == null && zoneFrame)
		{
			return isVisible(model, framePitchSin, framePitchCos, frameYawSin, frameYawCos, x - frameCamX, y - frameCamY, z - frameCamZ);
		}
		return true;
	}

	private void readClip()
	{
		clipZoom = client.get3dZoom();
		clipMidX2 = client.getRasterizer3D_clipMidX2();
		clipNegMidX = client.getRasterizer3D_clipNegativeMidX();
		clipNegMidY = client.getRasterizer3D_clipNegativeMidY();
		clipMidY2 = client.getRasterizer3D_clipMidY2();
		clipRead = true;
	}

	private boolean isVisible(Model model, float pitchSin, float pitchCos, float yawSin, float yawCos, int x, int y, int z)
	{
		return isVisible(model.getXYZMag(), model.getBottomY(), model.getModelHeight(), pitchSin, pitchCos, yawSin, yawCos, x, y, z);
	}

	private boolean isVisible(int xzMag, int bottomY, int modelHeight, float pitchSin, float pitchCos, float yawSin, float yawCos, int x, int y, int z)
	{
		if (!clipRead)
		{
			readClip();
		}
		final int zoom = clipZoom;

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
		if (zoneFrame)
		{
			return;  // zone mode: the scene comes from CPU Look's own walk
		}
		if (Build.DEVELOPER)
		{
			worldViewLog.draw(scene, projection, renderable, x, y, z);
		}
		final long t0 = timing ? System.nanoTime() : 0;
		final int before = targetBufferOffset;
		drawnSeeThrough = false;
		drawnPainter = false;
		if (orderTest)
		{
			recordCall(renderable, orientation, x, y, z, hash);
		}
		drawRenderable(projection, scene, renderable, orientation, x, y, z, hash);
		if (drawnSeeThrough && targetBufferOffset > before)
		{
			seeRange(before, targetBufferOffset - before);
		}
		if (drawnPainter && targetBufferOffset > before)
		{
			painterRange(before, targetBufferOffset - before);
		}
		if (timing)
		{
			timer.callback(t0);
		}
	}

	// Render mode 2 models, as the CPU draws them (its Model.draw with mode 2: the sorted faces with colour on and key
	// writes off, then again with colour off and key writes on): each model's solid faces tested against the depth from
	// before it, a later face covering an earlier one, then its depth written. Drawn after the other solid faces; the CPU
	// draws them in the game's order (the same pixels but where something drawn after the model in the CPU's first pass
	// cuts through it between its faces' depths). The geometry program, its uniforms set; the depth test on (greater).
	private void drawPainters()
	{
		GL43C.glUniform1i(uniAlphaPass, 1);
		GL43C.glUniform1i(uniPainterPass, 2);
		final boolean band = painterBand > 0 && fboPainter != -1;
		if (!Build.DEVELOPER || config.painterBatches())
		{
			drawPainterBatches(band);
			GL43C.glUniform1i(uniPainterPass, 0);
			return;
		}
		if (band)
		{
			GL43C.glUniform1i(uniPainterDepth, 4);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texPainterDepth);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
		}
		for (int i = 0; i < painterRanges; i++)
		{
			if (band)
			{
				// "Both fixed" (not the CPU's look): the model's own greatest key at each pixel, drawn alone first
				GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboPainter);
				GL43C.glDepthMask(true);
				GL43C.glClear(GL43C.GL_DEPTH_BUFFER_BIT);
				GL43C.glDrawArrays(GL43C.GL_TRIANGLES, painterFirst[i], painterCount[i]);
				GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboCpu);
				GL43C.glUniform1f(uniPainterBand, (float) painterBand);
			}
			GL43C.glDepthMask(false);
			GL43C.glDrawArrays(GL43C.GL_TRIANGLES, painterFirst[i], painterCount[i]);
			if (band)
			{
				GL43C.glUniform1f(uniPainterBand, 0f);
			}
			GL43C.glColorMask(false, false, false, false);
			GL43C.glDepthMask(true);
			GL43C.glDrawArrays(GL43C.GL_TRIANGLES, painterFirst[i], painterCount[i]);
			GL43C.glColorMask(true, true, true, true);
		}
		if (band)
		{
			GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
		}
		GL43C.glUniform1i(uniPainterPass, 0);
	}

	// Render mode 2 models in batches (11/19/2026): each model's colour then its keys (drawPainters), but the models whose
	// screen boxes don't overlap any earlier one's in the batch drawn together, a multi-draw for the colour and one for
	// the keys instead of two draws a model; with "Both fixed" also one clear of the scratch target and one draw into it
	// a batch, not a model (his GPU Trace 1, taken with Both fixed: 84 draws and 82 waits for the card at the GE, 0.87
	// ms; As the CPU's two draws a model cost about the same either way, run 8). Models that share no pixels can't
	// affect each other, nor each other's keys in the scratch target, and a model comes in a later batch than every
	// earlier model it overlaps (PainterBatch.layers), so every pixel is what the one-at-a-time drawing gives.
	private void drawPainterBatches(boolean band)
	{
		if (band)
		{
			GL43C.glUniform1i(uniPainterDepth, 4);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, texPainterDepth);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
		}
		final int n = painterRanges;
		final int batches = painterBatch.layers(n, painterX, painterY, painterZ, painterM, painterY0, painterY1, viewportWidth, viewportHeight);
		if (painterFirstBuf.capacity() < n)
		{
			painterFirstBuf = BufferUtils.createIntBuffer(n * 2);
			painterCountBuf = BufferUtils.createIntBuffer(n * 2);
		}
		final int[] layer = painterBatch.layer;
		for (int l = 0; l < batches; l++)
		{
			painterFirstBuf.clear();
			painterCountBuf.clear();
			for (int i = 0; i < n; i++)
			{
				if (layer[i] == l)
				{
					painterFirstBuf.put(painterFirst[i]);
					painterCountBuf.put(painterCount[i]);
				}
			}
			painterFirstBuf.flip();
			painterCountBuf.flip();
			if (band)
			{
				// "Both fixed": each model's own greatest key at each pixel, the batch's models alone together
				GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboPainter);
				GL43C.glDepthMask(true);
				GL43C.glClear(GL43C.GL_DEPTH_BUFFER_BIT);
				GL43C.glMultiDrawArrays(GL43C.GL_TRIANGLES, painterFirstBuf, painterCountBuf);
				GL43C.glBindFramebuffer(GL43C.GL_DRAW_FRAMEBUFFER, fboCpu);
				GL43C.glUniform1f(uniPainterBand, (float) painterBand);
			}
			GL43C.glDepthMask(false);
			GL43C.glMultiDrawArrays(GL43C.GL_TRIANGLES, painterFirstBuf, painterCountBuf);
			if (band)
			{
				GL43C.glUniform1f(uniPainterBand, 0f);
			}
			GL43C.glColorMask(false, false, false, false);
			GL43C.glDepthMask(true);
			GL43C.glMultiDrawArrays(GL43C.GL_TRIANGLES, painterFirstBuf, painterCountBuf);
			GL43C.glColorMask(true, true, true, true);
		}
		if (band)
		{
			GL43C.glActiveTexture(GL43C.GL_TEXTURE4);
			GL43C.glBindTexture(GL43C.GL_TEXTURE_2D, 0);
			GL43C.glActiveTexture(GL43C.GL_TEXTURE0);
		}
		if (timing)
		{
			timer.painters(n, batches);
		}
	}

	// stage B's B1 check: one draw as the client hands it over
	private void recordCall(Renderable r, int orientation, int x, int y, int z, long hash)
	{
		if (calls == callR.length)
		{
			int m = calls * 2;
			callR = Arrays.copyOf(callR, m);
			callO = Arrays.copyOf(callO, m);
			callX = Arrays.copyOf(callX, m);
			callY = Arrays.copyOf(callY, m);
			callZ = Arrays.copyOf(callZ, m);
			callH = Arrays.copyOf(callH, m);
		}
		callR[calls] = r;
		callO[calls] = orientation;
		callX[calls] = x;
		callY[calls] = y;
		callZ[calls] = z;
		callH[calls] = hash;
		calls++;
	}

	// Stage B's B1 check (claude/cpu-look-stage-b.md, 5b): each draw the client handed over this frame against the walk's
	// own working of it (FastOrder.draw, from the client's getters), matched by renderable: the same in full (turn, x,
	// height, z, hash), or which part differs; draws the client handed over that the walk doesn't have (temporary
	// objects off its tiles), and the walk's that the client didn't hand over. In the zone mode the walk's are all there is.
	private void fullCheck()
	{
		final FastOrder fo = fastOrder;
		final int n = fo.n;
		// the walk's entries by their object's hash (unique to an object: a model can be shared between objects, so
		// the renderable alone doesn't say which object it is), linked
		final java.util.HashMap<Long, Integer> head = new java.util.HashMap<>();
		final int[] next = new int[n];
		final long[] hashes = new long[n];
		final boolean[] claimed = new boolean[n];
		final int[] p = new int[4];
		int walkDraws = 0;
		for (int k = n - 1; k >= 0; k--)
		{
			next[k] = -1;
			if (fo.kkind[k] == FastOrder.K_GROUND)
			{
				continue;
			}
			walkDraws++;
			hashes[k] = fo.draw(k, p);
			Integer h = head.put(hashes[k], k);
			next[k] = h != null ? h : -1;
		}
		int same = 0, dO = 0, dX = 0, dY = 0, dZ = 0, otherRenderable = 0, clientOnly = 0, walkOnly = 0;
		String exDiff = null, exOther = null, exClient = null, exWalk = null;
		final java.util.TreeMap<String, Integer> clientKinds = new java.util.TreeMap<>(), walkKinds = new java.util.TreeMap<>();
		for (int i = 0; i < calls; i++)
		{
			Integer h = head.get(callH[i]);
			int match = -1, sameR = -1, any = -1;
			for (int k = h != null ? h : -1; k >= 0; k = next[k])
			{
				if (claimed[k])
				{
					continue;
				}
				if (any < 0)
				{
					any = k;
				}
				if (fo.keys[k] == callR[i])
				{
					fo.draw(k, p);
					if (p[0] == callO[i] && p[1] == callX[i] && p[2] == callY[i] && p[3] == callZ[i])
					{
						match = k;
						break;
					}
					if (sameR < 0)
					{
						sameR = k;
					}
				}
			}
			if (match >= 0)
			{
				claimed[match] = true;
				same++;
				continue;
			}
			String what = callR[i] == null ? "nothing" : callR[i].getClass().getSimpleName();
			if (sameR >= 0)
			{
				claimed[sameR] = true;
				fo.draw(sameR, p);
				dO += p[0] != callO[i] ? 1 : 0;
				dX += p[1] != callX[i] ? 1 : 0;
				dY += p[2] != callY[i] ? 1 : 0;
				dZ += p[3] != callZ[i] ? 1 : 0;
				if (exDiff == null)
				{
					exDiff = String.format("%s (kind %d): handed over turned %d at %d %d %d, the walk's turned %d at %d %d %d",
						what, fo.kkind[sameR], callO[i], callX[i], callY[i], callZ[i], p[0], p[1], p[2], p[3]);
				}
				continue;
			}
			if (any >= 0)
			{
				// the same object, another renderable (the client handed over a different one for it)
				claimed[any] = true;
				otherRenderable++;
				if (exOther == null)
				{
					exOther = String.format("hash %x: handed over %s, the walk's %s (kind %d)", callH[i], what,
						fo.keys[any].getClass().getSimpleName(), fo.kkind[any]);
				}
				continue;
			}
			clientOnly++;
			clientKinds.merge(what, 1, Integer::sum);
			if (exClient == null)
			{
				int tx = (callX[i] >> 7) + SCENE_OFFSET, ty = (callZ[i] >> 7) + SCENE_OFFSET, tp = (int) ((callH[i] >> TileObject.HASH_PLANE_SHIFT) & 3);
				exClient = String.format("%s turned %d at %d %d %d, hash %x (tile %d %d plane %d, %s)", what, callO[i], callX[i], callY[i],
					callZ[i], callH[i], tx, ty, tp, tx >= 0 && ty >= 0 && tx < Constants.EXTENDED_SCENE_SIZE && ty < Constants.EXTENDED_SCENE_SIZE
						&& visibleStamp[tp][tx][ty] == frameStamp ? "in view" : "not in view by tileInFrustum");
			}
		}
		for (int k = 0; k < n; k++)
		{
			if (fo.kkind[k] != FastOrder.K_GROUND && !claimed[k])
			{
				walkOnly++;
				String what = fo.keys[k].getClass().getSimpleName() + " kind " + fo.kkind[k];
				walkKinds.merge(what, 1, Integer::sum);
				if (exWalk == null)
				{
					fo.draw(k, p);
					Object src = fo.ksrc[k];
					String where = "";
					if (src instanceof TileObject)
					{
						TileObject t = (TileObject) src;
						where = String.format(" (plane %d, level shown %d)", t.getPlane(), framePlane);
					}
					exWalk = String.format("%s turned %d at %d %d %d, hash %x%s", what, p[0], p[1], p[2], p[3], hashes[k], where);
				}
			}
		}
		final long now = System.nanoTime();
		if (now - lastFullCheckLog > 5_000_000_000L)
		{
			lastFullCheckLog = now;
			log.info("Bitcrush zone-mode check (B1, by hash): {} draws handed over, {} in the walk; the same in full {}; the same object and renderable but differing in turn {}, x {}, height {}, z {} (first: {}); the same object, another renderable {} (first: {}); the client's alone {} {} (first: {}); the walk's alone {} {} (first: {})",
				calls, walkDraws, same, dO, dX, dY, dZ, exDiff, otherRenderable, exOther, clientOnly, clientKinds, exClient,
				walkOnly, walkKinds, exWalk);
		}
	}

	// a render mode 2 model's stretch of triangles (never joined to the last), with nothing known of where it is: it
	// overlaps every other in PainterBatch, so it's drawn on its own
	private void painterRange(int first, int count)
	{
		painterRange(first, count, 0, 0, 0, -1f, 0f, 0f);
	}

	// ... and with its place and its own bounds (PainterBatch.bounds; m < 0: unknown)
	private void painterRange(int first, int count, int x, int y, int z, float m, float y0, float y1)
	{
		if (painterRanges == painterFirst.length)
		{
			final int c = painterRanges * 2;
			painterFirst = Arrays.copyOf(painterFirst, c);
			painterCount = Arrays.copyOf(painterCount, c);
			painterX = Arrays.copyOf(painterX, c);
			painterY = Arrays.copyOf(painterY, c);
			painterZ = Arrays.copyOf(painterZ, c);
			painterM = Arrays.copyOf(painterM, c);
			painterY0 = Arrays.copyOf(painterY0, c);
			painterY1 = Arrays.copyOf(painterY1, c);
		}
		painterFirst[painterRanges] = first;
		painterCount[painterRanges] = count;
		painterX[painterRanges] = x;
		painterY[painterRanges] = y;
		painterZ[painterRanges] = z;
		painterM[painterRanges] = m;
		painterY0[painterRanges] = y0;
		painterY1[painterRanges] = y1;
		painterRanges++;
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
		drawRenderable(projection, scene, renderable, null, orientation, x, y, z, hash);
	}

	// given: the model the client already made for this draw (zone mode's dynamic and temporary objects), or null; no
	// projection in zone mode's own draws: the frame's camera is used for the culling, and the client checks clickboxes
	// itself there
	private void drawRenderable(Projection projection, Scene scene, Renderable renderable, Model given, int orientation, int x, int y, int z, long hash)
	{
		if (drawOrderLog.recording())
		{
			drawOrderLog.draw(scene, renderable, x, y, z, hash);
		}

		Model model, offsetModel;
		if (given != null)
		{
			model = given;
			offsetModel = given;
		}
		else if (renderable instanceof Model)
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
		if (Build.DEVELOPER && logPlayerModelNext && renderable == client.getLocalPlayer())
		{
			logPlayerModelNext = false;
			log.info("Bitcrush player model: {}", ModelDump.dump(model, renderable.getRenderMode(), orientation, x, y, z,
				(int) cameraX, (int) cameraY, (int) cameraZ, cameraYawUnits, cameraPitchUnits, client.getScale(), client.getCenterX(),
				client.getCenterY(), client.getViewportWidth(), client.getViewportHeight()));
		}
		// render mode 2 (players, most NPCs: the client's own rule, through RuneLite's Renderable.getRenderMode)
		final boolean painter = painterModels && renderable.getRenderMode() == Renderable.RENDERMODE_SORTED_NO_DEPTH;
		drawnPainter = painter;

		if (computeMode == ComputeMode.NONE)
		{
			// Apply height to renderable from the model
			if (model != renderable)
			{
				renderable.setModelHeight(model.getModelHeight());
			}

			model.calculateBoundsCylinder();

			if (!visibleModel(projection, model, x, y, z))
			{
				if (drawOrderLog.recording())
				{
					drawOrderLog.skipped();
				}
				return;
			}

			if (projection != null)
			{
				client.checkClickbox(projection, model, orientation, x, y, z, hash);
			}

			int start = targetBufferOffset;
			targetBufferOffset += sceneUploader.pushSortedModel(
				projection,
				model, orientation,
				x, y, z,
				vertexBuffer, uvBuffer);
			int m = markOf(scene, renderable, x, z, hash);
			mark(start, m, m != 0);
			mark(start, MARK_PAINTER, painter);
			mark(start, MARK_SOLID, drawSolidOnly);
			noteDraw(renderable, x, z, null, -1, targetBufferOffset - start, start);
		}
		// Model may be in the scene buffer
		else if (offsetModel.getSceneId() == sceneId)
		{
			assert model == renderable;

			model.calculateBoundsCylinder();

			if (!visibleModel(projection, model, x, y, z))
			{
				if (drawOrderLog.recording())
				{
					drawOrderLog.skipped();
				}
				return;
			}

			if (projection != null)
			{
				client.checkClickbox(projection, model, orientation, x, y, z, hash);
			}

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
				| (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0) | (painter ? FLAG_PAINTER : 0)
				| (drawSolidOnly ? FLAG_SOLID : 0));
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

			if (!visibleModel(projection, model, x, y, z))
			{
				if (drawOrderLog.recording())
				{
					drawOrderLog.skipped();
				}
				return;
			}

			if (projection != null)
			{
				client.checkClickbox(projection, model, orientation, x, y, z, hash);
			}

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
			buffer.put(orientation | (m == MARK_FREE ? FLAG_FREE : m == MARK_GROUND ? FLAG_GROUND : 0) | (painter ? FLAG_PAINTER : 0)
				| (drawSolidOnly ? FLAG_SOLID : 0));
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
		pass("records: count");
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
		pass("records: place");
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
		pass("records: note their faces");
		GL43C.glUniform1i(uniScanPhase, 2);
		GL43C.glDispatchCompute((faces + 1023) / 1024, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_SCATTER);
		}

		// the records, a thread each (only faces that draw are set up in full)
		pass("records: write");
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
		pass("records: solid faces");
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
		GL43C.glBindVertexArray(vaoRecords);
		GL43C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, recCommands.glBufferId);
		GL43C.glUniform1i(uniRecListB, 0);
		GL43C.glDrawArraysIndirect(GL43C.GL_TRIANGLES, 0L);
		if (keyTest && painterRanges > 0)
		{
			// render mode 2 models: their solid faces aren't in the solid list (setup_records.glsl); the geometry stage
			// draws them, a model at a time, the same pixels as the records would
			pass("records: players and NPCs (render mode 2)");
			GL43C.glUseProgram(glProgram);
			GL43C.glBindVertexArray(vaoCompute);
			drawPainters();
			GL43C.glUniform1i(uniAlphaPass, 0);
			GL43C.glUseProgram(glRecordProgram);
			GL43C.glBindVertexArray(vaoRecords);
		}
		if (timing)
		{
			timer.mark(FrameTimer.AFTER_SOLID);
		}
		if (keyTest)
		{
			pass("records: see-through faces");
			GL43C.glDepthMask(false);
			GL43C.glUniform1i(uniRecListB, 1);
			GL43C.glDrawArraysIndirect(GL43C.GL_TRIANGLES, 16L);
		}
		passEnd();
		GL43C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);
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

	// Diagnostics -> Name the card's passes for a profiler: each pass a KHR_debug group (glPushDebugGroup), so a GPU
	// profiler (Nsight Graphics' GPU Trace) shows the compute tiers, the record path's passes and the rest by name
	private boolean passNames, passOpen;

	private void pass(String name)
	{
		if (!passNames)
		{
			return;
		}
		if (passOpen)
		{
			GL43C.glPopDebugGroup();
		}
		GL43C.glPushDebugGroup(GL43C.GL_DEBUG_SOURCE_APPLICATION, 0, "Bitcrush: " + name);
		passOpen = true;
	}

	private void passEnd()
	{
		if (passOpen)
		{
			GL43C.glPopDebugGroup();
			passOpen = false;
		}
	}

	// the programs named too (glObjectLabel), for the profiler's shader rows
	private void nameProgram(int program, String name)
	{
		if (program > 0)
		{
			GL43C.glObjectLabel(GL43C.GL_PROGRAM, program, "Bitcrush: " + name);
		}
	}

	// Diagnostics -> Log the ground around me (GroundDump): the tiles within four of the local player's, every level
	private void logGround()
	{
		final net.runelite.api.Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		final WorldView wv = client.getTopLevelWorldView();
		final net.runelite.api.coords.LocalPoint at = me.getLocalLocation();
		try
		{
			final java.util.List<String> lines = GroundDump.dump(wv, at.getSceneX(), at.getSceneY(), 4, 0, (int) cameraX, (int) cameraY,
				(int) cameraZ, cameraYawUnits, cameraPitchUnits, client.getScale(), client.getCenterX(), client.getCenterY(),
				client.getViewportWidth(), client.getViewportHeight());
			for (String line : lines)
			{
				log.info("Bitcrush ground dump: {}", line);
			}
			log.info("Bitcrush ground dump: done, {} lines", lines.size());
		}
		catch (RuntimeException ex)
		{
			log.warn("Bitcrush ground dump failed", ex);
		}
	}

	private void viewSizeOf(int program)
	{
		if (program != -1)
		{
			GL43C.glProgramUniform2i(program, GL43C.glGetUniformLocation(program, "viewSize"), viewportWidth, viewportHeight);
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

	/**
	 * A tile's level for which tiles the client draws (ei.dm, read 11/19/2026): its plane, or 0 when its settings say
	 * it's seen from below (8). The client marks that at load from the settings at the tile's own level, before
	 * bridges move each tile above one down a level (the loader sets the mark, then moves them), so under a bridge
	 * (level 1's settings 2) a tile's settings are read one level up from where it now sits (11/19/2026: zone mode drew
	 * part of the Grand Exchange's upper level that the CPU leaves out). The scene's edge tiles are never marked.
	 */
	static int drawLevel(byte[][][] settings, int p, int x, int y)
	{
		if (settings == null || x <= 0 || y <= 0 || x >= settings[0].length - 1 || y >= settings[0][0].length - 1)
		{
			return p;
		}
		final int at = (settings[1][x][y] & 2) != 0 ? (p + 1) & 3 : p;
		return (settings[at][x][y] & 8) != 0 ? 0 : p;
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
