# MG4 top-view render

Based on **2025 MG MG4 (GLB)(mgmotor.com.mx)** by **MattDoesBlender**.

- Creator: https://sketchfab.com/MattDoesBlender
- Original model: https://sketchfab.com/3d-models/2025-mg-mg4-glbmgmotorcommx-ee2e3b0e1a7b481cb2c08e6fc6d2ed9e
- License: **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International**
- License text: https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.en

The rendered PNGs and modified Blender scene are derived assets provided under
**CC BY-NC-SA 4.0**, separately from the application's GPL code. Noncommercial use,
appropriate attribution, an indication of changes, and ShareAlike terms apply.

Changes made on 2026-11-09: orthographic top-down rendering with front upwards;
transparent background; studio lighting; exterior lamps disabled; window material
made opaque/tinted for an overlay; minimum roughness softened; original blue paint
retained. Uniform model normalization used the assumed 4.287 m overall length.
The original downloaded archive was not changed.

## Bundled files

- `colors/*.png`: eight prepared 768 x 1536 RGBA paint variants, front up.
- `topview.json`: shared orthographic registration and normalized body bounds.
- `color-renders.json`: names, user-supplied paint codes, material values and PNG hashes.
- `ATTRIBUTION.md`: this attribution and modification record.

Additional changes on 2026-11-09: six body paint/material variants rendered in
Blender; the original blue top-view render was retained as Piccadilly Blue.
The roof, glazing, tyres and other non-paint materials retain their prepared
appearance. Paint colors are screen approximations, not measured paint samples.
All eight derived PNGs remain under CC BY-NC-SA 4.0.

Additional change on 2026-11-10: Hunter Green (user-supplied paint codes 5ZCK / ZCK / GWH;ZCK), rendered with a matte body material (roughness 0.82, no clearcoat, reduced specular reflection).

The app scales the visible body uniformly to fit its approximate 1.836 x 4.287 m
black vehicle mask with a small inset; transparent margins are not body bounds.
The editable source scene and original model stay outside the APK.
