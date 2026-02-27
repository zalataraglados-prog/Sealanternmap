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
#slm-bridge {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 9999;
  pointer-events: auto;
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
}
#slm-bridge .slm-open-btn {
  border: 1px solid rgba(125, 211, 252, 0.3);
  border-radius: 10px;
  background: rgba(2, 6, 23, 0.92);
  color: #dbeafe;
  padding: 8px 12px;
  font-size: 12px;
  cursor: pointer;
}
#slm-bridge .slm-open-btn:hover {
  background: rgba(15, 23, 42, 0.96);
}
#slm-bridge .slm-panel {
  display: none;
  width: min(1180px, calc(100vw - 32px));
  height: min(800px, calc(100vh - 74px));
  background: #020617;
  border: 1px solid rgba(125, 211, 252, 0.3);
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.55);
  overflow: hidden;
  margin-bottom: 10px;
}
#slm-bridge[data-open="1"] .slm-panel {
  display: block;
}
#slm-bridge .slm-head {
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.3);
  background: rgba(15, 23, 42, 0.95);
}
#slm-bridge .slm-title {
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1;
}
#slm-bridge .slm-actions {
  display: flex;
  gap: 6px;
}
#slm-bridge .slm-act-btn {
  height: 26px;
  min-width: 26px;
  border: 1px solid rgba(255,255,255,0.16);
  border-radius: 8px;
  background: rgba(30,41,59,0.9);
  color: #e2e8f0;
  font-size: 12px;
  cursor: pointer;
}
#slm-bridge .slm-act-btn:hover {
  background: rgba(51,65,85,0.95);
}
#slm-bridge .slm-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.25);
  background: rgba(15, 23, 42, 0.85);
}
#slm-bridge .slm-tools label {
  color: #cbd5e1;
  font-size: 12px;
}
#slm-bridge .slm-tools select,
#slm-bridge .slm-tools button {
  height: 30px;
  border-radius: 8px;
  border: 1px solid rgba(148,163,184,0.35);
  background: rgba(30,41,59,0.95);
  color: #e2e8f0;
  padding: 0 10px;
  font-size: 12px;
}
#slm-bridge .slm-tools button {
  cursor: pointer;
}
#slm-bridge .slm-tools button:hover {
  background: rgba(51,65,85,0.95);
}
#slm-bridge .slm-status {
  min-height: 18px;
  font-size: 12px;
  color: #94a3b8;
}
#slm-bridge .slm-status.err {
  color: #fca5a5;
}
#slm-bridge iframe {
  width: 100%;
  height: calc(100% - 42px - 48px);
  border: 0;
  background: #000;
}
]]
end

local function build_html(servers_js, payload_b64, payload_error)
  local template = [[
<div id="slm-bridge" data-open="0">
  <div class="slm-panel">
    <div class="slm-head">
      <div class="slm-title">Sealantermap Bridge</div>
      <div class="slm-actions">
        <button class="slm-act-btn" id="slm-reload" title="Reload map">R</button>
        <button class="slm-act-btn" id="slm-close" title="Close">X</button>
      </div>
    </div>
    <div class="slm-tools">
      <label for="slm-server">Server</label>
      <select id="slm-server"></select>
      <button id="slm-check">Check</button>
      <button id="slm-install">Install/Update Core</button>
      <span id="slm-status" class="slm-status"></span>
    </div>
    <iframe id="slm-frame" src="__PREVIEW_URL__" loading="lazy"></iframe>
  </div>
  <button class="slm-open-btn" id="slm-open">Map</button>
</div>
<script>
(function() {
  const SERVERS = __SERVERS__;
  const PAYLOAD_BASE64 = "__PAYLOAD_BASE64__";
  const PAYLOAD_ERROR = "__PAYLOAD_ERROR__";
  const JAR_FILE_NAME = "__JAR_FILE_NAME__";

  const root = document.getElementById("slm-bridge");
  const btnOpen = document.getElementById("slm-open");
  const btnClose = document.getElementById("slm-close");
  const btnReload = document.getElementById("slm-reload");
  const btnCheck = document.getElementById("slm-check");
  const btnInstall = document.getElementById("slm-install");
  const selectServer = document.getElementById("slm-server");
  const statusEl = document.getElementById("slm-status");
  const frame = document.getElementById("slm-frame");

  if (!root || !btnOpen || !btnClose || !btnReload || !btnCheck || !btnInstall || !selectServer || !statusEl || !frame) {
    return;
  }

  function setOpen(v) {
    root.setAttribute("data-open", v ? "1" : "0");
  }

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
  if (!invoke) {
    setStatus("Tauri invoke unavailable in current runtime.", true);
  }

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
    if (!invoke) {
      setStatus("Invoke unavailable, cannot check.", true);
      return;
    }
    const sid = selectedServerId();
    if (!sid) {
      setStatus("Select a server first.", true);
      return;
    }
    try {
      setStatus("Checking plugin status...", false);
      const list = await invoke("m_get_plugins", { serverId: sid });
      const found = Array.isArray(list) && list.some((p) => {
        const fileName = String((p && p.file_name) || "").toLowerCase();
        const pluginName = String((p && p.name) || "").toLowerCase();
        return fileName === JAR_FILE_NAME.toLowerCase() || pluginName.indexOf("sealantermap") >= 0;
      });
      setStatus(found ? "Core installed on selected server." : "Core not installed on selected server.", false);
    } catch (e) {
      setStatus("Check failed: " + (e && e.message ? e.message : String(e)), true);
    }
  }

  async function installCore() {
    if (!invoke) {
      setStatus("Invoke unavailable, cannot install.", true);
      return;
    }
    const sid = selectedServerId();
    if (!sid) {
      setStatus("Select a server first.", true);
      return;
    }
    if (!PAYLOAD_BASE64) {
      setStatus("Core payload unavailable: " + PAYLOAD_ERROR, true);
      return;
    }

    btnInstall.disabled = true;
    btnCheck.disabled = true;
    try {
      setStatus("Installing Sealantermap core...", false);
      await invoke("m_install_plugin", {
        serverId: sid,
        fileData: getPayloadBytes(),
        fileName: JAR_FILE_NAME
      });
      setStatus("Install done. Restart server to load/update plugin.", false);
      await checkInstallState();
    } catch (e) {
      setStatus("Install failed: " + (e && e.message ? e.message : String(e)), true);
    } finally {
      btnInstall.disabled = false;
      btnCheck.disabled = false;
    }
  }

  btnOpen.addEventListener("click", function() {
    const isOpen = root.getAttribute("data-open") === "1";
    setOpen(!isOpen);
  });

  btnClose.addEventListener("click", function() {
    setOpen(false);
  });

  btnReload.addEventListener("click", function() {
    try {
      frame.contentWindow.location.reload();
    } catch (_e) {
      frame.src = frame.src;
    }
  });

  btnCheck.addEventListener("click", checkInstallState);
  btnInstall.addEventListener("click", installCore);

  window.addEventListener("keydown", function(e) {
    if (e.key === "Escape" && root.getAttribute("data-open") === "1") {
      setOpen(false);
    }
  });

  fillServerSelect();
  if (PAYLOAD_BASE64) {
    setStatus("Ready. Select server and install core once.", false);
  } else {
    setStatus("Payload error: " + PAYLOAD_ERROR, true);
  }
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

