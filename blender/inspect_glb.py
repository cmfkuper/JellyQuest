"""
Inspect and render a GLB in the seat studio: prints mesh stats, normalizes the
model (on the floor, centered, facing -y), renders front and back views.

Run:  blender -b -P inspect_glb.py -- <path-to-glb> <out-prefix>
"""

import bpy
import math
import os
import sys

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
GLB_PATH = argv[0] if argv else os.path.join(os.path.dirname(os.path.abspath(__file__)), "seat_ai.glb")
PREFIX = argv[1] if len(argv) > 1 else "glb"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "renders")

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

bpy.ops.import_scene.gltf(filepath=GLB_PATH)

# Join imported meshes for easy measurement (non-destructive to the file)
meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
for o in meshes:
    o.select_set(True)
bpy.context.view_layer.objects.active = meshes[0]
if len(meshes) > 1:
    bpy.ops.object.join()
obj = bpy.context.view_layer.objects.active

tris = sum(len(p.vertices) - 2 for p in obj.data.polygons)
print(f"STATS name={obj.name} verts={len(obj.data.vertices)} tris={tris} "
      f"materials={len(obj.data.materials)}")

# Normalize: center on origin, base on floor
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
xs = [v.co.x for v in obj.data.vertices]
ys = [v.co.y for v in obj.data.vertices]
zs = [v.co.z for v in obj.data.vertices]
dims = (max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))
print(f"STATS dims={dims[0]:.3f} x {dims[1]:.3f} x {dims[2]:.3f} m")
obj.location = (-(max(xs) + min(xs)) / 2, -(max(ys) + min(ys)) / 2, -min(zs))
bpy.ops.object.transform_apply(location=True)

# Optional third arg: normalize overall height to this many meters
if len(argv) > 2:
    target_h = float(argv[2])
    factor = target_h / dims[2]
    obj.scale = (factor, factor, factor)
    bpy.ops.object.transform_apply(scale=True)
    print(f"STATS scaled by {factor:.3f} to height {target_h}m")

# Studio floor + lights
floor_mat = bpy.data.materials.new("Floor")
floor_mat.use_nodes = True
floor_mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.12, 0.12, 0.13, 1)
bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 0, 0))
bpy.context.active_object.data.materials.append(floor_mat)

for name, loc, rot, energy, size in [
    ("key", (2.2, -2.6, 2.6), (math.radians(55), 0, math.radians(40)), 240, 2.2),
    ("fill", (-2.6, -1.8, 1.8), (math.radians(65), 0, math.radians(-55)), 80, 3.0),
    ("rim", (0.4, 2.8, 2.4), (math.radians(-50), math.radians(180), 0), 160, 1.8),
]:
    bpy.ops.object.light_add(type="AREA", location=loc, rotation=rot)
    light = bpy.context.active_object
    light.data.energy = energy
    light.data.size = size

world = bpy.context.scene.world
world.use_nodes = True
bg = world.node_tree.nodes.get("Background")
if bg:
    bg.inputs[0].default_value = (0.02, 0.02, 0.025, 1)


def render_view(name, cam_loc, look_at):
    bpy.ops.object.camera_add(location=cam_loc)
    cam = bpy.context.active_object
    cam.data.lens = 50
    target = bpy.data.objects.new(f"aim_{name}", None)
    target.location = look_at
    bpy.context.collection.objects.link(target)
    con = cam.constraints.new("TRACK_TO")
    con.target = target
    con.track_axis = "TRACK_NEGATIVE_Z"
    con.up_axis = "UP_Y"
    bpy.context.scene.camera = cam

    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 128
    scene.cycles.use_denoising = True
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "OPTIX"
        prefs.get_devices()
        for dev in prefs.devices:
            dev.use = True
        scene.cycles.device = "GPU"
    except Exception:
        pass
    scene.render.resolution_x = 1000
    scene.render.resolution_y = 1000
    os.makedirs(OUT_DIR, exist_ok=True)
    scene.render.filepath = os.path.join(OUT_DIR, name)
    bpy.ops.render.render(write_still=True)
    print(f"Rendered: {scene.render.filepath}")
    bpy.data.objects.remove(cam, do_unlink=True)
    bpy.data.objects.remove(target, do_unlink=True)


render_view(f"{PREFIX}_front.png", (1.5, -1.9, 1.25), (0, 0.1, 0.65))
render_view(f"{PREFIX}_back.png", (1.4, 2.1, 1.35), (0, 0.1, 0.75))
