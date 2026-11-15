"""Blender 5.x: licensed MG4 body plus four rigid wheel assemblies.

--blend SOURCE.blend --output DIR. Source/derivative: MattDoesBlender, CC BY-NC-SA 4.0.
Runtime: mm, X right, front -Y, Z down. Pivots are visual tire centers,
not measured kingpin axes or a calibrated vehicle steering model.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path
import bmesh
import bpy

PARTS = ("body", "front_left", "front_right", "rear_left", "rear_right")
WHEEL_OBJECTS = {"llantas1", "polySurface387", "Discos", "Calipers"}
TARGET_TRIANGLES = 88000


def wheel_group(point):
    return ("front_" if point.y < 0 else "rear_") + ("left" if point.x < 0 else "right")


def bounds(objects):
    points = [v.co for obj in objects for v in obj.data.vertices]
    return ([min(p[i] for p in points) for i in range(3)],
            [max(p[i] for p in points) for i in range(3)])


def join(objects, name):
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    if len(objects) > 1:
        bpy.ops.object.join()
    obj = bpy.context.object
    obj.name = name
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.remove_doubles(threshold=.000001)
    bpy.ops.object.mode_set(mode="OBJECT")
    return obj


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--blend", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(sys.argv[sys.argv.index("--")+1:])
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if any((out/n).exists() for n in ("car.obj", "car.mtl", "car.png", "model.json")):
        parser.error("Refusing to overwrite an existing model export")
    bpy.ops.wm.open_mainfile(filepath=str(args.blend.resolve()))
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH" and not o.hide_render]
    if {o.name for o in meshes} & WHEEL_OBJECTS != WHEEL_OBJECTS:
        raise RuntimeError("Expected original MG4 tire/rim/disc/caliper groups")
    materials = []
    for obj in meshes:
        for material in obj.data.materials:
            if material and material.name not in {m.name for m in materials}:
                materials.append(material)
    if not materials or len(materials) > 256:
        raise RuntimeError("Invalid material count")
    material_ids = {m.name: i for i, m in enumerate(materials)}
    # Bake parent/world transforms before joining; preserve glTF axis conversion.
    bpy.ops.object.select_all(action="DESELECT")
    for obj in meshes:
        world = obj.matrix_world.copy()
        obj.parent = None
        obj.matrix_world = world
        obj.select_set(True)
    bpy.context.view_layer.update()
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    lo, hi = bounds(meshes)
    scale = 4287/(hi[1]-lo[1])

    def runtime(point):
        return [(point[0]-(lo[0]+hi[0])/2)*scale,
                (point[1]-(lo[1]+hi[1])/2)*scale, -(point[2]-lo[2])*scale]

    groups = {name: [] for name in PARTS}
    pivots = {}
    for obj in meshes:
        if obj.name not in WHEEL_OBJECTS:
            groups["body"].append(obj)
            continue
        for polygon in obj.data.polygons:
            if len({wheel_group(obj.data.vertices[i].co) for i in polygon.vertices}) != 1:
                raise RuntimeError("Wheel face crosses assembly boundaries")
        for name in PARTS[1:]:
            piece = obj.copy()
            piece.data = obj.data.copy()
            bpy.context.collection.objects.link(piece)
            bm = bmesh.new()
            bm.from_mesh(piece.data)
            bmesh.ops.delete(bm, geom=[v for v in bm.verts if wheel_group(v.co) != name], context="VERTS")
            bm.to_mesh(piece.data)
            bm.free()
            if not piece.data.polygons:
                raise RuntimeError("Missing wheel geometry: " + name)
            if obj.name == "llantas1":
                a, b = bounds([piece])
                pivots[name] = runtime([(a[i]+b[i])/2 for i in range(3)])
            groups[name].append(piece)
        bpy.data.objects.remove(obj, do_unlink=True)
    parts = {name: join(groups[name], name) for name in PARTS}
    source_counts = {}
    for name, obj in parts.items():
        obj.data.calc_loop_triangles()
        source_counts[name] = len(obj.data.loop_triangles)
    source_total = sum(source_counts.values())
    for name, obj in parts.items():
        bpy.context.view_layer.objects.active = obj
        budget = int(TARGET_TRIANGLES*source_counts[name]/source_total)
        decimate = obj.modifiers.new("Runtime triangle budget", "DECIMATE")
        decimate.ratio = min(1, budget/source_counts[name])
        decimate.use_collapse_triangulate = True
        bpy.ops.object.modifier_apply(modifier=decimate.name)
        obj.data.calc_loop_triangles()
    triangles = sum(len(o.data.loop_triangles) for o in parts.values())
    if triangles > 100000:
        raise RuntimeError("Triangle budget exceeded")
    # Original colors in one shared 1024px atlas; source texture markings simplified.
    atlas = bpy.data.images.new("Runtime material atlas", width=1024, height=1024, alpha=False)
    pixels = [0.0, 0.0, 0.0, 1.0] * (1024*1024)
    for i, material in enumerate(materials):
        node = next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None) if material.use_nodes else None
        color = list(node.inputs["Base Color"].default_value if node else material.diffuse_color)
        ox, oy = (i % 16)*64, (i//16)*64
        for y in range(oy, oy+64):
            for x in range(ox, ox+64):
                pixels[(y*1024+x)*4:(y*1024+x)*4+4] = [*color[:3], 1.0]
    atlas.pixels.foreach_set(pixels)
    atlas.filepath_raw = str(out/"car.png")
    atlas.file_format = "PNG"
    atlas.save()
    for obj in parts.values():
        if not obj.data.uv_layers:
            obj.data.uv_layers.new(name="RuntimeAtlas")
        for polygon in obj.data.polygons:
            i = material_ids[obj.data.materials[polygon.material_index].name]
            for index in polygon.loop_indices:
                obj.data.uv_layers.active.data[index].uv = (((i % 16)*64+32)/1024, ((i//16)*64+32)/1024)
    with (out/"car.obj").open("w") as f:
        f.write("# MG4 rigid-wheel derivative; MattDoesBlender; CC BY-NC-SA 4.0\nmtllib car.mtl\nusemtl body\n")
        for name, pivot in pivots.items():
            f.write("# pivot %s %.5f %.5f %.5f\n" % (name, *pivot))
        vertex_offset = loop_offset = 0
        for name, obj in parts.items():
            mesh = obj.data
            f.write("g " + name + "\n")
            for vertex in mesh.vertices:
                f.write("v %.5f %.5f %.5f\n" % tuple(runtime(vertex.co)))
            for loop in mesh.loops:
                uv = mesh.uv_layers.active.data[loop.index].uv
                f.write("vt %.6f %.6f\n" % (uv.x, uv.y))
            for loop in mesh.loops:
                n = mesh.corner_normals[loop.index].vector
                f.write("vn %.6f %.6f %.6f\n" % (n.x, n.y, -n.z))
            for triangle in mesh.loop_triangles:
                f.write("f " + " ".join("%d/%d/%d" % (mesh.loops[i].vertex_index+1+vertex_offset,
                            i+1+loop_offset, i+1+loop_offset) for i in
                            (triangle.loops[0], triangle.loops[2], triangle.loops[1])) + "\n")
            vertex_offset += len(mesh.vertices)
            loop_offset += len(mesh.loops)
    (out/"car.mtl").write_text("newmtl body\nKd 1 1 1\nmap_Kd car.png\n")
    metadata = {"source": "https://sketchfab.com/3d-models/2025-mg-mg4-glbmgmotorcommx-ee2e3b0e1a7b481cb2c08e6fc6d2ed9e",
                "creator": "MattDoesBlender", "license": "CC BY-NC-SA 4.0",
                "material_note": "Original base colors; detailed markings simplified; shared material atlas",
                "source_polygons": source_total, "triangles": triangles, "atlas": [1024, 1024],
                "bounds_mm": [runtime([lo[0], lo[1], hi[2]]), runtime([hi[0], hi[1], lo[2]])],
                "coordinates": "millimetres; X right; front -Y; Z down; ground 0; positive steering turns right",
                "pivot_note": "Tire-bounds centers for visual animation, not measured steering axes",
                "parts": [{"name": n, "triangles": len(o.data.loop_triangles), "pivot_mm": pivots.get(n, [0, 0, 0])} for n, o in parts.items()],
                "sha256": {n: hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ("car.obj", "car.mtl", "car.png")}}
    (out/"model.json").write_text(json.dumps(metadata, indent=2)+"\n")
    print("MG4_RUNTIME_MODEL=" + json.dumps(metadata), flush=True)


if __name__ == "__main__":
    main()
