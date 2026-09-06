"""
Sconce glow bake — simulate the sconce's light physically in Cycles and bake
the wall scatter into a decal texture.

Rig: a warm ~3500K core plus four gel-tinted soft spotlights at the reference
beam angles (red/blue up +/-15deg narrow, green/amber down +/-40deg wide),
thrown against a white wall. The path tracer computes penumbra, falloff, and
color blending; the render becomes an emissive decal the theater applies at
every sconce. Run: blender -b -P sconce_glow_bake.py
Outputs renders/sconce_glow_raw.png (RGB; alpha added by ffmpeg afterwards).

The captured wall area is GLOW_W x GLOW_H meters, fixture at FIXTURE_V from
the bottom — theater_imax.py must place the decal quad with the same layout.
"""

import bpy
import math
import os

_HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(_HERE, "renders", "sconce_glow_raw.png")

# Generous canvas so the beams taper to nothing well inside the decal —
# a tight canvas amputates the throw at the quad edge ("rendered in a box").
GLOW_W = 4.2
GLOW_H = 5.6
FIXTURE_V = 2.0  # fixture height above the captured area's bottom edge

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

# White wall plane in the YZ plane (normal +x), sized to the capture area
bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0, GLOW_H / 2), rotation=(0, math.radians(90), 0))
wall = bpy.context.active_object
wall.scale = (GLOW_H, GLOW_W, 1)
mat = bpy.data.materials.new("Wall")
mat.use_nodes = True
mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (1, 1, 1, 1)
mat.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 1.0
wall.data.materials.append(mat)

# Light rig at the fixture point, just off the wall
ORIGIN = (0.10, 0.0, FIXTURE_V)
WARM = (1.0, 0.72, 0.45)  # ~3500K


def gel(color, warm_mix=0.22):
    return tuple(c * (1 - warm_mix) + w * warm_mix for c, w in zip(color, WARM))


def spot(name, color, tilt_deg, spread_deg, up, power):
    bpy.ops.object.light_add(type="SPOT", location=ORIGIN)
    light = bpy.context.active_object
    light.name = name
    light.data.color = color
    light.data.energy = power
    light.data.spot_size = math.radians(spread_deg * 2.6)
    light.data.spot_blend = 1.0          # fully feathered cone
    light.data.shadow_soft_size = 0.09   # fat source = soft penumbra
    a = math.radians(tilt_deg)
    zdir = 1.0 if up else -1.0
    # Aim along the wall plane: tilt from vertical, hugging the wall
    direction = (-0.08, math.sin(a), zdir * math.cos(a))
    length = math.sqrt(sum(d * d for d in direction))
    d = [c / length for c in direction]
    # Point the spot's -Z at `direction`
    pitch = math.acos(-d[2] if not up else d[2])
    light.rotation_euler = (
        math.radians(180) if not up else 0,
        0,
        0,
    )
    # Simpler: use a track-to constraint at a far point along the direction
    target = bpy.data.objects.new(f"aim_{name}", None)
    target.location = tuple(o + 3 * di for o, di in zip(ORIGIN, d))
    bpy.context.collection.objects.link(target)
    con = light.constraints.new("TRACK_TO")
    con.target = target
    con.track_axis = "TRACK_NEGATIVE_Z"
    con.up_axis = "UP_Y"
    return light


# Slightly shorter throws so the inverse-square taper visibly completes
spot("red", gel((1.0, 0.05, 0.03)), -15, 6, True, 78)
spot("blue", gel((0.15, 0.25, 1.0)), 15, 6, True, 78)
spot("green", gel((0.10, 0.85, 0.25)), -40, 8, False, 70)
spot("amber", gel((1.0, 0.45, 0.08)), 40, 8, False, 70)

# Warm core: small bare-bulb glow at the fixture mouth
bpy.ops.object.light_add(type="POINT", location=(0.06, 0, FIXTURE_V))
core = bpy.context.active_object
core.data.color = WARM
core.data.energy = 4
core.data.shadow_soft_size = 0.06

# World: black
world = bpy.context.scene.world
world.use_nodes = True
bg = world.node_tree.nodes.get("Background")
if bg:
    bg.inputs[1].default_value = 0.0

# Orthographic camera facing the wall
bpy.ops.object.camera_add(location=(2.5, 0, GLOW_H / 2), rotation=(0, math.radians(90), 0))
cam = bpy.context.active_object
cam.data.type = "ORTHO"
cam.data.ortho_scale = GLOW_H
cam.rotation_euler = (math.radians(90), 0, math.radians(90))
bpy.context.scene.camera = cam

scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.samples = 256
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
scene.render.resolution_x = int(512 * GLOW_W / GLOW_H)
scene.render.resolution_y = 512
scene.view_settings.view_transform = "Standard"  # keep colors saturated
os.makedirs(os.path.dirname(OUT), exist_ok=True)
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print(f"Rendered: {OUT}")
