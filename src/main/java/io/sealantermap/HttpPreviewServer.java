package io.sealantermap;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

final class HttpPreviewServer {
    private final String host;
    private final int port;
    private final Path imagePath;
    private final Function<Boolean, String> renderAction;
    private final BiFunction<Boolean, Long, String> autoAction;

    private volatile RenderSnapshot snapshot = RenderSnapshot.EMPTY;
    private volatile boolean unknownFogEnabled = true;
    private volatile boolean chunkBoundaryEnabled = false;
    private volatile String emptyColorHex = "#111827";
    private volatile String fogDisabledColorHex = "#dbeafe";
    private volatile String chunkBoundaryColorHex = "#1f2937";
    private volatile int chunkPixelSize = 1;
    private volatile boolean predictiveStartupEnabled = true;
    private volatile boolean texturePaletteEnabled = true;
    private volatile boolean autoUpdateEnabled = true;
    private volatile long autoUpdateIntervalSeconds = 10;

    private HttpServer server;
    private ExecutorService executor;

    HttpPreviewServer(
            String host,
            int port,
            Path imagePath,
            Function<Boolean, String> renderAction,
            BiFunction<Boolean, Long, String> autoAction
    ) {
        this.host = host;
        this.port = port;
        this.imagePath = imagePath;
        this.renderAction = renderAction;
        this.autoAction = autoAction;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sealantermap-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handleIndex);
        server.createContext("/map.png", this::handleMapImage);
        server.createContext("/stats.json", this::handleStatsJson);
        server.createContext("/action/render", this::handleActionRender);
        server.createContext("/action/auto", this::handleActionAuto);
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
            boolean texturePaletteEnabled
    ) {
        this.unknownFogEnabled = unknownFogEnabled;
        this.chunkBoundaryEnabled = chunkBoundaryEnabled;
        this.emptyColorHex = safeHex(emptyColorHex, "#111827");
        this.fogDisabledColorHex = safeHex(fogDisabledColorHex, "#dbeafe");
        this.chunkBoundaryColorHex = safeHex(chunkBoundaryColorHex, "#1f2937");
        this.chunkPixelSize = Math.max(1, chunkPixelSize);
        this.predictiveStartupEnabled = predictiveStartupEnabled;
        this.texturePaletteEnabled = texturePaletteEnabled;
    }

    void updateAutoRuntime(boolean autoUpdateEnabled, long autoUpdateIntervalSeconds) {
        this.autoUpdateEnabled = autoUpdateEnabled;
        this.autoUpdateIntervalSeconds = Math.max(2L, autoUpdateIntervalSeconds);
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        RenderSnapshot s = snapshot;
        String generatedText = s.generatedEpochMs > 0 ? Instant.ofEpochMilli(s.generatedEpochMs).toString() : "not yet";
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8"/>
                  <meta name="viewport" content="width=device-width,initial-scale=1"/>
                  <title>Sealantermap</title>
                  <style>
                    :root{
                      --bg:#0b1220; --card:#111827; --text:#e5e7eb; --muted:#9ca3af;
                      --line:#1f2937; --primary:#38bdf8; --ok:#22c55e; --warn:#f59e0b;
                    }
                    *{box-sizing:border-box}
                    body{margin:0;background:radial-gradient(1200px 700px at 20%% -10%%,#14213d 0%%,var(--bg) 45%%);color:var(--text);font-family:Consolas,Menlo,monospace}
                    .wrap{max-width:1200px;margin:0 auto;padding:12px}
                    .top{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;margin-bottom:10px}
                    .card{background:rgba(17,24,39,.92);border:1px solid var(--line);border-radius:10px;padding:10px}
                    .title{font-size:13px;color:var(--muted);margin:0 0 6px 0}
                    .value{font-size:18px;font-weight:700;margin:0}
                    .toolbar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-bottom:10px}
                    .ctrl{display:flex;align-items:center;gap:6px;background:var(--card);border:1px solid var(--line);border-radius:8px;padding:6px 8px;color:var(--muted);font-size:13px}
                    .ctrl input[type="range"]{width:120px}
                    .ctrl input[type="number"]{width:70px}
                    .btn{cursor:pointer;background:#1d4ed8;color:#fff;border:none;border-radius:6px;padding:4px 8px;font-size:12px}
                    .btn:hover{background:#1e40af}
                    .btn.secondary{background:#374151}
                    .btn.secondary:hover{background:#4b5563}
                    .btn:disabled{opacity:0.6;cursor:not-allowed}
                    .badge{display:inline-block;padding:2px 8px;border-radius:99px;border:1px solid var(--line);font-size:12px}
                    .viewer{position:relative;overflow:hidden;background:#030712;border:1px solid var(--line);border-radius:12px;height:72vh;min-height:380px}
                    .stage{position:absolute;left:0;top:0;transform-origin:0 0;will-change:transform}
                    .map{display:block;image-rendering:pixelated;image-rendering:crisp-edges}
                    .overlay{position:absolute;left:0;top:0;pointer-events:none}
                    .legend{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}
                    .sw{width:14px;height:14px;border-radius:3px;border:1px solid #0008;display:inline-block;vertical-align:middle;margin-right:5px}
                    .meta{margin-top:6px;font-size:12px;color:var(--muted)}
                    .links a{color:#93c5fd;text-decoration:none}
                    .ok{color:var(--ok)}
                    .warn{color:var(--warn)}
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="top">
                    <div class="card"><p class="title">状态</p><p class="value" id="status">%s</p></div>
                    <div class="card"><p class="title">图片大小</p><p class="value"><span id="wh">%dx%d</span></p></div>
                    <div class="card"><p class="title">已落盘区块</p><p class="value" id="chunks">%d</p></div>
                    <div class="card"><p class="title">渲染次数</p><p class="value" id="rc">%d</p></div>
                  </div>

                  <div class="toolbar">
                    <div class="ctrl">缩放 <button class="btn" id="zout">-</button><input id="zoom" type="range" min="1" max="16" value="4"/><button class="btn" id="zin">+</button><span id="zv">4x</span></div>
                    <div class="ctrl"><label><input type="checkbox" id="autoref" checked/> 页面自动刷新</label><input id="sec" type="number" min="2" max="60" value="4"/> 秒</div>
                    <div class="ctrl"><label><input type="checkbox" id="grid"/> 显示区块网格</label></div>
                    <div class="ctrl"><label><input type="checkbox" id="smooth"/> 平滑缩放</label></div>
                  </div>

                  <div class="toolbar">
                    <div class="ctrl"><button class="btn secondary" id="renderInc">增量渲染</button><button class="btn" id="renderFull">全量渲染</button></div>
                    <div class="ctrl"><span class="warn">性能警告: 全量渲染会重建底图并长时间占用主线程，建议低峰时段执行。</span></div>
                    <div class="ctrl"><label><input type="checkbox" id="srvAuto" %s/> 自动渲染</label></div>
                    <div class="ctrl">间隔 <input id="srvSec" type="number" min="2" max="3600" value="%d"/> 秒 <button class="btn secondary" id="saveAuto">应用</button></div>
                    <div class="ctrl"><span id="actionMsg" class="ok">ready</span></div>
                  </div>

                  <div class="toolbar">
                    <div class="ctrl"><span class="badge" id="fog"></span></div>
                    <div class="ctrl"><span class="badge" id="bd"></span></div>
                    <div class="ctrl"><span class="badge" id="pred"></span></div>
                    <div class="ctrl"><span class="badge" id="tex"></span></div>
                  </div>

                  <div id="viewer" class="viewer">
                    <div id="stage" class="stage">
                      <img id="map" class="map" src="/map.png?t=%d" alt="world overview"/>
                      <canvas id="gridLayer" class="overlay"></canvas>
                    </div>
                  </div>

                  <p class="meta">generated: <span id="gen">%s</span> | reason: <span id="reason">%s</span> | message: <span id="msg">%s</span></p>
                  <p class="meta links"><a href="/map.png">/map.png</a> | <a href="/stats.json">/stats.json</a></p>
                  <div class="legend">
                    <span class="badge"><i class="sw" style="background:%s"></i>未知/未落盘</span>
                    <span class="badge"><i class="sw" style="background:%s"></i>未知(关闭迷雾时)</span>
                    <span class="badge"><i class="sw" style="background:%s"></i>区块边界</span>
                  </div>
                </div>
                <script>
                (function(){
                  const cfg = {
                    unknownFogEnabled: %s,
                    chunkBoundaryEnabled: %s,
                    chunkPixelSize: %d,
                    predictiveStartupEnabled: %s,
                    texturePaletteEnabled: %s
                  };
                  const img = document.getElementById('map');
                  const viewer = document.getElementById('viewer');
                  const stage = document.getElementById('stage');
                  const grid = document.getElementById('gridLayer');
                  const ctx = grid.getContext('2d');
                  const zoomEl = document.getElementById('zoom');
                  const zv = document.getElementById('zv');
                  const autoRef = document.getElementById('autoref');
                  const secEl = document.getElementById('sec');
                  const gridEl = document.getElementById('grid');
                  const smoothEl = document.getElementById('smooth');
                  const srvAuto = document.getElementById('srvAuto');
                  const srvSec = document.getElementById('srvSec');
                  const actionMsg = document.getElementById('actionMsg');
                  const btnRenderInc = document.getElementById('renderInc');
                  const btnRenderFull = document.getElementById('renderFull');
                  const btnSaveAuto = document.getElementById('saveAuto');

                  document.getElementById('fog').textContent = '未知迷雾: ' + (cfg.unknownFogEnabled ? '开启' : '关闭');
                  document.getElementById('bd').textContent = '区块边界: ' + (cfg.chunkBoundaryEnabled ? '开启' : '关闭');
                  document.getElementById('pred').textContent = '首图预测: ' + (cfg.predictiveStartupEnabled ? '开启' : '关闭');
                  document.getElementById('tex').textContent = '纹理取色: ' + (cfg.texturePaletteEnabled ? '开启' : '关闭');

                  let zoom = Number(zoomEl.value);
                  let tx = 10, ty = 10;
                  let dragging = false, px = 0, py = 0;
                  let timer = null;

                  function setMsg(text, ok=true){
                    actionMsg.textContent = text;
                    actionMsg.className = ok ? 'ok' : 'warn';
                  }

                  function apply(){
                    stage.style.transform = `translate(${tx}px,${ty}px) scale(${zoom})`;
                    zv.textContent = zoom.toFixed(1) + 'x';
                    drawGrid();
                  }

                  function drawGrid(){
                    grid.width = img.naturalWidth || 0;
                    grid.height = img.naturalHeight || 0;
                    ctx.clearRect(0,0,grid.width,grid.height);
                    if(!gridEl.checked || !grid.width || !grid.height){ return; }
                    const step = Math.max(1, cfg.chunkPixelSize);
                    ctx.strokeStyle = 'rgba(17,24,39,0.55)';
                    ctx.lineWidth = 1;
                    for(let x=0;x<=grid.width;x+=step){ ctx.beginPath(); ctx.moveTo(x+0.5,0); ctx.lineTo(x+0.5,grid.height); ctx.stroke(); }
                    for(let y=0;y<=grid.height;y+=step){ ctx.beginPath(); ctx.moveTo(0,y+0.5); ctx.lineTo(grid.width,y+0.5); ctx.stroke(); }
                  }

                  function setSmooth(){ img.style.imageRendering = smoothEl.checked ? 'auto' : 'pixelated'; }

                  async function callJson(url){
                    const r = await fetch(url, {cache:'no-store'});
                    if(!r.ok){ throw new Error('HTTP ' + r.status); }
                    return await r.json();
                  }

                  async function withBusy(button, action){
                    if(!button){ return; }
                    const oldText = button.textContent;
                    button.disabled = true;
                    button.textContent = '处理中...';
                    try{
                      await action();
                    } finally {
                      button.disabled = false;
                      button.textContent = oldText;
                    }
                  }

                  async function requestRender(force){
                    const data = await callJson('/action/render?force=' + (force ? '1' : '0') + '&t=' + Date.now());
                    setMsg(data.message || 'render requested', true);
                  }

                  async function applyServerAuto(){
                    const sec = Math.max(2, Math.min(3600, Number(srvSec.value) || 10));
                    srvSec.value = sec;
                    const data = await callJson('/action/auto?enabled=' + (srvAuto.checked ? '1' : '0') + '&interval=' + sec + '&t=' + Date.now());
                    setMsg(data.message || 'auto updated', true);
                  }

                  zoomEl.addEventListener('input', ()=>{ zoom = Number(zoomEl.value); apply(); });
                  document.getElementById('zin').addEventListener('click', ()=>{ zoom = Math.min(16, zoom + 0.5); zoomEl.value = zoom; apply(); });
                  document.getElementById('zout').addEventListener('click', ()=>{ zoom = Math.max(1, zoom - 0.5); zoomEl.value = zoom; apply(); });

                  btnRenderInc.addEventListener('click', async ()=>{
                    setMsg('已发送增量渲染请求...', true);
                    try { await withBusy(btnRenderInc, ()=>requestRender(false)); }
                    catch(e){ setMsg('增量渲染请求失败: ' + e.message, false); }
                  });

                  btnRenderFull.addEventListener('click', async ()=>{
                    const confirmed = confirm(`性能警告:
全量渲染会重建底图，并长时间占用服务器主线程，可能触发 Paper Watchdog 报警。
建议低峰时段执行，平时优先增量渲染。

确定继续执行全量渲染吗？`);
                    if(!confirmed){
                      setMsg('已取消全量渲染请求', false);
                      return;
                    }
                    setMsg('已发送全量渲染请求...', false);
                    try { await withBusy(btnRenderFull, ()=>requestRender(true)); }
                    catch(e){ setMsg('全量渲染请求失败: ' + e.message, false); }
                  });

                  btnSaveAuto.addEventListener('click', async ()=>{
                    setMsg('正在更新自动渲染配置...', true);
                    try { await withBusy(btnSaveAuto, applyServerAuto); }
                    catch(e){ setMsg('自动渲染配置失败: ' + e.message, false); }
                  });

                  srvAuto.addEventListener('change', async ()=>{
                    setMsg('正在更新自动渲染开关...', true);
                    try { await applyServerAuto(); }
                    catch(e){ setMsg('自动渲染开关失败: ' + e.message, false); }
                  });

                  gridEl.addEventListener('change', drawGrid);
                  smoothEl.addEventListener('change', setSmooth);
                  img.addEventListener('load', drawGrid);

                  viewer.addEventListener('wheel', (e)=>{
                    e.preventDefault();
                    const delta = e.deltaY > 0 ? -0.5 : 0.5;
                    zoom = Math.max(1, Math.min(16, zoom + delta));
                    zoomEl.value = zoom;
                    apply();
                  }, {passive:false});

                  viewer.addEventListener('pointerdown', (e)=>{ dragging=true; px=e.clientX; py=e.clientY; viewer.setPointerCapture(e.pointerId); });
                  viewer.addEventListener('pointermove', (e)=>{
                    if(!dragging){ return; }
                    tx += (e.clientX - px);
                    ty += (e.clientY - py);
                    px = e.clientX; py = e.clientY;
                    apply();
                  });
                  viewer.addEventListener('pointerup', ()=>{ dragging=false; });
                  viewer.addEventListener('pointercancel', ()=>{ dragging=false; });

                  async function refreshStats(){
                    try{
                      const s = await callJson('/stats.json?t=' + Date.now());
                      document.getElementById('status').textContent = s.status;
                      document.getElementById('wh').textContent = `${s.width}x${s.height}`;
                      document.getElementById('chunks').textContent = s.chunkCount;
                      document.getElementById('rc').textContent = s.renderCount;
                      document.getElementById('gen').textContent = s.generatedEpochMs > 0 ? new Date(s.generatedEpochMs).toISOString() : 'not yet';
                      document.getElementById('reason').textContent = s.reason || '';
                      document.getElementById('msg').textContent = s.message || '';
                      srvAuto.checked = !!s.autoUpdateEnabled;
                      srvSec.value = s.autoUpdateIntervalSeconds || 10;
                    }catch(_){}
                  }

                  function refreshMap(){ img.src = '/map.png?t=' + Date.now(); }

                  function reschedule(){
                    if(timer){ clearInterval(timer); timer = null; }
                    if(!autoRef.checked){ return; }
                    const sec = Math.max(2, Math.min(60, Number(secEl.value) || 4));
                    secEl.value = sec;
                    timer = setInterval(()=>{ refreshMap(); refreshStats(); }, sec * 1000);
                  }

                  autoRef.addEventListener('change', reschedule);
                  secEl.addEventListener('change', reschedule);

                  setSmooth();
                  apply();
                  refreshStats();
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
                        autoUpdateEnabled ? "checked" : "",
                        autoUpdateIntervalSeconds,
                        System.currentTimeMillis(),
                        escape(generatedText),
                        escape(s.reason),
                        escape(s.message),
                        emptyColorHex,
                        fogDisabledColorHex,
                        chunkBoundaryColorHex,
                        unknownFogEnabled ? "true" : "false",
                        chunkBoundaryEnabled ? "true" : "false",
                        chunkPixelSize,
                        predictiveStartupEnabled ? "true" : "false",
                        texturePaletteEnabled ? "true" : "false"
                );

        writeBody(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void handleMapImage(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (!Files.exists(imagePath)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        writeBody(exchange, 200, "image/png", Files.readAllBytes(imagePath));
    }

    private void handleStatsJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        RenderSnapshot s = snapshot;
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
                + "\"unknownFogEnabled\":" + unknownFogEnabled + ","
                + "\"chunkBoundaryEnabled\":" + chunkBoundaryEnabled + ","
                + "\"chunkPixelSize\":" + chunkPixelSize + ","
                + "\"predictiveStartupEnabled\":" + predictiveStartupEnabled + ","
                + "\"texturePaletteEnabled\":" + texturePaletteEnabled + ","
                + "\"autoUpdateEnabled\":" + autoUpdateEnabled + ","
                + "\"autoUpdateIntervalSeconds\":" + autoUpdateIntervalSeconds + ","
                + "\"emptyColor\":\"" + jsonEscape(emptyColorHex) + "\","
                + "\"fogDisabledColor\":\"" + jsonEscape(fogDisabledColorHex) + "\","
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
        String message = renderAction == null ? "render action not configured" : renderAction.apply(force);
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
            String json = "{\"ok\":false,\"message\":\"missing enabled/interval\"}";
            writeBody(exchange, 400, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String message = autoAction == null ? "auto action not configured" : autoAction.apply(enabled, interval);
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
}
