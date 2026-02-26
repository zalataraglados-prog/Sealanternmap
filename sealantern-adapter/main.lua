-- Sealantermap Bridge (SeaLantern plugin)
-- Purpose:
-- 1) Keep Sealantermap rendering in server plugin side.
-- 2) Provide an in-app floating panel in SeaLantern for quick preview/debug.
--
-- This adapter is intentionally thin:
-- - no server file writes
-- - no process execution
-- - only UI/log permissions

local ROOT_ID = "sealantermap-bridge-root"
local STYLE_ID = "sealantermap-bridge-style"

-- Change this if your Sealantermap bind-port is different.
local PREVIEW_URL = "http://127.0.0.1:8156/"

local function build_css()
  return [[
#slm-bridge {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 9999;
  pointer-events: auto;
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
}
#slm-bridge .slm-open-btn {
  border: 1px solid rgba(255,255,255,0.16);
  border-radius: 10px;
  background: rgba(15,23,42,0.92);
  color: #e5f2ff;
  padding: 8px 12px;
  font-size: 12px;
  cursor: pointer;
  backdrop-filter: blur(6px);
}
#slm-bridge .slm-open-btn:hover {
  background: rgba(30,41,59,0.94);
}
#slm-bridge .slm-panel {
  display: none;
  width: min(1100px, calc(100vw - 36px));
  height: min(760px, calc(100vh - 86px));
  background: #020617;
  border: 1px solid rgba(125,211,252,0.32);
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.55);
  overflow: hidden;
  margin-bottom: 10px;
}
#slm-bridge[data-open="1"] .slm-panel {
  display: block;
}
#slm-bridge .slm-panel-head {
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  border-bottom: 1px solid rgba(148,163,184,0.28);
  background: rgba(15,23,42,0.95);
}
#slm-bridge .slm-title {
  color: #cde9ff;
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
#slm-bridge iframe {
  width: 100%;
  height: calc(100% - 38px);
  border: 0;
  background: #000;
}
]]
end

local function build_html()
  local html = [[
<div id="slm-bridge" data-open="0">
  <div class="slm-panel">
    <div class="slm-panel-head">
      <div class="slm-title">Sealantermap Preview</div>
      <div class="slm-actions">
        <button class="slm-act-btn" id="slm-bridge-reload" title="Reload">R</button>
        <button class="slm-act-btn" id="slm-bridge-close" title="Close">X</button>
      </div>
    </div>
    <iframe id="slm-bridge-frame" src="__PREVIEW_URL__" loading="lazy"></iframe>
  </div>
  <button class="slm-open-btn" id="slm-bridge-open">Map</button>
</div>
<script>
(function(){
  const root = document.getElementById("slm-bridge");
  if (!root) return;
  const btnOpen = document.getElementById("slm-bridge-open");
  const btnClose = document.getElementById("slm-bridge-close");
  const btnReload = document.getElementById("slm-bridge-reload");
  const frame = document.getElementById("slm-bridge-frame");
  if (!btnOpen || !btnClose || !btnReload || !frame) return;

  const setOpen = function(v){ root.setAttribute("data-open", v ? "1" : "0"); };
  const isOpen = function(){ return root.getAttribute("data-open") === "1"; };

  btnOpen.addEventListener("click", function(){
    setOpen(!isOpen());
  });

  btnClose.addEventListener("click", function(){
    setOpen(false);
  });

  btnReload.addEventListener("click", function(){
    try { frame.contentWindow.location.reload(); } catch (_e) { frame.src = frame.src; }
  });

  window.addEventListener("keydown", function(e){
    if (e.key === "Escape" && isOpen()) {
      setOpen(false);
    }
  });
})();
</script>
]]
  return string.gsub(html, "__PREVIEW_URL__", PREVIEW_URL)
end

local function mount_ui()
  sl.ui.inject_css(STYLE_ID, build_css())
  sl.ui.inject_html(ROOT_ID, build_html())
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
  sl.log.info("[SealantermapBridge] enabled, preview url = " .. PREVIEW_URL)
end

function onDisable()
  unmount_ui()
  sl.log.info("[SealantermapBridge] disabled")
end

function onUnload()
  unmount_ui()
  sl.log.info("[SealantermapBridge] unloaded")
end

