"""
Floor preview — render a candidate concrete texture in theater context before
committing it to the pipeline. Low camera angle over the front floor area with
warm can-light pools, matching the reference sheet's "FRONT FLOOR AREA" shot.

Run:  blender -b -P floor_preview.py -- <texture.png> <out.png> [--seams]

The floor is one 31x45m plane with the texture mapped ONCE (no repeats),
a procedural micro-grain overlay for close-up detail, and optional faint
expansion joints. This is the same material stack build_concrete_floor will
bake, so what you approve here is what ships.
"""

import bpy
import math
import os
import sys

argv = sys.argv[sys.argv.index("--") + 1:]
TEX = os.path.abspath(argv[0])
OUT = os.path.abspath(argv[1])
SEAMS = "--seams" in argv

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

# --- Floor: room footprint, texture mapped once ---
bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 22, 0))
floor = bpy.context.active_object
floor.scale = (31, 45, 1)

mat = bpy.data.materials.new("ConcretePreview")
mat.use_nodes = True
n = mat.node_tree.nodes
l = mat.node_tree.links
bsdf = n["Principled BSDF"]
bsdf.inputs["Roughness"].default_value = 0.30

img = bpy.data.images.load(TEX)
coord = n.new("ShaderNodeTexCoord")
tex = n.new("ShaderNodeTexImage")
tex.image = img
l.new(coord.outputs["UV"], tex.inputs["Vector"])

# Micro-grain: fine procedural noise multiply — close-up detail with zero
# repetition (the 2K sheet alone is ~45px/m over this footprint).
grain = n.new("ShaderNodeTexNoise")
grain.inputs["Scale"].default_value = 900.0
grain_ramp = n.new("ShaderNodeValToRGB")
grain_ramp.color_ramp.elements[0].color = (0.96, 0.96, 0.96, 1)
grain_ramp.color_ramp.elements[1].color = (1.03, 1.03, 1.03, 1)
l.new(grain.outputs["Fac"], grain_ramp.inputs["Fac"])
gmix = n.new("ShaderNodeMix"); gmix.data_type = "RGBA"; gmix.blend_type = "MULTIPLY"
gmix.inputs["Factor"].default_value = 1.0
l.new(tex.outputs["Color"], gmix.inputs[6])
l.new(grain_ramp.outputs["Color"], gmix.inputs[7])
out_color = gmix.outputs[2]

if SEAMS:
    # Faint expansion joints across the room width every ~7.5m: barely darker,
    # slightly wavy so they read as saw cuts, not tiles.
    sep = n.new("ShaderNodeSeparateXYZ")
    l.new(coord.outputs["UV"], sep.inputs["Vector"])
    seam_wave = n.new("ShaderNodeMath"); seam_wave.operation = "MULTIPLY"
    seam_wave.inputs[1].default_value = 6.0  # 6 joints along depth
    l.new(sep.outputs["Y"], seam_wave.inputs[0])
    frac = n.new("ShaderNodeMath"); frac.operation = "FRACT"
    l.new(seam_wave.outputs[0], frac.inputs[0])
    # Narrow dark pulse at each integer crossing
    center = n.new("ShaderNodeMath"); center.operation = "SUBTRACT"
    center.inputs[1].default_value = 0.5
    l.new(frac.outputs[0], center.inputs[0])
    absn = n.new("ShaderNodeMath"); absn.operation = "ABSOLUTE"
    l.new(center.outputs[0], absn.inputs[0])
    seam = n.new("ShaderNodeMath"); seam.operation = "GREATER_THAN"
    seam.inputs[1].default_value = 0.4985  # ~2px-wide line
    l.new(absn.outputs[0], seam.inputs[0])
    seam_scale = n.new("ShaderNodeMath"); seam_scale.operation = "MULTIPLY"
    seam_scale.inputs[1].default_value = 0.35  # 35% darker in the joint
    l.new(seam.outputs[0], seam_scale.inputs[0])
    seam_fac = n.new("ShaderNodeMath"); seam_fac.operation = "SUBTRACT"
    seam_fac.inputs[0].default_value = 1.0
    l.new(seam_scale.outputs[0], seam_fac.inputs[1])
    smix = n.new("ShaderNodeMix"); smix.data_type = "RGBA"; smix.blend_type = "MULTIPLY"
    smix.inputs["Factor"].default_value = 1.0
    seam_rgb = n.new("ShaderNodeCombineColor")
    l.new(seam_fac.outputs[0], seam_rgb.inputs[0])
    l.new(seam_fac.outputs[0], seam_rgb.inputs[1])
    l.new(seam_fac.outputs[0], seam_rgb.inputs[2])
    l.new(out_color, smix.inputs[6])
    l.new(seam_rgb.outputs[0], smix.inputs[7])
    out_color = smix.outputs[2]

l.new(out_color, bsdf.inputs["Base Color"])
floor.data.materials.append(mat)

# --- Dark walls + a few seat-shaped blockers for context ---
wallmat = bpy.data.materials.new("Wall")
wallmat.use_nodes = True
wallmat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.02, 0.02, 0.025, 1)
for x in (-15.5, 15.5):
    bpy.ops.mesh.primitive_plane_add(size=1, location=(x, 22, 9), rotation=(0, math.radians(90), 0))
    w = bpy.context.active_object
    w.scale = (18, 45, 1)
    w.data.materials.append(wallmat)

seatmat = bpy.data.materials.new("Seat")
seatmat.use_nodes = True
seatmat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.30, 0.05, 0.07, 1)
for row_y in (20, 21.2):
    for sx in range(-6, 7, 2):
        bpy.ops.mesh.primitive_cube_add(size=1, location=(sx * 0.75, row_y, 0.5))
        s = bpy.context.active_object
        s.scale = (0.64, 0.55, 1.0)
        s.data.materials.append(seatmat)

# --- Warm can-light pools like the reference (3000K spots from above) ---
for ly in (6, 11, 16):
    for lx in (-6, 0, 6):
        bpy.ops.object.light_add(type="SPOT", location=(lx, ly, 17.5))
        light = bpy.context.active_object
        light.data.color = (1.0, 0.72, 0.45)
        light.data.energy = 9000
        light.data.spot_size = math.radians(30)
        light.data.spot_blend = 0.9
        light.data.shadow_soft_size = 0.4

# Dim red screen-glow wash from the front
bpy.ops.object.light_add(type="AREA", location=(0, -0.5, 8), rotation=(math.radians(-75), 0, 0))
glow = bpy.context.active_object
glow.data.color = (1.0, 0.25, 0.2)
glow.data.energy = 2600
glow.data.size = 22

world = bpy.context.scene.world
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[1].default_value = 0.0

# --- Camera: low over the front floor, looking toward the seats ---
bpy.ops.object.camera_add(location=(0, 3.0, 1.4))
cam = bpy.context.active_object
target = bpy.data.objects.new("aim", None)
target.location = (0, 14, 0.3)
bpy.context.collection.objects.link(target)
con = cam.constraints.new("TRACK_TO")
con.target = target
con.track_axis = "TRACK_NEGATIVE_Z"
con.up_axis = "UP_Y"
cam.data.lens = 28
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
scene.view_settings.exposure = -1.3
scene.render.resolution_x = 1600
scene.render.resolution_y = 900
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print(f"Rendered: {OUT}")
