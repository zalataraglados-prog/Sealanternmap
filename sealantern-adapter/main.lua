-- Sealantermap Bridge (SeaLantern plugin)
-- Single package, dual responsibility:
-- 1) Install/update Sealantermap game-side jar into selected server plugins folder.
-- 2) Provide an in-app map preview panel in SeaLantern UI.

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
/* Modal Overlay */
#slm-large-modal {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  z-index: 99999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
.slm-modal-content {
  width: 100%;
  height: 100%;
  background: var(--sl-glass-bg, rgba(15, 23, 42, 0.95));
  border: 1px solid var(--sl-glass-border, rgba(255, 255, 255, 0.1));
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 24px 48px rgba(0,0,0,0.5);
}
.slm-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
.slm-modal-title {
  font-size: 16px;
  font-weight: bold;
  color: #e2e8f0;
  display: flex;
  align-items: center;
  gap: 12px;
}
.slm-modal-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.slm-modal-actions label {
  color: #e2e8f0;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}
#slm-modal-close {
  background: rgba(255,255,255,0.1);
  border: none;
  border-radius: 6px;
  width: 28px; height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #e2e8f0;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}
#slm-modal-close:hover {
  background: rgba(239, 68, 68, 0.8);
  color: white;
}
.slm-modal-body {
  flex: 1;
  background: #000;
  position: relative;
}
.slm-modal-body iframe {
  width: 100%;
  height: 100%;
  border: none;
}
/* Background Injection for Server Card */
.slm-card-bg {
  position: absolute;
  top: -20px; left: -20px; right: -20px; bottom: -20px; /* Hide inner edges/white borders of leaflet */
  z-index: 0;
  opacity: 0.35;
  filter: blur(2px) saturate(1.2) brightness(0.9);
  pointer-events: none;
  mask-image: linear-gradient(to bottom, rgba(0,0,0,1) 30%, rgba(0,0,0,0) 100%);
  -webkit-mask-image: linear-gradient(to bottom, rgba(0,0,0,1) 30%, rgba(0,0,0,0) 100%);
}
.server-card > *:not(.slm-card-bg) {
  z-index: 1;
  position: relative;
}
/* Game Clock in Modal Header */
.slm-modal-clock {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(30, 41, 59, 0.4);
  border: 1px solid rgba(125, 211, 252, 0.1);
  border-radius: 6px;
  padding: 4px 10px;
  font-family: inherit;
  font-size: 13px;
  transition: all 0.3s ease;
}
.slm-modal-clock.day {
  color: #fde047;
  border-color: rgba(253, 224, 71, 0.3);
}
.slm-modal-clock.night {
  color: #93c5fd;
  border-color: rgba(147, 197, 253, 0.3);
}
/* Install Area inside Modal for core jarring */
.slm-tools {
  display: flex;
  gap: 8px;
  align-items: center;
}
.slm-tools select,
.slm-tools button {
  height: 28px;
  border-radius: 6px;
  border: 1px solid rgba(148,163,184,0.35);
  background: rgba(30,41,59,0.95);
  color: #e2e8f0;
  padding: 0 10px;
  font-size: 12px;
}
.slm-tools button { cursor: pointer; }
.slm-tools button:hover { background: rgba(51,65,85,0.95); }
.slm-status { font-size: 12px; color: #94a3b8; max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.slm-status.err { color: #fca5a5; }
]]
end

local function build_html(servers_js, payload_b64, payload_error)
  local template = [[
<div id="slm-bridge" style="display: none;"></div>
<script>
(function() {
  const PREVIEW_URL = "__PREVIEW_URL__";
  const SERVERS = __SERVERS__;
  const PAYLOAD_BASE64 = "__PAYLOAD_BASE64__";
  const PAYLOAD_ERROR = "__PAYLOAD_ERROR__";
  const JAR_FILE_NAME = "__JAR_FILE_NAME__";

  // Prevent multiple injections
  if (document.getElementById("slm-large-modal")) return;

  const modalHTML = `
    <div id="slm-large-modal" style="display: none;">
      <div class="slm-modal-content glass-strong">
        <div class="slm-modal-header">
           <div class="slm-modal-title">
             🗺️ Sealantermap 
             <div id="slm-modal-clock" class="slm-modal-clock" style="display:none;">
               <span class="slm-ci">☀️</span><span class="slm-ct">--:--</span>
             </div>
             
             <!-- Core Installer Settings included here -->
             <div class="slm-tools" style="margin-left: 12px;">
                 <select id="slm-server"></select>
                 <button id="slm-check">Check</button>
                 <button id="slm-install">Install Core</button>
                 <span id="slm-status" class="slm-status" title="__PAYLOAD_ERROR__"></span>
             </div>
           </div>
           
           <div class="slm-modal-actions">
              <button id="slm-reload" title="刷新地图" style="background:transparent;border:none;color:#e2e8f0;cursor:pointer;font-size:16px;">🔄</button>
              <label title="将首页地图背景替换为无白边的地图截图"><input type="checkbox" id="slm-bg-toggle"> 启用首页地图背景</label>
              <button id="slm-modal-close" title="关闭弹窗">✕</button>
           </div>
        </div>
        <div class="slm-modal-body">
           <iframe id="slm-modal-frame" src="about:blank"></iframe>
        </div>
      </div>
    </div>
  `;
  document.body.insertAdjacentHTML('beforeend', modalHTML);

  const modal = document.getElementById("slm-large-modal");
  const closeBtn = document.getElementById("slm-modal-close");
  const btnReload = document.getElementById("slm-reload");
  const bgToggle = document.getElementById("slm-bg-toggle");
  const mFrame = document.getElementById("slm-modal-frame");
  
  const mClock = document.getElementById("slm-modal-clock");
  const mClockIcon = mClock.querySelector(".slm-ci");
  const mClockTime = mClock.querySelector(".slm-ct");
  
  const btnCheck = document.getElementById("slm-check");
  const btnInstall = document.getElementById("slm-install");
  const selectServer = document.getElementById("slm-server");
  const statusEl = document.getElementById("slm-status");
  
  function setStatus(text, isError) {
    statusEl.textContent = text || "";
    statusEl.classList.toggle("err", !!isError);
  }

  function getInvoke() {
    if (window.__TAURI__ && window.__TAURI__.core && typeof window.__TAURI__.core.invoke === "function") {
      return window.__TAURI__.core.invoke;
    }
    if (window.__TAURI_INTERNALS__ && typeof window.__TAURI_INTERNALS__.invoke === "function") {
      return window.__TAURI_INTERNALS__.invoke;
    }
    return null;
  }

  const invoke = getInvoke();
  if (!invoke) setStatus("Tauri invoke unavailable", true);

  function decodeBase64(base64) {
    const bin = atob(base64);
    const out = new Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
        out[i] = bin.charCodeAt(i);
    }
    return out;
  }

  let payloadBytes = null;
  function getPayloadBytes() {
    if (payloadBytes) return payloadBytes;
    payloadBytes = decodeBase64(PAYLOAD_BASE64);
    return payloadBytes;
  }

  function selectedServerId() {
    return selectServer.value || "";
  }

  function fillServerSelect() {
    selectServer.innerHTML = "";
    for (const s of SERVERS) {
      const opt = document.createElement("option");
      opt.value = s.id;
      opt.textContent = s.name + " (" + s.id + ")";
      selectServer.appendChild(opt);
    }
    if (!SERVERS.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No server found";
      selectServer.appendChild(opt);
      selectServer.disabled = true;
      btnInstall.disabled = true;
      btnCheck.disabled = true;
    }
  }

  async function checkInstallState() {
    if (!invoke) return;
    const sid = selectedServerId();
    if (!sid) return setStatus("Select a server", true);
    
    try {
      setStatus("Checking...", false);
      const list = await invoke("m_get_plugins", { serverId: sid });
      const found = Array.isArray(list) && list.some((p) => {
        const fileName = String((p && p.file_name) || "").toLowerCase();
        const pluginName = String((p && p.name) || "").toLowerCase();
        return fileName === JAR_FILE_NAME.toLowerCase() || pluginName.indexOf("sealantermap") >= 0;
      });
      setStatus(found ? "Core installed" : "Core not installed", false);
    } catch (e) {
      setStatus("Check failed", true);
    }
  }

  async function installCore() {
    if (!invoke) return;
    const sid = selectedServerId();
    if (!sid) return setStatus("Select a server", true);
    if (!PAYLOAD_BASE64) return setStatus("Payload unavailable", true);

    btnInstall.disabled = true;
    btnCheck.disabled = true;
    try {
      setStatus("Installing...", false);
      await invoke("m_install_plugin", {
        serverId: sid,
        fileData: getPayloadBytes(),
        fileName: JAR_FILE_NAME
      });
      setStatus("Install done. Restart server", false);
      await checkInstallState();
    } catch (e) {
      setStatus("Install failed", true);
    } finally {
      btnInstall.disabled = false;
      btnCheck.disabled = false;
    }
  }
  
  fillServerSelect();
  btnCheck.addEventListener("click", checkInstallState);
  btnInstall.addEventListener("click", installCore);
  
  if (PAYLOAD_BASE64) {
    setStatus("Ready", false);
  } else {
    setStatus("Payload error", true);
  }

  // Load bg settings
  bgToggle.checked = localStorage.getItem("slm_bg_enabled") === "1";
  bgToggle.addEventListener("change", e => {
      localStorage.setItem("slm_bg_enabled", e.target.checked ? "1" : "0");
      updateServerCardBackgrounds();
  });

  closeBtn.addEventListener("click", () => {
     modal.style.display = "none";
     mFrame.src = "about:blank"; // Save resources when closed
  });
  
  btnReload.addEventListener("click", function() {
    try {
      mFrame.contentWindow.location.reload();
    } catch (_e) {
      mFrame.src = mFrame.src;
    }
  });

  window.addEventListener("keydown", function(e) {
    if (e.key === "Escape" && modal.style.display !== "none") {
      modal.style.display = "none";
      mFrame.src = "about:blank";
    }
  });

  // Inject Console Button 
  setInterval(() => {
     const quickGroups = document.querySelector(".quick-groups");
     if (quickGroups && !document.getElementById("slm-console-open-btn")) {
        const btn = document.createElement("div");
        btn.id = "slm-console-open-btn";
        btn.className = "quick-btn";
        btn.textContent = "🗺️ 打开地图";
        
        // Insert as first button
        if (quickGroups.firstChild) {
            quickGroups.insertBefore(btn, quickGroups.firstChild);
        } else {
            quickGroups.appendChild(btn);
        }

        btn.addEventListener("click", () => {
            modal.style.display = "flex";
            if(mFrame.src !== PREVIEW_URL) mFrame.src = PREVIEW_URL;
        });
     }
  }, 1000);

  function formatTimeOfDay(ticks) {
    if (ticks < 0) return "--:--";
    let mcHours = (ticks / 1000) + 6;
    if (mcHours >= 24) mcHours -= 24;
    const h = Math.floor(mcHours);
    const m = Math.floor((mcHours - h) * 60);
    return String(h).padStart(2, '0') + ":" + String(m).padStart(2, '0');
  }

  // Polling Map Stats for Clock
  setInterval(async () => {
    try {
      if (modal.style.display === "none") return;
      const res = await fetch(PREVIEW_URL + "stats.json?t=" + Date.now(), { cache: 'no-store' });
      if (res.ok) {
        const stats = await res.json();
        const gt = stats.gameTimeTicks !== undefined ? stats.gameTimeTicks : -1;
        if (gt >= 0) {
          mClock.style.display = "flex";
          const isDay = gt < 12000;
          mClock.className = "slm-modal-clock " + (isDay ? "day" : "night");
          mClockIcon.textContent = isDay ? "☀️" : "🌙";
          mClockTime.textContent = formatTimeOfDay(gt);
        } else {
          mClock.style.display = "none";
        }
      }
    } catch(e) {}
  }, 2000);

  // Background Injection for Home 
  function updateServerCardBackgrounds() {
     const isEnabled = localStorage.getItem("slm_bg_enabled") === "1";
     const cards = document.querySelectorAll(".server-card");
     
     cards.forEach(card => {
         let bgContainer = card.querySelector(".slm-card-bg");
         
         if (isEnabled) {
             if (!bgContainer) {
                 bgContainer = document.createElement("div");
                 bgContainer.className = "slm-card-bg";
                 // Negative coords in css will crop out leaflet borders, getting a clean map snippet
                 bgContainer.innerHTML = `<iframe src="${PREVIEW_URL}" style="width: 100%; height: 100%; border: none; pointer-events: none;"></iframe>`;
                 card.insertBefore(bgContainer, card.firstChild);
             }
         } else {
             if (bgContainer) bgContainer.remove();
         }
     });
  }

  setInterval(updateServerCardBackgrounds, 2000);

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
