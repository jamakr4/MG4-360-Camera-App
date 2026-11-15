import bpy, sys, json, math
from pathlib import Path
from mathutils import Vector

glb, out = map(Path, sys.argv[sys.argv.index("--")+1:])
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
bpy.ops.import_scene.gltf(filepath=str(glb))
meshes=[o for o in bpy.context.scene.objects if o.type=="MESH"]
points=[o.matrix_world @ Vector(c) for o in meshes for c in o.bound_box]
low=[min(p[i] for p in points) for i in range(3)]
high=[max(p[i] for p in points) for i in range(3)]
report={"bounds_min":low,"bounds_max":high,"dimensions":[high[i]-low[i] for i in range(3)],"objects":[],"materials":[]}
for o in meshes:
    pts=[o.matrix_world @ Vector(c) for c in o.bound_box]
    lo=[min(p[i] for p in pts) for i in range(3)]
    hi=[max(p[i] for p in pts) for i in range(3)]
    report["objects"].append({"name":o.name,"materials":[m.name for m in o.data.materials if m],"vertices":len(o.data.vertices),"min":lo,"max":hi})
for m in bpy.data.materials:
    values={}
    if m.use_nodes:
        for node in m.node_tree.nodes:
            if node.type=="BSDF_PRINCIPLED":
                for key in ["Base Color","Metallic","Roughness","Alpha","Transmission Weight","IOR","Emission Color","Emission Strength"]:
                    if key in node.inputs:
                        v=node.inputs[key].default_value
                        values[key]=list(v) if hasattr(v,"__len__") else v
    report["materials"].append({"name":m.name,**values})
(out/"scene-info.json").write_text(json.dumps(report,indent=2))
bpy.ops.wm.save_as_mainfile(filepath=str(out/"imported.blend"))
print("MG4_INSPECTION="+json.dumps(report))
