"""
Seat design studio — one theater seat, studio-lit, rendered front and back
for art direction. Iterate here; the approved builder gets ported into
theater_imax.py.

Run:  blender -b -P seat_design.py
Outputs renders/seat_front.png and renders/seat_back.png
"""

import bpy
import math
import os

_HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(_HERE, "renders")

# ------------------------------------------------------------------- helpers


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()


def set_input(node, names, value):
    for name in names if isinstance(names, (list, tuple)) else [names]:
        sock = node.inputs.get(name)
        if sock is not None:
            sock.default_value = value
            return True
    return False


def make_material(name, color, roughness=0.9, sheen=0.0, metallic=0.0, fabric_bump=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    bsdf = nodes.get("Principled BSDF")
    set_input(bsdf, "Base Color", (*color, 1.0))
    set_input(bsdf, "Roughness", roughness)
    set_input(bsdf, "Metallic", metallic)
    if sheen:
        set_input(bsdf, ["Sheen Weight", "Sheen"], sheen)
    if fabric_bump > 0:
        # Fine woven-cloth bump: high-frequency noise driving a normal bump.
        noise = nodes.new("ShaderNodeTexNoise")
        noise.inputs["Scale"].default_value = 260.0
        noise.inputs["Detail"].default_value = 4.0
        bump = nodes.new("ShaderNodeBump")
        bump.inputs["Strength"].default_value = fabric_bump
        bump.inputs["Distance"].default_value = 0.002
        links.new(noise.outputs["Fac"], bump.inputs["Height"])
        links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])
    return mat


def soft_box(name, size, location, material, rotation=(0, 0, 0),
             corner=0.03, subdiv=2, taper=0.0, bend_deg=0.0):
    """Upholstered form: box -> corner bevel -> optional taper/bend -> subsurf.
    All modifiers are baked so joins keep the geometry."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=location, rotation=rotation)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    if material:
        obj.data.materials.append(material)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)

    mod = obj.modifiers.new("Bevel", "BEVEL")
    mod.width = corner
    mod.segments = 2
    bpy.ops.object.modifier_apply(modifier=mod.name)

    if taper != 0.0:
        t = obj.modifiers.new("Taper", "SIMPLE_DEFORM")
        t.deform_method = "TAPER"
        t.factor = taper
        t.deform_axis = "Z"
        bpy.ops.object.modifier_apply(modifier=t.name)

    if bend_deg != 0.0:
        b = obj.modifiers.new("Bend", "SIMPLE_DEFORM")
        b.deform_method = "BEND"
        b.angle = math.radians(bend_deg)
        b.deform_axis = "X"
        bpy.ops.object.modifier_apply(modifier=b.name)

    s = obj.modifiers.new("Subsurf", "SUBSURF")
    s.levels = subdiv
    s.render_levels = subdiv
    bpy.ops.object.modifier_apply(modifier=s.name)
    bpy.ops.object.shade_smooth()
    return obj


def add_box(name, size, location, material, rotation=(0, 0, 0), bevel=0.0, bevel_segments=4):
    bpy.ops.mesh.primitive_cube_add(size=1, location=location, rotation=rotation)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    if material:
        obj.data.materials.append(material)
    if bevel > 0:
        # Bake scale first (so the bevel is uniform, not stretched), then bake
        # the bevel into the mesh — joins discard live modifiers.
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
        mod = obj.modifiers.new("Bevel", "BEVEL")
        mod.width = bevel
        mod.segments = bevel_segments
        bpy.ops.object.modifier_apply(modifier=mod.name)
    return obj


def add_cylinder(name, radius, depth, location, material, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_cylinder_add(radius=radius, depth=depth, location=location,
                                        rotation=rotation, vertices=24)
    obj = bpy.context.active_object
    obj.name = name
    if material:
        obj.data.materials.append(material)
    return obj

# ---------------------------------------------------------------- the seat


def build_seat(fabric, fabric_dark, frame):
    """Classic 90s cinema rocker, per blender/reference/seat_reference.png:
    one-piece rounded backrest with a stitched headrest inset, thick rolled
    cushion, black cupholders capping the armrest fronts, and open steel
    stanchion legs with floor plates. Origin at floor center, facing -y.
    """
    parts = []
    recline = math.radians(-8)  # reference chair is fairly upright

    # Steel stanchion legs: flat plates + floor mounting feet + hinge blocks
    for sx in (-0.27, 0.27):
        parts.append(add_box("leg", (0.035, 0.30, 0.42), (sx, 0.10, 0.21), frame, bevel=0.008))
        parts.append(add_box("foot", (0.14, 0.34, 0.025), (sx, 0.10, 0.0125), frame, bevel=0.006))
        parts.append(add_box("hinge", (0.045, 0.16, 0.14), (sx, 0.16, 0.44), frame, bevel=0.012))

    # Seat cushion — plush subdivided form, rolled front edge, slight droop
    parts.append(soft_box("cushion", (0.50, 0.52, 0.20), (0, 0.02, 0.46), fabric,
                          rotation=(math.radians(5), 0, 0), corner=0.06, subdiv=2))
    # Under-cushion bulge (the rolled front lip of the reference)
    parts.append(soft_box("cushionlip", (0.46, 0.14, 0.14), (0, -0.20, 0.40), fabric,
                          rotation=(math.radians(12), 0, 0), corner=0.05, subdiv=2))

    # Backrest — one-piece shell: tapered toward the top, gently bowed like
    # the reference side view, pillow-soft edges
    parts.append(soft_box("back", (0.54, 0.16, 0.88), (0, 0.315, 0.87), fabric,
                          rotation=(recline, 0, 0), corner=0.06, subdiv=2,
                          taper=-0.14, bend_deg=14))
    # Stitched headrest pillow, proud of the face
    parts.append(soft_box("headpatch", (0.30, 0.06, 0.18), (0, 0.20, 1.10), fabric_dark,
                          rotation=(recline, 0, 0), corner=0.03, subdiv=2))

    # Armrests: padded top, big black cupholder capping the front
    for sx in (-0.315, 0.315):
        parts.append(add_box("armplate", (0.035, 0.34, 0.16), (sx, 0.12, 0.52), frame, bevel=0.008))
        parts.append(soft_box("armpad", (0.105, 0.36, 0.085), (sx, 0.10, 0.63), fabric,
                              corner=0.03, subdiv=2))
        parts.append(add_cylinder("cup", 0.075, 0.11, (sx, -0.13, 0.615), frame))
        parts.append(add_cylinder("cuprim", 0.083, 0.025, (sx, -0.13, 0.665), frame))
        # dark bore suggests the cup opening
        parts.append(add_cylinder("cupbore", 0.058, 0.006, (sx, -0.13, 0.680), frame))

    for p in parts:
        p.select_set(True)
    bpy.context.view_layer.objects.active = parts[0]
    bpy.ops.object.join()
    seat = bpy.context.active_object
    seat.name = "TheaterSeat"
    # Smooth-shade the bevels (sharp edges preserved by angle threshold) —
    # without this the bevel segments render as visible facet bands.
    try:
        bpy.ops.object.shade_auto_smooth(angle=math.radians(40))
    except Exception:
        bpy.ops.object.shade_smooth()
    return seat

# ------------------------------------------------------------------- studio


def build_studio():
    floor = make_material("StudioFloor", (0.12, 0.12, 0.13), roughness=0.8)
    bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 0, 0))
    bpy.context.active_object.data.materials.append(floor)

    def area_light(name, loc, rot, energy, size):
        bpy.ops.object.light_add(type="AREA", location=loc, rotation=rot)
        light = bpy.context.active_object
        light.name = name
        light.data.energy = energy
        light.data.size = size
        return light

    # Dimmer, warmer studio — closer to how the seat reads in a dark theater,
    # and keeps the deep red from washing out to pink.
    area_light("key", (2.2, -2.6, 2.6), (math.radians(55), 0, math.radians(40)), 240, 2.2)
    area_light("fill", (-2.6, -1.8, 1.8), (math.radians(65), 0, math.radians(-55)), 80, 3.0)
    area_light("rim", (0.4, 2.8, 2.4), (math.radians(-50), math.radians(180), 0), 160, 1.8)

    world = bpy.context.scene.world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs[0].default_value = (0.02, 0.02, 0.025, 1)
        bg.inputs[1].default_value = 1.0


def render_view(name, cam_loc, look_at):
    bpy.ops.object.camera_add(location=cam_loc)
    cam = bpy.context.active_object
    cam.data.lens = 50
    # Aim via a track-to constraint — no hand-rolled euler math.
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


clear_scene()
fabric = make_material("Fabric", (0.42, 0.045, 0.028), roughness=0.9, sheen=0.2, fabric_bump=0.35)
fabric_dark = make_material("FabricDark", (0.34, 0.038, 0.025), roughness=0.92, sheen=0.15, fabric_bump=0.35)
frame = make_material("Frame", (0.025, 0.025, 0.028), roughness=0.5, metallic=0.4)
build_seat(fabric, fabric_dark, frame)
build_studio()
render_view("seat_front.png", (1.5, -1.9, 1.25), (0, 0.1, 0.65))
render_view("seat_back.png", (1.4, 2.1, 1.35), (0, 0.1, 0.75))
