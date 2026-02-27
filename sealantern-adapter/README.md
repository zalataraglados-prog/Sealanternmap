# Sealantermap SeaLantern Adapter (One-Package)

This folder provides a **single SeaLantern plugin package** that handles two parts:

1. **SeaLantern UI integration**
- Adds a floating `Map` button.
- Opens an in-app map panel (iframe to `http://127.0.0.1:8156/`).

2. **Game server core installation**
- Bundles `sealantermap-0.1.0.jar` as payload.
- Installs/updates that jar into selected server `plugins` folder by calling SeaLantern `m_install_plugin`.

## Why this is "one plugin"

User installs only one SeaLantern plugin zip.
Inside that zip, the adapter contains the game-side jar payload and installs it when requested from the panel.

## Package layout

- `manifest.json`
- `main.lua`
- `payload/sealantermap-0.1.0.jar`

## Required permissions

- `log`
- `ui`
- `fs` (read payload from plugin data)
- `server` (list servers)

## Build package

```powershell
.\package.ps1
```

Output:

```text
sealantermap-bridge-sealantern.zip
```

## Install in SeaLantern

1. Open SeaLantern -> Plugins.
2. Install from zip: `sealantermap-bridge-sealantern.zip`.
3. Enable plugin `sealantermap-bridge`.
4. Click floating `Map` button.
5. Select a server and click `Install/Update Core`.
6. Restart that Minecraft server once.

## Notes

- This adapter does not replace Sealantermap core logic.
- It only manages installation + panel embedding in SeaLantern.
