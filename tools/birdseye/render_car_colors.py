"""Render ready-to-use PNG paint variants from the local MG4 top-view Blender scene.

Run with Blender --background --python render_car_colors.py -- --blend FILE --output DIR.
The scene/model/derived images have their own CC BY-NC-SA 4.0 license. No geometry
is embedded in this script. Paint names/codes are user supplied; sRGB values are
screen approximations, not measured manufacturer paint samples.
"""
import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

import bpy

PAINTS = [
    ("dover_white", "Dover White / Arctic White", "WSB / LIY", "#E8E9E5", 0.0, 0.24),
    ("andes_grey", "Andes Grey / Cool Gray", "5ZBY / PAG", "#747A80", 0.45, 0.23),
    ("pebble_black", "Pebble Black / Black Knight", "PBC", "#151719", 0.45, 0.22),
    ("diamond_red", "Diamond Red / Topspeed Red", "5ZAU / RSJ", "#BA132B", 0.40, 0.21),
    ("fizzy_orange", "Fizzy Orange / Bubble Orange", "NFZ / ZBP", "#F36F14", 0.0, 0.24),
    ("piccadilly_blue", "Piccadilly Blue / Brighton Blue", "JAY / 5JAY", "#008BD8", 0.15, 0.13),
    ("cosmic_silver", "Cosmic Silver / Medal Silver", "MBC / SSA", "#B8BDC2", 0.70, 0.23),
    ("hunter_green", "Hunter Green", "5ZCK / ZCK / GWH;ZCK", "#4C604D", 0.0, 0.82),
]


def linear_color(hex_color):
    rgb = [int(hex_color[i:i+2], 16)/255 for i in (1, 3, 5)]
    return tuple(c/12.92 if c <= .04045 else ((c+.055)/1.055)**2.4 for c in rgb)+(1,)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blend", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--blue-reference", type=Path)
    parser.add_argument("--colors", nargs="+", choices=[p[0] for p in PAINTS],
                        help="Render selected variants and retain existing manifest entries")
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args(sys.argv[sys.argv.index("--")+1:])
    args.output.mkdir(parents=True, exist_ok=True)
    paints = [p for p in PAINTS if not args.colors or p[0] in args.colors]
    for identifier, *_ in paints:
        if (args.output/(identifier+".png")).exists() and not args.overwrite:
            parser.error("Output exists; use --overwrite to re-render your variants")
    bpy.ops.wm.open_mainfile(filepath=str(args.blend))
    scene = bpy.context.scene
    if scene.camera.data.type != "ORTHO":
        raise RuntimeError("An orthographic top-view scene is required")
    scene.render.resolution_x = 768
    scene.render.resolution_y = 1536
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.color_depth = "8"
    scene.render.use_persistent_data = True
    scene.cycles.samples = 48
    scene.cycles.use_denoising = True
    scene.cycles.use_adaptive_sampling = True
    scene.cycles.adaptive_threshold = .04
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 6
    scene.cycles.device = "CPU"
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "METAL"
        prefs.get_devices()
        for device in prefs.devices:
            device.use = device.type == "METAL"
        if any(d.use for d in prefs.devices):
            scene.cycles.device = "GPU"
    except Exception:
        pass
    body = bpy.data.objects["Carroceria"]
    paint = body.data.materials[0]
    principled = next(n for n in paint.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    manifest_path = args.output/"color-renders.json"
    previous = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    manifest = {
        "source": "https://sketchfab.com/3d-models/2025-mg-mg4-glbmgmotorcommx-ee2e3b0e1a7b481cb2c08e6fc6d2ed9e",
        "creator": "MattDoesBlender", "license": "CC BY-NC-SA 4.0",
        "license_url": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        "resolution": [768, 1536],
        "note": "Pre-rendered body-paint variations; user supplied paint names/codes; colors are screen approximations.",
        "variants": previous.get("variants", {}),
    }
    for identifier, name, codes, swatch, metallic, roughness in paints:
        target = args.output/(identifier+".png")
        if identifier == "piccadilly_blue" and args.blue_reference:
            shutil.copy2(args.blue_reference, target)
        else:
            principled.inputs["Base Color"].default_value = linear_color(swatch)
            principled.inputs["Metallic"].default_value = metallic
            principled.inputs["Roughness"].default_value = roughness
            if identifier == "hunter_green":
                principled.inputs["Coat Weight"].default_value = 0
                principled.inputs["Specular IOR Level"].default_value = .2
            scene.render.filepath = str(target)
            bpy.ops.render.render(write_still=True)
        manifest["variants"][identifier] = {
            "name": name, "codes": codes, "swatch": swatch,
            "metallic": metallic, "roughness": roughness,
            "finish": "matte" if identifier == "hunter_green" else "gloss",
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        }
        print("MG4_COLOR_DONE="+identifier, flush=True)
    manifest_path.write_text(json.dumps(manifest, indent=2)+"\n")


if __name__ == "__main__":
    main()
