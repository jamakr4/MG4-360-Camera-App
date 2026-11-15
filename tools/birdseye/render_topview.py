import bpy, sys, math, json, hashlib, argparse
from pathlib import Path
from mathutils import Vector, Matrix
from bpy_extras.object_utils import world_to_camera_view

parser=argparse.ArgumentParser(description="Prepare and render the local licensed MG4 GLB")
parser.add_argument("--input",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args(sys.argv[sys.argv.index("--")+1:])
out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
if any((out/n).exists() for n in ("mg4-topview.blend","mg4-topview.png","render-metadata.json")):
    parser.error("Output already contains generated files")
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(args.input.resolve()))
scene=bpy.context.scene
objects=list(scene.objects)
meshes=[o for o in objects if o.type=="MESH"]
points=[o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
lo=Vector([min(p[i] for p in points) for i in range(3)])
hi=Vector([max(p[i] for p in points) for i in range(3)])
factor=4.287/(hi.y-lo.y)
root=bpy.data.objects.new("MG4_geometry_normalized_by_overall_length",None)
scene.collection.objects.link(root)
for o in objects:
    if o.parent is None: o.parent=root
root.matrix_world=Matrix.Scale(factor,4) @ Matrix.Translation(Vector((-(lo.x+hi.x)/2,-(lo.y+hi.y)/2,-lo.z)))
bpy.context.view_layer.update()
# Keep the model's original blue paint. Lights are off for a neutral parked-car asset.
for name in ["calaveras_prendidas","faros_prendidos"]:
    if name in bpy.data.objects: bpy.data.objects[name].hide_render=True
# Soften perfect-mirror export values without changing the source geometry/paint.
for material in bpy.data.materials:
    if not material.use_nodes: continue
    for node in material.node_tree.nodes:
        if node.type=="BSDF_PRINCIPLED":
            rough=node.inputs.get("Roughness")
            if rough and rough.default_value<0.06: rough.default_value=0.13
            if material.name=="windowglass":
                node.inputs["Alpha"].default_value=1
                node.inputs["Base Color"].default_value=(0.025,0.038,0.055,1)
                node.inputs["Metallic"].default_value=0.25
                node.inputs["Roughness"].default_value=0.19
scene.render.engine="CYCLES"
scene.cycles.samples=48
scene.cycles.use_adaptive_sampling=True
scene.cycles.adaptive_threshold=0.04
scene.cycles.use_denoising=True
scene.cycles.max_bounces=6
scene.cycles.transparent_max_bounces=8
scene.cycles.device="CPU"
devices=[]
try:
    settings=bpy.context.preferences.addons["cycles"].preferences
    settings.compute_device_type="METAL"
    settings.get_devices()
    for device in settings.devices:
        device.use=device.type=="METAL"
        devices.append({"name":device.name,"type":device.type,"use":device.use})
    if any(d["use"] for d in devices): scene.cycles.device="GPU"
except Exception as e:
    devices.append({"fallback":str(e)})
world=bpy.data.worlds.new("Neutral studio environment")
scene.world=world;world.use_nodes=True
world.node_tree.nodes["Background"].inputs["Color"].default_value=(0.42,0.48,0.60,1)
world.node_tree.nodes["Background"].inputs["Strength"].default_value=0.55
def area(name,location,energy,size,size_y,target):
    light=bpy.data.lights.new(name,"AREA");light.energy=energy;light.shape="RECTANGLE";light.size=size;light.size_y=size_y
    obj=bpy.data.objects.new(name,light);scene.collection.objects.link(obj);obj.location=location
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat("-Z","Y").to_euler()
area("Key softbox",(-3.8,-1.5,5.8),720,4,5,(0,-0.2,0.8))
area("Fill softbox",(3.5,1.0,4.5),480,3,5,(0,0,0.8))
area("Rear rim",(0,4.5,4.0),480,3,2,(0,1,0.7))
camera=bpy.data.cameras.new("Orthographic top camera")
cam=bpy.data.objects.new("Orthographic top camera",camera);scene.collection.objects.link(cam)
scene.camera=cam;camera.type="ORTHO";camera.clip_start=0.01;camera.clip_end=100
cam.location=(0,0,10);cam.rotation_euler=(0,0,math.pi)
camera.ortho_scale=4.287*1.10
scene.render.resolution_x=768;scene.render.resolution_y=1536;scene.render.resolution_percentage=100
scene.render.image_settings.file_format="PNG";scene.render.image_settings.color_mode="RGBA";scene.render.image_settings.color_depth="8"
scene.render.film_transparent=True
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.view_settings.exposure=0;scene.view_settings.gamma=1
scene.render.threads_mode="FIXED";scene.render.threads=6
bpy.context.view_layer.update()
def bounds_in_image(selected):
    coords=[world_to_camera_view(scene,cam,o.matrix_world @ Vector(c)) for o in selected for c in o.bound_box]
    return {"x0":min(p.x for p in coords),"x1":max(p.x for p in coords),
            "y0":1-max(p.y for p in coords),"y1":1-min(p.y for p in coords)}
for step in range(10):
    bounds=bounds_in_image(meshes)
    if min(bounds["x0"],bounds["y0"],1-bounds["x1"],1-bounds["y1"])>=0.035:break
    camera.ortho_scale*=1.03
    bpy.context.view_layer.update()
metadata={"source":"https://sketchfab.com/3d-models/2025-mg-mg4-glbmgmotorcommx-ee2e3b0e1a7b481cb2c08e6fc6d2ed9e",
          "creator":"MattDoesBlender","license":"CC BY-NC-SA 4.0",
          "license_url":"https://creativecommons.org/licenses/by-nc-sa/4.0/",
          "projection":"orthographic","front":"image top; model negative Y",
          "source_dimensions":list(hi-lo),"uniform_scale":factor,
          "assumed_overall_length_m":4.287,
          "overall_image_bounds_normalized":bounds_in_image(meshes),
          "body_image_bounds_normalized":bounds_in_image([o for o in meshes if o.name=="Carroceria"]),
          "devices":devices,"resolution":[768,1536],
          "changes":["Uniform normalization by assumed 4.287 m overall length; original aspect retained",
                     "Studio lighting and orthographic camera",
                     "Exterior lamps disabled; window material opaque/tinted for an overlay",
                     "Minimum material roughness 0.13","Original blue paint retained"],
          "note":"Preview only. Model width, mirrors, physical footprint and placement require separate validation."}
(out/"render-metadata.json").write_text(json.dumps(metadata,indent=2))
scene.render.filepath=str(out/"mg4-topview.png")
bpy.ops.wm.save_as_mainfile(filepath=str(out/"mg4-topview.blend"))
bpy.ops.render.render(write_still=True)
# A separate perspective view makes front/rear, glass and paint easy to inspect.
cam.location=(4.6,-6.8,5.4)
cam.rotation_euler=(Vector((0,0,0.75))-cam.location).to_track_quat("-Z","Y").to_euler()
camera.type="PERSP";camera.lens=58
scene.render.resolution_x=900;scene.render.resolution_y=900;scene.cycles.samples=32
scene.render.filepath=str(out/"mg4-orientation-check.png")
bpy.ops.render.render(write_still=True)
print("MG4_RENDER_DONE="+str(out))
