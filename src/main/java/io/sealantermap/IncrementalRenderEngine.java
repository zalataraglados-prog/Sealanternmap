package io.sealantermap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.Bukkit;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.EnumMap;
import java.util.BitSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

final class IncrementalRenderEngine {
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int CHUNKS_PER_REGION_EDGE = 32;
    private static final int CHUNKS_PER_REGION = CHUNKS_PER_REGION_EDGE * CHUNKS_PER_REGION_EDGE;
    private static final int LOCATION_TABLE_BYTES = CHUNKS_PER_REGION * 4;
    private static final int PRECUT_TILE_SIZE = 256;
    private static final int MAX_CHUNK_SAMPLE_CACHE_ENTRIES = 65_536;
    private static final int MAX_CHUNK_DETAIL_CACHE_ENTRIES = 1_024;
    private static final long MAX_REGION_RASTER_CACHE_BYTES = 96L * 1024L * 1024L;
    // Multi-point sampling inside a chunk for better visual readability.
    private static final int[][] CHUNK_SAMPLE_OFFSETS = {
            {8, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12}
    };

    private final Path worldPath;
    private final Path regionDir;
    private final Path outputImage;
    private final Path outputMask;
    private final Path tileRootDir;
    private final World world;
    private final int pixelSize;
    private final int emptyRgb;
    private final int filledRgb;
    private final boolean unknownFogEnabled;
    private final int fogDisabledRgb;
    // Deprecated compatibility fields:
    // retained in constructor/config pipeline, but boundary rendering is retired.
    private final boolean chunkBoundaryEnabled;
    private final int chunkBoundaryRgb;
    private final Logger logger;
    private final int startupWindowChunks;
    private final boolean predictiveStartupEnabled;
    private final long predictorSalt;
    private final int biomeSampleY;
    private final boolean texturePaletteEnabled;
    private final Map<Material, Integer> texturePalette;
    private final EnumMap<Material, Integer> materialColorCache = new EnumMap<>(Material.class);
    private final Map<Long, Integer> chunkSampleColorCache = new LinkedHashMap<>(16_384, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > MAX_CHUNK_SAMPLE_CACHE_ENTRIES;
        }
    };
    private final Map<Long, int[]> chunkDetailedColorCache = new LinkedHashMap<>(1_024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > MAX_CHUNK_DETAIL_CACHE_ENTRIES;
        }
    };
    private final Map<Long, RegionRasterCacheEntry> regionRasterCache = new LinkedHashMap<>(32, 0.75f, true);
    private long regionRasterCacheBytes = 0L;

    private final Object lock = new Object();
    private final Map<Long, RegionState> regions = new HashMap<>();
    private final Set<String> pendingRegionFiles = new HashSet<>();

    private BufferedImage canvas;
    private int canvasWidthPixels = 0;
    private int canvasHeightPixels = 0;
    private int minChunkX = 0;
    private int maxChunkX = 0;
    private int minChunkZ = 0;
    private int maxChunkZ = 0;
    private long chunkCount = 0;
    private long renderCount = 0;
    private long levelDatLastModifiedMs = 0;
    private long playerDataFileCount = 0;
    private boolean initialized = false;
    private boolean startupWindowLocked = false;
    private int startupWindowMinChunkX = 0;
    private int startupWindowMaxChunkX = 0;
    private int startupWindowMinChunkZ = 0;
    private int startupWindowMaxChunkZ = 0;
    private BufferedImage startupPredictionCanvas;
    // Only true while building the startup baseline render pass.
    // Prevents predictive colors from leaking into steady-state incremental/full renders.
    private boolean predictiveSamplingActive = false;

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean watchRunning;
    private volatile boolean forceRebuild = false;

    static final class FullRenderSession {
        private enum Phase {
            SCANNING,
            PREPARE_CANVAS,
            DRAWING,
            FINALIZING,
            DONE
        }

        private final String reason;
        private final List<Path> files;
        private final List<RegionState> orderedStates = new ArrayList<>();
        private Phase phase = Phase.SCANNING;
        private int scanIndex = 0;
        private int drawIndex = 0;
        private RegionState activeDrawRegion;
        private int activeDrawChunkBit = -1;
        private BufferedImage activeRegionCanvas;
        private int activeRegionPixelX;
        private int activeRegionPixelZ;
        private long totalDrawableChunks = 0L;
        private long drawnChunks = 0L;
        private int localMinX = Integer.MAX_VALUE;
        private int localMaxX = Integer.MIN_VALUE;
        private int localMinZ = Integer.MAX_VALUE;
        private int localMaxZ = Integer.MIN_VALUE;

        private FullRenderSession(String reason, List<Path> files) {
            this.reason = reason;
            this.files = files;
        }

        private int totalFiles() {
            return files.size();
        }
    }

    static final class FullRenderStepResult {
        final boolean done;
        final RenderSnapshot snapshot;

        private FullRenderStepResult(boolean done, RenderSnapshot snapshot) {
            this.done = done;
            this.snapshot = snapshot;
        }
    }

    IncrementalRenderEngine(
            Path worldPath,
            Path regionDir,
            Path outputImage,
            Path outputMask,
            World world,
            int startupWindowChunks,
            boolean predictiveStartupEnabled,
            boolean texturePaletteEnabled,
            String minecraftJarPath,
            int pixelSize,
            Color emptyColor,
            Color filledColor,
            boolean unknownFogEnabled,
            Color fogDisabledColor,
            boolean chunkBoundaryEnabled,
            Color chunkBoundaryColor,
            Logger logger
    ) {
        this.worldPath = worldPath;
        this.regionDir = regionDir;
        this.outputImage = outputImage;
        this.outputMask = outputMask;
        this.tileRootDir = resolveTileRootDir(outputImage);
        this.world = world;
        this.startupWindowChunks = Math.max(0, startupWindowChunks);
        this.predictiveStartupEnabled = predictiveStartupEnabled;
        this.texturePaletteEnabled = texturePaletteEnabled;
        this.pixelSize = Math.max(1, pixelSize);
        this.emptyRgb = emptyColor.getRGB();
        this.filledRgb = filledColor.getRGB();
        this.unknownFogEnabled = unknownFogEnabled;
        this.fogDisabledRgb = fogDisabledColor.getRGB();
        // Boundary settings are carried for backward-compatible config/schema handling only.
        // Engine render path no longer consumes these values.
        this.chunkBoundaryEnabled = chunkBoundaryEnabled;
        this.chunkBoundaryRgb = chunkBoundaryColor.getRGB();
        this.logger = logger;
        this.predictorSalt = buildPredictorSalt(world);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int seaY = world.getSeaLevel();
        this.biomeSampleY = Math.min(maxY, Math.max(minY, seaY));
        /*
         * Texture palette is fixed ON in current pipeline.
         *
         * We keep the constructor parameter for backward compatibility with existing
         * call sites/config schema, but runtime no longer supports a disabled palette
         * mode because it causes color-style drift between sessions.
         *
         * Legacy disabled branch (kept here as migration note):
         * this.texturePalette = texturePaletteEnabled
         *         ? TextureColorPalette.load(logger, minecraftJarPath)
         *         : Map.of();
         */
        this.texturePalette = TextureColorPalette.load(logger, minecraftJarPath);
    }

    RenderSnapshot renderStartupBaseline(String reason) throws Exception {
        if (startupWindowChunks <= 0) {
            return renderFull(reason);
        }

        synchronized (lock) {
            predictiveSamplingActive = predictiveStartupEnabled;
            try {
                canvasWidthPixels = 0;
                canvasHeightPixels = 0;
                regions.clear();
                chunkCount = 0L;

                Location spawn = world.getSpawnLocation();
                int centerChunkX = spawn.getBlockX() >> 4;
                int centerChunkZ = spawn.getBlockZ() >> 4;
                int half = Math.max(1, startupWindowChunks / 2);
                int windowMinX = centerChunkX - half;
                int windowMaxX = centerChunkX + half - 1;
                int windowMinZ = centerChunkZ - half;
                int windowMaxZ = centerChunkZ + half - 1;

                startupWindowLocked = true;
                startupWindowMinChunkX = windowMinX;
                startupWindowMaxChunkX = windowMaxX;
                startupWindowMinChunkZ = windowMinZ;
                startupWindowMaxChunkZ = windowMaxZ;

                minChunkX = windowMinX;
                maxChunkX = windowMaxX;
                minChunkZ = windowMinZ;
                maxChunkZ = windowMaxZ;

                startupPredictionCanvas = shouldBuildStartupPredictionLayer()
                        ? buildPredictiveCanvasForCurrentBounds()
                        : null;
                canvas = startupPredictionCanvas != null ? copyImage(startupPredictionCanvas) : createBackgroundCanvasForCurrentBounds();
                rememberCanvasMetrics(canvas);

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
                    for (Path file : stream) {
                        RegionState raw = loadRegionFromDisk(file);
                        if (raw == null || raw.chunkCount == 0) {
                            continue;
                        }
                        BitSet clipped = clipChunksToWindow(raw.regionX, raw.regionZ, raw.chunks, windowMinX, windowMaxX, windowMinZ, windowMaxZ);
                        int clippedCount = clipped.cardinality();
                        if (clippedCount == 0) {
                            continue;
                        }

                        RegionState state = new RegionState(raw.regionX, raw.regionZ, clipped, clippedCount, raw.fileSize, raw.lastModifiedMs);
                        regions.put(packKey(state.regionX, state.regionZ), state);
                        chunkCount += state.chunkCount;
                        drawRegion(canvas, state);
                    }
                }
                writeCanvasToDisk(null);
                initialized = true;
                forceRebuild = false;
                pendingRegionFiles.clear();
                refreshSaveMeta();
                renderCount++;
                String mode = startupPredictionCanvas != null ? "predictive" : "fog-mask";
                releaseRenderBuffers();
                return snapshot("ok", reason, "startup " + mode + " baseline " + startupWindowChunks + "x" + startupWindowChunks + " chunks");
            } finally {
                predictiveSamplingActive = false;
            }
        }
    }

    void startWatcher() throws IOException {
        synchronized (lock) {
            if (watchRunning) {
                return;
            }
            watchService = regionDir.getFileSystem().newWatchService();
            regionDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            watchRunning = true;
        }

        watchThread = new Thread(this::watchLoop, "sealantermap-watch");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    void stopWatcher() {
        synchronized (lock) {
            watchRunning = false;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // no-op
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }

    /**
     * Legacy full render entry.
     *
     * This path clears in-memory region state, rescans all region files and rebuilds
     * the whole canvas in one call. It is kept for bootstrap and fallback scenarios
     * where incremental assumptions are no longer trustworthy.
     */
    RenderSnapshot renderFull(String reason) throws Exception {
        synchronized (lock) {
            canvasWidthPixels = 0;
            canvasHeightPixels = 0;
            regions.clear();
            chunkCount = 0L;
            startupWindowLocked = false;
            startupPredictionCanvas = null;
            int localMinX = Integer.MAX_VALUE;
            int localMaxX = Integer.MIN_VALUE;
            int localMinZ = Integer.MAX_VALUE;
            int localMaxZ = Integer.MIN_VALUE;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
                for (Path file : stream) {
                    RegionState state = loadRegionFromDisk(file);
                    if (state == null || state.chunkCount == 0) {
                        continue;
                    }
                    long key = packKey(state.regionX, state.regionZ);
                    regions.put(key, state);
                    chunkCount += state.chunkCount;

                    RegionBounds b = regionBounds(state.regionX, state.regionZ);
                    localMinX = Math.min(localMinX, b.minChunkX);
                    localMaxX = Math.max(localMaxX, b.maxChunkX);
                    localMinZ = Math.min(localMinZ, b.minChunkZ);
                    localMaxZ = Math.max(localMaxZ, b.maxChunkZ);
                }
            }

            if (regions.isEmpty()) {
                minChunkX = 0;
                maxChunkX = 0;
                minChunkZ = 0;
                maxChunkZ = 0;
                canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                canvas.setRGB(0, 0, backgroundRgb());
                rememberCanvasMetrics(canvas);
            } else {
                minChunkX = localMinX;
                maxChunkX = localMaxX;
                minChunkZ = localMinZ;
                maxChunkZ = localMaxZ;
                rebuildCanvasFromStates();
            }

            writeCanvasToDisk(null);
            initialized = true;
            forceRebuild = false;
            pendingRegionFiles.clear();
            refreshSaveMeta();
            renderCount++;
            releaseRenderBuffers();
            return snapshot("ok", reason, "full render complete");
        }
    }

    FullRenderSession beginFullRenderSession(String reason) throws Exception {
        synchronized (lock) {
            canvasWidthPixels = 0;
            canvasHeightPixels = 0;
            regions.clear();
            chunkCount = 0L;
            startupWindowLocked = false;
            startupPredictionCanvas = null;

            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
                for (Path file : stream) {
                    files.add(file);
                }
            }
            files.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

            FullRenderSession session = new FullRenderSession(reason, files);
            if (files.isEmpty()) {
                session.phase = FullRenderSession.Phase.PREPARE_CANVAS;
            }
            return session;
        }
    }

    /**
     * Step-based full render state machine.
     *
     * The caller provides two budgets:
     * - {@code maxRegionsPerStep}: upper bound of drawable work units in this tick
     * - {@code maxMillisPerStep}: wall-clock guard to prevent long single-step stalls
     *
     * The method advances the session phase in order:
     * SCANNING -> PREPARE_CANVAS -> DRAWING -> FINALIZING -> DONE.
     * It may exit early when either budget is exhausted and return a progress snapshot.
     */
    FullRenderStepResult stepFullRenderSession(
            FullRenderSession session,
            int maxRegionsPerStep,
            long maxMillisPerStep
    ) throws Exception {
        if (session == null) {
            throw new IllegalArgumentException("full render session is null");
        }
        synchronized (lock) {
            if (session.phase == FullRenderSession.Phase.DONE) {
                return new FullRenderStepResult(true, snapshot("ok", session.reason, "full render complete"));
            }

            int chunkBudget = Math.max(1, maxRegionsPerStep);
            long nanosBudget = Math.max(1L, maxMillisPerStep) * 1_000_000L;
            long deadline = System.nanoTime() + nanosBudget;
            int processed = 0;

            while (processed < chunkBudget && System.nanoTime() <= deadline) {
                switch (session.phase) {
                    case SCANNING:
                        processed += stepScanPhase(session);
                        break;
                    case PREPARE_CANVAS:
                        stepPrepareCanvasPhase(session);
                        break;
                    case DRAWING:
                        processed += stepDrawingPhase(session, chunkBudget - processed, deadline);
                        break;
                    case FINALIZING:
                        return stepFinalizingPhase(session);
                    case DONE:
                        return new FullRenderStepResult(true, snapshot("ok", session.reason, "full render complete"));
                    default:
                        return new FullRenderStepResult(false, buildFullRenderProgressSnapshot(session));
                }
            }

            return new FullRenderStepResult(false, buildFullRenderProgressSnapshot(session));
        }
    }

    /**
     * Scan one region file and update aggregate bounds/counters.
     *
     * Returns consumed work units (0/1). When all files are scanned the phase is
     * transitioned to PREPARE_CANVAS.
     */
    private int stepScanPhase(FullRenderSession session) throws IOException {
        if (session.scanIndex >= session.totalFiles()) {
            session.phase = FullRenderSession.Phase.PREPARE_CANVAS;
            return 0;
        }

        Path file = session.files.get(session.scanIndex++);
        RegionState state = loadRegionFromDisk(file);
        if (state != null && state.chunkCount > 0) {
            long key = packKey(state.regionX, state.regionZ);
            regions.put(key, state);
            session.orderedStates.add(state);
            chunkCount += state.chunkCount;

            RegionBounds b = regionBounds(state.regionX, state.regionZ);
            session.localMinX = Math.min(session.localMinX, b.minChunkX);
            session.localMaxX = Math.max(session.localMaxX, b.maxChunkX);
            session.localMinZ = Math.min(session.localMinZ, b.minChunkZ);
            session.localMaxZ = Math.max(session.localMaxZ, b.maxChunkZ);
            session.totalDrawableChunks += state.chunkCount;
        }
        if (session.scanIndex >= session.totalFiles()) {
            session.phase = FullRenderSession.Phase.PREPARE_CANVAS;
        }
        return 1;
    }

    private void stepPrepareCanvasPhase(FullRenderSession session) throws IOException {
        preparePrecutTileRootForFullRender();
        if (regions.isEmpty()) {
            minChunkX = 0;
            maxChunkX = 0;
            minChunkZ = 0;
            maxChunkZ = 0;
            canvasWidthPixels = 1;
            canvasHeightPixels = 1;
            writeSolidTile(0, 0, 1, 1, backgroundRgb());
            session.phase = FullRenderSession.Phase.FINALIZING;
            return;
        }

        minChunkX = session.localMinX;
        maxChunkX = session.localMaxX;
        minChunkZ = session.localMinZ;
        maxChunkZ = session.localMaxZ;
        canvasWidthPixels = Math.max(1, (maxChunkX - minChunkX + 1) * pixelSize);
        canvasHeightPixels = Math.max(1, (maxChunkZ - minChunkZ + 1) * pixelSize);
        /*
         * Prime tile canvas for the entire current bounds before region overlays.
         *
         * Why:
         * 1) Step-based full render writes region tiles progressively and does not keep one
         *    giant in-memory image. Without prefill, areas without region files can remain
         *    missing in tile output.
         * 2) Unknown-fog OFF should still show baseline map content (predictive/base color)
         *    instead of blank viewer background.
         * 3) Region flush phase only needs to overwrite persisted-chunk areas on top of this
         *    baseline, preserving incremental write behavior.
         */
        prefillPrecutTilesForCurrentBounds();
        session.phase = FullRenderSession.Phase.DRAWING;
    }

    /**
     * Draw as many chunks as allowed by remaining budget and deadline.
     *
     * This is intentionally chunk-budgeted so large worlds do not block a single
     * scheduler cycle. The caller is expected to invoke this method repeatedly until
     * session phase reaches FINALIZING.
     */
    private int stepDrawingPhase(FullRenderSession session, int remainingBudget, long deadlineNanos) throws IOException {
        if (remainingBudget <= 0) {
            return 0;
        }

        if (session.activeDrawRegion == null) {
            if (session.drawIndex >= session.orderedStates.size()) {
                session.phase = FullRenderSession.Phase.FINALIZING;
                return 0;
            }
            session.activeDrawRegion = session.orderedStates.get(session.drawIndex);
            session.activeDrawChunkBit = session.activeDrawRegion.chunks.nextSetBit(0);
            prepareActiveRegionCanvas(session);
        }

        int consumed = 0;
        while (consumed < remainingBudget && System.nanoTime() <= deadlineNanos && session.activeDrawRegion != null) {
            if (session.activeDrawChunkBit < 0) {
                flushActiveRegionCanvasToTiles(session);
                session.activeRegionCanvas = null;
                session.drawIndex++;
                session.activeDrawRegion = null;
                break;
            }

            drawRegionChunkOnActiveCanvas(session, session.activeDrawRegion, session.activeDrawChunkBit);
            session.drawnChunks++;
            consumed++;
            session.activeDrawChunkBit = session.activeDrawRegion.chunks.nextSetBit(session.activeDrawChunkBit + 1);
        }
        return consumed;
    }

    /**
     * Persist the rebuilt canvas and publish final snapshot.
     */
    private FullRenderStepResult stepFinalizingPhase(FullRenderSession session) throws IOException {
        if (session.activeRegionCanvas != null) {
            flushActiveRegionCanvasToTiles(session);
            session.activeRegionCanvas = null;
        }
        writeImageAtomic(buildKnownMask(), outputMask);
        Files.deleteIfExists(outputImage);
        initialized = true;
        forceRebuild = false;
        pendingRegionFiles.clear();
        refreshSaveMeta();
        renderCount++;
        session.phase = FullRenderSession.Phase.DONE;
        releaseRenderBuffers();
        return new FullRenderStepResult(true, snapshot("ok", session.reason, "full render complete"));
    }

    /**
     * Incremental render entry.
     *
     * Decision order:
     * 1) Bootstrap to full render if engine is not initialized.
     * 2) Skip when no pending region changes and no forced rebuild.
     * 3) Process changed region files and classify each change as:
     *    - no-op
     *    - patchable dirty rectangle update
     *    - rebuild-required change
     * 4) Execute either partial patch write or full canvas rebuild.
     */
    RenderSnapshot renderIncremental(String reason) throws Exception {
        synchronized (lock) {
            if (!initialized) {
                return renderFull(reason + "-bootstrap");
            }

            boolean hasPending = !pendingRegionFiles.isEmpty();
            if (!hasPending && !forceRebuild) {
                refreshSaveMeta();
                return snapshot("skipped", reason, "no changed region file");
            }

            Set<String> changed = new HashSet<>(pendingRegionFiles);
            pendingRegionFiles.clear();

            boolean rebuildNeeded = forceRebuild;
            boolean unknownRebuildCause = forceRebuild;
            forceRebuild = false;
            boolean changedApplied = false;
            int dirtyMinPixelX = Integer.MAX_VALUE;
            int dirtyMinPixelZ = Integer.MAX_VALUE;
            int dirtyMaxPixelXExclusive = Integer.MIN_VALUE;
            int dirtyMaxPixelZExclusive = Integer.MIN_VALUE;

            for (String fileName : changed) {
                IncrementalChangeResult result = processChangedRegion(fileName);
                if (!result.changed()) {
                    continue;
                }
                changedApplied = true;
                if (result.rebuildNeeded()) {
                    rebuildNeeded = true;
                    continue;
                }

                PixelRect dirty = result.dirtyRect();
                if (dirty != null) {
                    dirtyMinPixelX = Math.min(dirtyMinPixelX, dirty.x());
                    dirtyMinPixelZ = Math.min(dirtyMinPixelZ, dirty.y());
                    dirtyMaxPixelXExclusive = Math.max(dirtyMaxPixelXExclusive, dirty.x() + dirty.width());
                    dirtyMaxPixelZExclusive = Math.max(dirtyMaxPixelZExclusive, dirty.y() + dirty.height());
                }
            }

            if (!changedApplied && !rebuildNeeded) {
                refreshSaveMeta();
                return snapshot("skipped", reason, "pending queue had no effective changes");
            }

            if (rebuildNeeded) {
                if (unknownRebuildCause) {
                    clearChunkRenderCaches();
                }
                if (!startupWindowLocked) {
                    recalcBoundsFromStates();
                }
                rebuildCanvasFromStates();
                writeCanvasToDisk(null);
            } else {
                if (dirtyMinPixelX == Integer.MAX_VALUE || dirtyMinPixelZ == Integer.MAX_VALUE) {
                    writeCanvasToDisk(null);
                } else {
                    int rectWidth = Math.max(1, dirtyMaxPixelXExclusive - dirtyMinPixelX);
                    int rectHeight = Math.max(1, dirtyMaxPixelZExclusive - dirtyMinPixelZ);
                    writeCanvasToDisk(new PixelRect(dirtyMinPixelX, dirtyMinPixelZ, rectWidth, rectHeight));
                }
            }
            refreshSaveMeta();
            renderCount++;
            releaseRenderBuffers();
            return snapshot("ok", reason, rebuildNeeded ? "incremental+rebuild" : "incremental patch");
        }
    }

    /**
     * Convert one changed region filename into an incremental action.
     *
     * Contract:
     * - invalid filename -> ignored()
     * - no effective chunk diff -> unchanged()
     * - bounds/topology sensitive change -> rebuild()
     * - in-bounds content diff -> patched(PixelRect)
     */
    private IncrementalChangeResult processChangedRegion(String fileName) throws Exception {
        RegionCoord coord = parseRegionFileName(fileName);
        if (coord == null) {
            return IncrementalChangeResult.ignored();
        }

        long key = packKey(coord.regionX, coord.regionZ);
        RegionState oldState = regions.get(key);
        RegionState newState = loadRegionFromDisk(regionDir.resolve(fileName));
        if (newState != null && newState.chunkCount == 0) {
            newState = null;
        }
        if (startupWindowLocked) {
            oldState = clipStateToStartupWindow(oldState);
            newState = clipStateToStartupWindow(newState);
        }

        if (sameRegionState(oldState, newState)) {
            return IncrementalChangeResult.ignored();
        }

        if (edgeSensitiveChange(oldState, newState)) {
            applyState(key, oldState, newState);
            return IncrementalChangeResult.rebuildRequired();
        }

        if (canvas == null && !ensureCanvasLoadedFromDisk()) {
            applyState(key, oldState, newState);
            return IncrementalChangeResult.rebuildRequired();
        }

        RegionState patchTarget = newState != null ? newState : oldState;
        PixelRect dirtyRect = regionDirtyRect(patchTarget);
        applyRegionPatch(oldState, newState);
        applyState(key, oldState, newState);
        return IncrementalChangeResult.patched(dirtyRect);
    }

    private PixelRect regionDirtyRect(RegionState state) {
        if (state == null) {
            return null;
        }
        int regionMinChunkX = state.regionX * CHUNKS_PER_REGION_EDGE;
        int regionMinChunkZ = state.regionZ * CHUNKS_PER_REGION_EDGE;
        int pixelX = (regionMinChunkX - minChunkX) * pixelSize;
        int pixelZ = (regionMinChunkZ - minChunkZ) * pixelSize;
        int regionPixels = CHUNKS_PER_REGION_EDGE * pixelSize;
        return new PixelRect(pixelX, pixelZ, regionPixels, regionPixels);
    }

    void markAllDirty() {
        synchronized (lock) {
            forceRebuild = true;
        }
    }

    /**
     * Region directory watch loop.
     *
     * Any overflow/invalid watch key is treated as potential event loss and escalated
     * to {@code forceRebuild=true}. This keeps correctness over opportunistic patching.
     */
    private void watchLoop() {
        while (watchRunning) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        synchronized (lock) {
                            forceRebuild = true;
                        }
                        continue;
                    }

                    Object context = event.context();
                    if (!(context instanceof Path path)) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (!REGION_FILE_PATTERN.matcher(fileName).matches()) {
                        continue;
                    }

                    synchronized (lock) {
                        pendingRegionFiles.add(fileName);
                    }
                }
                if (!key.reset()) {
                    synchronized (lock) {
                        forceRebuild = true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException ignored) {
                // Expected when watcher is intentionally stopped/rebuilt.
                return;
            } catch (Exception e) {
                if (!watchRunning) {
                    return;
                }
                logger.warning("Region watch loop error: " + e.getMessage());
                synchronized (lock) {
                    forceRebuild = true;
                }
            }
        }
    }

    private static long packKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private void invalidateChunkRenderCacheForRegion(int regionX, int regionZ) {
        int minX = regionX * CHUNKS_PER_REGION_EDGE;
        int minZ = regionZ * CHUNKS_PER_REGION_EDGE;
        for (int localZ = 0; localZ < CHUNKS_PER_REGION_EDGE; localZ++) {
            int chunkZ = minZ + localZ;
            for (int localX = 0; localX < CHUNKS_PER_REGION_EDGE; localX++) {
                int chunkX = minX + localX;
                long key = packChunkKey(chunkX, chunkZ);
                chunkSampleColorCache.remove(key);
                chunkDetailedColorCache.remove(key);
            }
        }
        invalidateRegionRasterCacheEntry(regionX, regionZ);
    }

    private void clearChunkRenderCaches() {
        chunkSampleColorCache.clear();
        chunkDetailedColorCache.clear();
        clearRegionRasterCache();
    }

    private void invalidateRegionRasterCacheEntry(int regionX, int regionZ) {
        long key = packKey(regionX, regionZ);
        RegionRasterCacheEntry removed = regionRasterCache.remove(key);
        if (removed != null) {
            regionRasterCacheBytes = Math.max(0L, regionRasterCacheBytes - removed.approxBytes);
        }
    }

    private void clearRegionRasterCache() {
        regionRasterCache.clear();
        regionRasterCacheBytes = 0L;
    }

    private static long estimateImageBytes(BufferedImage image) {
        return (long) image.getWidth() * (long) image.getHeight() * 4L;
    }

    private static long mix(long hash, long value) {
        hash ^= value + 0x9e3779b97f4a7c15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    private long regionRasterSignature(RegionState region) {
        long hash = 0x6a09e667f3bcc909L;
        hash = mix(hash, region.regionX);
        hash = mix(hash, region.regionZ);
        hash = mix(hash, region.chunkCount);
        hash = mix(hash, region.fileSize);
        hash = mix(hash, region.lastModifiedMs);
        hash = mix(hash, region.chunks.hashCode());
        hash = mix(hash, pixelSize);
        /*
         * Chunk-boundary overlay is retired. Signature intentionally ignores boundary
         * toggle/color so deprecated config values do not trigger unnecessary raster
         * cache churn.
         *
         * Legacy lines kept here as documentation:
         * hash = mix(hash, chunkBoundaryEnabled ? 1L : 0L);
         * hash = mix(hash, chunkBoundaryRgb);
         */
        hash = mix(hash, predictiveStartupEnabled ? 1L : 0L);
        hash = mix(hash, texturePaletteEnabled ? 1L : 0L);
        hash = mix(hash, predictorSalt);
        hash = mix(hash, biomeSampleY);
        return hash;
    }

    private BufferedImage createRegionRaster(RegionState region) {
        int regionPixels = CHUNKS_PER_REGION_EDGE * pixelSize;
        BufferedImage raster = new BufferedImage(regionPixels, regionPixels, BufferedImage.TYPE_INT_ARGB);
        for (int idx = region.chunks.nextSetBit(0); idx >= 0; idx = region.chunks.nextSetBit(idx + 1)) {
            int localX = idx & 31;
            int localZ = idx >> 5;
            int chunkX = (region.regionX * CHUNKS_PER_REGION_EDGE) + localX;
            int chunkZ = (region.regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
            int pixelX = localX * pixelSize;
            int pixelZ = localZ * pixelSize;
            drawChunkCell(raster, pixelX, pixelZ, chunkX, chunkZ);
        }
        return raster;
    }

    private void cacheRegionRaster(long key, long signature, BufferedImage raster) {
        long approxBytes = estimateImageBytes(raster);
        RegionRasterCacheEntry old = regionRasterCache.put(key, new RegionRasterCacheEntry(signature, raster, approxBytes));
        if (old != null) {
            regionRasterCacheBytes = Math.max(0L, regionRasterCacheBytes - old.approxBytes);
        }
        regionRasterCacheBytes += approxBytes;
        while (regionRasterCacheBytes > MAX_REGION_RASTER_CACHE_BYTES && !regionRasterCache.isEmpty()) {
            Map.Entry<Long, RegionRasterCacheEntry> eldest = regionRasterCache.entrySet().iterator().next();
            RegionRasterCacheEntry removed = eldest.getValue();
            regionRasterCache.remove(eldest.getKey());
            if (removed != null) {
                regionRasterCacheBytes = Math.max(0L, regionRasterCacheBytes - removed.approxBytes);
            }
        }
    }

    private BufferedImage getOrBuildRegionRaster(RegionState region) {
        long key = packKey(region.regionX, region.regionZ);
        long signature = regionRasterSignature(region);
        RegionRasterCacheEntry cached = regionRasterCache.get(key);
        if (cached != null && cached.signature == signature) {
            return cached.raster;
        }
        BufferedImage raster = createRegionRaster(region);
        cacheRegionRaster(key, signature, raster);
        return raster;
    }

    private BufferedImage getCachedRegionRasterIfFresh(RegionState region) {
        long key = packKey(region.regionX, region.regionZ);
        long signature = regionRasterSignature(region);
        RegionRasterCacheEntry cached = regionRasterCache.get(key);
        if (cached != null && cached.signature == signature) {
            return cached.raster;
        }
        return null;
    }

    private static RegionCoord parseRegionFileName(String fileName) {
        Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        return new RegionCoord(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private RegionState loadRegionFromDisk(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        RegionCoord coord = parseRegionFileName(file.getFileName().toString());
        if (coord == null) {
            return null;
        }
        BitSet chunks = readChunkPresence(file);
        int count = chunks.cardinality();
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        return new RegionState(coord.regionX, coord.regionZ, chunks, count, size, lastModified);
    }

    private static BitSet readChunkPresence(Path regionFile) throws IOException {
        BitSet occupied = new BitSet(CHUNKS_PER_REGION);
        byte[] table = new byte[LOCATION_TABLE_BYTES];

        try (InputStream input = Files.newInputStream(regionFile)) {
            int offset = 0;
            while (offset < LOCATION_TABLE_BYTES) {
                int read = input.read(table, offset, LOCATION_TABLE_BYTES - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            int base = i * 4;
            int locationOffset = ((table[base] & 0xFF) << 16)
                    | ((table[base + 1] & 0xFF) << 8)
                    | (table[base + 2] & 0xFF);
            int sectorCount = table[base + 3] & 0xFF;
            if (locationOffset != 0 && sectorCount != 0) {
                occupied.set(i);
            }
        }
        return occupied;
    }

    private static BitSet clipChunksToWindow(
            int regionX,
            int regionZ,
            BitSet source,
            int windowMinX,
            int windowMaxX,
            int windowMinZ,
            int windowMaxZ
    ) {
        BitSet clipped = new BitSet(CHUNKS_PER_REGION);
        for (int idx = source.nextSetBit(0); idx >= 0; idx = source.nextSetBit(idx + 1)) {
            int localX = idx & 31;
            int localZ = idx >> 5;
            int chunkX = (regionX * CHUNKS_PER_REGION_EDGE) + localX;
            int chunkZ = (regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
            if (chunkX >= windowMinX && chunkX <= windowMaxX && chunkZ >= windowMinZ && chunkZ <= windowMaxZ) {
                clipped.set(idx);
            }
        }
        return clipped;
    }

    private RegionState clipStateToStartupWindow(RegionState state) {
        if (state == null || !startupWindowLocked) {
            return state;
        }
        BitSet clipped = clipChunksToWindow(
                state.regionX,
                state.regionZ,
                state.chunks,
                startupWindowMinChunkX,
                startupWindowMaxChunkX,
                startupWindowMinChunkZ,
                startupWindowMaxChunkZ
        );
        int clippedCount = clipped.cardinality();
        if (clippedCount == 0) {
            return null;
        }
        if (clippedCount == state.chunkCount && clipped.equals(state.chunks)) {
            return state;
        }
        return new RegionState(state.regionX, state.regionZ, clipped, clippedCount, state.fileSize, state.lastModifiedMs);
    }

    private BufferedImage createBackgroundCanvasForCurrentBounds() {
        int widthChunks = Math.max(1, maxChunkX - minChunkX + 1);
        int heightChunks = Math.max(1, maxChunkZ - minChunkZ + 1);
        int widthPixels = Math.max(1, widthChunks * pixelSize);
        int heightPixels = Math.max(1, heightChunks * pixelSize);
        BufferedImage image = new BufferedImage(widthPixels, heightPixels, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(backgroundRgb(), false));
        g.fillRect(0, 0, widthPixels, heightPixels);
        g.dispose();
        return image;
    }

    private BufferedImage buildPredictiveCanvasForCurrentBounds() {
        BufferedImage predicted = createBackgroundCanvasForCurrentBounds();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                int pixelX = (chunkX - minChunkX) * pixelSize;
                int pixelZ = (chunkZ - minChunkZ) * pixelSize;
                int rgb = predictChunkColor(chunkX, chunkZ);
                drawUniformChunkCell(predicted, pixelX, pixelZ, rgb);
            }
        }
        return predicted;
    }

    private boolean shouldUsePredictiveBaseCanvas() {
        return predictiveStartupEnabled;
    }

    private void fillPredictedRegionOnCanvas(
            BufferedImage image,
            int pixelStartX,
            int pixelStartZ,
            int regionX,
            int regionZ
    ) {
        int regionMinChunkX = regionX * CHUNKS_PER_REGION_EDGE;
        int regionMinChunkZ = regionZ * CHUNKS_PER_REGION_EDGE;
        for (int localZ = 0; localZ < CHUNKS_PER_REGION_EDGE; localZ++) {
            int chunkZ = regionMinChunkZ + localZ;
            for (int localX = 0; localX < CHUNKS_PER_REGION_EDGE; localX++) {
                int chunkX = regionMinChunkX + localX;
                int px = pixelStartX + (localX * pixelSize);
                int pz = pixelStartZ + (localZ * pixelSize);
                drawUniformChunkCell(image, px, pz, predictChunkColor(chunkX, chunkZ));
            }
        }
    }

    private void restoreRectFromPrediction(int x, int z, int width, int height) {
        if (canvas == null || startupPredictionCanvas == null) {
            return;
        }
        int clampedX = Math.max(0, x);
        int clampedZ = Math.max(0, z);
        int maxX = Math.min(canvas.getWidth(), x + width);
        int maxZ = Math.min(canvas.getHeight(), z + height);
        if (maxX <= clampedX || maxZ <= clampedZ) {
            return;
        }

        for (int py = clampedZ; py < maxZ; py++) {
            for (int px = clampedX; px < maxX; px++) {
                canvas.setRGB(px, py, startupPredictionCanvas.getRGB(px, py));
            }
        }
    }

    private void applyState(long key, RegionState oldState, RegionState newState) {
        if (oldState != null) {
            invalidateChunkRenderCacheForRegion(oldState.regionX, oldState.regionZ);
        }
        if (newState != null && (oldState == null
                || oldState.regionX != newState.regionX
                || oldState.regionZ != newState.regionZ)) {
            invalidateChunkRenderCacheForRegion(newState.regionX, newState.regionZ);
        }
        if (oldState != null) {
            chunkCount -= oldState.chunkCount;
        }
        if (newState == null) {
            regions.remove(key);
        } else {
            regions.put(key, newState);
            chunkCount += newState.chunkCount;
        }
    }

    private boolean edgeSensitiveChange(RegionState oldState, RegionState newState) {
        if (startupWindowLocked) {
            return false;
        }
        if (regions.isEmpty()) {
            return true;
        }
        if (newState != null) {
            RegionBounds b = regionBounds(newState.regionX, newState.regionZ);
            if (b.minChunkX < minChunkX || b.maxChunkX > maxChunkX || b.minChunkZ < minChunkZ || b.maxChunkZ > maxChunkZ) {
                return true;
            }
        }

        if (oldState == null) {
            return false;
        }
        RegionBounds oldBounds = regionBounds(oldState.regionX, oldState.regionZ);
        return oldBounds.minChunkX == minChunkX
                || oldBounds.maxChunkX == maxChunkX
                || oldBounds.minChunkZ == minChunkZ
                || oldBounds.maxChunkZ == maxChunkZ;
    }

    private void applyRegionPatch(RegionState oldState, RegionState newState) {
        RegionState target = newState != null ? newState : oldState;
        if (target == null || canvas == null) {
            return;
        }

        int regionMinChunkX = target.regionX * CHUNKS_PER_REGION_EDGE;
        int regionMinChunkZ = target.regionZ * CHUNKS_PER_REGION_EDGE;
        int pixelX = (regionMinChunkX - minChunkX) * pixelSize;
        int pixelZ = (regionMinChunkZ - minChunkZ) * pixelSize;
        int regionPixels = CHUNKS_PER_REGION_EDGE * pixelSize;

        if (startupPredictionCanvas != null) {
            restoreRectFromPrediction(pixelX, pixelZ, regionPixels, regionPixels);
        } else {
            if (shouldUsePredictiveBaseCanvas()) {
                fillPredictedRegionOnCanvas(canvas, pixelX, pixelZ, target.regionX, target.regionZ);
            } else {
                Graphics2D g = canvas.createGraphics();
                g.setColor(new Color(backgroundRgb(), false));
                g.fillRect(pixelX, pixelZ, regionPixels, regionPixels);
                g.dispose();
            }
        }

        if (newState != null) {
            drawRegion(canvas, newState);
        }
    }

    private void recalcBoundsFromStates() {
        if (startupWindowLocked) {
            minChunkX = startupWindowMinChunkX;
            maxChunkX = startupWindowMaxChunkX;
            minChunkZ = startupWindowMinChunkZ;
            maxChunkZ = startupWindowMaxChunkZ;
            return;
        }
        if (regions.isEmpty()) {
            minChunkX = 0;
            maxChunkX = 0;
            minChunkZ = 0;
            maxChunkZ = 0;
            return;
        }

        int localMinX = Integer.MAX_VALUE;
        int localMaxX = Integer.MIN_VALUE;
        int localMinZ = Integer.MAX_VALUE;
        int localMaxZ = Integer.MIN_VALUE;
        for (RegionState state : regions.values()) {
            RegionBounds b = regionBounds(state.regionX, state.regionZ);
            localMinX = Math.min(localMinX, b.minChunkX);
            localMaxX = Math.max(localMaxX, b.maxChunkX);
            localMinZ = Math.min(localMinZ, b.minChunkZ);
            localMaxZ = Math.max(localMaxZ, b.maxChunkZ);
        }
        minChunkX = localMinX;
        maxChunkX = localMaxX;
        minChunkZ = localMinZ;
        maxChunkZ = localMaxZ;
    }

    private void rebuildCanvasFromStates() {
        if (startupWindowLocked) {
            recalcBoundsFromStates();
            canvas = startupPredictionCanvas != null ? copyImage(startupPredictionCanvas) : createBackgroundCanvasForCurrentBounds();
            rememberCanvasMetrics(canvas);
            for (RegionState state : regions.values()) {
                drawRegion(canvas, state);
            }
            return;
        }

        if (regions.isEmpty()) {
            canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            canvas.setRGB(0, 0, backgroundRgb());
            rememberCanvasMetrics(canvas);
            return;
        }

        int widthChunks = maxChunkX - minChunkX + 1;
        int heightChunks = maxChunkZ - minChunkZ + 1;
        int widthPixels = Math.max(1, widthChunks * pixelSize);
        int heightPixels = Math.max(1, heightChunks * pixelSize);
        if (shouldUsePredictiveBaseCanvas()) {
            canvas = buildPredictiveCanvasForCurrentBounds();
        } else {
            canvas = new BufferedImage(widthPixels, heightPixels, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.setColor(new Color(backgroundRgb(), false));
            g.fillRect(0, 0, widthPixels, heightPixels);
            g.dispose();
        }
        rememberCanvasMetrics(canvas);

        for (RegionState state : regions.values()) {
            drawRegion(canvas, state);
        }
    }

    private void drawRegion(BufferedImage image, RegionState region) {
        int regionMinChunkX = region.regionX * CHUNKS_PER_REGION_EDGE;
        int regionMinChunkZ = region.regionZ * CHUNKS_PER_REGION_EDGE;
        int pixelX = (regionMinChunkX - minChunkX) * pixelSize;
        int pixelZ = (regionMinChunkZ - minChunkZ) * pixelSize;
        BufferedImage raster = getOrBuildRegionRaster(region);
        Graphics2D g = image.createGraphics();
        g.drawImage(raster, pixelX, pixelZ, null);
        g.dispose();
    }

    private void drawRegionChunk(BufferedImage image, RegionState region, int chunkBitIndex) {
        int localX = chunkBitIndex & 31;
        int localZ = chunkBitIndex >> 5;
        int chunkX = (region.regionX * CHUNKS_PER_REGION_EDGE) + localX;
        int chunkZ = (region.regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
        int pixelX = (chunkX - minChunkX) * pixelSize;
        int pixelZ = (chunkZ - minChunkZ) * pixelSize;
        drawChunkCell(image, pixelX, pixelZ, chunkX, chunkZ);
    }

    private void prepareActiveRegionCanvas(FullRenderSession session) {
        if (session.activeDrawRegion == null) {
            return;
        }
        int regionPixels = CHUNKS_PER_REGION_EDGE * pixelSize;
        session.activeRegionCanvas = new BufferedImage(regionPixels, regionPixels, BufferedImage.TYPE_INT_RGB);
        if (shouldUsePredictiveBaseCanvas()) {
            /*
             * Predictive base mode:
             * region buffer starts from prediction, then persisted chunks are drawn on top.
             * We intentionally skip cached raster fast-path because cached unknown cells
             * encode plain background and would overwrite predicted terrain.
             */
            fillPredictedRegionOnCanvas(
                    session.activeRegionCanvas,
                    0,
                    0,
                    session.activeDrawRegion.regionX,
                    session.activeDrawRegion.regionZ
            );
        } else {
            Graphics2D g = session.activeRegionCanvas.createGraphics();
            g.setColor(new Color(backgroundRgb(), false));
            g.fillRect(0, 0, regionPixels, regionPixels);
            BufferedImage cachedRegion = getCachedRegionRasterIfFresh(session.activeDrawRegion);
            if (cachedRegion != null) {
                g.drawImage(cachedRegion, 0, 0, null);
                session.activeDrawChunkBit = -1;
            }
            g.dispose();
        }

        int regionMinChunkX = session.activeDrawRegion.regionX * CHUNKS_PER_REGION_EDGE;
        int regionMinChunkZ = session.activeDrawRegion.regionZ * CHUNKS_PER_REGION_EDGE;
        session.activeRegionPixelX = (regionMinChunkX - minChunkX) * pixelSize;
        session.activeRegionPixelZ = (regionMinChunkZ - minChunkZ) * pixelSize;
    }

    private void drawRegionChunkOnActiveCanvas(FullRenderSession session, RegionState region, int chunkBitIndex) {
        if (session.activeRegionCanvas == null) {
            return;
        }
        int localX = chunkBitIndex & 31;
        int localZ = chunkBitIndex >> 5;
        int chunkX = (region.regionX * CHUNKS_PER_REGION_EDGE) + localX;
        int chunkZ = (region.regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
        int pixelX = localX * pixelSize;
        int pixelZ = localZ * pixelSize;
        drawChunkCell(session.activeRegionCanvas, pixelX, pixelZ, chunkX, chunkZ);
    }

    private void preparePrecutTileRootForFullRender() throws IOException {
        Path sizeDir = tileRootDir.resolve("s" + PRECUT_TILE_SIZE);
        deleteDirectoryIfExists(sizeDir);
        Files.createDirectories(sizeDir);
    }

    private void writeSolidTile(int tileX, int tileY, int width, int height, int rgb) throws IOException {
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        BufferedImage tile = new BufferedImage(safeW, safeH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new Color(rgb, false));
        g.fillRect(0, 0, safeW, safeH);
        g.dispose();
        writeImageAtomic(tile, tileFilePath(tileX, tileY));
    }

    private void flushActiveRegionCanvasToTiles(FullRenderSession session) throws IOException {
        if (session.activeRegionCanvas == null) {
            return;
        }
        int regionW = session.activeRegionCanvas.getWidth();
        int regionH = session.activeRegionCanvas.getHeight();
        int globalX = session.activeRegionPixelX;
        int globalZ = session.activeRegionPixelZ;
        int endX = globalX + regionW;
        int endZ = globalZ + regionH;

        int tileStartX = (globalX / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileStartZ = (globalZ / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileEndX = ((Math.max(globalX, endX - 1)) / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileEndZ = ((Math.max(globalZ, endZ - 1)) / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;

        for (int tileZ = tileStartZ; tileZ <= tileEndZ; tileZ += PRECUT_TILE_SIZE) {
            for (int tileX = tileStartX; tileX <= tileEndX; tileX += PRECUT_TILE_SIZE) {
                int interLeft = Math.max(tileX, globalX);
                int interTop = Math.max(tileZ, globalZ);
                int interRight = Math.min(tileX + PRECUT_TILE_SIZE, endX);
                int interBottom = Math.min(tileZ + PRECUT_TILE_SIZE, endZ);
                if (interRight <= interLeft || interBottom <= interTop) {
                    continue;
                }

                BufferedImage tile = loadOrCreateTileImage(tileX, tileZ);
                if (tile == null) {
                    continue;
                }

                int srcX1 = interLeft - globalX;
                int srcY1 = interTop - globalZ;
                int srcX2 = interRight - globalX;
                int srcY2 = interBottom - globalZ;

                int dstX1 = interLeft - tileX;
                int dstY1 = interTop - tileZ;
                int dstX2 = interRight - tileX;
                int dstY2 = interBottom - tileZ;

                Graphics2D g = tile.createGraphics();
                g.drawImage(
                        session.activeRegionCanvas,
                        dstX1, dstY1, dstX2, dstY2,
                        srcX1, srcY1, srcX2, srcY2,
                        null
                );
                g.dispose();

                writeImageAtomic(tile, tileFilePath(tileX, tileZ));
            }
        }
    }

    private void prefillPrecutTilesForCurrentBounds() throws IOException {
        int width = Math.max(1, canvasWidthPixels);
        int height = Math.max(1, canvasHeightPixels);
        for (int tileZ = 0; tileZ < height; tileZ += PRECUT_TILE_SIZE) {
            int tileHeight = Math.min(PRECUT_TILE_SIZE, height - tileZ);
            for (int tileX = 0; tileX < width; tileX += PRECUT_TILE_SIZE) {
                int tileWidth = Math.min(PRECUT_TILE_SIZE, width - tileX);
                BufferedImage tile = new BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB);
                if (shouldUsePredictiveBaseCanvas()) {
                    paintPredictiveTileBaseline(tile, tileX, tileZ);
                } else {
                    Graphics2D g = tile.createGraphics();
                    g.setColor(new Color(backgroundRgb(), false));
                    g.fillRect(0, 0, tileWidth, tileHeight);
                    g.dispose();
                }
                writeImageAtomic(tile, tileFilePath(tileX, tileZ));
            }
        }
    }

    private void paintPredictiveTileBaseline(BufferedImage tile, int tilePixelX, int tilePixelZ) {
        int tileWidth = tile.getWidth();
        int tileHeight = tile.getHeight();
        if (tileWidth <= 0 || tileHeight <= 0) {
            return;
        }
        int step = Math.max(1, pixelSize);
        int chunkStartX = minChunkX + Math.floorDiv(tilePixelX, step);
        int chunkEndX = minChunkX + Math.floorDiv(tilePixelX + tileWidth - 1, step);
        int chunkStartZ = minChunkZ + Math.floorDiv(tilePixelZ, step);
        int chunkEndZ = minChunkZ + Math.floorDiv(tilePixelZ + tileHeight - 1, step);

        for (int chunkZ = chunkStartZ; chunkZ <= chunkEndZ; chunkZ++) {
            int chunkTop = (chunkZ - minChunkZ) * step;
            int chunkBottom = chunkTop + step;
            int localTop = Math.max(0, chunkTop - tilePixelZ);
            int localBottom = Math.min(tileHeight, chunkBottom - tilePixelZ);
            if (localBottom <= localTop) {
                continue;
            }
            for (int chunkX = chunkStartX; chunkX <= chunkEndX; chunkX++) {
                int chunkLeft = (chunkX - minChunkX) * step;
                int chunkRight = chunkLeft + step;
                int localLeft = Math.max(0, chunkLeft - tilePixelX);
                int localRight = Math.min(tileWidth, chunkRight - tilePixelX);
                if (localRight <= localLeft) {
                    continue;
                }
                int rgb = predictChunkColor(chunkX, chunkZ);
                for (int y = localTop; y < localBottom; y++) {
                    for (int x = localLeft; x < localRight; x++) {
                        tile.setRGB(x, y, rgb);
                    }
                }
            }
        }
    }

    private static void fillBlock(BufferedImage image, int x, int z, int size, int rgb) {
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                image.setRGB(x + dx, z + dz, rgb);
            }
        }
    }

    private void drawUniformChunkCell(BufferedImage image, int x, int z, int rgb) {
        fillBlock(image, x, z, pixelSize, rgb);
    }

    private void drawChunkCell(BufferedImage image, int x, int z, int chunkX, int chunkZ) {
        if (pixelSize <= 1) {
            drawUniformChunkCell(image, x, z, sampleChunkColor(chunkX, chunkZ));
            return;
        }

        int[] colors = drawDetailedChunkPixels(chunkX, chunkZ);
        writeDetailedChunkPixels(image, x, z, colors);
    }

    @SuppressWarnings("unused")
    private void drawChunkBoundaryOverlay(BufferedImage image, int x, int z) {
        /*
         * Retired implementation note:
         * - Older versions blended a vivid border around each chunk cell directly into the
         *   rendered image.
         * - This caused zoom-level inconsistencies and high-frequency visual noise.
         * - The boundary feature is now frontend-removed and backend-disabled.
         *
         * Method intentionally left as a no-op (instead of deletion) to preserve clear
         * migration history and make deprecation intent explicit in code review.
         */
    }

    private int[] drawDetailedChunkPixels(int chunkX, int chunkZ) {
        int cells = Math.max(1, pixelSize);
        long chunkKey = packChunkKey(chunkX, chunkZ);
        int[] cached = chunkDetailedColorCache.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        int[] colors = new int[cells * cells];

        // In predictive mode, keep global visual consistency and avoid a "spawn-only sharp island".
        // Detailed live sampling is disabled here on purpose.
        if (usePredictiveStartupLayer()) {
            int fallback = predictChunkColor(chunkX, chunkZ);
            Arrays.fill(colors, fallback);
            chunkDetailedColorCache.put(chunkKey, colors);
            return colors;
        }

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            int fallback = predictChunkColor(chunkX, chunkZ);
            Arrays.fill(colors, fallback);
            chunkDetailedColorCache.put(chunkKey, colors);
            return colors;
        }

        int baseBlockX = chunkX << 4;
        int baseBlockZ = chunkZ << 4;
        Biome biome = world.getBiome(baseBlockX + 8, biomeSampleY, baseBlockZ + 8);
        int minY = world.getMinHeight();
        try {
            int[] blockColors = new int[16 * 16];
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockZ = baseBlockZ + localZ;
                for (int localX = 0; localX < 16; localX++) {
                    int blockX = baseBlockX + localX;
                    int topY = world.getHighestBlockYAt(blockX, blockZ);
                    int y = Math.max(minY, topY - 1);
                    Material type = world.getBlockAt(blockX, y, blockZ).getType();
                    blockColors[(localZ << 4) | localX] = applyBiomeTint(type, materialToRgb(type), biome);
                }
            }
            for (int pz = 0; pz < cells; pz++) {
                int localZ = localBlockCoord(pz, cells);
                for (int px = 0; px < cells; px++) {
                    int localX = localBlockCoord(px, cells);
                    colors[(pz * cells) + px] = blockColors[(localZ << 4) | localX];
                }
            }
            chunkDetailedColorCache.put(chunkKey, colors);
            return colors;
        } catch (Exception ignored) {
            int fallback = sampleChunkColor(chunkX, chunkZ);
            Arrays.fill(colors, fallback);
            chunkDetailedColorCache.put(chunkKey, colors);
            return colors;
        }
    }

    private void writeDetailedChunkPixels(BufferedImage image, int pixelX, int pixelZ, int[] colors) {
        int cells = Math.max(1, pixelSize);
        for (int pz = 0; pz < cells; pz++) {
            for (int px = 0; px < cells; px++) {
                image.setRGB(pixelX + px, pixelZ + pz, colors[(pz * cells) + px]);
            }
        }
    }

    private static int localBlockCoord(int pixelIndex, int cells) {
        double ratio = (pixelIndex + 0.5D) / (double) cells;
        int local = (int) Math.floor(ratio * 16.0D);
        return Math.max(0, Math.min(15, local));
    }

    @SuppressWarnings("unused")
    private int blendBoundaryOverlay(int baseRgb) {
        /*
         * Retired helper:
         * previous logic used screen+boost blending to make chunk borders vivid.
         * With boundary overlay retired, this helper returns the input unchanged and
         * is kept only as migration documentation.
         */
        return baseRgb;
    }

    private int backgroundRgb() {
        // Keep base map clean/readable; unknown fog is applied later in HTTP tile overlay.
        return fogDisabledRgb;
    }

    private boolean shouldBuildStartupPredictionLayer() {
        return predictiveStartupEnabled;
    }

    private boolean usePredictiveStartupLayer() {
        return predictiveSamplingActive;
    }

    private int predictChunkColor(int chunkX, int chunkZ) {
        try {
            int blockX = (chunkX << 4) + 8;
            int blockZ = (chunkZ << 4) + 8;
            Biome biome = world.getBiome(blockX, biomeSampleY, blockZ);
            int base = biomeToRgb(biome);
            return shadeBySeed(base, chunkX, chunkZ);
        } catch (Exception ignored) {
            return backgroundRgb();
        }
    }

    private int biomeToRgb(Biome biome) {
        String name = biome.name();
        if (name.contains("NETHER")) {
            return rgb(122, 48, 48);
        }
        if (name.contains("THE_END") || name.contains("END")) {
            return rgb(217, 214, 160);
        }
        if (name.contains("OCEAN") || name.contains("RIVER")) {
            return rgb(68, 128, 219);
        }
        if (name.contains("BEACH") || name.contains("SHORE")) {
            return rgb(214, 203, 154);
        }
        if (name.contains("MUSHROOM")) {
            return rgb(142, 108, 170);
        }
        if (name.contains("BADLANDS") || name.contains("DESERT")) {
            return rgb(203, 168, 104);
        }
        if (name.contains("SAVANNA")) {
            return rgb(181, 184, 102);
        }
        if (name.contains("SNOW") || name.contains("ICE") || name.contains("FROZEN")) {
            return rgb(228, 238, 255);
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            return rgb(96, 128, 84);
        }
        if (name.contains("JUNGLE") || name.contains("BAMBOO")) {
            return rgb(78, 155, 83);
        }
        if (name.contains("TAIGA") || name.contains("GROVE")) {
            return rgb(94, 134, 106);
        }
        if (name.contains("FOREST") || name.contains("MEADOW") || name.contains("PLAINS")) {
            return rgb(106, 171, 92);
        }
        if (name.contains("MOUNTAIN") || name.contains("PEAK") || name.contains("HILLS") || name.contains("STONY")) {
            return rgb(148, 148, 154);
        }
        if (name.contains("CAVE")) {
            return rgb(110, 110, 118);
        }
        return filledRgb;
    }

    private int shadeBySeed(int baseRgb, int chunkX, int chunkZ) {
        long mixed = predictorSalt
                ^ (((long) chunkX) * 341_873_128_712L)
                ^ (((long) chunkZ) * 132_897_987_541L);
        mixed ^= (mixed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= (mixed >>> 33);
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= (mixed >>> 33);

        int shade = (int) ((mixed & 0x1F) - 16);
        int r = clampColor(((baseRgb >> 16) & 0xFF) + shade);
        int g = clampColor(((baseRgb >> 8) & 0xFF) + shade);
        int b = clampColor((baseRgb & 0xFF) + shade);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private int sampleChunkColor(int chunkX, int chunkZ) {
        long chunkKey = packChunkKey(chunkX, chunkZ);
        Integer cached = chunkSampleColorCache.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        try {
            if (usePredictiveStartupLayer()) {
                int predicted = predictChunkColor(chunkX, chunkZ);
                chunkSampleColorCache.put(chunkKey, predicted);
                return predicted;
            }
            // Never force-load chunks during render: main-thread chunk generation/loading
            // can block and trigger Paper watchdog.
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                int predicted = predictChunkColor(chunkX, chunkZ);
                chunkSampleColorCache.put(chunkKey, predicted);
                return predicted;
            }
            int baseBlockX = chunkX << 4;
            int baseBlockZ = chunkZ << 4;
            Biome biome = world.getBiome(baseBlockX + 8, biomeSampleY, baseBlockZ + 8);

            long r = 0L;
            long g = 0L;
            long b = 0L;
            int n = 0;
            for (int[] offset : CHUNK_SAMPLE_OFFSETS) {
                int blockX = baseBlockX + offset[0];
                int blockZ = baseBlockZ + offset[1];
                int topY = world.getHighestBlockYAt(blockX, blockZ);
                int y = Math.max(world.getMinHeight(), topY - 1);
                Material type = world.getBlockAt(blockX, y, blockZ).getType();
                int color = applyBiomeTint(type, materialToRgb(type), biome);
                r += (color >> 16) & 0xFF;
                g += (color >> 8) & 0xFF;
                b += color & 0xFF;
                n++;
            }
            if (n <= 0) {
                chunkSampleColorCache.put(chunkKey, filledRgb);
                return filledRgb;
            }
            int value = rgb((int) (r / n), (int) (g / n), (int) (b / n));
            chunkSampleColorCache.put(chunkKey, value);
            return value;
        } catch (Exception ignored) {
            chunkSampleColorCache.put(chunkKey, filledRgb);
            return filledRgb;
        }
    }

    private int applyBiomeTint(Material type, int baseRgb, Biome biome) {
        String name = type.name();
        if (name.contains("AIR")) {
            return backgroundRgb();
        }

        // Grass/leaves/water look closer to in-game map style with biome tinting.
        if (name.contains("WATER") || name.contains("KELP") || name.contains("SEAGRASS") || name.contains("BUBBLE_COLUMN")) {
            int waterBase = blendRgb(baseRgb, rgb(58, 120, 238), 0.55f);
            int biomeMixed = blendRgb(waterBase, biomeToRgb(biome), 0.16f);
            return boostRgb(biomeMixed, 1.10f, 1.06f);
        }
        if (name.contains("GRASS") || name.contains("LEAVES") || name.contains("VINE")
                || name.contains("FERN") || name.contains("MOSS") || name.contains("AZALEA")) {
            int biomeMixed = blendRgb(baseRgb, biomeToRgb(biome), 0.40f);
            return boostRgb(biomeMixed, 1.14f, 1.04f);
        }
        return boostRgb(baseRgb, 1.05f, 1.02f);
    }

    private int materialToRgb(Material type) {
        Integer cached = materialColorCache.get(type);
        if (cached != null) {
            return cached;
        }
        /*
         * Texture palette is mandatory in current render pipeline.
         *
         * Legacy behavior:
         * - enabled=true: try texture palette first
         * - enabled=false: skip palette and use legacy heuristic only
         *
         * Disabled path is intentionally retired to keep map colors deterministic
         * across restarts and avoid UI/runtime mismatch after removing the toggle.
         */
        Integer fromTexture = texturePalette.get(type);
        if (fromTexture != null) {
            materialColorCache.put(type, fromTexture);
            return fromTexture;
        }

        int color = materialToRgbLegacy(type);
        materialColorCache.put(type, color);
        return color;
    }

    private static int blendRgb(int a, int b, float ratio) {
        float t = Math.max(0.0f, Math.min(1.0f, ratio));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return rgb(
                (int) (ar * (1.0f - t) + br * t),
                (int) (ag * (1.0f - t) + bg * t),
                (int) (ab * (1.0f - t) + bb * t)
        );
    }

    private static int boostRgb(int color, float satMul, float briMul) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float sat = Math.max(0.0f, Math.min(1.0f, hsb[1] * satMul));
        float bri = Math.max(0.18f, Math.min(1.0f, hsb[2] * briMul));
        return Color.HSBtoRGB(hsb[0], sat, bri);
    }

    private int materialToRgbLegacy(Material type) {
        String name = type.name();
        if (name.contains("WATER") || name.contains("KELP") || name.contains("SEAGRASS")) {
            return rgb(66, 135, 245);
        }
        if (name.contains("LAVA")) {
            return rgb(255, 101, 0);
        }
        if (name.contains("SNOW") || name.contains("ICE")) {
            return rgb(230, 240, 255);
        }
        if (name.contains("SAND")) {
            return rgb(218, 210, 158);
        }
        if (name.contains("RED_SAND")) {
            return rgb(201, 128, 77);
        }
        if (name.contains("GRASS") || name.contains("MOSS") || name.contains("LEAVES")
                || name.contains("AZALEA") || name.contains("VINE")) {
            return rgb(96, 171, 88);
        }
        if (name.contains("DIRT") || name.contains("PODZOL") || name.contains("MUD")) {
            return rgb(122, 91, 62);
        }
        if (name.contains("LOG") || name.contains("WOOD") || name.contains("PLANK")) {
            return rgb(132, 102, 69);
        }
        if (name.contains("NETHERRACK") || name.contains("NETHER")) {
            return rgb(121, 46, 46);
        }
        if (name.contains("END_STONE")) {
            return rgb(219, 221, 163);
        }
        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("GRAVEL")
                || name.contains("COBBLE") || name.contains("ANDESITE")
                || name.contains("DIORITE") || name.contains("GRANITE")
                || name.contains("TUFF") || name.contains("BASALT")
                || name.contains("BLACKSTONE")) {
            return rgb(126, 126, 132);
        }
        if (name.contains("AIR")) {
            return backgroundRgb();
        }
        return filledRgb;
    }

    private static int rgb(int r, int g, int b) {
        return new Color(r, g, b).getRGB();
    }

    private static long buildPredictorSalt(World world) {
        long salt = world.getSeed();
        salt ^= ((long) world.getEnvironment().ordinal()) << 56;
        salt ^= ((long) world.getMinHeight()) << 40;
        salt ^= ((long) world.getMaxHeight()) << 24;
        salt ^= ((long) world.getSeaLevel()) << 8;
        salt ^= Bukkit.getBukkitVersion().hashCode();
        if (world.getGenerator() != null) {
            salt ^= world.getGenerator().getClass().getName().hashCode();
        }
        return salt;
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private void writeCanvasToDisk(PixelRect incrementalDirtyRect) throws IOException {
        writeImageAtomic(canvas, outputImage);
        writeImageAtomic(buildKnownMask(), outputMask);
        if (incrementalDirtyRect == null) {
            rebuildAllPrecutTiles();
        } else {
            updatePrecutTilesForRect(incrementalDirtyRect);
        }
    }

    private void releaseRenderBuffers() {
        canvas = null;
        startupPredictionCanvas = null;
    }

    private void rememberCanvasMetrics(BufferedImage image) {
        if (image == null) {
            return;
        }
        canvasWidthPixels = image.getWidth();
        canvasHeightPixels = image.getHeight();
    }

    private boolean ensureCanvasLoadedFromDisk() {
        if (canvas != null) {
            return true;
        }
        if (!Files.isRegularFile(outputImage)) {
            return false;
        }
        try {
            BufferedImage loaded = ImageIO.read(outputImage.toFile());
            if (loaded == null) {
                return false;
            }
            canvas = loaded;
            rememberCanvasMetrics(canvas);
            return true;
        } catch (Exception e) {
            logger.warning("Failed to reload canvas from disk: " + e.getMessage());
            return false;
        }
    }

    private void rebuildAllPrecutTiles() throws IOException {
        if (canvas == null) {
            return;
        }
        Path sizeDir = tileRootDir.resolve("s" + PRECUT_TILE_SIZE);
        deleteDirectoryIfExists(sizeDir);
        Files.createDirectories(sizeDir);

        int width = canvas.getWidth();
        int height = canvas.getHeight();
        for (int y = 0; y < height; y += PRECUT_TILE_SIZE) {
            for (int x = 0; x < width; x += PRECUT_TILE_SIZE) {
                writePrecutTile(x, y);
            }
        }
    }

    private void updatePrecutTilesForRect(PixelRect rect) throws IOException {
        if (canvas == null || rect == null) {
            return;
        }
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int minX = Math.max(0, rect.x);
        int minY = Math.max(0, rect.y);
        int maxX = Math.min(width - 1, rect.x + rect.width - 1);
        int maxY = Math.min(height - 1, rect.y + rect.height - 1);
        if (maxX < minX || maxY < minY) {
            return;
        }

        int tileMinX = (minX / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileMinY = (minY / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileMaxX = (maxX / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;
        int tileMaxY = (maxY / PRECUT_TILE_SIZE) * PRECUT_TILE_SIZE;

        Path sizeDir = tileRootDir.resolve("s" + PRECUT_TILE_SIZE);
        Files.createDirectories(sizeDir);
        for (int y = tileMinY; y <= tileMaxY; y += PRECUT_TILE_SIZE) {
            for (int x = tileMinX; x <= tileMaxX; x += PRECUT_TILE_SIZE) {
                writePrecutTile(x, y);
            }
        }
    }

    private void writePrecutTile(int tileX, int tileY) throws IOException {
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        if (tileX >= width || tileY >= height) {
            return;
        }
        int tileWidth = Math.min(PRECUT_TILE_SIZE, width - tileX);
        int tileHeight = Math.min(PRECUT_TILE_SIZE, height - tileY);
        if (tileWidth <= 0 || tileHeight <= 0) {
            return;
        }
        BufferedImage tile = canvas.getSubimage(tileX, tileY, tileWidth, tileHeight);
        Path file = tileRootDir.resolve("s" + PRECUT_TILE_SIZE)
                .resolve("x" + tileX + "_y" + tileY + ".png");
        writeImageAtomic(tile, file);
    }

    private BufferedImage loadOrCreateTileImage(int tileX, int tileY) throws IOException {
        int tileWidth = Math.min(PRECUT_TILE_SIZE, Math.max(0, canvasWidthPixels - tileX));
        int tileHeight = Math.min(PRECUT_TILE_SIZE, Math.max(0, canvasHeightPixels - tileY));
        if (tileWidth <= 0 || tileHeight <= 0) {
            return null;
        }
        Path file = tileFilePath(tileX, tileY);
        if (Files.isRegularFile(file)) {
            BufferedImage existing = ImageIO.read(file.toFile());
            if (existing != null
                    && existing.getWidth() == tileWidth
                    && existing.getHeight() == tileHeight) {
                return existing;
            }
        }
        BufferedImage tile = new BufferedImage(tileWidth, tileHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        g.setColor(new Color(backgroundRgb(), false));
        g.fillRect(0, 0, tileWidth, tileHeight);
        g.dispose();
        return tile;
    }

    private Path tileFilePath(int tileX, int tileY) {
        return tileRootDir.resolve("s" + PRECUT_TILE_SIZE)
                .resolve("x" + tileX + "_y" + tileY + ".png");
    }

    private void deleteDirectoryIfExists(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                deletePathWithLog(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                if (exc != null) {
                    logger.warning("Tile cleanup traversal warning at " + directory + ": " + exc.getMessage());
                }
                deletePathWithLog(directory);
                return FileVisitResult.CONTINUE;
            }

            private void deletePathWithLog(Path path) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.warning("Failed to delete tile path: " + path + " (" + e.getMessage() + ")");
                }
            }
        });
    }

    private static Path resolveTileRootDir(Path imagePath) {
        Path parent = imagePath.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        String fileName = imagePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return parent.resolve("tiles").resolve(stem);
    }

    private BufferedImage buildKnownMask() {
        // Chunk-level fog semantics: one mask pixel per chunk (not per rendered pixel).
        // This keeps memory bounded even for very high render quality (e.g. q80).
        int width = Math.max(1, maxChunkX - minChunkX + 1);
        int height = Math.max(1, maxChunkZ - minChunkZ + 1);
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int knownArgb = 0xFFFFFFFF;
        for (RegionState region : regions.values()) {
            for (int idx = region.chunks.nextSetBit(0); idx >= 0; idx = region.chunks.nextSetBit(idx + 1)) {
                int localX = idx & 31;
                int localZ = idx >> 5;
                int chunkX = (region.regionX * CHUNKS_PER_REGION_EDGE) + localX;
                int chunkZ = (region.regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
                int maskX = chunkX - minChunkX;
                int maskZ = chunkZ - minChunkZ;
                if (maskX >= 0 && maskX < width && maskZ >= 0 && maskZ < height) {
                    mask.setRGB(maskX, maskZ, knownArgb);
                }
            }
        }
        return mask;
    }

    private static void writeImageAtomic(BufferedImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        ImageIO.write(image, "png", tmp.toFile());
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void refreshSaveMeta() throws IOException {
        Path levelDat = worldPath.resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            levelDatLastModifiedMs = Files.getLastModifiedTime(levelDat).toMillis();
        } else {
            levelDatLastModifiedMs = 0L;
        }

        long players = 0L;
        Path playerDataDir = worldPath.resolve("playerdata");
        if (Files.isDirectory(playerDataDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDataDir, "*.dat")) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        players++;
                    }
                }
            }
        }
        playerDataFileCount = players;
    }

    private RenderSnapshot buildFullRenderProgressSnapshot(FullRenderSession session) {
        String message;
        if (session.phase == FullRenderSession.Phase.SCANNING) {
            message = "full render scanning regions " + session.scanIndex + "/" + session.totalFiles();
        } else if (session.phase == FullRenderSession.Phase.PREPARE_CANVAS) {
            message = "full render preparing canvas";
        } else if (session.phase == FullRenderSession.Phase.DRAWING) {
            message = "full render drawing chunks " + session.drawnChunks + "/" + Math.max(1L, session.totalDrawableChunks);
        } else if (session.phase == FullRenderSession.Phase.FINALIZING) {
            message = "full render finalizing output";
        } else {
            message = "full render finished";
        }
        int width = canvas == null ? canvasWidthPixels : canvas.getWidth();
        int height = canvas == null ? canvasHeightPixels : canvas.getHeight();
        return new RenderSnapshot(
                "running",
                session.reason,
                message,
                Instant.now().toEpochMilli(),
                width,
                height,
                chunkCount,
                regions.size(),
                playerDataFileCount,
                renderCount
        );
    }

    private RenderSnapshot snapshot(String status, String reason, String message) {
        int width = canvas == null ? canvasWidthPixels : canvas.getWidth();
        int height = canvas == null ? canvasHeightPixels : canvas.getHeight();
        long regionFiles = Math.max(0, regions.size());
        return new RenderSnapshot(
                status,
                reason,
                message,
                Instant.now().toEpochMilli(),
                width,
                height,
                chunkCount,
                regionFiles,
                playerDataFileCount,
                renderCount
        );
    }

    private static boolean sameRegionState(RegionState a, RegionState b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.chunkCount != b.chunkCount) {
            return false;
        }
        if (a.fileSize == b.fileSize && a.lastModifiedMs == b.lastModifiedMs) {
            return true;
        }
        return a.chunks.equals(b.chunks);
    }

    private static RegionBounds regionBounds(int regionX, int regionZ) {
        int minX = regionX * CHUNKS_PER_REGION_EDGE;
        int minZ = regionZ * CHUNKS_PER_REGION_EDGE;
        return new RegionBounds(minX, minX + CHUNKS_PER_REGION_EDGE - 1, minZ, minZ + CHUNKS_PER_REGION_EDGE - 1);
    }

    private record RegionRasterCacheEntry(
            long signature,
            BufferedImage raster,
            long approxBytes
    ) {
    }

    private record RegionCoord(int regionX, int regionZ) {
    }

    private record RegionBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
    }

    private record RegionState(
            int regionX,
            int regionZ,
            BitSet chunks,
            int chunkCount,
            long fileSize,
            long lastModifiedMs
    ) {
    }

    private record IncrementalChangeResult(
            boolean changed,
            boolean rebuildNeeded,
            PixelRect dirtyRect
    ) {
        private static IncrementalChangeResult ignored() {
            return new IncrementalChangeResult(false, false, null);
        }

        private static IncrementalChangeResult rebuildRequired() {
            return new IncrementalChangeResult(true, true, null);
        }

        private static IncrementalChangeResult patched(PixelRect dirtyRect) {
            return new IncrementalChangeResult(true, false, dirtyRect);
        }
    }

    private record PixelRect(int x, int y, int width, int height) {
    }
}
