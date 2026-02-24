package io.sealantermap;

import org.bukkit.Chunk;
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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
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

    private final Path worldPath;
    private final Path regionDir;
    private final Path outputImage;
    private final World world;
    private final int pixelSize;
    private final int emptyRgb;
    private final int filledRgb;
    private final boolean unknownFogEnabled;
    private final int fogDisabledRgb;
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

    private final Object lock = new Object();
    private final Map<Long, RegionState> regions = new HashMap<>();
    private final Set<String> pendingRegionFiles = new HashSet<>();

    private BufferedImage canvas;
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

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean watchRunning;
    private volatile boolean forceRebuild = false;

    IncrementalRenderEngine(
            Path worldPath,
            Path regionDir,
            Path outputImage,
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
        this.world = world;
        this.startupWindowChunks = Math.max(0, startupWindowChunks);
        this.predictiveStartupEnabled = predictiveStartupEnabled;
        this.texturePaletteEnabled = texturePaletteEnabled;
        this.pixelSize = Math.max(1, pixelSize);
        this.emptyRgb = emptyColor.getRGB();
        this.filledRgb = filledColor.getRGB();
        this.unknownFogEnabled = unknownFogEnabled;
        this.fogDisabledRgb = fogDisabledColor.getRGB();
        this.chunkBoundaryEnabled = chunkBoundaryEnabled;
        this.chunkBoundaryRgb = chunkBoundaryColor.getRGB();
        this.logger = logger;
        this.predictorSalt = buildPredictorSalt(world);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int seaY = world.getSeaLevel();
        this.biomeSampleY = Math.min(maxY, Math.max(minY, seaY));
        this.texturePalette = texturePaletteEnabled
                ? TextureColorPalette.load(logger, minecraftJarPath)
                : Map.of();
    }

    RenderSnapshot renderStartupBaseline(String reason) throws Exception {
        if (startupWindowChunks <= 0) {
            return renderFull(reason);
        }

        synchronized (lock) {
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

            startupPredictionCanvas = usePredictiveStartupLayer() ? buildPredictiveCanvasForCurrentBounds() : null;
            canvas = startupPredictionCanvas != null ? copyImage(startupPredictionCanvas) : createBackgroundCanvasForCurrentBounds();

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
            writeCanvasToDisk();
            initialized = true;
            forceRebuild = false;
            pendingRegionFiles.clear();
            refreshSaveMeta();
            renderCount++;
            String mode = startupPredictionCanvas != null ? "predictive" : "fog-mask";
            return snapshot("ok", reason, "startup " + mode + " baseline " + startupWindowChunks + "x" + startupWindowChunks + " chunks");
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

    RenderSnapshot renderFull(String reason) throws Exception {
        synchronized (lock) {
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
            } else {
                minChunkX = localMinX;
                maxChunkX = localMaxX;
                minChunkZ = localMinZ;
                maxChunkZ = localMaxZ;
                rebuildCanvasFromStates();
            }

            writeCanvasToDisk();
            initialized = true;
            forceRebuild = false;
            pendingRegionFiles.clear();
            refreshSaveMeta();
            renderCount++;

            return snapshot("ok", reason, "full render complete");
        }
    }

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
            forceRebuild = false;
            boolean changedApplied = false;

            for (String fileName : changed) {
                RegionCoord coord = parseRegionFileName(fileName);
                if (coord == null) {
                    continue;
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
                    continue;
                }

                changedApplied = true;
                if (edgeSensitiveChange(oldState, newState)) {
                    applyState(key, oldState, newState);
                    rebuildNeeded = true;
                    continue;
                }

                if (canvas == null) {
                    applyState(key, oldState, newState);
                    rebuildNeeded = true;
                    continue;
                }

                applyRegionPatch(oldState, newState);
                applyState(key, oldState, newState);
            }

            if (!changedApplied && !rebuildNeeded) {
                refreshSaveMeta();
                return snapshot("skipped", reason, "pending queue had no effective changes");
            }

            if (rebuildNeeded) {
                if (!startupWindowLocked) {
                    recalcBoundsFromStates();
                }
                rebuildCanvasFromStates();
            }

            writeCanvasToDisk();
            refreshSaveMeta();
            renderCount++;
            return snapshot("ok", reason, rebuildNeeded ? "incremental+rebuild" : "incremental patch");
        }
    }

    void markAllDirty() {
        synchronized (lock) {
            forceRebuild = true;
        }
    }

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
            } catch (Exception e) {
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
                drawChunkCell(predicted, pixelX, pixelZ, rgb);
            }
        }
        return predicted;
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
            Graphics2D g = canvas.createGraphics();
            g.setColor(new Color(backgroundRgb(), false));
            g.fillRect(pixelX, pixelZ, regionPixels, regionPixels);
            g.dispose();
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
            for (RegionState state : regions.values()) {
                drawRegion(canvas, state);
            }
            return;
        }

        if (regions.isEmpty()) {
            canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            canvas.setRGB(0, 0, backgroundRgb());
            return;
        }

        int widthChunks = maxChunkX - minChunkX + 1;
        int heightChunks = maxChunkZ - minChunkZ + 1;
        int widthPixels = Math.max(1, widthChunks * pixelSize);
        int heightPixels = Math.max(1, heightChunks * pixelSize);
        canvas = new BufferedImage(widthPixels, heightPixels, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(backgroundRgb(), false));
        g.fillRect(0, 0, widthPixels, heightPixels);
        g.dispose();

        for (RegionState state : regions.values()) {
            drawRegion(canvas, state);
        }
    }

    private void drawRegion(BufferedImage image, RegionState region) {
        for (int idx = region.chunks.nextSetBit(0); idx >= 0; idx = region.chunks.nextSetBit(idx + 1)) {
            int localX = idx & 31;
            int localZ = idx >> 5;
            int chunkX = (region.regionX * CHUNKS_PER_REGION_EDGE) + localX;
            int chunkZ = (region.regionZ * CHUNKS_PER_REGION_EDGE) + localZ;
            int pixelX = (chunkX - minChunkX) * pixelSize;
            int pixelZ = (chunkZ - minChunkZ) * pixelSize;
            int rgb = sampleChunkColor(chunkX, chunkZ);
            drawChunkCell(image, pixelX, pixelZ, rgb);
        }
    }

    private static void fillBlock(BufferedImage image, int x, int z, int size, int rgb) {
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                image.setRGB(x + dx, z + dz, rgb);
            }
        }
    }

    private void drawChunkCell(BufferedImage image, int x, int z, int rgb) {
        fillBlock(image, x, z, pixelSize, rgb);
        if (!chunkBoundaryEnabled || pixelSize < 2) {
            return;
        }
        int max = pixelSize - 1;
        for (int i = 0; i <= max; i++) {
            image.setRGB(x + i, z, chunkBoundaryRgb);
            image.setRGB(x + i, z + max, chunkBoundaryRgb);
            image.setRGB(x, z + i, chunkBoundaryRgb);
            image.setRGB(x + max, z + i, chunkBoundaryRgb);
        }
    }

    private int backgroundRgb() {
        return unknownFogEnabled ? emptyRgb : fogDisabledRgb;
    }

    private boolean usePredictiveStartupLayer() {
        // Unknown fog semantics: any chunk not yet persisted to region files must stay covered.
        return predictiveStartupEnabled && !unknownFogEnabled;
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
        try {
            if (!world.isChunkGenerated(chunkX, chunkZ)) {
                return backgroundRgb();
            }

            boolean wasLoaded = world.isChunkLoaded(chunkX, chunkZ);
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            int blockX = (chunkX << 4) + 8;
            int blockZ = (chunkZ << 4) + 8;
            int topY = world.getHighestBlockYAt(blockX, blockZ);
            int y = Math.max(world.getMinHeight(), topY - 1);
            Material type = world.getBlockAt(blockX, y, blockZ).getType();
            int color = materialToRgb(type);

            if (!wasLoaded) {
                chunk.unload(false);
            }
            return color;
        } catch (Exception ignored) {
            return filledRgb;
        }
    }

    private int materialToRgb(Material type) {
        Integer cached = materialColorCache.get(type);
        if (cached != null) {
            return cached;
        }

        if (texturePaletteEnabled) {
            Integer fromTexture = texturePalette.get(type);
            if (fromTexture != null) {
                materialColorCache.put(type, fromTexture);
                return fromTexture;
            }
        }

        int color = materialToRgbLegacy(type);
        materialColorCache.put(type, color);
        return color;
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

    private void writeCanvasToDisk() throws IOException {
        Files.createDirectories(outputImage.getParent());
        Path tmp = outputImage.resolveSibling(outputImage.getFileName() + ".tmp");
        ImageIO.write(canvas, "png", tmp.toFile());
        try {
            Files.move(tmp, outputImage, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, outputImage, StandardCopyOption.REPLACE_EXISTING);
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

    private RenderSnapshot snapshot(String status, String reason, String message) {
        int width = canvas == null ? 0 : canvas.getWidth();
        int height = canvas == null ? 0 : canvas.getHeight();
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
}
