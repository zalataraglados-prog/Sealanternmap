package io.sealantermap;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

final class HttpPreviewServer {
  private static final int HTTP_WORKER_THREADS = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
  private static final int MAX_CONCURRENT_TILE_RENDERS = 2;
  private static final long TILE_RENDER_ACQUIRE_TIMEOUT_MS = 80L;
  private static final int PRECUT_TILE_SIZE = 256;
  private static final int AUTO_COARSE_CHUNKS_PER_PIXEL = 4;

  /**
   * Tile delivery strategy is intentionally split into three mutually-exclusive
   * modes.
   * FAST_PRECUT:
   * - Default frontend path.
   * - Reads already pre-cut tile files only.
   * - No large-map crop IO at request time.
   *
   * AUTO_LOD_PRECUT:
   * - Automatic coarse fallback path when viewport pressure is high.
   * - Composes result from low-quality pre-cut tiles.
   * - Still avoids runtime full-map crop reads.
   *
   * MANUAL_LARGE_CROP:
   * - Explicit troubleshooting/maintenance path only (manual=1).
   * - Allows direct region crop from large map PNG.
   * - Kept for operator control, never default interactive flow.
   */
  private enum TileAccessMode {
    FAST_PRECUT,
    AUTO_LOD_PRECUT,
    MANUAL_LARGE_CROP
  }

  private final String host;
  private final int port;
  private volatile Path imagePath;
  private volatile Path maskPath;
  private final Function<Boolean, String> renderAction;
  private final BiFunction<Boolean, Long, String> autoAction;
  private final BiFunction<String, Boolean, String> visualAction;
  private final Function<Integer, String> qualityAction;

  private volatile RenderSnapshot snapshot = RenderSnapshot.EMPTY;
  private volatile boolean unknownFogEnabled = true;
  // Deprecated runtime flag kept only for API/config backward compatibility.
  // Frontend boundary overlay was removed and backend no longer draws chunk boundary lines.
  private volatile boolean chunkBoundaryEnabled = false;
  private volatile String emptyColorHex = "#111827";
  private volatile String fogDisabledColorHex = "#ffffff";
  // Deprecated color field kept only so old clients/config readers do not break.
  private volatile String chunkBoundaryColorHex = "#00e5ff";
  private volatile int chunkPixelSize = 1;
  private volatile boolean predictiveStartupEnabled = true;
  private volatile boolean texturePaletteEnabled = true;
  private volatile boolean autoUpdateEnabled = true;
  private volatile long autoUpdateIntervalSeconds = 30;
  private volatile long gameTimeTicks = -1L;
  private volatile boolean dayTime = true;
  private volatile MaskGrid cachedMaskGrid;
  private volatile CachedUnknownMask cachedUnknownMask;
  private final Object tileCacheLock = new Object();
  private final Map<String, byte[]> tilePngCache = new LinkedHashMap<>(256, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
      return size() > 256;
    }
  };
  private final Semaphore tileRenderSemaphore = new Semaphore(MAX_CONCURRENT_TILE_RENDERS, true);
  private final AtomicLong tileBusyRejectCount = new AtomicLong(0L);

  private HttpServer server;
  private ExecutorService executor;

  HttpPreviewServer(
      String host,
      int port,
      Path imagePath,
      Path maskPath,
      Function<Boolean, String> renderAction,
      BiFunction<Boolean, Long, String> autoAction,
      BiFunction<String, Boolean, String> visualAction,
      Function<Integer, String> qualityAction) {
    this.host = host;
    this.port = port;
    this.imagePath = imagePath;
    this.maskPath = maskPath;
    this.renderAction = renderAction;
    this.autoAction = autoAction;
    this.visualAction = visualAction;
    this.qualityAction = qualityAction;
  }

  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(host, port), 0);
    AtomicInteger threadId = new AtomicInteger(1);
    executor = Executors.newFixedThreadPool(HTTP_WORKER_THREADS, r -> {
      Thread t = new Thread(r, "sealantermap-http-" + threadId.getAndIncrement());
      t.setDaemon(true);
      return t;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handleIndex);
    server.createContext("/map.png", this::handleMapImage);
    server.createContext("/tile.png", this::handleMapTileImage);
    server.createContext("/mask.png", this::handleMaskImage);
    server.createContext("/unknown-mask.png", this::handleUnknownMaskImage);
    server.createContext("/stats.json", this::handleStatsJson);
    // Frontend action endpoint: queue incremental/full render.
    server.createContext("/action/render", this::handleActionRender);
    // Frontend action endpoint: update auto-render config.
    server.createContext("/action/auto", this::handleActionAuto);
    // Frontend action endpoint: toggle visual switches (fog/predictive/texture).
    // "chunkboundary" is kept as a deprecated backend-compatible action key.
    server.createContext("/action/visual", this::handleActionVisual);
    // Frontend action endpoint: switch render quality and trigger full rebuild.
    server.createContext("/action/quality", this::handleActionQuality);
    server.start();
  }

  void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  void updateSnapshot(RenderSnapshot next) {
    if (next != null) {
      snapshot = next;
    }
  }

  void updateVisualConfig(
      boolean unknownFogEnabled,
      boolean chunkBoundaryEnabled,
      String emptyColorHex,
      String fogDisabledColorHex,
      String chunkBoundaryColorHex,
      int chunkPixelSize,
      boolean predictiveStartupEnabled,
      boolean texturePaletteEnabled) {
    boolean shouldClearCache = this.chunkPixelSize != Math.max(1, chunkPixelSize)
        || !safeHex(this.emptyColorHex, "#111827").equals(safeHex(emptyColorHex, "#111827"));
    this.unknownFogEnabled = unknownFogEnabled;
    // NOTE:
    // chunkBoundaryEnabled/chunkBoundaryColorHex are retained for backward compatibility
    // with older stats consumers and persisted configs, but boundary rendering itself has
    // been removed from both frontend and backend render pipeline.
    this.chunkBoundaryEnabled = chunkBoundaryEnabled;
    this.emptyColorHex = safeHex(emptyColorHex, "#111827");
    this.fogDisabledColorHex = safeHex(fogDisabledColorHex, "#ffffff");
    this.chunkBoundaryColorHex = safeHex(chunkBoundaryColorHex, "#00e5ff");
    this.chunkPixelSize = Math.max(1, chunkPixelSize);
    this.predictiveStartupEnabled = predictiveStartupEnabled;
    // Texture palette is fixed ON (frontend toggle removed).
    this.texturePaletteEnabled = true;
    if (shouldClearCache) {
      clearTileCache();
    }
  }

  void updateAutoRuntime(boolean autoUpdateEnabled, long autoUpdateIntervalSeconds) {
    this.autoUpdateEnabled = autoUpdateEnabled;
    this.autoUpdateIntervalSeconds = Math.max(2L, autoUpdateIntervalSeconds);
  }

  void updateGameTime(long gameTimeTicks, boolean dayTime) {
    this.gameTimeTicks = gameTimeTicks < 0 ? -1L : gameTimeTicks;
    this.dayTime = dayTime;
  }

  void updateImagePaths(Path imagePath, Path maskPath) {
    boolean changed = false;
    if (imagePath != null) {
      if (this.imagePath == null || !this.imagePath.equals(imagePath)) {
        changed = true;
      }
      this.imagePath = imagePath;
    }
    if (maskPath != null) {
      if (this.maskPath == null || !this.maskPath.equals(maskPath)) {
        changed = true;
      }
      this.maskPath = maskPath;
    }
    if (changed) {
      clearTileCache();
      cachedMaskGrid = null;
      cachedUnknownMask = null;
    }
  }

  private void handleIndex(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }

    RenderSnapshot s = snapshot;
    String generatedText = s.generatedEpochMs > 0 ? Instant.ofEpochMilli(s.generatedEpochMs).toString() : "not yet";
    long currentGameTimeTicks = gameTimeTicks;
    String gameTimeText = formatGameTimeStopwatch(currentGameTimeTicks);
    String dayNightText = currentGameTimeTicks < 0 ? "\u672a\u77e5" : (dayTime ? "\u767d\u5929" : "\u9ed1\u591c");
    String html = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8"/>
          <meta name="viewport" content="width=device-width,initial-scale=1"/>
          <title>Sealantermap</title>
          <style>
            :root{
              --bg:#ffffff; --card:#ffffff; --text:#0f172a; --muted:#64748b;
              --line:#e2e8f0; --primary:#0ea5e9; --ok:#16a34a; --warn:#d97706;
              --sl-btn-bg:#e0f2fe;
              --sl-btn-bg-hover:#bae6fd;
              --sl-btn-secondary:#ffffff;
              --sl-btn-secondary-hover:#f8fafc;
              --viewer-bg:#ffffff;
            }
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
            .wrap{max-width:1200px;margin:0 auto;padding:16px}
            .top{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:12px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px;backdrop-filter:none;-webkit-backdrop-filter:none;box-shadow:0 1px 2px rgba(15,23,42,0.04)}
            .title{font-size:13px;color:var(--muted);margin:0 0 6px 0;font-weight:500}
            .value{font-size:20px;font-weight:600;margin:0;font-family:Consolas,Menlo,monospace}
            .toolbar{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-bottom:12px}
            .ctrl{display:flex;align-items:center;gap:8px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 12px;color:var(--text);font-size:13px;backdrop-filter:none;-webkit-backdrop-filter:none}
            .ctrl input[type="range"]{width:120px;accent-color:var(--primary)}
            .ctrl input[type="number"]{width:70px;background:#ffffff;border:1px solid var(--line);color:var(--text);border-radius:4px;padding:2px 6px}
            .btn{cursor:pointer;background:var(--sl-btn-bg);color:#0c4a6e;border:1px solid #bae6fd;border-radius:6px;padding:6px 12px;font-size:13px;font-weight:500;transition:all 0.2s}
            .btn:hover{background:var(--sl-btn-bg-hover)}
            .btn.secondary{background:var(--sl-btn-secondary);color:var(--text);border:1px solid var(--line)}
            .btn.secondary:hover{background:var(--sl-btn-secondary-hover)}
            .btn:disabled{opacity:0.5;cursor:not-allowed}
            .badge{display:inline-flex;align-items:center;padding:4px 10px;border-radius:99px;background:#ffffff;border:1px solid var(--line);font-size:12px}
            .viewer{position:relative;overflow:hidden;background:var(--viewer-bg);border:1px solid var(--line);border-radius:12px;height:82vh;min-height:520px;contain:layout paint style;isolation:isolate}
            .stage{position:absolute;left:0;top:0;transform-origin:0 0;will-change:transform;backface-visibility:hidden}
            .tile-layer{position:relative;backface-visibility:hidden}
            .tile{position:absolute;display:block;image-rendering:pixelated;image-rendering:crisp-edges;backface-visibility:hidden}
            .fog-overlay{
              position:absolute;
              left:0;top:0;
              pointer-events:none;
              display:none;
              z-index:4;
              background:#000;
              mix-blend-mode:normal;
              backface-visibility:hidden;
              will-change:transform,width,height;
            }
            .quality-group{display:flex;gap:6px;flex-wrap:wrap}
            .quality-btn.active{background:#0ea5e9;color:#ffffff;border:1px solid #0284c7}
            .legend{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}
            .sw{width:14px;height:14px;border-radius:3px;border:1px solid #0008;display:inline-block;vertical-align:middle;margin-right:5px}
            .meta{margin-top:6px;font-size:12px;color:var(--muted)}
            .links a{color:#0369a1;text-decoration:none}
            .ok{color:var(--ok)}
            .warn{color:var(--warn)}
            .confirm-overlay{position:fixed;inset:0;background:rgba(15,23,42,0.18);display:none;align-items:center;justify-content:center;z-index:30}
            .confirm-card{width:min(520px, calc(100%% - 24px));background:#ffffff;border:1px solid var(--line);border-radius:12px;padding:14px;box-shadow:0 8px 20px rgba(15,23,42,0.12)}
            .confirm-title{margin:0 0 8px 0;font-size:15px;font-weight:700}
            .confirm-text{margin:0;color:var(--muted);line-height:1.5}
            .confirm-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:14px}
          </style>
        </head>
        <body>
        <div class="wrap">
          <div class="top">
            <div class="card"><p class="title">引擎状态</p><p class="value" id="status">%s</p></div>
            <div class="card"><p class="title">图片尺寸</p><p class="value"><span id="wh">%dx%d</span></p></div>
            <div class="card"><p class="title">已落盘区块</p><p class="value" id="chunks">%d</p></div>
            <div class="card"><p class="title">渲染次数</p><p class="value" id="rc">%d</p></div>
            <div class="card"><p class="title">游戏时间</p><p class="value"><span id="gt">%s</span></p><p class="meta card-note"><span id="dn">%s</span> | 实时秒表 (20 ticks = 1 s)</p></div>
          </div>

          <div class="toolbar">
            <div class="ctrl">缩放 <button class="btn" id="zout">-</button><input id="zoom" type="range" min="0.1" max="64" step="0.1" value="1"/><button class="btn" id="zin">+</button><span id="zv">1x</span></div>
            <div class="ctrl"><label><input type="checkbox" id="autoref"/> 自动刷新</label><input id="sec" type="number" min="2" max="60" value="4"/> 秒<button class="btn secondary" id="triggerRef">立即刷新</button></div>
            <div class="ctrl"><label><input type="checkbox" id="smooth"/> 平滑缩放</label></div>
          </div>

          <div class="toolbar">
            <div class="ctrl"><button class="btn secondary" id="renderInc">增量渲染</button><button class="btn" id="renderFull">全量渲染</button></div>
            <div class="ctrl"><span class="warn">性能提示: 全量渲染会重建基底图，耗时可能较长。</span></div>
            <div class="ctrl"><label><input type="checkbox" id="srvAuto" %s/> 后台自动渲染</label></div>
            <div class="ctrl">间隔 <input id="srvSec" type="number" min="2" max="3600" value="%d"/> 秒<button class="btn secondary" id="saveAuto">应用</button></div>
            <div class="ctrl"><span id="actionMsg" class="ok">就绪</span></div>
          </div>

          <div class="toolbar">
            <div class="ctrl">
              画质
              <div class="quality-group">
                <button class="btn secondary quality-btn" id="q1" data-edge="1">1x</button>
                <button class="btn secondary quality-btn" id="q2" data-edge="2">4x</button>
                <button class="btn secondary quality-btn" id="q3" data-edge="3">9x</button>
                <button class="btn secondary quality-btn" id="q16" data-edge="16">方块</button>
                <button class="btn secondary quality-btn" id="q80" data-edge="80">巡检</button>
              </div>
            </div>
            <div class="ctrl"><span class="warn">切换画质会触发全量重建。16/80 档更慢，但适合细节巡检。</span></div>
          </div>

          <div class="toolbar">
            <div class="ctrl"><button class="btn secondary" id="fog"></button></div>
          </div>

          <div id="viewer" class="viewer">
            <div id="stage" class="stage">
              <div id="tileLayer" class="tile-layer"></div>
            </div>
            <div id="fogLayer" class="fog-overlay"></div>
          </div>

          <p class="meta">生成时间: <span id="gen">%s</span> | 触发原因: <span id="reason">%s</span> | 消息: <span id="msg">%s</span></p>
          <p class="meta links"><a href="/map.png">/map.png</a> | <a href="/tile.png?x=0&y=0&size=256">/tile.png</a> | <a href="/tile.png?x=0&y=0&size=256&manual=1">/tile.png(manual)</a> | <a href="/stats.json">/stats.json</a></p>
          <div class="legend">
            <span class="badge"><i class="sw" style="background:%s"></i>未知区域 / 未渲染</span>
            <span class="badge"><i class="sw" style="background:%s"></i>未知区域（关闭迷雾）</span>
          </div>
        </div>
        <div id="confirmOverlay" class="confirm-overlay">
          <div class="confirm-card">
            <h3 class="confirm-title">&#25805;&#20316;&#30830;&#35748;</h3>
            <p id="confirmText" class="confirm-text"></p>
            <div class="confirm-actions">
              <button class="btn secondary" id="confirmCancel">&#21462;&#28040;</button>
              <button class="btn" id="confirmOk">&#32487;&#32493;</button>
            </div>
          </div>
        </div>
        <script>
        (function(){
          const cfg = {
            unknownFogEnabled: %s,
            chunkPixelSize: %d,
            predictiveStartupEnabled: %s,
            texturePaletteEnabled: true,
            emptyColor: '%s',
            fogDisabledColor: '%s',
            mapWidth: %d,
            mapHeight: %d
          };

          const viewer = document.getElementById('viewer');
          const stage = document.getElementById('stage');
          const tileLayer = document.getElementById('tileLayer');
          const fog = document.getElementById('fogLayer');
          if(fog && fog.parentElement !== viewer){
            viewer.appendChild(fog);
          }
          const zoomEl = document.getElementById('zoom');
          const zv = document.getElementById('zv');
          const autoRef = document.getElementById('autoref');
          const secEl = document.getElementById('sec');
          const btnTriggerRef = document.getElementById('triggerRef');
          const smoothEl = document.getElementById('smooth');
          const srvAuto = document.getElementById('srvAuto');
          const srvSec = document.getElementById('srvSec');
          const actionMsg = document.getElementById('actionMsg');
          const btnRenderInc = document.getElementById('renderInc');
          const btnRenderFull = document.getElementById('renderFull');
          const btnSaveAuto = document.getElementById('saveAuto');
          const qualityButtons = Array.from(document.querySelectorAll('.quality-btn'));
          const btnFog = document.getElementById('fog');
          const confirmOverlay = document.getElementById('confirmOverlay');
          const confirmText = document.getElementById('confirmText');
          const confirmCancel = document.getElementById('confirmCancel');
          const confirmOk = document.getElementById('confirmOk');

          const VISUAL_PREF_KEY = 'slmap_visual_prefs_v1';
          const ZOOM_MIN = 0.1;
          const ZOOM_MAX = 64;
          const TILE_SIZE = 256;
          const TILE_OVERSCAN = 1;
          const MAX_TILE_INFLIGHT = 10;
          const TILE_REQUEST_TIMEOUT_MS = 15000;
          const MAX_TILE_RETRY = 2;
          const IDLE_TILE_RETENTION_TILES = 2;
          const DRAG_TILE_RETENTION_TILES = 8;
          const DRAG_REQUEST_EXTRA_TILES = 1;
          const DRAG_TILE_REFRESH_INTERVAL_MS = 180;
          const DRAG_TILE_REFRESH_MIN_DELTA_PX = 24;
          const DRAG_CLEANUP_DELAY_MS = 220;
          const LOW_ZOOM_FILTER_CUTOFF = 0.75;
          const VIEW_GUARD_ZOOM_LINE = 0.60;
          const VIEW_GUARD_MISS_THRESHOLD = 0.85;
          const VIEW_GUARD_EXIT_ZOOM_LINE = 0.85;
          const VIEW_GUARD_REARM_HIT_THRESHOLD = 0.72;
          const VIEW_GUARD_REARM_STABLE_MS = 2000;
          const VIEW_GUARD_COOLDOWN_MS = 3500;
          const COARSE_CHUNKS_PER_PIXEL = 4;
          const QUALITY_SWITCH_STATS_WAIT_MS = 90000;
          const QUALITY_SWITCH_POLL_MS = 800;

          let zoom = Number(zoomEl.value);
          let tx = 10, ty = 10;
          let dragging = false, px = 0, py = 0;
          let timer = null;
          let triggerRefreshTimer = null;
          let tileEpoch = Date.now();
          const tileCache = new Map();
          let neededTileKeys = new Set();
          let tileQueue = [];
          const tileInflight = new Map();
          let tileQueueDirty = false;
          let dragCleanupTimer = null;
          let lastDragRefreshMs = 0;
          let dragMovedSinceRefreshPx = 0;
          let rafPending = false;
          let rafNeedTileRefresh = false;
          let renderedTx = tx;
          let renderedTy = ty;
          let renderedZoom = zoom;
          let textureFilterState = '';
          let coarseMode = false;
          let lastZoomForGuard = zoom;
          let guardRearmArmed = true;
          let guardRecoverStableSinceMs = 0;
          let guardCooldownUntilMs = 0;
          let guardSmoothedHitRatio = 1;
          let autoLodFallbackNotified = false;
          let fogMaskImage = null;
          let fogMaskVersion = '';
          let fogMaskLoading = false;
          let fogMaskAppliedVersion = '';
          let gameClockBaseTicks = null;
          let gameClockBaseMs = 0;
          let gameClockTimer = null;
          let lastRenderSignature = '';
          let qualitySwitching = false;

          function clampZoom(v){
            return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, v));
          }

          function mapWidth(){
            return Math.max(1, Number(cfg.mapWidth || 1));
          }

          function mapHeight(){
            return Math.max(1, Number(cfg.mapHeight || 1));
          }

          // Keep pan in legal bounds:
          // - If map projection is smaller than viewport, center it.
          // - If larger, clamp so no blank area leaks into viewport.
          function clampPanToViewport(){
            const vw = Math.max(1, viewer.clientWidth || 1);
            const vh = Math.max(1, viewer.clientHeight || 1);
            const drawW = mapWidth() * zoom;
            const drawH = mapHeight() * zoom;
            if(drawW <= vw){
              tx = (vw - drawW) * 0.5;
            } else {
              const minTx = vw - drawW;
              if(tx < minTx){ tx = minTx; }
              if(tx > 0){ tx = 0; }
            }
            if(drawH <= vh){
              ty = (vh - drawH) * 0.5;
            } else {
              const minTy = vh - drawH;
              if(ty < minTy){ ty = minTy; }
              if(ty > 0){ ty = 0; }
            }
          }

          function viewerCenterAnchor(){
            return {
              x: Math.max(1, viewer.clientWidth || 1) * 0.5,
              y: Math.max(1, viewer.clientHeight || 1) * 0.5
            };
          }

          // Zoom around viewport center so zoom-in does not drift to top-left.
          function setZoomKeepingCenter(nextZoom){
            const oldZoom = Math.max(0.0001, zoom);
            const anchor = viewerCenterAnchor();
            const worldX = (anchor.x - tx) / oldZoom;
            const worldY = (anchor.y - ty) / oldZoom;
            zoom = clampZoom(nextZoom);
            tx = anchor.x - (worldX * zoom);
            ty = anchor.y - (worldY * zoom);
            clampPanToViewport();
          }

          function effectiveSampleStep(){
            if(!coarseMode){
              return 1;
            }
            const pxPerChunk = Math.max(1, Number(cfg.chunkPixelSize || 1));
            return Math.max(1, Math.round(pxPerChunk * COARSE_CHUNKS_PER_PIXEL));
          }

          function effectiveTileSpan(){
            return TILE_SIZE * effectiveSampleStep();
          }

          function ensureStageSize(){
            const w = mapWidth();
            const h = mapHeight();
            tileLayer.style.width = w + 'px';
            tileLayer.style.height = h + 'px';
          }

          function loadVisualPrefs(){
            try{
              const raw = localStorage.getItem(VISUAL_PREF_KEY);
              if(!raw){ return; }
              const parsed = JSON.parse(raw);
              if(typeof parsed.unknownFogEnabled === 'boolean'){ cfg.unknownFogEnabled = parsed.unknownFogEnabled; }
            }catch(_){ }
          }

          function saveVisualPrefs(){
            try{
              localStorage.setItem(VISUAL_PREF_KEY, JSON.stringify({
                unknownFogEnabled: cfg.unknownFogEnabled
              }));
            }catch(_){ }
          }

          function setMsg(text, ok=true){
            actionMsg.textContent = text;
            actionMsg.className = ok ? 'ok' : 'warn';
          }

          function renderSignature(s){
            return [
              Number(s.renderCount || 0),
              Number(s.generatedEpochMs || 0),
              Number(s.width || 0),
              Number(s.height || 0),
              Number(s.chunkPixelSize || 0)
            ].join('|');
          }

          function qualityText(edge){
            if(edge === 1){ return '1像素/区块'; }
            if(edge === 2){ return '4像素/区块'; }
            if(edge === 3){ return '9像素/区块'; }
            if(edge === 16){ return '1像素/方块'; }
            if(edge === 80){ return '25像素/方块（巡检）'; }
            return edge + '像素/区块';
          }

          function syncQualityButtonLabels(){
            for(const btn of qualityButtons){
              const edge = Number(btn.dataset.edge || 1);
              btn.textContent = qualityText(edge);
              btn.title = qualityText(edge);
            }
          }

          function setQualityButtonsDisabled(disabled){
            for(const btn of qualityButtons){
              btn.disabled = !!disabled;
            }
          }

          function sleep(ms){
            return new Promise(resolve => setTimeout(resolve, Math.max(0, Number(ms) || 0)));
          }

          function isActionFailedMessage(message){
            const text = String(message || '').toLowerCase();
            return text.startsWith('failed')
              || text.includes('quality switch failed')
              || text.includes('timeout waiting for server main thread')
              || text.includes('失败')
              || text.includes('超时');
          }

          function resetLodGuardState(){
            coarseMode = false;
            autoLodFallbackNotified = false;
            guardRearmArmed = true;
            guardRecoverStableSinceMs = 0;
            guardCooldownUntilMs = 0;
            guardSmoothedHitRatio = 1;
            lastZoomForGuard = zoom;
          }

          function preserveViewportAnchorForTopology(oldW, oldH, newW, newH){
            const oW = Math.max(1, Number(oldW) || 1);
            const oH = Math.max(1, Number(oldH) || 1);
            const nW = Math.max(1, Number(newW) || 1);
            const nH = Math.max(1, Number(newH) || 1);
            const anchor = viewerCenterAnchor();
            const oldMapX = (anchor.x - tx) / Math.max(0.0001, zoom);
            const oldMapY = (anchor.y - ty) / Math.max(0.0001, zoom);
            const nx = Math.max(0, Math.min(1, oldMapX / oW));
            const ny = Math.max(0, Math.min(1, oldMapY / oH));
            const newMapX = nx * nW;
            const newMapY = ny * nH;
            tx = anchor.x - (newMapX * zoom);
            ty = anchor.y - (newMapY * zoom);
          }

          function pad2(v){
            const n = Math.max(0, Number(v) || 0);
            return n < 10 ? ('0' + n) : String(n);
          }

          function formatGameTimeStopwatch(ticks){
            const t = Number(ticks);
            if(!Number.isFinite(t) || t < 0){
              return '未知';
            }
            const totalSeconds = Math.floor(t / 20);
            const days = Math.floor(totalSeconds / 86400);
            const hours = Math.floor((totalSeconds %% 86400) / 3600);
            const minutes = Math.floor((totalSeconds %% 3600) / 60);
            const seconds = totalSeconds %% 60;
            const time = `${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}`;
            return days > 0 ? `${days}天 ${time}` : time;
          }

          function phaseTextFromTicks(ticks){
            const t = Number(ticks);
            if(!Number.isFinite(t) || t < 0){
              return '\u672a\u77e5';
            }
            const dayTick = ((Math.floor(t) %% 24000) + 24000) %% 24000;
            return dayTick < 12000 ? '\u767d\u5929' : '\u9ed1\u591c';
          }

          function updateGameClockView(ticks){
            document.getElementById('gt').textContent = formatGameTimeStopwatch(ticks);
            document.getElementById('dn').textContent = phaseTextFromTicks(ticks);
          }

          function setGameClockBase(serverTicks){
            const t = Number(serverTicks);
            if(!Number.isFinite(t) || t < 0){
              gameClockBaseTicks = null;
              gameClockBaseMs = 0;
              if(gameClockTimer){
                clearInterval(gameClockTimer);
                gameClockTimer = null;
              }
              updateGameClockView(-1);
              return;
            }
            gameClockBaseTicks = Math.floor(t);
            gameClockBaseMs = Date.now();
            updateGameClockView(gameClockBaseTicks);
            if(gameClockTimer){
              return;
            }
            gameClockTimer = setInterval(() => {
              if(gameClockBaseTicks == null){
                return;
              }
              const elapsedMs = Date.now() - gameClockBaseMs;
              const estimatedTicks = gameClockBaseTicks + Math.max(0, Math.floor((elapsedMs * 20) / 1000));
              updateGameClockView(estimatedTicks);
            }, 250);
          }

          function syncQualityControl(){
            const edge = Math.max(1, Number(cfg.chunkPixelSize || 1));
            for(const btn of qualityButtons){
              const active = Number(btn.dataset.edge || 0) === edge;
              btn.classList.toggle('active', active);
            }
          }

          function applySmoothToTile(tile){
            tile.style.imageRendering = smoothEl.checked ? 'auto' : 'pixelated';
          }

          function setSmooth(){
            for(const tile of tileCache.values()){
              applySmoothToTile(tile);
            }
          }

          function setTextureFilter(){
            // CSS filter is expensive at low zoom and during drag; disable in those cases
            // to avoid edge shimmer/flicker.
            //
            // Texture palette toggle is retired and fixed ON, so "enabled" no longer
            // depends on cfg.texturePaletteEnabled.
            //
            // Legacy branch (retired):
            // const enabled = cfg.texturePaletteEnabled && !dragging && zoom >= LOW_ZOOM_FILTER_CUTOFF;
            const enabled = !dragging && zoom >= LOW_ZOOM_FILTER_CUTOFF;
            const next = enabled ? 'saturate(1.12) contrast(1.05)' : 'none';
            if(next !== textureFilterState){
              textureFilterState = next;
              tileLayer.style.filter = next;
            }
          }

          function updateGridStyle(){
            /*
             * Chunk-boundary frontend overlay is retired.
             *
             * Why keep this no-op:
             * 1) Some existing call sites still invoke updateGridStyle() in the
             *    render/transform flow. Keeping a stable no-op avoids touching many
             *    unrelated rendering paths in one change.
             * 2) This preserves backward compatibility for older cached frontend code
             *    paths while guaranteeing boundary lines are never shown again.
             * 3) Future cleanup can safely remove this method after all call sites are
             *    refactored together.
             */
          }

          function updateFogMaskVersionFromStats(s){
            const next = [
              Number(s.renderCount || 0),
              Number(s.generatedEpochMs || 0),
              Number(s.width || 0),
              Number(s.height || 0),
              Number(s.chunkPixelSize || 0)
            ].join('|');
            if(next === fogMaskVersion){
              return;
            }
            fogMaskVersion = next;
            fogMaskImage = null;
            fogMaskLoading = false;
            fogMaskAppliedVersion = '';
            if(fog){
              fog.style.display = 'none';
              fog.style.maskImage = 'none';
              fog.style.webkitMaskImage = 'none';
            }
          }

          function applyFogMaskResource(version){
            if(!fog || !version){
              return;
            }
            if(fogMaskAppliedVersion === version){
              return;
            }
            const url = `/unknown-mask.png?t=${encodeURIComponent(version)}`;
            fog.style.maskImage = `url("${url}")`;
            fog.style.webkitMaskImage = `url("${url}")`;
            fog.style.maskRepeat = 'no-repeat';
            fog.style.webkitMaskRepeat = 'no-repeat';
            fog.style.maskSize = '100%% 100%%';
            fog.style.webkitMaskSize = '100%% 100%%';
            fog.style.maskPosition = '0 0';
            fog.style.webkitMaskPosition = '0 0';
            fogMaskAppliedVersion = version;
          }

          function clearFogOverlayState(){
            fogMaskImage = null;
            fogMaskLoading = false;
            fogMaskAppliedVersion = '';
            if(fog){
              fog.style.display = 'none';
              fog.style.maskImage = 'none';
              fog.style.webkitMaskImage = 'none';
            }
          }
          function ensureFogMaskLoaded(){
            if(!cfg.unknownFogEnabled || !fogMaskVersion || fogMaskLoading || fogMaskImage){
              return;
            }
            fogMaskLoading = true;
            const version = fogMaskVersion;
            const img = new Image();
            img.decoding = 'async';
            img.onload = () => {
              if(version !== fogMaskVersion){
                fogMaskLoading = false;
                return;
              }
              fogMaskImage = img;
              fogMaskLoading = false;
              applyFogMaskResource(version);
              requestFrame(false);
            };
            img.onerror = () => {
              if(version === fogMaskVersion){
                fogMaskImage = null;
                fogMaskLoading = false;
              }
            };
            img.src = `/unknown-mask.png?t=${encodeURIComponent(version)}`;
          }

          function updateFogOverlayGeometry(){
            if(!fog){
              return;
            }
            if(!cfg.unknownFogEnabled){
              fog.style.display = 'none';
              return;
            }
            ensureFogMaskLoaded();
            if(!fogMaskImage || !fogMaskAppliedVersion || cfg.mapWidth <= 0 || cfg.mapHeight <= 0){
              fog.style.display = 'none';
              return;
            }
            fog.style.display = 'block';
            const drawW = cfg.mapWidth * renderedZoom;
            const drawH = cfg.mapHeight * renderedZoom;
            if(drawW <= 0 || drawH <= 0){
              fog.style.display = 'none';
              return;
            }
            fog.style.left = `${renderedTx}px`;
            fog.style.top = `${renderedTy}px`;
            fog.style.width = `${drawW}px`;
            fog.style.height = `${drawH}px`;
          }

          function refreshVisualButtons(){
            btnFog.textContent = '未知迷雾：' + (cfg.unknownFogEnabled ? '开启' : '关闭');
            syncQualityButtonLabels();
            // Keep base map background stable. Unknown fog is handled by overlay only.
            const viewerBg = (cfg.fogDisabledColor && String(cfg.fogDisabledColor).trim()) || '#ffffff';
            viewer.style.background = viewerBg;
            if(fog){
              fog.style.background = '#000000';
            }
            setTextureFilter();
            updateGridStyle();
            updateFogOverlayGeometry();
            syncQualityControl();
          }

          function snapDevicePixel(value){
            const dpr = Math.max(1, Number(window.devicePixelRatio) || 1);
            return Math.round(value * dpr) / dpr;
          }

          function applyTransformOnly(){
            clampPanToViewport();
            const sx = snapDevicePixel(tx);
            const sy = snapDevicePixel(ty);
            if(sx !== renderedTx || sy !== renderedTy || zoom !== renderedZoom){
              renderedTx = sx;
              renderedTy = sy;
              renderedZoom = zoom;
              stage.style.transform = `translate3d(${renderedTx}px,${renderedTy}px,0) scale(${renderedZoom})`;
            }
            zv.textContent = (zoom < 1 ? zoom.toFixed(2) : zoom.toFixed(1)) + 'x';
            setTextureFilter();
            updateGridStyle();
            updateFogOverlayGeometry();
          }

          function requestFrame(refreshTilesNeeded){
            if(refreshTilesNeeded){
              rafNeedTileRefresh = true;
            }
            if(rafPending){
              return;
            }
            rafPending = true;
            requestAnimationFrame(() => {
              rafPending = false;
              applyTransformOnly();
              if(rafNeedTileRefresh){
                rafNeedTileRefresh = false;
                refreshTiles();
              }
            });
          }

          function apply(refresh=true){
            zoom = clampZoom(zoom);
            clampPanToViewport();
            zoomEl.value = String(zoom);
            requestFrame(refresh);
          }

          function resetTileRequests(){
            for(const inflight of tileInflight.values()){
              try { inflight.controller.abort(); } catch(_) { }
            }
            tileInflight.clear();
            tileQueue = [];
            tileQueueDirty = false;
            neededTileKeys = new Set();
          }

          function purgeTiles(){
            for(const tile of tileCache.values()){
              const oldUrl = tile.dataset.objurl;
              if(oldUrl){
                try { URL.revokeObjectURL(oldUrl); } catch(_) { }
                delete tile.dataset.objurl;
              }
            }
            tileLayer.replaceChildren();
            tileCache.clear();
          }

          function clearTiles(){
            resetTileRequests();
            purgeTiles();
          }

          function visibleRect(){
            const vw = viewer.clientWidth;
            const vh = viewer.clientHeight;
            const w = mapWidth();
            const h = mapHeight();
            const pad = TILE_SIZE * TILE_OVERSCAN;
            const left = Math.max(0, Math.floor((-renderedTx) / zoom) - pad);
            const top = Math.max(0, Math.floor((-renderedTy) / zoom) - pad);
            const right = Math.min(w, Math.ceil((vw - renderedTx) / zoom) + pad);
            const bottom = Math.min(h, Math.ceil((vh - renderedTy) / zoom) + pad);
            return {left, top, right, bottom};
          }

          function tileUrl(x, y, autoLod){
            return `/tile.png?x=${x}&y=${y}&size=${TILE_SIZE}&autoLod=${autoLod ? 1 : 0}&t=${tileEpoch}`;
          }

          function dropQueuedTask(key){
            if(tileQueue.length === 0){ return; }
            tileQueue = tileQueue.filter(task => task.key !== key);
            tileQueueDirty = true;
          }

          function queuePriority(sx, sy, centerX, centerY){
            const tx = sx + (TILE_SIZE * 0.5);
            const ty = sy + (TILE_SIZE * 0.5);
            const dx = tx - centerX;
            const dy = ty - centerY;
            return (dx * dx) + (dy * dy);
          }

          function pumpTileQueue(){
            if(tileQueueDirty){
              tileQueue.sort((a, b) => a.priority - b.priority);
              tileQueueDirty = false;
            }
            while(tileInflight.size < MAX_TILE_INFLIGHT && tileQueue.length > 0){
              const task = tileQueue.shift();
              if(!task){ break; }
              if(task.epoch !== tileEpoch){ continue; }
              if(!neededTileKeys.has(task.key)){ continue; }
              const existingInflight = tileInflight.get(task.key);
              if(existingInflight){
                if(existingInflight.epoch === task.epoch){
                  continue;
                }
                try { existingInflight.controller.abort(); } catch(_) { }
                tileInflight.delete(task.key);
              }

              const controller = new AbortController();
              tileInflight.set(task.key, { controller, epoch: task.epoch });
              const timeoutId = setTimeout(() => controller.abort(), TILE_REQUEST_TIMEOUT_MS);

              fetch(tileUrl(task.sx, task.sy, task.autoLod), { cache: 'no-store', signal: controller.signal })
                .then(r => {
                  if(!r.ok){
                    if(task.autoLod && r.status === 404){
                      // Auto LOD now uses pre-cut-only path.
                      // If coarse assets are missing, immediately fall back to normal sampling.
                      if(coarseMode){
                        coarseMode = false;
                        guardRearmArmed = false;
                        guardCooldownUntilMs = Date.now() + VIEW_GUARD_COOLDOWN_MS;
                        tileEpoch = Date.now();
                        resetTileRequests();
                        purgeTiles();
                        refreshVisualButtons();
                        requestFrame(true);
                      }
                      if(!autoLodFallbackNotified){
                        autoLodFallbackNotified = true;
                        setMsg('Auto LOD pre-cut tiles unavailable, fallback to normal sampling.', false);
                      }
                      throw new Error('HTTP 404');
                    }
                    if((r.status === 429 || r.status === 503) && task.retries < MAX_TILE_RETRY){
                      // Backpressure from server: delayed retry.
                      setTimeout(() => {
                        if(task.epoch !== tileEpoch || !neededTileKeys.has(task.key)){ return; }
                        tileQueue.push({
                          key: task.key,
                          tile: task.tile,
                          sx: task.sx,
                          sy: task.sy,
                          sample: task.sample,
                          autoLod: task.autoLod,
                          epoch: task.epoch,
                          priority: task.priority,
                          retries: task.retries + 1
                        });
                        tileQueueDirty = true;
                        pumpTileQueue();
                      }, 120 + (task.retries * 120));
                    }
                    throw new Error('HTTP ' + r.status);
                  }
                  return r.blob();
                })
                .then(blob => {
                  if(task.epoch !== tileEpoch || !neededTileKeys.has(task.key)){ return null; }
                  const objUrl = URL.createObjectURL(blob);
                  const preload = new Image();
                  preload.decoding = 'async';
                  preload.src = objUrl;
                  return Promise.resolve(preload.decode())
                    .catch(() => { })
                    .then(() => ({ objUrl }));
                })
                .then(payload => {
                  if(!payload){ return; }
                  const objUrl = payload.objUrl;
                  if(task.epoch !== tileEpoch || !neededTileKeys.has(task.key)){
                    try { URL.revokeObjectURL(objUrl); } catch(_) { }
                    return;
                  }
                  const oldUrl = task.tile.dataset.objurl;
                  task.tile.src = objUrl;
                  task.tile.dataset.objurl = objUrl;
                  task.tile.dataset.epoch = String(task.epoch);
                  task.tile.dataset.sample = String(task.sample);
                  if(oldUrl && oldUrl !== objUrl){
                    try { URL.revokeObjectURL(oldUrl); } catch(_) { }
                  }
                })
                .catch(_ => { })
                .finally(() => {
                  clearTimeout(timeoutId);
                  const inflight = tileInflight.get(task.key);
                  if(inflight && inflight.controller === controller){
                    tileInflight.delete(task.key);
                  }
                  pumpTileQueue();
                });
            }
          }

          function enqueueTileLoad(key, tile, sx, sy, centerX, centerY, sampleStep){
            if(tile.dataset.epoch === String(tileEpoch) && tile.dataset.sample === String(sampleStep)){ return; }
            const inflight = tileInflight.get(key);
            if(inflight && inflight.epoch === tileEpoch){ return; }
            if(tileQueue.some(task => task.key === key && task.epoch === tileEpoch)){ return; }
            const autoLod = coarseMode;
            tileQueue.push({
              key,
              tile,
              sx,
              sy,
              sample: sampleStep,
              autoLod,
              epoch: tileEpoch,
              priority: queuePriority(sx, sy, centerX, centerY),
              retries: 0
            });
            tileQueueDirty = true;
          }

          // Drag path is render-light by design:
          // 1) every pointer move updates transform only;
          // 2) tile fetch checks are debounced by both time and distance.
          function scheduleDragTileRefresh(force=false, deltaPx=0){
            dragMovedSinceRefreshPx += Math.max(0, Number(deltaPx) || 0);
            const now = Date.now();
            const elapsed = now - lastDragRefreshMs;
            if(!force){
              if(elapsed < DRAG_TILE_REFRESH_INTERVAL_MS){
                return;
              }
              if(dragMovedSinceRefreshPx < DRAG_TILE_REFRESH_MIN_DELTA_PX){
                return;
              }
            }
            lastDragRefreshMs = now;
            dragMovedSinceRefreshPx = 0;
            requestFrame(true);
          }

          function scheduleDragCleanup(){
            if(dragCleanupTimer){
              clearTimeout(dragCleanupTimer);
            }
            dragCleanupTimer = setTimeout(() => {
              dragCleanupTimer = null;
              if(!dragging){
                requestFrame(true);
              }
            }, DRAG_CLEANUP_DELAY_MS);
          }

          function updateTileGeometry(tile, sx, sy, mapW, mapH, tileSpan){
            tile.style.left = sx + 'px';
            tile.style.top = sy + 'px';
            tile.style.width = Math.max(1, Math.min(tileSpan, mapW - sx)) + 'px';
            tile.style.height = Math.max(1, Math.min(tileSpan, mapH - sy)) + 'px';
          }

          function updateGuardRearm(nowMs, hitRatio){
            guardSmoothedHitRatio = (guardSmoothedHitRatio * 0.70) + (hitRatio * 0.30);
            if(guardRearmArmed){
              guardRecoverStableSinceMs = 0;
              return;
            }
            if(guardSmoothedHitRatio >= VIEW_GUARD_REARM_HIT_THRESHOLD){
              if(guardRecoverStableSinceMs <= 0){
                guardRecoverStableSinceMs = nowMs;
                return;
              }
              if((nowMs - guardRecoverStableSinceMs) >= VIEW_GUARD_REARM_STABLE_MS){
                guardRearmArmed = true;
                guardRecoverStableSinceMs = 0;
              }
              return;
            }
            guardRecoverStableSinceMs = 0;
          }

          function refreshTiles(){
            ensureStageSize();
            const w = mapWidth();
            const h = mapHeight();
            if(w <= 0 || h <= 0){
              return;
            }

            const rect = visibleRect();
            if(rect.right <= rect.left || rect.bottom <= rect.top){
              return;
            }

            const shrinking = zoom < (lastZoomForGuard - 0.0001);
            const sampleStep = effectiveSampleStep();
            const tileSpan = TILE_SIZE * sampleStep;
            const requestPadPx = tileSpan * (dragging ? DRAG_REQUEST_EXTRA_TILES : 0);
            const requestLeft = Math.max(0, rect.left - requestPadPx);
            const requestTop = Math.max(0, rect.top - requestPadPx);
            const requestRight = Math.min(w, rect.right + requestPadPx);
            const requestBottom = Math.min(h, rect.bottom + requestPadPx);

            const x0 = Math.floor(requestLeft / tileSpan);
            const x1 = Math.floor((Math.max(requestLeft, requestRight - 1)) / tileSpan);
            const y0 = Math.floor(requestTop / tileSpan);
            const y1 = Math.floor((Math.max(requestTop, requestBottom - 1)) / tileSpan);
            const centerX = (rect.left + rect.right) * 0.5;
            const centerY = (rect.top + rect.bottom) * 0.5;
            const needed = new Set();
            let missCount = 0;
            const retentionTiles = dragging ? DRAG_TILE_RETENTION_TILES : IDLE_TILE_RETENTION_TILES;
            const keepPadPx = tileSpan * (TILE_OVERSCAN + retentionTiles);
            const keepLeft = Math.max(0, Math.floor((-renderedTx) / zoom) - keepPadPx);
            const keepTop = Math.max(0, Math.floor((-renderedTy) / zoom) - keepPadPx);
            const keepRight = Math.min(w, Math.ceil((viewer.clientWidth - renderedTx) / zoom) + keepPadPx);
            const keepBottom = Math.min(h, Math.ceil((viewer.clientHeight - renderedTy) / zoom) + keepPadPx);
            const keepX0 = Math.floor(keepLeft / tileSpan);
            const keepX1 = Math.floor((Math.max(keepLeft, keepRight - 1)) / tileSpan);
            const keepY0 = Math.floor(keepTop / tileSpan);
            const keepY1 = Math.floor((Math.max(keepTop, keepBottom - 1)) / tileSpan);
            const keep = new Set();
            for(let yk = keepY0; yk <= keepY1; yk++){
              for(let xk = keepX0; xk <= keepX1; xk++){
                keep.add(`${xk}:${yk}:s${sampleStep}`);
              }
            }

            for(let tyi = y0; tyi <= y1; tyi++){
              for(let txi = x0; txi <= x1; txi++){
                const sx = txi * tileSpan;
                const sy = tyi * tileSpan;
                const key = `${txi}:${tyi}:s${sampleStep}`;
                needed.add(key);
                let tile = tileCache.get(key);
                let created = false;
                if(!tile){
                  tile = document.createElement('img');
                  tile.className = 'tile';
                  tile.draggable = false;
                  applySmoothToTile(tile);
                  tileLayer.appendChild(tile);
                  tileCache.set(key, tile);
                  created = true;
                }
                updateTileGeometry(tile, sx, sy, w, h, tileSpan);
                const ready = !!tile.dataset.objurl
                  && tile.dataset.epoch === String(tileEpoch)
                  && tile.dataset.sample === String(sampleStep);
                if(created || !ready){
                  missCount++;
                }
                enqueueTileLoad(key, tile, sx, sy, centerX, centerY, sampleStep);
              }
            }
            const neededCount = needed.size;
            const missRatio = neededCount > 0 ? (missCount / neededCount) : 0;
            const hitRatio = Math.max(0, Math.min(1, 1 - missRatio));
            const nowMs = Date.now();
            if(!coarseMode){
              updateGuardRearm(nowMs, hitRatio);
            }
            if(!coarseMode
              && shrinking
              && zoom <= VIEW_GUARD_ZOOM_LINE
              && missRatio >= VIEW_GUARD_MISS_THRESHOLD
              && guardRearmArmed
              && nowMs >= guardCooldownUntilMs){
              coarseMode = true;
              autoLodFallbackNotified = false;
              guardRearmArmed = false;
              guardRecoverStableSinceMs = 0;
              guardCooldownUntilMs = nowMs + VIEW_GUARD_COOLDOWN_MS;
              tileEpoch = Date.now();
              resetTileRequests();
              purgeTiles();
              refreshVisualButtons();
              setMsg(`Zoom guard triggered: miss ${(missRatio * 100).toFixed(0)}%%, switched to 4 chunks/pixel.`, false);
              lastZoomForGuard = zoom;
              refreshTiles();
              return;
            }
            if(coarseMode && zoom >= VIEW_GUARD_EXIT_ZOOM_LINE){
              coarseMode = false;
              autoLodFallbackNotified = false;
              guardCooldownUntilMs = Math.max(guardCooldownUntilMs, nowMs + VIEW_GUARD_COOLDOWN_MS);
              tileEpoch = Date.now();
              resetTileRequests();
              purgeTiles();
              refreshVisualButtons();
              setMsg('Exited 4 chunks/pixel auto mode; returned to normal sampling.', true);
              lastZoomForGuard = zoom;
              refreshTiles();
              return;
            }

            // During drag, keep a wider "needed" set to avoid near-edge tile cancel/reload flicker.
            neededTileKeys = dragging ? keep : needed;

            if(dragging){
              pumpTileQueue();
              lastZoomForGuard = zoom;
              return;
            }

            for(const [key, tile] of tileCache.entries()){
              if(!keep.has(key)){
                const inflight = tileInflight.get(key);
                if(inflight){
                  try { inflight.controller.abort(); } catch(_) { }
                  tileInflight.delete(key);
                }
                dropQueuedTask(key);
                const oldUrl = tile.dataset.objurl;
                if(oldUrl){
                  try { URL.revokeObjectURL(oldUrl); } catch(_) { }
                }
                tile.remove();
                tileCache.delete(key);
                continue;
              }
              if(!needed.has(key)){
                const inflight = tileInflight.get(key);
                if(inflight){
                  try { inflight.controller.abort(); } catch(_) { }
                  tileInflight.delete(key);
                }
                dropQueuedTask(key);
              }
            }
            pumpTileQueue();
            lastZoomForGuard = zoom;
          }

          async function callJson(url, timeoutMs=12000){
            const controller = new AbortController();
            const timerId = setTimeout(() => controller.abort(), Math.max(1000, Number(timeoutMs) || 12000));
            try{
              const r = await fetch(url, { cache:'no-store', signal: controller.signal });
              if(!r.ok){
                throw new Error('HTTP ' + r.status);
              }
              return await r.json();
            } catch(e){
              if(e && e.name === 'AbortError'){
                throw new Error('request timeout');
              }
              throw e;
            } finally {
              clearTimeout(timerId);
            }
          }

          async function withBusy(button, action){
            if(!button){ return; }
            const oldText = button.textContent;
            button.disabled = true;
            button.textContent = 'Processing...';
            try{
              return await action();
            } finally {
              button.disabled = false;
              button.textContent = oldText;
            }
          }

          async function themedConfirm(message){
            if(!confirmOverlay || !confirmText || !confirmCancel || !confirmOk){
              return confirm(message);
            }
            confirmText.textContent = message;
            confirmOverlay.style.display = 'flex';
            return await new Promise((resolve) => {
              let done = false;
              const finish = (value) => {
                if(done){ return; }
                done = true;
                confirmOverlay.style.display = 'none';
                confirmCancel.removeEventListener('click', onCancel);
                confirmOk.removeEventListener('click', onOk);
                confirmOverlay.removeEventListener('click', onOverlay);
                document.removeEventListener('keydown', onEsc);
                resolve(value);
              };
              const onCancel = () => finish(false);
              const onOk = () => finish(true);
              const onOverlay = (event) => {
                if(event.target === confirmOverlay){
                  finish(false);
                }
              };
              const onEsc = (event) => {
                if(event.key === 'Escape'){
                  finish(false);
                }
              };
              confirmCancel.addEventListener('click', onCancel);
              confirmOk.addEventListener('click', onOk);
              confirmOverlay.addEventListener('click', onOverlay);
              document.addEventListener('keydown', onEsc);
            });
          }

          async function requestRender(force){
            const data = await callJson('/action/render?force=' + (force ? '1' : '0') + '&t=' + Date.now());
            setMsg(data.message || 'render requested', true);
            scheduleTriggeredRefresh(1000);
          }

          async function applyServerAuto(){
            const sec = Math.max(2, Math.min(3600, Number(srvSec.value) || 30));
            srvSec.value = sec;
            const data = await callJson('/action/auto?enabled=' + (srvAuto.checked ? '1' : '0') + '&interval=' + sec + '&t=' + Date.now());
            setMsg(data.message || '自动渲染设置已更新', true);
            scheduleTriggeredRefresh(300);
          }

          async function applyQualityEdge(edgeRaw){
            const edge = Math.max(1, Number(edgeRaw || 1));
            if(edge === Math.max(1, Number(cfg.chunkPixelSize || 1))){
              setMsg('画质未变化：' + qualityText(edge), true);
              return { edge, message: '画质未变化', unchanged: true };
            }
            if(edge === 16){
              const confirmed = await themedConfirm('16 档为 1 像素/方块，会触发全量重建，渲染耗时和负载会增加。是否继续？');
              if(!confirmed){
                setMsg('已取消切换到 16 档。', false);
                return null;
              }
            }
            if(edge === 80){
              const confirmed = await themedConfirm('80 档为 25 像素/方块（5x5），用于细节巡检。会生成更大图片且全量重建更慢。是否继续？');
              if(!confirmed){
                setMsg('已取消切换到 80 档。', false);
                return null;
              }
            }
            const data = await callJson('/action/quality?edge=' + edge + '&t=' + Date.now(), 45000);
            if(isActionFailedMessage(data && data.message)){
              throw new Error(data.message || '画质切换操作失败');
            }
            return { edge, message: (data && data.message) || ('画质已更新：' + qualityText(edge)) };
          }

          async function waitForQualityTopology(targetEdge, oldSignature, oldRenderCount){
            const start = Date.now();
            while((Date.now() - start) < QUALITY_SWITCH_STATS_WAIT_MS){
              try{
                const s = await callJson('/stats.json?t=' + Date.now(), 10000);
                const nextSig = renderSignature(s);
                const edgeOk = Math.max(1, Number(s.chunkPixelSize || 1)) === targetEdge;
                const renderCount = Math.max(0, Number(s.renderCount || 0));
                if(edgeOk && renderCount > oldRenderCount && nextSig !== oldSignature){
                  return s;
                }
              } catch(_){ }
              await sleep(QUALITY_SWITCH_POLL_MS);
            }
            return null;
          }

          zoomEl.addEventListener('input', ()=>{
            setZoomKeepingCenter(Number(zoomEl.value));
            apply();
          });
          document.getElementById('zin').addEventListener('click', ()=>{
            setZoomKeepingCenter(zoom < 1 ? zoom + 0.1 : zoom * 1.25);
            zoomEl.value = String(zoom);
            apply();
          });
          document.getElementById('zout').addEventListener('click', ()=>{
            setZoomKeepingCenter(zoom <= 1 ? zoom - 0.1 : zoom / 1.25);
            zoomEl.value = String(zoom);
            apply();
          });

          for(const btn of qualityButtons){
            btn.addEventListener('click', async ()=>{
              if(qualitySwitching){
                setMsg('画质切换进行中，请稍候...', false);
                return;
              }
              const edge = Number(btn.dataset.edge || 1);
              setMsg('正在切换画质并请求全量重建...', false);
              try {
                qualitySwitching = true;
                setQualityButtonsDisabled(true);
                clearFogOverlayState();
                const oldRenderCount = Math.max(0, Number(cfg.renderCount || 0));
                const oldSignature = lastRenderSignature || [
                  Number(cfg.renderCount || 0),
                  Number(cfg.generatedEpochMs || 0),
                  Number(cfg.mapWidth || 0),
                  Number(cfg.mapHeight || 0),
                  Number(cfg.chunkPixelSize || 0)
                ].join('|');
                resetTileRequests();
                purgeTiles();
                const result = await withBusy(btn, ()=>applyQualityEdge(edge));
                if(!result){
                  return;
                }
                if(result.unchanged){
                  return;
                }
                const topology = await waitForQualityTopology(edge, oldSignature, oldRenderCount);
                if(topology){
                  setMsg(result.message || ('画质已更新：' + qualityText(edge)), true);
                } else {
                  setMsg('已接受画质切换，正在后台等待重建完成...', false);
                }
                await refreshStats();
                refreshMap(false);
              } catch(e){
                setMsg('画质切换失败：' + e.message, false);
              } finally {
                qualitySwitching = false;
                setQualityButtonsDisabled(false);
              }
            });
          }

          btnRenderInc.addEventListener('click', async ()=>{
            setMsg('已请求增量渲染。', true);
            try { await withBusy(btnRenderInc, ()=>requestRender(false)); }
            catch(e){ setMsg('增量渲染请求失败：' + e.message, false); }
          });

          btnRenderFull.addEventListener('click', async ()=>{
            const confirmed = await themedConfirm('性能警告：全量渲染会重建基底图，可能较长时间占用服务端主线程（可能触发 watchdog）。建议低峰期执行。是否继续？');
            if(!confirmed){
              setMsg('已取消全量渲染请求。', false);
              return;
            }
            setMsg('已请求全量渲染。', false);
            try { await withBusy(btnRenderFull, ()=>requestRender(true)); }
            catch(e){ setMsg('全量渲染请求失败：' + e.message, false); }
          });

          btnSaveAuto.addEventListener('click', async ()=>{
            setMsg('正在更新自动渲染设置...', true);
            try { await withBusy(btnSaveAuto, applyServerAuto); }
            catch(e){ setMsg('自动渲染设置更新失败：' + e.message, false); }
          });

          srvAuto.addEventListener('change', async ()=>{
            setMsg('正在切换自动渲染...', true);
            try { await applyServerAuto(); }
            catch(e){ setMsg('自动渲染切换失败：' + e.message, false); }
          });

          btnFog.addEventListener('click', async ()=>{
            const next = !cfg.unknownFogEnabled;
            setMsg('正在切换未知迷雾...', true);
            try {
              await withBusy(btnFog, async () => {
                const data = await callJson(`/action/visual?name=unknownfog&enabled=${next ? '1' : '0'}&t=` + Date.now());
                cfg.unknownFogEnabled = next;
                if(next){
                  ensureFogMaskLoaded();
                }
                saveVisualPrefs();
                refreshVisualButtons();
                requestFrame(false);
                setMsg(data.message || '未知迷雾已切换。', true);
              });
            } catch(e) {
              setMsg('未知迷雾切换失败：' + e.message, false);
            }
          });

          smoothEl.addEventListener('change', ()=>{ setSmooth(); refreshTiles(); });

          viewer.addEventListener('wheel', (e)=>{
            e.preventDefault();
            const factor = e.deltaY > 0 ? (1 / 1.12) : 1.12;
            setZoomKeepingCenter(zoom * factor);
            zoomEl.value = String(zoom);
            apply();
          }, {passive:false});

          viewer.addEventListener('pointerdown', (e)=>{
            dragging = true;
            setTextureFilter();
            lastDragRefreshMs = Date.now();
            dragMovedSinceRefreshPx = 0;
            if(dragCleanupTimer){
              clearTimeout(dragCleanupTimer);
              dragCleanupTimer = null;
            }
            px = e.clientX;
            py = e.clientY;
            viewer.setPointerCapture(e.pointerId);
          });
          viewer.addEventListener('pointermove', (e)=>{
            if(!dragging){ return; }
            const dx = e.clientX - px;
            const dy = e.clientY - py;
            tx += dx;
            ty += dy;
            px = e.clientX; py = e.clientY;
            apply(false);
            scheduleDragTileRefresh(false, Math.abs(dx) + Math.abs(dy));
          });
          viewer.addEventListener('pointerup', ()=>{
            dragging = false;
            setTextureFilter();
            scheduleDragTileRefresh(true, 0);
            scheduleDragCleanup();
          });
          viewer.addEventListener('pointercancel', ()=>{
            dragging = false;
            setTextureFilter();
            scheduleDragTileRefresh(true, 0);
            scheduleDragCleanup();
          });
          window.addEventListener('resize', ()=>{ requestFrame(true); });

          async function refreshStats(){
            try{
              const s = await callJson('/stats.json?t=' + Date.now());
              const prevChunkPixelSize = Math.max(1, Number(cfg.chunkPixelSize || 1));
              const prevMapWidth = Math.max(1, Number(cfg.mapWidth || 1));
              const prevMapHeight = Math.max(1, Number(cfg.mapHeight || 1));
              const sig = renderSignature(s);
              const changed = sig !== lastRenderSignature;
              document.getElementById('status').textContent = s.status;
              document.getElementById('wh').textContent = `${s.width}x${s.height}`;
              document.getElementById('chunks').textContent = s.chunkCount;
              document.getElementById('rc').textContent = s.renderCount;
              cfg.renderCount = Math.max(0, Number(s.renderCount || 0));
              cfg.generatedEpochMs = Math.max(0, Number(s.generatedEpochMs || 0));
              const gameTimeTicks = Number(s.gameTimeTicks);
              setGameClockBase(gameTimeTicks);
              document.getElementById('gen').textContent = s.generatedEpochMs > 0 ? new Date(s.generatedEpochMs).toISOString() : 'not yet';
              document.getElementById('reason').textContent = s.reason || '';
              document.getElementById('msg').textContent = s.message || '';
              srvAuto.checked = !!s.autoUpdateEnabled;
              srvSec.value = s.autoUpdateIntervalSeconds || 30;
              cfg.chunkPixelSize = Math.max(1, Number(s.chunkPixelSize || cfg.chunkPixelSize || 1));
              cfg.mapWidth = Math.max(1, Number(s.width || cfg.mapWidth || 1));
              cfg.mapHeight = Math.max(1, Number(s.height || cfg.mapHeight || 1));
              if (s.emptyColor) { cfg.emptyColor = s.emptyColor; }
              if (s.fogDisabledColor) { cfg.fogDisabledColor = s.fogDisabledColor; }
              updateFogMaskVersionFromStats(s);
              ensureFogMaskLoaded();
              refreshVisualButtons();
              if(changed){
                lastRenderSignature = sig;
                const topologyChanged = prevChunkPixelSize !== cfg.chunkPixelSize
                  || prevMapWidth !== cfg.mapWidth
                  || prevMapHeight !== cfg.mapHeight;
                if(topologyChanged){
                  preserveViewportAnchorForTopology(prevMapWidth, prevMapHeight, cfg.mapWidth, cfg.mapHeight);
                  resetLodGuardState();
                }
                // When topology changes (quality/size), force clear to avoid mixed old/new tile mosaics.
                refreshMap(!topologyChanged);
              }
              return s;
            }catch(_){
              return null;
            }
          }

          function refreshMap(keepVisible=true){
            tileEpoch = Date.now();
            if(!keepVisible){
              resetTileRequests();
              purgeTiles();
            }
            refreshTiles();
          }

          function reschedule(){
            if(timer){ clearInterval(timer); timer = null; }
            if(!autoRef.checked){ return; }
            const sec = Math.max(2, Math.min(60, Number(secEl.value) || 4));
            secEl.value = sec;
            timer = setInterval(()=>{ refreshStats(); }, sec * 1000);
          }

          function scheduleTriggeredRefresh(delayMs=250){
            const delay = Math.max(0, Number(delayMs) || 0);
            if(triggerRefreshTimer){
              clearTimeout(triggerRefreshTimer);
            }
            triggerRefreshTimer = setTimeout(() => {
              triggerRefreshTimer = null;
              refreshStats();
            }, delay);
          }

          autoRef.addEventListener('change', reschedule);
          secEl.addEventListener('change', reschedule);
          if(btnTriggerRef){
            btnTriggerRef.addEventListener('click', ()=>{ scheduleTriggeredRefresh(0); });
          }

          loadVisualPrefs();
          ensureStageSize();
          refreshVisualButtons();
          setSmooth();
          apply();
          refreshStats();
          refreshMap(false);
          reschedule();
        })();
        </script>
        </body></html>
        """
        .formatted(
            escape(s.status),
            s.width,
            s.height,
            s.chunkCount,
            s.renderCount,
            escape(gameTimeText),
            escape(dayNightText),
            autoUpdateEnabled ? "checked" : "",
            autoUpdateIntervalSeconds,
            escape(generatedText),
            escape(s.reason),
            escape(s.message),
            emptyColorHex,
            fogDisabledColorHex,
            unknownFogEnabled ? "true" : "false",
            chunkPixelSize,
            predictiveStartupEnabled ? "true" : "false",
            emptyColorHex,
            fogDisabledColorHex,
            s.width,
            s.height);

    writeBody(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
  }

  private void handleMapImage(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Path currentImage = imagePath;
    if (currentImage == null || !Files.exists(currentImage)) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    writeBody(exchange, 200, "image/png", Files.readAllBytes(currentImage));
  }

  private void handleMapTileImage(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    TileRequest request = parseTileRequest(exchange, imagePath);
    if (request == null) {
      return;
    }

    byte[] cached = getCachedTile(request.cacheKey);
    if (cached != null) {
      writeBody(exchange, 200, "image/png", cached);
      return;
    }

    byte[] directPrecutBytes = readDirectPrecutBytes(request);
    if (directPrecutBytes != null) {
      putCachedTile(request.cacheKey, directPrecutBytes);
      writeBody(exchange, 200, "image/png", directPrecutBytes);
      return;
    }

    if (!tryAcquireTileRenderSlot(exchange)) {
      return;
    }
    try {
      BufferedImage tile = loadTileForRequest(request);
      if (tile == null) {
        sendNotFound(exchange);
        return;
      }
      byte[] bytes = encodeTilePng(tile);
      putCachedTile(request.cacheKey, bytes);
      writeBody(exchange, 200, "image/png", bytes);
    } finally {
      tileRenderSemaphore.release();
    }
  }

  private TileRequest parseTileRequest(HttpExchange exchange, Path currentImage) throws IOException {
    Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    Long xRaw = parseLongNullable(q.get("x"));
    Long yRaw = parseLongNullable(q.get("y"));
    Long sizeRaw = parseLongNullable(q.get("size"));
    boolean autoLodRequested = parseBoolean(q.get("autoLod"), false);
    boolean manualLargeMapRequested = parseBoolean(q.get("manual"), false);
    TileAccessMode accessMode = resolveTileAccessMode(autoLodRequested, manualLargeMapRequested);

    int tileX = (int) Math.max(0L, xRaw == null ? 0L : xRaw);
    int tileY = (int) Math.max(0L, yRaw == null ? 0L : yRaw);
    int requestedSize = (int) Math.max(32L, Math.min(1024L, sizeRaw == null ? 256L : sizeRaw));
    int sampleStep = 1;
    if (accessMode == TileAccessMode.AUTO_LOD_PRECUT) {
      long forcedSample = (long) Math.max(1, chunkPixelSize) * AUTO_COARSE_CHUNKS_PER_PIXEL;
      sampleStep = (int) Math.max(1L, Math.min(4096L, forcedSample));
    }

    RenderSnapshot s = snapshot;
    int mapWidth = Math.max(0, s.width);
    int mapHeight = Math.max(0, s.height);
    if (mapWidth <= 0 || mapHeight <= 0) {
      sendNotFound(exchange);
      return null;
    }
    if (tileX >= mapWidth || tileY >= mapHeight) {
      sendNotFound(exchange);
      return null;
    }

    Path precutTile = sampleStep == 1 ? resolvePrecutTilePath(currentImage, tileX, tileY, requestedSize) : null;
    boolean hasPrecut = precutTile != null && Files.isRegularFile(precutTile);
    boolean hasMap = currentImage != null && Files.isRegularFile(currentImage);
    if (!hasPrecut && !hasMap) {
      sendNotFound(exchange);
      return null;
    }

    long imageLastModifiedMs = hasMap
        ? Files.getLastModifiedTime(currentImage).toMillis()
        : Math.max(1L, s.generatedEpochMs);
    String cacheKey = buildTileCacheKey(
        currentImage,
        imageLastModifiedMs,
        tileX,
        tileY,
        requestedSize,
        sampleStep,
        accessMode.name().toLowerCase(),
        false,
        safeHex(emptyColorHex, "#111827"),
        chunkPixelSize,
        0L);

    return new TileRequest(
        currentImage,
        precutTile,
        accessMode,
        tileX,
        tileY,
        requestedSize,
        sampleStep,
        mapWidth,
        mapHeight,
        cacheKey
    );
  }

  private byte[] readDirectPrecutBytes(TileRequest request) throws IOException {
    if (request.accessMode != TileAccessMode.FAST_PRECUT) {
      return null;
    }
    if (request.sampleStep != 1) {
      return null;
    }
    if (request.precutTile == null || !Files.isRegularFile(request.precutTile)) {
      return null;
    }
    return Files.readAllBytes(request.precutTile);
  }

  private boolean tryAcquireTileRenderSlot(HttpExchange exchange) throws IOException {
    boolean acquired = false;
    try {
      acquired = tileRenderSemaphore.tryAcquire(TILE_RENDER_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (acquired) {
      return true;
    }
    tileBusyRejectCount.incrementAndGet();
    Headers headers = exchange.getResponseHeaders();
    headers.set("Retry-After", "1");
    exchange.sendResponseHeaders(429, -1);
    exchange.close();
    return false;
  }

  private BufferedImage loadTileForRequest(TileRequest request) throws IOException {
    switch (request.accessMode) {
      case AUTO_LOD_PRECUT:
        return readAutoLodTileFromPrecut(
            request.currentImage,
            request.tileX,
            request.tileY,
            request.requestedSize,
            request.sampleStep,
            request.mapWidth,
            request.mapHeight);
      case MANUAL_LARGE_CROP:
        return readManualLargeMapTile(
            request.currentImage,
            request.tileX,
            request.tileY,
            request.requestedSize,
            request.sampleStep,
            request.mapWidth,
            request.mapHeight);
      case FAST_PRECUT:
      default:
        if (request.sampleStep == 1 && request.precutTile != null && Files.isRegularFile(request.precutTile)) {
          return ImageIO.read(request.precutTile.toFile());
        }
        return null;
    }
  }

  private static byte[] encodeTilePng(BufferedImage tile) throws IOException {
    int tileWidth = Math.max(1, tile.getWidth());
    int tileHeight = Math.max(1, tile.getHeight());
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, tileWidth * tileHeight / 2));
    ImageIO.write(tile, "png", out);
    return out.toByteArray();
  }

  private static void sendNotFound(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  /**
   * Access mode precedence:
   * 1) manual=1 always wins (explicit operator override).
   * 2) autoLod=1 uses low-quality pre-cut composition.
   * 3) fallback to normal fast pre-cut mode.
   */
  private TileAccessMode resolveTileAccessMode(boolean autoLodRequested, boolean manualLargeMapRequested) {
    if (manualLargeMapRequested) {
      return TileAccessMode.MANUAL_LARGE_CROP;
    }
    if (autoLodRequested) {
      return TileAccessMode.AUTO_LOD_PRECUT;
    }
    return TileAccessMode.FAST_PRECUT;
  }

  /**
   * Manual path only: reads a region from the large map image.
   * Returns null when source image is unavailable.
   */
  private BufferedImage readManualLargeMapTile(
      Path currentImage,
      int tileX,
      int tileY,
      int requestedSize,
      int sampleStep,
      int mapWidth,
      int mapHeight) throws IOException {
    if (currentImage == null || !Files.isRegularFile(currentImage)) {
      return null;
    }
    int tileMapWidth = Math.min(mapWidth - tileX, requestedSize * sampleStep);
    int tileMapHeight = Math.min(mapHeight - tileY, requestedSize * sampleStep);
    return readPngRegion(currentImage, tileX, tileY, tileMapWidth, tileMapHeight, sampleStep);
  }

  private BufferedImage readAutoLodTileFromPrecut(
      Path currentImage,
      int tileX,
      int tileY,
      int requestedSize,
      int sampleStep,
      int mapWidth,
      int mapHeight) throws IOException {
    if (currentImage == null || !Files.isRegularFile(currentImage)) {
      return null;
    }
    Path lowQualityImage = resolveQualityImageByEdge(currentImage, 1);
    if (lowQualityImage == null || !Files.isRegularFile(lowQualityImage)) {
      return null;
    }

    int currentEdge = parseQualityEdge(currentImage, Math.max(1, chunkPixelSize));
    int safeEdge = Math.max(1, currentEdge);
    int lowMapWidth = Math.max(1, mapWidth / safeEdge);
    int lowMapHeight = Math.max(1, mapHeight / safeEdge);

    int lowStartX = Math.max(0, tileX / safeEdge);
    int lowStartY = Math.max(0, tileY / safeEdge);
    int lowSampleStep = Math.max(1, sampleStep / safeEdge);
    int lowSpanWidth = Math.min(lowMapWidth - lowStartX, requestedSize * lowSampleStep);
    int lowSpanHeight = Math.min(lowMapHeight - lowStartY, requestedSize * lowSampleStep);
    if (lowSpanWidth <= 0 || lowSpanHeight <= 0) {
      return null;
    }
    return composeDownsampledTileFromPrecut(
        lowQualityImage,
        lowStartX,
        lowStartY,
        lowSpanWidth,
        lowSpanHeight,
        lowSampleStep,
        requestedSize);
  }

  private BufferedImage composeDownsampledTileFromPrecut(
      Path sourceImage,
      int srcStartX,
      int srcStartY,
      int srcSpanWidth,
      int srcSpanHeight,
      int srcSampleStep,
      int requestedSize) throws IOException {
    int outWidth = Math.max(1, Math.min(requestedSize, (int) Math.ceil(srcSpanWidth / (double) srcSampleStep)));
    int outHeight = Math.max(1, Math.min(requestedSize, (int) Math.ceil(srcSpanHeight / (double) srcSampleStep)));
    BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);

    Graphics2D g = out.createGraphics();
    g.setColor(new Color(parseHexRgb(fogDisabledColorHex), false));
    g.fillRect(0, 0, outWidth, outHeight);
    g.dispose();

    int tileSize = PRECUT_TILE_SIZE;
    int minTileX = (srcStartX / tileSize) * tileSize;
    int minTileY = (srcStartY / tileSize) * tileSize;
    int maxTileX = ((srcStartX + srcSpanWidth - 1) / tileSize) * tileSize;
    int maxTileY = ((srcStartY + srcSpanHeight - 1) / tileSize) * tileSize;

    Map<String, BufferedImage> loadedTiles = new HashMap<>();
    for (int ty = minTileY; ty <= maxTileY; ty += tileSize) {
      for (int tx = minTileX; tx <= maxTileX; tx += tileSize) {
        Path tilePath = resolvePrecutTilePath(sourceImage, tx, ty, tileSize);
        if (tilePath == null || !Files.isRegularFile(tilePath)) {
          continue;
        }
        BufferedImage tile = ImageIO.read(tilePath.toFile());
        if (tile != null) {
          loadedTiles.put(tx + ":" + ty, tile);
        }
      }
    }
    if (loadedTiles.isEmpty()) {
      return null;
    }

    int maxSrcX = srcStartX + srcSpanWidth - 1;
    int maxSrcY = srcStartY + srcSpanHeight - 1;
    for (int oy = 0; oy < outHeight; oy++) {
      int srcY = Math.min(maxSrcY, srcStartY + (oy * srcSampleStep));
      int tileBaseY = (srcY / tileSize) * tileSize;
      int localY = srcY - tileBaseY;
      for (int ox = 0; ox < outWidth; ox++) {
        int srcX = Math.min(maxSrcX, srcStartX + (ox * srcSampleStep));
        int tileBaseX = (srcX / tileSize) * tileSize;
        int localX = srcX - tileBaseX;
        BufferedImage tile = loadedTiles.get(tileBaseX + ":" + tileBaseY);
        if (tile == null || localX < 0 || localY < 0 || localX >= tile.getWidth() || localY >= tile.getHeight()) {
          continue;
        }
        out.setRGB(ox, oy, tile.getRGB(localX, localY));
      }
    }
    return out;
  }

  private static Path resolveQualityImageByEdge(Path currentImage, int edge) {
    if (currentImage == null) {
      return null;
    }
    String fileName = currentImage.getFileName().toString();
    int marker = fileName.lastIndexOf("-q");
    if (marker < 0) {
      return null;
    }
    int digitsStart = marker + 2;
    int idx = digitsStart;
    while (idx < fileName.length() && Character.isDigit(fileName.charAt(idx))) {
      idx++;
    }
    if (idx <= digitsStart) {
      return null;
    }
    String replaced = fileName.substring(0, digitsStart) + edge + fileName.substring(idx);
    Path parent = currentImage.getParent();
    return parent == null ? Path.of(replaced) : parent.resolve(replaced);
  }

  private static int parseQualityEdge(Path imagePath, int fallback) {
    if (imagePath == null) {
      return Math.max(1, fallback);
    }
    String fileName = imagePath.getFileName().toString();
    int marker = fileName.lastIndexOf("-q");
    if (marker < 0) {
      return Math.max(1, fallback);
    }
    int digitsStart = marker + 2;
    int idx = digitsStart;
    while (idx < fileName.length() && Character.isDigit(fileName.charAt(idx))) {
      idx++;
    }
    if (idx <= digitsStart) {
      return Math.max(1, fallback);
    }
    try {
      return Math.max(1, Integer.parseInt(fileName.substring(digitsStart, idx)));
    } catch (NumberFormatException ignored) {
      return Math.max(1, fallback);
    }
  }

  private static Path resolvePrecutTilePath(Path imagePath, int tileX, int tileY, int size) {
    if (imagePath == null || size != PRECUT_TILE_SIZE) {
      return null;
    }
    if (tileX < 0 || tileY < 0 || tileX % size != 0 || tileY % size != 0) {
      return null;
    }
    Path parent = imagePath.getParent();
    if (parent == null) {
      return null;
    }
    String file = imagePath.getFileName().toString();
    int dot = file.lastIndexOf('.');
    String stem = dot > 0 ? file.substring(0, dot) : file;
    return parent.resolve("tiles")
        .resolve(stem)
        .resolve("s" + size)
        .resolve("x" + tileX + "_y" + tileY + ".png");
  }

  private static BufferedImage readPngRegion(Path file, int x, int y, int width, int height, int sampleStep)
      throws IOException {
    try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
      if (input == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int imageWidth = reader.getWidth(0);
        int imageHeight = reader.getHeight(0);
        int rx = Math.max(0, Math.min(x, imageWidth - 1));
        int ry = Math.max(0, Math.min(y, imageHeight - 1));
        int rw = Math.max(1, Math.min(width, imageWidth - rx));
        int rh = Math.max(1, Math.min(height, imageHeight - ry));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(rx, ry, rw, rh));
        int safeSample = Math.max(1, sampleStep);
        if (safeSample > 1) {
          param.setSourceSubsampling(safeSample, safeSample, 0, 0);
        }
        return reader.read(0, param);
      } finally {
        reader.dispose();
      }
    }
  }

  private void applyUnknownFogToTile(BufferedImage tile, int globalX, int globalY, int sampleStep) throws IOException {
    Path currentMask = maskPath;
    if (currentMask == null || !Files.exists(currentMask)) {
      return;
    }
    MaskGrid mask = getCachedMaskGrid(currentMask);
    if (mask == null) {
      return;
    }
    int maskWidth = mask.width;
    int maskHeight = mask.height;
    if (maskWidth <= 0 || maskHeight <= 0) {
      return;
    }

    int step = Math.max(1, chunkPixelSize);
    int sample = Math.max(1, sampleStep);
    int tileWidth = tile.getWidth();
    int tileHeight = tile.getHeight();
    if (tileWidth <= 0 || tileHeight <= 0) {
      return;
    }

    int mapStartX = globalX;
    int mapStartY = globalY;
    int mapEndX = globalX + (tileWidth * sample);
    int mapEndY = globalY + (tileHeight * sample);

    int chunkStartX = Math.floorDiv(mapStartX, step);
    int chunkEndX = Math.floorDiv(mapEndX - 1, step);
    int chunkStartY = Math.floorDiv(mapStartY, step);
    int chunkEndY = Math.floorDiv(mapEndY - 1, step);

    Graphics2D g = tile.createGraphics();
    g.setColor(new Color(parseHexRgb(emptyColorHex), false));
    for (int chunkY = chunkStartY; chunkY <= chunkEndY; chunkY++) {
      for (int chunkX = chunkStartX; chunkX <= chunkEndX; chunkX++) {
        boolean known = chunkX >= 0 && chunkY >= 0 && chunkX < maskWidth && chunkY < maskHeight
            && mask.isKnown(chunkX, chunkY);
        if (known) {
          continue;
        }

        int chunkMapLeft = chunkX * step;
        int chunkMapTop = chunkY * step;
        int chunkMapRight = chunkMapLeft + step;
        int chunkMapBottom = chunkMapTop + step;

        int localLeft = (int) Math.floor((chunkMapLeft - mapStartX) / (double) sample);
        int localTop = (int) Math.floor((chunkMapTop - mapStartY) / (double) sample);
        int localRight = (int) Math.ceil((chunkMapRight - mapStartX) / (double) sample);
        int localBottom = (int) Math.ceil((chunkMapBottom - mapStartY) / (double) sample);
        localLeft = Math.max(0, localLeft);
        localTop = Math.max(0, localTop);
        localRight = Math.min(tileWidth, localRight);
        localBottom = Math.min(tileHeight, localBottom);
        if (localRight <= localLeft || localBottom <= localTop) {
          continue;
        }
        g.fillRect(localLeft, localTop, localRight - localLeft, localBottom - localTop);
      }
    }
    g.dispose();
  }

  private MaskGrid getCachedMaskGrid(Path currentMask) throws IOException {
    long modified = Files.getLastModifiedTime(currentMask).toMillis();
    MaskGrid existing = cachedMaskGrid;
    if (existing != null && existing.matches(currentMask, modified)) {
      return existing;
    }
    BufferedImage reloaded = ImageIO.read(currentMask.toFile());
    if (reloaded == null) {
      return null;
    }
    int width = Math.max(1, reloaded.getWidth());
    int height = Math.max(1, reloaded.getHeight());
    BitSet known = new BitSet(width * height);
    for (int y = 0; y < height; y++) {
      int base = y * width;
      for (int x = 0; x < width; x++) {
        int alpha = (reloaded.getRGB(x, y) >>> 24) & 0xFF;
        if (alpha > 0) {
          known.set(base + x);
        }
      }
    }
    MaskGrid fresh = new MaskGrid(currentMask, modified, width, height, known);
    cachedMaskGrid = fresh;
    cachedUnknownMask = null;
    clearTileCache();
    return fresh;
  }

  private void handleMaskImage(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Path currentMask = maskPath;
    if (currentMask == null || !Files.exists(currentMask)) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    writeBody(exchange, 200, "image/png", Files.readAllBytes(currentMask));
  }

  private void handleUnknownMaskImage(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Path currentMask = maskPath;
    if (currentMask == null || !Files.exists(currentMask)) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    byte[] bytes = getUnknownMaskPng(currentMask);
    if (bytes == null || bytes.length == 0) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    writeBody(exchange, 200, "image/png", bytes);
  }

  private byte[] getUnknownMaskPng(Path currentMask) throws IOException {
    long modified = Files.getLastModifiedTime(currentMask).toMillis();
    CachedUnknownMask cached = cachedUnknownMask;
    if (cached != null && cached.matches(currentMask, modified)) {
      return cached.pngBytes;
    }

    MaskGrid mask = getCachedMaskGrid(currentMask);
    if (mask == null || mask.width <= 0 || mask.height <= 0) {
      return null;
    }

    BufferedImage image = new BufferedImage(mask.width, mask.height, BufferedImage.TYPE_INT_ARGB);
    int unknownArgb = 0xFF000000;
    int knownArgb = 0x00000000;
    for (int y = 0; y < mask.height; y++) {
      int row = y * mask.width;
      for (int x = 0; x < mask.width; x++) {
        image.setRGB(x, y, mask.known.get(row + x) ? knownArgb : unknownArgb);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, mask.width * mask.height / 4));
    ImageIO.write(image, "png", out);
    byte[] bytes = out.toByteArray();
    cachedUnknownMask = new CachedUnknownMask(currentMask, modified, bytes);
    return bytes;
  }

  private void handleStatsJson(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }

    RenderSnapshot s = snapshot;
    long currentGameTimeTicks = gameTimeTicks;
    String dayPhase = currentGameTimeTicks < 0 ? "unknown" : (dayTime ? "day" : "night");
    String json = "{"
        + "\"status\":\"" + jsonEscape(s.status) + "\","
        + "\"reason\":\"" + jsonEscape(s.reason) + "\","
        + "\"message\":\"" + jsonEscape(s.message) + "\","
        + "\"generatedEpochMs\":" + s.generatedEpochMs + ","
        + "\"width\":" + s.width + ","
        + "\"height\":" + s.height + ","
        + "\"chunkCount\":" + s.chunkCount + ","
        + "\"regionFileCount\":" + s.regionFileCount + ","
        + "\"playerDataFileCount\":" + s.playerDataFileCount + ","
        + "\"renderCount\":" + s.renderCount + ","
        + "\"gameTimeTicks\":" + currentGameTimeTicks + ","
        + "\"dayPhase\":\"" + jsonEscape(dayPhase) + "\","
        + "\"unknownFogEnabled\":" + unknownFogEnabled + ","
        // Deprecated stats fields kept for compatibility with legacy dashboards.
        + "\"chunkBoundaryEnabled\":" + chunkBoundaryEnabled + ","
        + "\"chunkPixelSize\":" + chunkPixelSize + ","
        + "\"predictiveStartupEnabled\":" + predictiveStartupEnabled + ","
        + "\"texturePaletteEnabled\":" + texturePaletteEnabled + ","
        + "\"autoUpdateEnabled\":" + autoUpdateEnabled + ","
        + "\"autoUpdateIntervalSeconds\":" + autoUpdateIntervalSeconds + ","
        + "\"tileBusyRejectCount\":" + tileBusyRejectCount.get() + ","
        + "\"mapFile\":\"" + jsonEscape(imagePath == null ? "" : imagePath.getFileName().toString()) + "\","
        + "\"maskFile\":\"" + jsonEscape(maskPath == null ? "" : maskPath.getFileName().toString()) + "\","
        + "\"emptyColor\":\"" + jsonEscape(emptyColorHex) + "\","
        + "\"fogDisabledColor\":\"" + jsonEscape(fogDisabledColorHex) + "\","
        // Deprecated compatibility field. Renderer/frontend no longer uses boundary color.
        + "\"chunkBoundaryColor\":\"" + jsonEscape(chunkBoundaryColorHex) + "\""
        + "}";
    writeBody(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private void handleActionRender(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    boolean force = parseBoolean(q.get("force"), false);
    String message = renderAction == null ? "渲染动作未配置" : renderAction.apply(force);
    String json = "{\"ok\":true,\"message\":\"" + jsonEscape(message) + "\"}";
    writeBody(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private void handleActionAuto(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    Boolean enabled = parseBooleanNullable(q.get("enabled"));
    Long interval = parseLongNullable(q.get("interval"));
    if (enabled == null && interval == null) {
      String json = "{\"ok\":false,\"message\":\"缺少 enabled 或 interval 参数\"}";
      writeBody(exchange, 400, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
      return;
    }
    String message = autoAction == null ? "自动动作未配置" : autoAction.apply(enabled, interval);
    String json = "{\"ok\":true,\"message\":\"" + jsonEscape(message) + "\"}";
    writeBody(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private void handleActionVisual(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    String name = q.get("name");
    Boolean enabled = parseBooleanNullable(q.get("enabled"));
    if (name == null || name.isBlank() || enabled == null) {
      String json = "{\"ok\":false,\"message\":\"缺少 name 或 enabled 参数\"}";
      writeBody(exchange, 400, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
      return;
    }
    String message = visualAction == null ? "视觉动作未配置" : visualAction.apply(name, enabled);
    String json = "{\"ok\":true,\"message\":\"" + jsonEscape(message) + "\"}";
    writeBody(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private void handleActionQuality(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
    Long edgeRaw = parseLongNullable(q.get("edge"));
    if (edgeRaw == null) {
      String json = "{\"ok\":false,\"message\":\"缺少 edge 参数\"}";
      writeBody(exchange, 400, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
      return;
    }
    int edge = (int) Math.max(1L, Math.min(1024L, edgeRaw));
    String message = qualityAction == null ? "画质动作未配置" : qualityAction.apply(edge);
    String json = "{\"ok\":true,\"message\":\"" + jsonEscape(message) + "\"}";
    writeBody(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeBody(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
    headers.set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private String buildTileCacheKey(
      Path image,
      long imageModified,
      int x,
      int y,
      int size,
      int sampleStep,
      String modeTag,
      boolean fog,
      String fogColor,
      int pixelStep,
      long maskModified) {
    return image.toAbsolutePath()
        + "|m=" + imageModified
        + "|x=" + x
        + "|y=" + y
        + "|s=" + size
        + "|sample=" + sampleStep
        + "|mode=" + (modeTag == null ? "fast" : modeTag)
        + "|fog=" + fog
        + "|c=" + fogColor
        + "|step=" + pixelStep
        + "|mask=" + maskModified;
  }

  private byte[] getCachedTile(String key) {
    synchronized (tileCacheLock) {
      return tilePngCache.get(key);
    }
  }

  private void putCachedTile(String key, byte[] pngBytes) {
    synchronized (tileCacheLock) {
      tilePngCache.put(key, pngBytes);
    }
  }

  private void clearTileCache() {
    synchronized (tileCacheLock) {
      tilePngCache.clear();
    }
  }

  private static final class TileRequest {
    private final Path currentImage;
    private final Path precutTile;
    private final TileAccessMode accessMode;
    private final int tileX;
    private final int tileY;
    private final int requestedSize;
    private final int sampleStep;
    private final int mapWidth;
    private final int mapHeight;
    private final String cacheKey;

    private TileRequest(
        Path currentImage,
        Path precutTile,
        TileAccessMode accessMode,
        int tileX,
        int tileY,
        int requestedSize,
        int sampleStep,
        int mapWidth,
        int mapHeight,
        String cacheKey
    ) {
      this.currentImage = currentImage;
      this.precutTile = precutTile;
      this.accessMode = accessMode;
      this.tileX = tileX;
      this.tileY = tileY;
      this.requestedSize = requestedSize;
      this.sampleStep = sampleStep;
      this.mapWidth = mapWidth;
      this.mapHeight = mapHeight;
      this.cacheKey = cacheKey;
    }
  }

  private static final class CachedUnknownMask {
    private final Path path;
    private final long modifiedMs;
    private final byte[] pngBytes;

    private CachedUnknownMask(Path path, long modifiedMs, byte[] pngBytes) {
      this.path = path;
      this.modifiedMs = modifiedMs;
      this.pngBytes = pngBytes;
    }

    private boolean matches(Path currentPath, long currentModifiedMs) {
      return path.equals(currentPath) && modifiedMs == currentModifiedMs;
    }
  }

  private static final class MaskGrid {
    private final Path path;
    private final long modifiedMs;
    private final int width;
    private final int height;
    private final BitSet known;

    private MaskGrid(Path path, long modifiedMs, int width, int height, BitSet known) {
      this.path = path;
      this.modifiedMs = modifiedMs;
      this.width = width;
      this.height = height;
      this.known = known;
    }

    private boolean matches(Path currentPath, long currentModifiedMs) {
      return path.equals(currentPath) && modifiedMs == currentModifiedMs;
    }

    private boolean isKnown(int x, int y) {
      return known.get((y * width) + x);
    }
  }

  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> map = new HashMap<>();
    if (raw == null || raw.isBlank()) {
      return map;
    }
    String[] pairs = raw.split("&");
    for (String pair : pairs) {
      if (pair == null || pair.isBlank()) {
        continue;
      }
      int idx = pair.indexOf('=');
      String key = idx < 0 ? pair : pair.substring(0, idx);
      String value = idx < 0 ? "" : pair.substring(idx + 1);
      map.put(urlDecode(key), urlDecode(value));
    }
    return map;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static boolean parseBoolean(String raw, boolean fallback) {
    Boolean value = parseBooleanNullable(raw);
    return value == null ? fallback : value;
  }

  private static Boolean parseBooleanNullable(String raw) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim().toLowerCase();
    if ("1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v)) {
      return true;
    }
    if ("0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v)) {
      return false;
    }
    return null;
  }

  private static Long parseLongNullable(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String formatGameTimeStopwatch(long ticks) {
    if (ticks < 0L) {
      return "未知";
    }
    long totalSeconds = Math.max(0L, ticks / 20L);
    long days = totalSeconds / 86_400L;
    long hours = (totalSeconds % 86_400L) / 3_600L;
    long minutes = (totalSeconds % 3_600L) / 60L;
    long seconds = totalSeconds % 60L;
    String time = String.format("%02d:%02d:%02d", hours, minutes, seconds);
    return days > 0L ? (days + "天 " + time) : time;
  }

  private static String escape(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String jsonEscape(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

  private static String safeHex(String raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    String v = raw.trim();
    if (v.matches("^#[0-9a-fA-F]{6}$")) {
      return v;
    }
    return fallback;
  }

  private static int parseHexRgb(String raw) {
    String hex = safeHex(raw, "#000000");
    int r = Integer.parseInt(hex.substring(1, 3), 16);
    int g = Integer.parseInt(hex.substring(3, 5), 16);
    int b = Integer.parseInt(hex.substring(5, 7), 16);
    return (0xFF << 24) | (r << 16) | (g << 8) | b;
  }
}




