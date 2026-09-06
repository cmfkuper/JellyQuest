"""
Floor check — import the exported GLB and render the TIERS from a viewer-like
angle so concrete stretching/striping is visible before install. This is the
view the diorama seat close-up misses.

Run: blender -b -P floor_check.py -- <glb> <out.png>
"""

import bpy
import math
import os
import sys

argv = sys.argv[sys.argv.index("--") + 1:]
GLB = os.path.abspath(argv[0])
OUT = os.path.abspath(argv[1])

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

bpy.ops.import_scene.gltf(filepath=GLB)

# Bright even light so the texture mapping is the only thing being judged
bpy.ops.object.light_add(type="SUN", location=(0, 20, 30))
sun = bpy.context.active_object
sun.data.energy = 4.0
sun.rotation_euler = (math.radians(15), 0, 0)
world = bpy.context.scene.world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[1].default_value = 0.6

# Camera: standing at the back rows looking down the rake — tier tops fill
# the frame. The glTF importer restores Blender's Z-up authoring space:
# X width, Y depth (screen wall at 0, rows 19.5-38), Z up (tier tops to ~4m).
bpy.ops.object.camera_add(location=(4.0, 32.0, 7.5))
cam = bpy.context.active_object
target = bpy.data.objects.new("aim", None)
target.location = (0, 22.0, 1.0)
bpy.context.collection.objects.link(target)
con = cam.constraints.new("TRACK_TO")
con.target = target
con.track_axis = "TRACK_NEGATIVE_Z"
con.up_axis = "UP_Y"
cam.data.lens = 24
bpy.context.scene.camera = cam

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 64
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
scene.render.resolution_x = 1600
scene.render.resolution_y = 900
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print(f"Rendered: {OUT}")

# Second render: at the viewer's feet (middle seat, looking down-forward) —
# the texel-density check the wide shot can't judge.
cam.location = (3.0, 29.0, 2.4)
target.location = (4.5, 26.0, 0.2)
close_out = OUT.replace(".png", "_close.png")
scene.render.filepath = close_out
bpy.ops.render.render(write_still=True)
print(f"Rendered: {close_out}")
