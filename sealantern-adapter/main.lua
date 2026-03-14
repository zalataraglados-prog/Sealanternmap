-- Sealantermap Bridge (SeaLantern plugin)

local ROOT_ID = "sealantermap-bridge-root"
local STYLE_ID = "sealantermap-bridge-style"

local PREVIEW_URL = "http://127.0.0.1:8156/"
local PAYLOAD_RELATIVE_PATH = "payload/sealantermap-0.1.0.jar"
local PAYLOAD_FILE_NAME = "sealantermap-0.1.0.jar"

local function js_escape(value)
  local s = tostring(value or "")
  s = s:gsub("\\", "\\\\")
  s = s:gsub("\"", "\\\"")
  s = s:gsub("\r", "\\r")
  s = s:gsub("\n", "\\n")
  s = s:gsub("</", "<\\/")
  return s
end

local function fill_template(template, vars)
  local out = template
  for key, value in pairs(vars) do
    out = out:gsub("__" .. key .. "__", function()
      return value
    end)
  end
  return out
end

local function collect_servers_js()
  local ok, list = pcall(function()
    return sl.server.list()
  end)
  if not ok or type(list) ~= "table" then
    return "[]", 0
  end

  local rows = {}
  local count = 0
  for _, server in ipairs(list) do
    if server and server.id then
      local id = js_escape(server.id)
      local name = js_escape(server.name or server.id)
      rows[#rows + 1] = string.format("{id:\"%s\",name:\"%s\"}", id, name)
      count = count + 1
    end
  end

  return "[" .. table.concat(rows, ",") .. "]", count
end

local function load_payload_base64()
  if not sl.fs.exists(PAYLOAD_RELATIVE_PATH) then
    return nil, "payload missing: " .. PAYLOAD_RELATIVE_PATH
  end

  local ok, payload = pcall(function()
    return sl.fs.read_binary(PAYLOAD_RELATIVE_PATH)
  end)

  if not ok or type(payload) ~= "string" or payload == "" then
    return nil, "failed to read payload"
  end

  return payload, nil
end

local function build_css()
  return [[
#slm-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 9100;
  border: 1px solid var(--sl-primary-dark, #0369a1);
  background: var(--sl-primary, #0ea5e9);
  color: #fff;
  border-radius: 10px;
  padding: 10px 14px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}
#slm-modal {
  position: fixed;
  inset: 0;
  z-index: 99999;
  display: none;
  align-items: center;
  justify-content: center;
  background: rgba(2,6,23,.62);
  padding: 24px;
}
#slm-card {
  width: 100%;
  height: 100%;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(148,163,184,.25);
  background: #0b1220;
  display: flex;
  flex-direction: column;
}
#slm-head {
  height: 52px;
  background: #111827;
  border-bottom: 1px solid rgba(148,163,184,.2);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
}
#slm-title { color: #e2e8f0; font-weight: 700; }
#slm-tools { display:flex; align-items:center; gap:8px; }
#slm-tools select,
#slm-tools button,
#slm-tools a {
  height: 30px;
  border-radius: 6px;
  border: 1px solid rgba(148,163,184,.3);
  background: rgba(30,41,59,.95);
  color: #e2e8f0;
  padding: 0 10px;
  font-size: 12px;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
}
#slm-status { max-width: 220px; color:#94a3b8; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
#slm-status.err { color:#fca5a5; }
#slm-close {
  width: 30px; height: 30px;
  border: none; border-radius: 6px;
  background: rgba(255,255,255,.1); color:#e2e8f0; cursor:pointer;
}
#slm-body { flex:1; position:relative; background:#000; }
#slm-frame { width:100%; height:100%; border:none; background:#fff; }
#slm-mask {
  position:absolute; inset:0;
  display:flex; align-items:center; justify-content:center;
  text-align:center; padding:24px; color:#cbd5e1;
  background: rgba(2,6,23,.65);
}
]]
end

local function build_html(servers_js, payload_b64, payload_error)
  local template = [[
<div id="slm-root">
  <button id="slm-fab" title="Open map">Map</button>
  <div id="slm-modal">
    <div id="slm-card">
      <div id="slm-head">
        <div id="slm-title">Sealantermap Preview</div>
        <div id="slm-tools">
          <select id="slm-server"></select>
          <button id="slm-check">Check Core</button>
          <button id="slm-install">Install Core</button>
          <a id="slm-open" href="__PREVIEW_URL__" target="_blank" rel="noopener noreferrer">Open Browser</a>
          <span id="slm-status" title="__PAYLOAD_ERROR__"></span>
        </div>
        <button id="slm-close" title="Close">x</button>
      </div>
      <div id="slm-body">
        <iframe id="slm-frame" src="about:blank"></iframe>
        <div id="slm-mask">Click Map to load preview.</div>
      </div>
    </div>
  </div>
</div>
<script>
(function(){
  if (window.__SLM_BRIDGE_MOUNTED__) return;
  window.__SLM_BRIDGE_MOUNTED__ = true;

  const PREVIEW_URL = "__PREVIEW_URL__";
  const SERVERS = __SERVERS__;
  const PAYLOAD_BASE64 = "__PAYLOAD_BASE64__";
  const PAYLOAD_ERROR = "__PAYLOAD_ERROR__";
  const JAR_FILE_NAME = "__JAR_FILE_NAME__";

  const fab = document.getElementById("slm-fab");
  const modal = document.getElementById("slm-modal");
  const closeBtn = document.getElementById("slm-close");
  const frame = document.getElementById("slm-frame");
  const mask = document.getElementById("slm-mask");
  const serverSel = document.getElementById("slm-server");
  const checkBtn = document.getElementById("slm-check");
  const installBtn = document.getElementById("slm-install");
  const statusEl = document.getElementById("slm-status");

  let watchdog = 0;
  let payloadBytes = null;

  function setStatus(text, isError){
    statusEl.textContent = text || "";
    statusEl.classList.toggle("err", !!isError);
  }
  function showMask(text){
    mask.textContent = text || "";
    mask.style.display = "flex";
  }
  function hideMask(){
    mask.style.display = "none";
  }

  function getInvoke(){
    if (window.__TAURI__ && window.__TAURI__.core && typeof window.__TAURI__.core.invoke === "function") return window.__TAURI__.core.invoke;
    if (window.__TAURI_INTERNALS__ && typeof window.__TAURI_INTERNALS__.invoke === "function") return window.__TAURI_INTERNALS__.invoke;
    return null;
  }
  const invoke = getInvoke();

  function decodeBase64(b64){
    const bin = atob(b64);
    const out = new Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function getPayloadBytes(){
    if (!payloadBytes) payloadBytes = decodeBase64(PAYLOAD_BASE64);
    return payloadBytes;
  }

  function selectedServerId(){
    return serverSel.value || "";
  }

  function fillServerSelect(){
    serverSel.innerHTML = "";
    for (const s of SERVERS){
      const opt = document.createElement("option");
      opt.value = s.id;
      opt.textContent = s.name + " (" + s.id + ")";
      serverSel.appendChild(opt);
    }
    if (!SERVERS.length){
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No server detected";
      serverSel.appendChild(opt);
      serverSel.disabled = true;
      checkBtn.disabled = true;
      installBtn.disabled = true;
    }
  }

  async function checkInstallState(){
    if (!invoke) return setStatus("Tauri invoke unavailable", true);
    const sid = selectedServerId();
    if (!sid) return setStatus("Please select a server", true);
    try {
      setStatus("Checking...", false);
      const list = await invoke("m_get_plugins", { serverId: sid });
      const found = Array.isArray(list) && list.some((p)=>{
        const fileName = String((p && p.file_name) || "").toLowerCase();
        const pluginName = String((p && p.name) || "").toLowerCase();
        return fileName === JAR_FILE_NAME.toLowerCase() || pluginName.includes("sealantermap");
      });
      setStatus(found ? "Core installed" : "Core not installed", false);
    } catch (_e){
      setStatus("Check failed", true);
    }
  }

  async function installCore(){
    if (!invoke) return setStatus("Tauri invoke unavailable", true);
    const sid = selectedServerId();
    if (!sid) return setStatus("Please select a server", true);
    if (!PAYLOAD_BASE64) return setStatus("Payload unavailable", true);
    checkBtn.disabled = true;
    installBtn.disabled = true;
    try {
      setStatus("Installing...", false);
      await invoke("m_install_plugin", {
        serverId: sid,
        fileData: getPayloadBytes(),
        fileName: JAR_FILE_NAME
      });
      setStatus("Installed. Restart that MC server.", false);
      await checkInstallState();
    } catch (_e){
      setStatus("Install failed", true);
    } finally {
      checkBtn.disabled = false;
      installBtn.disabled = false;
    }
  }

  function clearWatchdog(){
    if (watchdog){
      clearTimeout(watchdog);
      watchdog = 0;
    }
  }
  function armWatchdog(){
    clearWatchdog();
    watchdog = setTimeout(function(){
      showMask("Map backend not reachable. Start MC instance with Sealantermap core.");
    }, 8000);
  }

  function openModal(){
    modal.style.display = "flex";
    showMask("Connecting...");
    armWatchdog();
    const join = PREVIEW_URL.indexOf("?") >= 0 ? "&" : "?";
    frame.src = PREVIEW_URL + join + "t=" + Date.now();
  }
  function closeModal(){
    modal.style.display = "none";
    clearWatchdog();
    frame.src = "about:blank";
    showMask("Click Map to load preview.");
  }

  async function preflight(){
    try {
      const res = await fetch(PREVIEW_URL + "stats.json?t=" + Date.now(), { cache: "no-store" });
      setStatus(res.ok ? "Map backend online" : ("Backend HTTP " + res.status), !res.ok);
    } catch (_e){
      setStatus("Map backend offline", true);
    }
  }

  frame.addEventListener("load", function(){ clearWatchdog(); hideMask(); });
  frame.addEventListener("error", function(){ clearWatchdog(); showMask("Map iframe load failed."); });

  fab.addEventListener("click", openModal);
  closeBtn.addEventListener("click", closeModal);
  checkBtn.addEventListener("click", checkInstallState);
  installBtn.addEventListener("click", installCore);

  window.addEventListener("keydown", function(e){
    if (e.key === "Escape" && modal.style.display !== "none") {
      closeModal();
    }
  });

  fillServerSelect();
  if (PAYLOAD_BASE64) setStatus("Ready", false);
  else setStatus(PAYLOAD_ERROR || "Payload unavailable", true);
  preflight();
})();
</script>
]]

  return fill_template(template, {
    PREVIEW_URL = js_escape(PREVIEW_URL),
    SERVERS = servers_js,
    PAYLOAD_BASE64 = js_escape(payload_b64 or ""),
    PAYLOAD_ERROR = js_escape(payload_error or ""),
    JAR_FILE_NAME = js_escape(PAYLOAD_FILE_NAME)
  })
end

local function mount_ui()
  local servers_js, server_count = collect_servers_js()
  local payload_b64, payload_error = load_payload_base64()
  sl.ui.inject_css(STYLE_ID, build_css())
  sl.ui.inject_html(ROOT_ID, build_html(servers_js, payload_b64, payload_error))
  sl.log.info("[SealantermapBridge] UI mounted, servers=" .. tostring(server_count))
  if payload_error then
    sl.log.warn("[SealantermapBridge] payload error: " .. payload_error)
  end
end

local function unmount_ui()
  sl.ui.remove_html(ROOT_ID)
  sl.ui.remove_css(STYLE_ID)
end

function onLoad()
  sl.log.info("[SealantermapBridge] loaded")
end

function onEnable()
  mount_ui()
  sl.log.info("[SealantermapBridge] enabled")
end

function onDisable()
  unmount_ui()
  sl.log.info("[SealantermapBridge] disabled")
end

function onUnload()
  unmount_ui()
  sl.log.info("[SealantermapBridge] unloaded")
end