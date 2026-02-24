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

public final class SealantermapPlugin extends JavaPlugin {
    private HttpPreviewServer previewServer;
    private IncrementalRenderEngine renderEngine;
    private BukkitTask autoRenderTask;

    private Path worldPath;
    private Path regionPath;
    private Path outputImage;
    private int chunkPixelSize;
    private int startupWindowChunks;
    private boolean startupPredictiveEnabled;
    private boolean texturePaletteEnabled;
    private String minecraftJarPath;
    private Color emptyColor;
    private Color filledColor;
    private boolean unknownFogEnabled;
    private Color unknownFogDisabledColor;
    private boolean chunkBoundaryEnabled;
    private Color chunkBoundaryColor;
    private boolean renderOnStartup;
    private boolean autoUpdateEnabled;
    private long autoUpdateIntervalSeconds;
    private boolean watchRegionDirectory;
    private World targetWorld;

    private volatile RenderSnapshot latestSnapshot = RenderSnapshot.EMPTY;
    private final Object renderLock = new Object();
    private boolean renderRunning;

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
                    this::requestManualRender,
                    this::updateAutoRenderSettings
            );
            previewServer.updateSnapshot(latestSnapshot);
            pushPreviewConfig();
            previewServer.start();

            renderEngine = new IncrementalRenderEngine(
                    worldPath,
                    regionPath,
                    outputImage,
                    targetWorld,
                    startupWindowChunks,
                    startupPredictiveEnabled,
                    texturePaletteEnabled,
                    minecraftJarPath,
                    chunkPixelSize,
                    emptyColor,
                    filledColor,
                    unknownFogEnabled,
                    unknownFogDisabledColor,
                    chunkBoundaryEnabled,
                    chunkBoundaryColor,
                    getLogger()
            );
            if (watchRegionDirectory) {
                renderEngine.startWatcher();
            } else {
                getLogger().warning("Region watcher disabled; incremental queue only via manual markAllDirty.");
            }

            registerCommands();
            configureAutoRenderTask();

            if (renderOnStartup) {
                renderOnce("startup-full", true);
            }

            getLogger().info("Sealantermap ready.");
            getLogger().info("World path: " + worldPath);
            getLogger().info("Image path: " + outputImage);
            getLogger().info("Preview URL: http://" + host + ":" + port + "/");
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
                if (renderEngine != null) {
                    renderEngine.stopWatcher();
                }
                renderEngine = new IncrementalRenderEngine(
                        worldPath,
                        regionPath,
                        outputImage,
                        targetWorld,
                        startupWindowChunks,
                        startupPredictiveEnabled,
                        texturePaletteEnabled,
                        minecraftJarPath,
                        chunkPixelSize,
                        emptyColor,
                        filledColor,
                        unknownFogEnabled,
                        unknownFogDisabledColor,
                        chunkBoundaryEnabled,
                        chunkBoundaryColor,
                        getLogger()
                );
                if (watchRegionDirectory) {
                    renderEngine.startWatcher();
                }
                pushPreviewConfig();
                configureAutoRenderTask();
                triggerRenderAsync("reload-full", true);
                sender.sendMessage("[Sealantermap] config reloaded.");
            } catch (Exception e) {
                sender.sendMessage("[Sealantermap] reload failed: " + e.getMessage());
            }
            return true;
        }

        sender.sendMessage("Usage: /slmap <status|render [force]|auto <on|off>|interval <seconds>|reload>");
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

    private void triggerRenderAsync(String reason, boolean forceFull) {
        getServer().getScheduler().runTask(this, () -> renderOnce(reason, forceFull));
    }

    private void renderOnce(String reason, boolean forceFull) {
        synchronized (renderLock) {
            if (renderRunning) {
                return;
            }
            renderRunning = true;
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
            synchronized (renderLock) {
                renderRunning = false;
            }
        }
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

        Path outputDir = getDataFolder().toPath().resolve(getConfig().getString("output-dir", "maps"));
        outputImage = outputDir.resolve(getConfig().getString("output-file", "overview.png"));
        chunkPixelSize = Math.max(1, getConfig().getInt("chunk-pixel-size", 1));
        startupWindowChunks = Math.max(0, getConfig().getInt("local-render.startup-window-chunks", 128));
        startupPredictiveEnabled = getConfig().getBoolean("local-render.startup-predictive.enabled", true);
        texturePaletteEnabled = getConfig().getBoolean("visual.texture-color.enabled", true);
        minecraftJarPath = getConfig().getString("visual.texture-color.minecraft-jar", "").trim();
        emptyColor = parseHexColor(getConfig().getString("empty-color", "#111827"), new Color(0x11, 0x18, 0x27));
        filledColor = parseHexColor(getConfig().getString("land-color", "#6EE7B7"), new Color(0x6E, 0xE7, 0xB7));
        unknownFogEnabled = getConfig().getBoolean("visual.unknown-fog.enabled", true);
        unknownFogDisabledColor = parseHexColor(
                getConfig().getString("visual.unknown-fog.disabled-color", "#dbeafe"),
                new Color(0xDB, 0xEA, 0xFE)
        );
        chunkBoundaryEnabled = getConfig().getBoolean("visual.chunk-boundary.enabled", false);
        chunkBoundaryColor = parseHexColor(
                getConfig().getString("visual.chunk-boundary.color", "#1f2937"),
                new Color(0x1F, 0x29, 0x37)
        );

        renderOnStartup = getConfig().getBoolean("local-render.render-on-startup", true);
        autoUpdateEnabled = getConfig().getBoolean("local-render.auto-update.enabled", true);
        autoUpdateIntervalSeconds = Math.max(2L, getConfig().getLong("local-render.auto-update.interval-seconds", 10L));
        watchRegionDirectory = getConfig().getBoolean("local-render.watch-region-directory", true);
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
            return future.get(5, TimeUnit.SECONDS);
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

    private static String colorToHex(Color color) {
        int rgb = color.getRGB() & 0xFFFFFF;
        return String.format("#%06X", rgb);
    }
}
