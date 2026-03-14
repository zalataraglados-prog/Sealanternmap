package io.sealantermap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SealantermapPlugin extends JavaPlugin {
    private HttpPreviewServer previewServer;
    private IncrementalRenderEngine renderEngine;
    private BukkitTask autoRenderTask;
    private BukkitTask worldTimeSyncTask;

    private Path worldPath;
    private Path regionPath;
    private Path outputDirPath;
    private String outputFileStem;
    private String outputFileExt;
    private Path outputImage;
    private Path outputMask;
    private int chunkPixelSize;
    private int startupWindowChunks;
    private boolean startupPredictiveEnabled;
    private boolean texturePaletteEnabled;
    private String minecraftJarPath;
    private Color emptyColor;
    private Color filledColor;
    private boolean unknownFogEnabled;
    private Color unknownFogDisabledColor;
    // Deprecated compatibility fields:
    // - kept so legacy config keys and /stats.json schema remain readable
    // - no longer used to drive any frontend or backend boundary rendering behavior
    private boolean chunkBoundaryEnabled;
    private Color chunkBoundaryColor;
    private boolean renderOnStartup;
    private boolean autoUpdateEnabled;
    private long autoUpdateIntervalSeconds;
    private boolean watchRegionDirectory;
    private boolean budgetedFullRenderEnabled;
    private int fullRenderMaxChunksPerTick;
    private long fullRenderMaxMillisPerTick;
    private World targetWorld;

    private volatile RenderSnapshot latestSnapshot = RenderSnapshot.EMPTY;
    private final Object renderLock = new Object();
    private boolean renderRunning;
    private boolean pendingRenderRequested;
    private boolean pendingRenderForceFull;
    private String pendingRenderReason;
    private BukkitTask fullRenderBudgetTask;
    private IncrementalRenderEngine.FullRenderSession fullRenderSession;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        try {
            loadRuntimeConfig();
            if (!Files.isDirectory(regionPath)) {
                throw new IllegalStateException("Region directory not found: " + regionPath);
            }

            String host = getConfig().getString("bind-host", "127.0.0.1");
            int port = getConfig().getInt("bind-port", 8156);
            previewServer = new HttpPreviewServer(
                    host,
                    port,
                    outputImage,
                    outputMask,
                    this::requestManualRender,
                    this::updateAutoRenderSettings,
                    this::updateVisualSettings,
                    this::updateRenderQuality
            );
            previewServer.updateSnapshot(latestSnapshot);
            pushPreviewConfig();
            previewServer.start();

            rebuildRenderEngine();

            registerCommands();
            configureAutoRenderTask();
            configureWorldTimeSyncTask();

            if (renderOnStartup) {
                renderOnce("startup-full", true);
            }

            getLogger().info("Sealantermap ready.");
            getLogger().info("World path: " + worldPath);
            getLogger().info("Image path: " + outputImage);
            getLogger().info("Mask path: " + outputMask);
            getLogger().info("Preview URL: http://" + host + ":" + port + "/");
            getLogger().info("Compatibility target: Bukkit/Spigot/Paper/Purpur 1.20+");
        } catch (Exception e) {
            getLogger().severe("Failed to start Sealantermap: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (autoRenderTask != null) {
            autoRenderTask.cancel();
            autoRenderTask = null;
        }
        if (worldTimeSyncTask != null) {
            worldTimeSyncTask.cancel();
            worldTimeSyncTask = null;
        }
        cancelFullRenderBudgetTask();
        if (renderEngine != null) {
            renderEngine.stopWatcher();
            renderEngine = null;
        }
        if (previewServer != null) {
            previewServer.stop();
            previewServer = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"slmap".equalsIgnoreCase(command.getName())) {
            return false;
        }
        if (!sender.hasPermission("sealantermap.admin") && !sender.isOp()) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            RenderSnapshot s = latestSnapshot;
            sender.sendMessage("[Sealantermap] status=" + s.status + ", reason=" + s.reason + ", message=" + s.message);
            sender.sendMessage("[Sealantermap] chunks=" + s.chunkCount + ", image=" + s.width + "x" + s.height
                    + ", regions=" + s.regionFileCount + ", playerdata=" + s.playerDataFileCount);
            sender.sendMessage("[Sealantermap] renders=" + s.renderCount + ", generated=" + formatEpochMs(s.generatedEpochMs));
            if (targetWorld != null) {
                long gameTime = Math.floorMod(targetWorld.getTime(), 24_000L);
                sender.sendMessage("[Sealantermap] game-time=" + gameTime + ", phase=" + (gameTime < 12_000L ? "day" : "night"));
            }
            return true;
        }

        if ("render".equalsIgnoreCase(args[0]) || "update".equalsIgnoreCase(args[0])) {
            boolean force = args.length > 1 && "force".equalsIgnoreCase(args[1]);
            triggerRenderAsync(force ? "manual-full" : "manual-incremental", force);
            sender.sendMessage("[Sealantermap] render requested. force=" + force);
            return true;
        }

        if ("auto".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /slmap auto <on|off>");
                return true;
            }
            Boolean enabled = parseOnOff(args[1]);
            if (enabled == null) {
                sender.sendMessage("Usage: /slmap auto <on|off>");
                return true;
            }
            sender.sendMessage("[Sealantermap] " + updateAutoRenderSettings(enabled, null));
            return true;
        }

        if ("interval".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /slmap interval <seconds>=2..3600");
                return true;
            }
            Long seconds = parseIntervalSeconds(args[1]);
            if (seconds == null) {
                sender.sendMessage("Usage: /slmap interval <seconds>=2..3600");
                return true;
            }
            sender.sendMessage("[Sealantermap] " + updateAutoRenderSettings(null, seconds));
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            reloadConfig();
            getConfig().options().copyDefaults(true);
            saveConfig();
            try {
                loadRuntimeConfig();
                rebuildRenderEngine();
                pushPreviewConfig();
                configureAutoRenderTask();
                configureWorldTimeSyncTask();
                triggerRenderAsync("reload-full", true);
                sender.sendMessage("[Sealantermap] config reloaded.");
            } catch (Exception e) {
                sender.sendMessage("[Sealantermap] reload failed: " + e.getMessage());
            }
            return true;
        }

        if ("quality".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /slmap quality <1|2|3|16|80>");
                return true;
            }
            Integer edge = parseQualityEdge(args[1]);
            if (edge == null) {
                sender.sendMessage("Usage: /slmap quality <1|2|3|16|80>");
                return true;
            }
            sender.sendMessage("[Sealantermap] " + updateRenderQuality(edge));
            return true;
        }

        sender.sendMessage("Usage: /slmap <status|render [force]|auto <on|off>|interval <seconds>|quality <1|2|3|16|80>|reload>");
        return true;
    }

    private void registerCommands() {
        PluginCommand command = getCommand("slmap");
        if (command == null) {
            throw new IllegalStateException("Command 'slmap' not found in plugin.yml");
        }
    }

    private void configureAutoRenderTask() {
        if (autoRenderTask != null) {
            autoRenderTask.cancel();
            autoRenderTask = null;
        }
        if (!autoUpdateEnabled) {
            if (previewServer != null) {
                previewServer.updateAutoRuntime(autoUpdateEnabled, autoUpdateIntervalSeconds);
            }
            return;
        }

        long ticks = Math.max(20L, autoUpdateIntervalSeconds * 20L);
        autoRenderTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> renderOnce("auto-incremental", false),
                ticks,
                ticks
        );
        getLogger().info("Auto render enabled, interval=" + autoUpdateIntervalSeconds + "s");
        if (previewServer != null) {
            previewServer.updateAutoRuntime(autoUpdateEnabled, autoUpdateIntervalSeconds);
        }
    }

    private void configureWorldTimeSyncTask() {
        if (worldTimeSyncTask != null) {
            worldTimeSyncTask.cancel();
            worldTimeSyncTask = null;
        }
        if (targetWorld == null || previewServer == null) {
            return;
        }
        syncWorldTimeToPreview();
        worldTimeSyncTask = getServer().getScheduler().runTaskTimer(
                this,
                this::syncWorldTimeToPreview,
                20L,
                20L
        );
    }

    private void syncWorldTimeToPreview() {
        if (targetWorld == null || previewServer == null) {
            return;
        }
        long gameTime = Math.floorMod(targetWorld.getTime(), 24_000L);
        previewServer.updateGameTime(gameTime, gameTime < 12_000L);
    }

    private void triggerRenderAsync(String reason, boolean forceFull) {
        getServer().getScheduler().runTask(this, () -> {
            if (forceFull && budgetedFullRenderEnabled) {
                startBudgetedFullRender(reason);
            } else {
                renderOnce(reason, forceFull);
            }
        });
    }

    private void renderOnce(String reason, boolean forceFull) {
        if (!acquireRenderSlotOrQueue(reason, forceFull)) {
            return;
        }

        long startedNs = System.nanoTime();
        try {
            if (renderEngine == null) {
                throw new IllegalStateException("render engine not initialized");
            }
            RenderSnapshot next;
            if ("startup-full".equals(reason) && !forceFull) {
                next = renderEngine.renderStartupBaseline(reason);
            } else if ("startup-full".equals(reason) && forceFull) {
                next = renderEngine.renderStartupBaseline(reason);
            } else {
                next = forceFull ? renderEngine.renderFull(reason) : renderEngine.renderIncremental(reason);
            }
            updateSnapshot(next);
            if ("ok".equals(next.status)) {
                long costMs = (System.nanoTime() - startedNs) / 1_000_000L;
                getLogger().info("Render done (" + reason + "), chunks=" + next.chunkCount
                        + ", image=" + next.width + "x" + next.height
                        + ", costMs=" + costMs);
            }
        } catch (Exception e) {
            RenderSnapshot current = latestSnapshot;
            updateSnapshot(new RenderSnapshot(
                    "error",
                    reason,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    System.currentTimeMillis(),
                    current.width,
                    current.height,
                    current.chunkCount,
                    current.regionFileCount,
                    current.playerDataFileCount,
                    current.renderCount
            ));
            getLogger().warning("Render failed (" + reason + "): " + e.getMessage());
        } finally {
            finishRenderCycleAndMaybeRerun();
        }
    }

    private void startBudgetedFullRender(String reason) {
        if (!acquireRenderSlotOrQueue(reason, true)) {
            return;
        }
        try {
            if (renderEngine == null) {
                throw new IllegalStateException("render engine not initialized");
            }
            cancelFullRenderBudgetTask();
            fullRenderSession = renderEngine.beginFullRenderSession(reason);
            updateSnapshot(new RenderSnapshot(
                    "running",
                    reason,
                    "full render queued (budget mode)",
                    System.currentTimeMillis(),
                    latestSnapshot.width,
                    latestSnapshot.height,
                    latestSnapshot.chunkCount,
                    latestSnapshot.regionFileCount,
                    latestSnapshot.playerDataFileCount,
                    latestSnapshot.renderCount
            ));

            fullRenderBudgetTask = getServer().getScheduler().runTaskTimer(this, () -> {
                runBudgetedFullRenderStep(reason);
            }, 1L, 1L);
        } catch (Exception e) {
            updateSnapshot(new RenderSnapshot(
                    "error",
                    reason,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    System.currentTimeMillis(),
                    latestSnapshot.width,
                    latestSnapshot.height,
                    latestSnapshot.chunkCount,
                    latestSnapshot.regionFileCount,
                    latestSnapshot.playerDataFileCount,
                    latestSnapshot.renderCount
            ));
            getLogger().warning("Budgeted full render failed to start (" + reason + "): " + e.getMessage());
            cancelFullRenderBudgetTask();
            finishRenderCycleAndMaybeRerun();
        }
    }

    private void runBudgetedFullRenderStep(String reason) {
        if (renderEngine == null || fullRenderSession == null) {
            cancelFullRenderBudgetTask();
            finishRenderCycleAndMaybeRerun();
            return;
        }
        try {
            IncrementalRenderEngine.FullRenderStepResult step = renderEngine.stepFullRenderSession(
                    fullRenderSession,
                    fullRenderMaxChunksPerTick,
                    fullRenderMaxMillisPerTick
            );
            updateSnapshot(step.snapshot);
            if (step.done) {
                cancelFullRenderBudgetTask();
                if ("ok".equals(step.snapshot.status)) {
                    getLogger().info("Budgeted full render done (" + reason + "), chunks="
                            + step.snapshot.chunkCount + ", image=" + step.snapshot.width + "x" + step.snapshot.height);
                }
                finishRenderCycleAndMaybeRerun();
            }
        } catch (Exception e) {
            updateSnapshot(new RenderSnapshot(
                    "error",
                    reason,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    System.currentTimeMillis(),
                    latestSnapshot.width,
                    latestSnapshot.height,
                    latestSnapshot.chunkCount,
                    latestSnapshot.regionFileCount,
                    latestSnapshot.playerDataFileCount,
                    latestSnapshot.renderCount
            ));
            getLogger().warning("Budgeted full render failed (" + reason + "): " + e.getMessage());
            cancelFullRenderBudgetTask();
            finishRenderCycleAndMaybeRerun();
        }
    }

    private boolean acquireRenderSlotOrQueue(String reason, boolean forceFull) {
        synchronized (renderLock) {
            if (renderRunning) {
                pendingRenderRequested = true;
                pendingRenderForceFull = pendingRenderForceFull || forceFull;
                pendingRenderReason = reason;
                return false;
            }
            renderRunning = true;
            return true;
        }
    }

    private void finishRenderCycleAndMaybeRerun() {
        boolean rerun = false;
        boolean rerunForce = false;
        String rerunReason = null;
        synchronized (renderLock) {
            renderRunning = false;
            if (pendingRenderRequested) {
                rerun = true;
                rerunForce = pendingRenderForceFull;
                rerunReason = (pendingRenderReason == null || pendingRenderReason.isBlank())
                        ? "queued-render"
                        : pendingRenderReason;
                pendingRenderRequested = false;
                pendingRenderForceFull = false;
                pendingRenderReason = null;
            }
        }
        if (rerun) {
            triggerRenderAsync(rerunReason, rerunForce);
        }
    }

    private void cancelFullRenderBudgetTask() {
        if (fullRenderBudgetTask != null) {
            fullRenderBudgetTask.cancel();
            fullRenderBudgetTask = null;
        }
        fullRenderSession = null;
    }

    private void updateSnapshot(RenderSnapshot snapshot) {
        latestSnapshot = snapshot;
        if (previewServer != null) {
            previewServer.updateSnapshot(snapshot);
        }
    }

    private void pushPreviewConfig() {
        if (previewServer == null) {
            return;
        }
        previewServer.updateImagePaths(outputImage, outputMask);
        previewServer.updateVisualConfig(
                unknownFogEnabled,
                chunkBoundaryEnabled,
                colorToHex(emptyColor),
                colorToHex(unknownFogDisabledColor),
                colorToHex(chunkBoundaryColor),
                chunkPixelSize,
                startupPredictiveEnabled,
                texturePaletteEnabled
        );
        previewServer.updateAutoRuntime(autoUpdateEnabled, autoUpdateIntervalSeconds);
    }

    private void loadRuntimeConfig() {
        worldPath = resolveWorldPath();
        regionPath = worldPath.resolve("region");
        String worldName = getConfig().getString("world-name", "world");
        targetWorld = getServer().getWorld(worldName);
        if (targetWorld == null) {
            throw new IllegalStateException("Bukkit world not loaded: " + worldName);
        }

        outputDirPath = getDataFolder().toPath().resolve(getConfig().getString("output-dir", "maps"));
        String outputFileRaw = getConfig().getString("output-file", "overview.png");
        if (outputFileRaw == null || outputFileRaw.isBlank()) {
            outputFileRaw = "overview.png";
        }
        String outputFileName = Paths.get(outputFileRaw.trim()).getFileName().toString();
        int dot = outputFileName.lastIndexOf('.');
        if (dot > 0 && dot < outputFileName.length() - 1) {
            outputFileStem = outputFileName.substring(0, dot);
            outputFileExt = outputFileName.substring(dot);
        } else {
            outputFileStem = outputFileName;
            outputFileExt = ".png";
        }
        int configuredEdge = Math.max(1, getConfig().getInt("chunk-pixel-size", 1));
        Integer normalizedEdge = normalizeQualityEdge(configuredEdge);
        if (normalizedEdge == null) {
            chunkPixelSize = configuredEdge >= 16 ? 16 : 1;
            getConfig().set("chunk-pixel-size", chunkPixelSize);
            saveConfig();
        } else {
            chunkPixelSize = normalizedEdge;
        }
        refreshOutputPathsForCurrentQuality();
        startupWindowChunks = Math.max(0, getConfig().getInt("local-render.startup-window-chunks", 128));
        boolean legacyPredictiveEnabled = getConfig().getBoolean("local-render.startup-predictive.enabled", true);
        /*
         * Startup predictive rendering is now forced ON.
         * Keep config key only for backward compatibility and migrate false -> true.
         */
        startupPredictiveEnabled = true;
        if (!legacyPredictiveEnabled) {
            getConfig().set("local-render.startup-predictive.enabled", true);
            saveConfig();
            getLogger().info("local-render.startup-predictive.enabled=false is deprecated and ignored (forced true).");
        }
        boolean legacyTexturePaletteEnabled = getConfig().getBoolean("visual.texture-color.enabled", true);
        /*
         * Texture palette is fixed ON.
         * Frontend toggle has been removed; backend runtime always keeps this enabled.
         * Keep config key only for backward compatibility and migrate false -> true.
         */
        texturePaletteEnabled = true;
        if (!legacyTexturePaletteEnabled) {
            getConfig().set("visual.texture-color.enabled", true);
            saveConfig();
            getLogger().info("visual.texture-color.enabled=false is deprecated and ignored (forced true).");
        }
        minecraftJarPath = getConfig().getString("visual.texture-color.minecraft-jar", "").trim();
        emptyColor = parseHexColor(getConfig().getString("empty-color", "#111827"), new Color(0x11, 0x18, 0x27));
        filledColor = parseHexColor(getConfig().getString("land-color", "#6EE7B7"), new Color(0x6E, 0xE7, 0xB7));
        unknownFogEnabled = getConfig().getBoolean("visual.unknown-fog.enabled", true);
        String unknownFogDisabledRaw = getConfig().getString("visual.unknown-fog.disabled-color", "#ffffff");
        // Migrate legacy light-blue background default to pure white.
        if (unknownFogDisabledRaw != null && unknownFogDisabledRaw.trim().equalsIgnoreCase("#dbeafe")) {
            unknownFogDisabledRaw = "#ffffff";
            getConfig().set("visual.unknown-fog.disabled-color", unknownFogDisabledRaw);
            saveConfig();
        }
        unknownFogDisabledColor = parseHexColor(
                unknownFogDisabledRaw,
                new Color(0xFF, 0xFF, 0xFF)
        );
        boolean legacyChunkBoundaryEnabled = getConfig().getBoolean("visual.chunk-boundary.enabled", false);
        String boundaryRaw = getConfig().getString("visual.chunk-boundary.color", "#00e5ff");
        // Migrate legacy dark default to vivid cyan for better boundary readability.
        if (boundaryRaw != null && boundaryRaw.trim().equalsIgnoreCase("#1f2937")) {
            boundaryRaw = "#00e5ff";
            getConfig().set("visual.chunk-boundary.color", boundaryRaw);
            saveConfig();
        }
        /*
         * Chunk-boundary overlay is retired.
         *
         * We still parse and keep color/settings for backward compatibility with old
         * configs and old clients that may read these fields from stats. Runtime behavior
         * is forced to "disabled" so no render path can re-enable boundary overlays.
         */
        chunkBoundaryEnabled = false;
        if (legacyChunkBoundaryEnabled) {
            getLogger().info("visual.chunk-boundary.enabled is deprecated and ignored (forced false).");
        }
        chunkBoundaryColor = parseHexColor(
                boundaryRaw,
                new Color(0x00, 0xE5, 0xFF)
        );

        renderOnStartup = getConfig().getBoolean("local-render.render-on-startup", true);
        autoUpdateEnabled = getConfig().getBoolean("local-render.auto-update.enabled", true);
        autoUpdateIntervalSeconds = Math.max(2L, getConfig().getLong("local-render.auto-update.interval-seconds", 30L));
        watchRegionDirectory = getConfig().getBoolean("local-render.watch-region-directory", true);
        budgetedFullRenderEnabled = getConfig().getBoolean("local-render.full-render-budget.enabled", true);
        fullRenderMaxChunksPerTick = Math.max(
                1,
                getConfig().getInt("local-render.full-render-budget.max-chunks-per-tick", 64)
        );
        fullRenderMaxMillisPerTick = Math.max(
                1L,
                getConfig().getLong("local-render.full-render-budget.max-millis-per-tick", 12L)
        );
    }

    private void rebuildRenderEngine() throws Exception {
        if (renderEngine != null) {
            renderEngine.stopWatcher();
        }
        refreshOutputPathsForCurrentQuality();
        renderEngine = new IncrementalRenderEngine(
                worldPath,
                regionPath,
                outputImage,
                outputMask,
                targetWorld,
                startupWindowChunks,
                startupPredictiveEnabled,
                texturePaletteEnabled,
                minecraftJarPath,
                chunkPixelSize,
                emptyColor,
                filledColor,
                false,
                unknownFogDisabledColor,
                false,
                chunkBoundaryColor,
                getLogger()
        );
        if (watchRegionDirectory) {
            renderEngine.startWatcher();
        } else {
            getLogger().warning("Region watcher disabled; incremental queue only via manual markAllDirty.");
        }
        if (previewServer != null) {
            previewServer.updateImagePaths(outputImage, outputMask);
        }
    }

    private void refreshOutputPathsForCurrentQuality() {
        outputImage = outputDirPath.resolve(outputFileStem + "-q" + chunkPixelSize + outputFileExt);
        outputMask = outputDirPath.resolve(outputFileStem + "-mask-q" + chunkPixelSize + ".png");
    }

    private Path resolveWorldPath() {
        String configuredPath = getConfig().getString("world-path", "").trim();
        if (!configuredPath.isEmpty()) {
            return Paths.get(configuredPath);
        }
        String worldName = getConfig().getString("world-name", "world");
        return getServer().getWorldContainer().toPath().resolve(worldName);
    }

    private Color parseHexColor(String raw, Color fallback) {
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() != 6) {
            return fallback;
        }
        try {
            int rgb = Integer.parseInt(normalized, 16);
            return new Color(rgb);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatEpochMs(long epochMs) {
        if (epochMs <= 0) {
            return "n/a";
        }
        return Instant.ofEpochMilli(epochMs).toString();
    }

    private String requestManualRender(boolean forceFull) {
        return runOnMainThreadAndWait(() -> {
            triggerRenderAsync(forceFull ? "manual-full-web" : "manual-incremental-web", forceFull);
            return "render requested. force=" + forceFull;
        });
    }

    private String updateAutoRenderSettings(Boolean enabled, Long intervalSeconds) {
        return runOnMainThreadAndWait(() -> {
            if (enabled != null) {
                autoUpdateEnabled = enabled;
                getConfig().set("local-render.auto-update.enabled", autoUpdateEnabled);
            }
            if (intervalSeconds != null) {
                autoUpdateIntervalSeconds = Math.max(2L, Math.min(3600L, intervalSeconds));
                getConfig().set("local-render.auto-update.interval-seconds", autoUpdateIntervalSeconds);
            }
            saveConfig();
            configureAutoRenderTask();
            if (previewServer != null) {
                previewServer.updateAutoRuntime(autoUpdateEnabled, autoUpdateIntervalSeconds);
            }
            return "auto-update=" + autoUpdateEnabled + ", interval=" + autoUpdateIntervalSeconds + "s";
        });
    }

    private String updateVisualSettings(String name, Boolean enabled) {
        if (name == null || name.isBlank() || enabled == null) {
            return "invalid visual toggle";
        }
        String key = name.trim().toLowerCase();
        if ("chunkboundary".equals(key)) {
            return "chunkboundary is deprecated: overlay removed from frontend and backend renderer";
        }
        if ("texturepalette".equals(key)) {
            return "texturepalette is deprecated: fixed ON and no longer toggleable";
        }
        if ("predictivestartup".equals(key)) {
            return "predictivestartup is deprecated: fixed ON and no longer toggleable";
        }
        if (!"unknownfog".equals(key)) {
            return "unknown visual toggle: " + name;
        }

        boolean target = enabled;
        getServer().getScheduler().runTask(this, () -> applyVisualSettingsOnMainThread(key, target));
        return "scheduled " + key + "=" + target;
    }

    private void applyVisualSettingsOnMainThread(String key, boolean enabled) {
        switch (key) {
            case "unknownfog" -> {
                unknownFogEnabled = enabled;
                getConfig().set("visual.unknown-fog.enabled", unknownFogEnabled);
            }
            /*
             * case "chunkboundary" (removed):
             * Legacy behavior toggled backend/frontend boundary overlay and persisted
             * visual.chunk-boundary.enabled. This is intentionally retired to avoid
             * boundary rendering divergence and zoom-level flicker issues.
             * See updateVisualSettings(): requests now return a deprecation message.
             */
            /*
             * case "predictivestartup" (removed):
             * Startup predictive is fixed ON to keep first-frame behavior deterministic.
             *
             * case "texturepalette" (removed):
             * Texture palette is fixed ON to keep map color logic stable and avoid
             * runtime style drift from accidental toggles.
             */
            default -> {
                return;
            }
        }

        saveConfig();
        pushPreviewConfig();
    }

    private String updateRenderQuality(Integer edge) {
        if (edge == null) {
            return "无效的画质档位";
        }
        return runOnMainThreadAndWait(() -> applyRenderQualityOnMainThread(edge));
    }

    private String applyRenderQualityOnMainThread(int edge) {
        Integer normalized = normalizeQualityEdge(edge);
        if (normalized == null) {
            return "画质档位必须是：1、2、3、16、80";
        }
        if (chunkPixelSize == normalized) {
            return "画质未变化：" + qualityLabel(normalized);
        }

        chunkPixelSize = normalized;
        getConfig().set("chunk-pixel-size", chunkPixelSize);
        saveConfig();
        try {
            refreshOutputPathsForCurrentQuality();
            rebuildRenderEngine();
            pushPreviewConfig();
            triggerRenderAsync("quality-full-web", true);
            return "画质已切换为 " + qualityLabel(chunkPixelSize) + "，已排队全量重建";
        } catch (Exception e) {
            return "画质切换失败：" + e.getMessage();
        }
    }

    private String runOnMainThreadAndWait(java.util.function.Supplier<String> action) {
        if (Bukkit.isPrimaryThread()) {
            return action.get();
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        getServer().getScheduler().runTask(this, () -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.complete("failed: " + e.getMessage());
            }
        });
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "failed: timeout waiting for server main thread";
        } catch (Exception e) {
            return "failed: " + e.getMessage();
        }
    }

    private static Boolean parseOnOff(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if ("on".equals(value) || "true".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("off".equals(value) || "false".equals(value) || "0".equals(value)) {
            return false;
        }
        return null;
    }

    private static Long parseIntervalSeconds(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 2 || value > 3600) {
                return null;
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer parseQualityEdge(String raw) {
        try {
            return normalizeQualityEdge(Integer.parseInt(raw.trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer normalizeQualityEdge(int edge) {
        if (edge == 1 || edge == 2 || edge == 3 || edge == 16 || edge == 80) {
            return edge;
        }
        return null;
    }

    private static String qualityLabel(int edge) {
        return switch (edge) {
            case 1 -> "1像素/区块";
            case 2 -> "4像素/区块";
            case 3 -> "9像素/区块";
            case 16 -> "1像素/方块";
            case 80 -> "25像素/方块（巡检）";
            default -> edge + "像素/区块";
        };
    }

    private static String colorToHex(Color color) {
        int rgb = color.getRGB() & 0xFFFFFF;
        return String.format("#%06X", rgb);
    }
}
