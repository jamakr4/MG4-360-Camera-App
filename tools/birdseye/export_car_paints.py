"""Prepare eight MG4 palette atlases for the existing rigid-wheel runtime OBJ.

Blender --background --python export_car_paints.py -- --blend SOURCE.blend
  --model-dir EXPORTED_MODEL --paints TOPVIEW_COLOR_MANIFEST --output NEW_DIRECTORY

Shares the existing mesh/UVs. Paint names, display colors and finish parameters
come from the same manifest as the 2D artwork. CC BY-NC-SA 4.0 derivatives.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import bpy


def display_color(hex_color):
    # GLES2 samples these bytes directly, like the existing camera/2D pipeline.
    # Do not write linear-light values into a texture sampled as display RGB.
    return [int(hex_color[i:i+2], 16)/255 for i in (1, 3, 5)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blend", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--paints", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(sys.argv[sys.argv.index("--")+1:])
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error("Refusing to overwrite an existing paint export")
    (out/"paints").mkdir(parents=True, exist_ok=True)
    metadata = json.loads((args.model_dir/"model.json").read_text())
    for name in ("car.obj", "car.mtl", "car.png"):
        if hashlib.sha256((args.model_dir/name).read_bytes()).hexdigest() != metadata["sha256"][name]:
            raise RuntimeError("Model input hash mismatch: " + name)
    variants = json.loads(args.paints.read_text())["variants"]
    if len(variants) != 8 or "piccadilly_blue" not in variants:
        raise RuntimeError("Expected the eight existing Parking paint choices")
    bpy.ops.wm.open_mainfile(filepath=str(args.blend.resolve()))
    materials = []
    for obj in bpy.context.scene.objects:
        if obj.type == "MESH" and not obj.hide_render:
            for material in obj.data.materials:
                if material and material.name not in {m.name for m in materials}:
                    materials.append(material)
    paint = bpy.data.objects["Carroceria"].data.materials[0]
    slot = [m.name for m in materials].index(paint.name)
    ox, oy = (slot % 16)*64, (slot//16)*64
    source = bpy.data.images.load(str((args.model_dir/"car.png").resolve()), check_existing=False)
    source.colorspace_settings.name = "Non-Color"  # Preserve existing RGB bytes of every other material.
    if tuple(source.size) != (1024, 1024):
        raise RuntimeError("Expected the existing 1024px material palette")
    original = list(source.pixels[:])
    # Assert that the unchanged source scene and model agree on the material order.
    for i, material in enumerate(materials):
        if i == slot:
            continue  # The blue base may already be a prepared display-RGB palette.
        node = next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None) if material.use_nodes else None
        expected = node.inputs["Base Color"].default_value if node else material.diffuse_color
        p = (((i//16)*64+32)*1024+(i % 16)*64+32)*4
        if max(abs(original[p+c]-expected[c]) for c in range(3)) > .012:
            raise RuntimeError("Palette/source material mismatch: " + material.name)
    # Every OBJ material UV must sample a cell center; downsampling to 256px then
    # preserves exactly the same solid palette samples, with eight atlases using 2 MiB.
    for line in (args.model_dir/"car.obj").open():
        if line.startswith("vt "):
            u, v = map(float, line.split()[1:])
            if any(abs((value*16-.5)-round(value*16-.5)) > .001 for value in (u, v)):
                raise RuntimeError("Model has non-palette UVs")
    manifest = {
        "source": metadata["source"], "creator": metadata["creator"],
        "license": "CC BY-NC-SA 4.0", "license_url": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        "mesh_sha256": metadata["sha256"]["car.obj"], "resolution": [1024, 1024],
        "runtime_resolution": [256, 256], "paint_material": paint.name, "paint_slot": slot,
        "paint_rect_uv": [ox/1024, 1-(oy+64)/1024, (ox+64)/1024, 1-oy/1024],
        "note": "Only the body-paint cell changes. Black roof, glazing, trim, lamps and wheels are retained. Colors/finishes are screen approximations, not measured paint samples.",
        "variants": {},
    }
    image = bpy.data.images.new("Prepared MG4 paint palette", width=1024, height=1024, alpha=False)
    image.colorspace_settings.name = "Non-Color"
    image.file_format = "PNG"
    for identifier, info in variants.items():
        if not identifier.replace("_", "").isalnum():
            raise RuntimeError("Unsafe paint identifier")
        pixels = original.copy()
        rgb = display_color(info["swatch"])
        for y in range(oy, oy+64):
            for x in range(ox, ox+64):
                p = (y*1024+x)*4
                pixels[p:p+4] = [*rgb, 1]
        image.pixels.foreach_set(pixels)
        target = out/"paints"/(identifier+".png")
        image.filepath_raw = str(target)
        image.save()
        manifest["variants"][identifier] = {k: info[k] for k in ("name", "codes", "swatch", "metallic", "roughness")}
        manifest["variants"][identifier].update({
            "finish": "matte" if identifier == "hunter_green" else "gloss",
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        })
        if identifier == "piccadilly_blue":
            (out/"car.png").write_bytes(target.read_bytes())
    metadata["material_note"] = "Eight prepared body-paint palettes with shared geometry; black roof and other materials retained"
    metadata["paint_variants"] = "paint-variants.json"
    metadata["sha256"]["car.png"] = hashlib.sha256((out/"car.png").read_bytes()).hexdigest()
    (out/"model.json").write_text(json.dumps(metadata, indent=2)+"\n")
    (out/"paint-variants.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print("MG4_3D_PAINTS="+json.dumps(manifest), flush=True)


if __name__ == "__main__":
    main()
