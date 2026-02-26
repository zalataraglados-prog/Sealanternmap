# Sealantermap SeaLantern Adapter

This folder contains a **SeaLantern app plugin** (Lua) that embeds Sealantermap web preview into SeaLantern UI.

## What it does

- Adds a floating `Map` button in SeaLantern.
- Opens an in-app panel with an iframe pointing to Sealantermap preview URL.
- Keeps implementation lightweight and isolated from server-side render logic.

## Default preview URL

- `http://127.0.0.1:8156/`

If your Sealantermap uses a different port, edit `PREVIEW_URL` in `main.lua`.

## Permissions used

- `log`
- `ui`

No file write, process execution, or network permission is required.

## Install in SeaLantern

1. Zip the files in this folder (`manifest.json`, `main.lua`) as a plugin package.
2. Open SeaLantern -> Plugins.
3. Install from zip.
4. Enable plugin `sealantermap-bridge`.

## Notes

- This adapter is for **SeaLantern UI integration** only.
- Actual map rendering is still handled by the Bukkit/Paper-side Sealantermap plugin.

