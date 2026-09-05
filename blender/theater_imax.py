"""
JellyQuest IMAX theater generator.

Run headless:  blender -b -P theater_imax.py
Outputs a preview render to blender/renders/imax_preview.png
(and later, a GLB export for the app).

Dimensions mirror the app's IMAX preset (TheaterExperiences.kt):
  screen 22m x 15.4m, bottom at 0.9m; seats 19.5m..38m at a 12 deg rake;
  room ~26m wide at the screen, 18m ceiling, back wall ~5m behind last row.

Coordinate system: screen wall at y=0, +y = toward the back of the house,
z up, x across the room. The Quest app will translate on import.

Modeling philosophy (per project direction): the camera only ever sits at
the three seat positions, so detail goes where those sightlines look —
seat BACKS near the camera get full geometry, distant rows are simplified,
and nothing invisible is modeled.
"""

import bpy
import math
import os

# ---------------------------------------------------------------- parameters

SCREEN_W = 22.0
SCREEN_H = 15.4
SCREEN_BOTTOM = 0.9

# Aspect ratio of the film being "shown" in the preview. The physical canvas
# is 1.43:1 (true IMAX 15/70); content letterboxes into it exactly like the
# app's ScreenFit does. Try 2.39 (scope), 1.90 (IMAX digital), 1.85, 1.78.
CONTENT_ASPECT = 2.39

ROW_FRONT = 19.5      # first row distance from screen
ROW_BACK = 38.0       # last row distance
ROW_PITCH = 1.15      # spacing between rows
RAKE = math.tan(math.radians(12))

ROOM_HALF_W_FRONT = 13.0
ROOM_HALF_W_BACK = 14.5
ROOM_DEPTH = 43.0     # back wall distance from screen
CEILING = 18.0

SEAT_PITCH = 0.62     # arc distance between seat centers
AISLE_CENTERS = [-4.5, 4.5]
AISLE_HALF = 0.65

CAMERA_ROW = 28.0     # "Middle" seat
DETAIL_RADIUS = 5.0   # rows within this of the camera get detailed seats

RENDER_OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "renders", "imax_preview.png")

# ------------------------------------------------------------------- helpers


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()
    for block in (bpy.data.meshes, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
        for item in list(block):
            if item.users == 0:
                block.remove(item)


def set_input(node, names, value):
    """Set a node input by any of several names (API names drift between versions)."""
    for name in names if isinstance(names, (list, tuple)) else [names]:
        sock = node.inputs.get(name)
        if sock is not None:
            sock.default_value = value
            return True
    return False


def make_material(name, color, roughness=0.9, emission=None, emission_strength=0.0, sheen=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    set_input(bsdf, "Base Color", (*color, 1.0))
    set_input(bsdf, "Roughness", roughness)
    if sheen:
        set_input(bsdf, ["Sheen Weight", "Sheen"], sheen)
    if emission is not None:
        set_input(bsdf, ["Emission Color", "Emission"], (*emission, 1.0))
        set_input(bsdf, "Emission Strength", emission_strength)
    return mat


def make_screen_material():
    """Emissive 'movie frame': warm center fading to cool edges, with soft blotches."""
    mat = bpy.data.materials.new("ScreenEmit")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    nodes.clear()

    out = nodes.new("ShaderNodeOutputMaterial")
    emit = nodes.new("ShaderNodeEmission")
    ramp = nodes.new("ShaderNodeValToRGB")
    grad = nodes.new("ShaderNodeTexGradient")
    noise = nodes.new("ShaderNodeTexNoise")
    mixc = nodes.new("ShaderNodeMix")
    coord = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeMapping")

    grad.gradient_type = "SPHERICAL"
    mapping.inputs["Location"].default_value = (-0.5, -0.55, 0.0)
    mapping.inputs["Scale"].default_value = (1.0, 1.6, 1.0)

    ramp.color_ramp.elements[0].position = 0.0
    ramp.color_ramp.elements[0].color = (0.02, 0.05, 0.12, 1.0)   # deep blue edges
    ramp.color_ramp.elements[1].position = 0.85
    ramp.color_ramp.elements[1].color = (1.0, 0.75, 0.45, 1.0)    # warm bright center

    noise.inputs["Scale"].default_value = 3.0
    mixc.data_type = "RGBA"
    set_input(mixc, ["Factor", "Fac"], 0.15)

    links.new(coord.outputs["UV"], mapping.inputs["Vector"])
    links.new(mapping.outputs["Vector"], grad.inputs["Vector"])
    links.new(grad.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], mixc.inputs[6])
    links.new(noise.outputs["Color"], mixc.inputs[7])
    links.new(mixc.outputs[2], emit.inputs["Color"])
    emit.inputs["Strength"].default_value = 2.6
    links.new(emit.outputs["Emission"], out.inputs["Surface"])
    return mat


def add_box(name, size, location, material, rotation=(0, 0, 0), bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=location, rotation=rotation)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (size[0], size[1], size[2])
    if material:
        obj.data.materials.append(material)
    if bevel > 0:
        mod = obj.modifiers.new("Bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 3
    return obj


def add_plane(name, verts, material):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], [list(range(len(verts)))])
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    if material:
        obj.data.materials.append(material)
    return obj

# ---------------------------------------------------------------- seat build


def build_seat_template(name, mats, detailed):
    """One theater seat, origin at floor center, facing -y (toward screen)."""
    parts = []
    fabric, frame = mats
    # pedestal
    parts.append(add_box(f"{name}_ped", (0.5, 0.42, 0.34), (0, 0.05, 0.17), frame))
    # cushion
    parts.append(add_box(
        f"{name}_cushion", (0.52, 0.5, 0.14), (0, 0.0, 0.42), fabric,
        bevel=0.06 if detailed else 0.03,
    ))
    # backrest, tilted back ~10 deg
    parts.append(add_box(
        f"{name}_back", (0.52, 0.14, 0.72), (0, 0.28, 0.80), fabric,
        rotation=(math.radians(-10), 0, 0),
        bevel=0.06 if detailed else 0.03,
    ))
    if detailed:
        # headrest bump gives the seat back a recognizable silhouette
        parts.append(add_box(
            f"{name}_head", (0.42, 0.12, 0.20), (0, 0.34, 1.16), fabric,
            rotation=(math.radians(-10), 0, 0), bevel=0.05,
        ))
        for sx in (-0.30, 0.30):
            parts.append(add_box(f"{name}_arm", (0.07, 0.48, 0.09), (sx, 0.06, 0.60), frame, bevel=0.02))

    for p in parts:
        p.select_set(True)
    bpy.context.view_layer.objects.active = parts[0]
    bpy.ops.object.join()
    seat = bpy.context.active_object
    seat.name = name
    return seat


def place_seat(template, x, y, z, face_angle):
    dup = template.copy()  # shares mesh data — cheap instancing
    dup.location = (x, y, z)
    dup.rotation_euler = (0, 0, face_angle)
    bpy.context.collection.objects.link(dup)

# -------------------------------------------------------------------- scene


def build():
    clear_scene()

    fabric = make_material("SeatFabric", (0.30, 0.045, 0.055), roughness=0.85, sheen=0.3)
    frame = make_material("SeatFrame", (0.05, 0.05, 0.055), roughness=0.6)
    carpet = make_material("Carpet", (0.045, 0.028, 0.025), roughness=1.0)
    curtain = make_material("Curtain", (0.16, 0.02, 0.035), roughness=0.9, sheen=0.4)
    wall = make_material("WallFabric", (0.045, 0.05, 0.085), roughness=0.95)
    baffle = make_material("Baffle", (0.06, 0.07, 0.12), roughness=0.95)
    ceilmat = make_material("Ceiling", (0.03, 0.03, 0.035), roughness=1.0)
    black = make_material("MaskBlack", (0.012, 0.012, 0.014), roughness=0.95)
    amber = make_material("AisleLight", (0.2, 0.1, 0.02), emission=(1.0, 0.55, 0.15), emission_strength=8.0)
    downlight = make_material("Downlight", (0.1, 0.1, 0.1), emission=(1.0, 0.8, 0.6), emission_strength=25.0)
    exitgreen = make_material("ExitSign", (0.02, 0.1, 0.02), emission=(0.2, 1.0, 0.3), emission_strength=6.0)

    # Screen canvas: full 1.43:1 IMAX field as near-black masking, with the
    # emissive "film" letterboxed inside at CONTENT_ASPECT — mirroring how the
    # app fits each movie's native ratio into the screen.
    canvas_aspect = SCREEN_W / SCREEN_H
    if CONTENT_ASPECT >= canvas_aspect:
        content_w, content_h = SCREEN_W, SCREEN_W / CONTENT_ASPECT
    else:
        content_h, content_w = SCREEN_H, SCREEN_H * CONTENT_ASPECT

    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0.02, SCREEN_BOTTOM + SCREEN_H / 2),
                                     rotation=(math.radians(90), 0, 0))
    canvas = bpy.context.active_object
    canvas.name = "ScreenCanvas"
    canvas.scale = (SCREEN_W, SCREEN_H, 1)
    canvas.data.materials.append(black)

    # Content plane sits proud of the masking canvas (toward the viewer).
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, 0.08, SCREEN_BOTTOM + SCREEN_H / 2),
                                     rotation=(math.radians(90), 0, 0))
    screen = bpy.context.active_object
    screen.name = "Screen"
    screen.scale = (content_w, content_h, 1)
    screen.data.materials.append(make_screen_material())

    add_plane("ScreenWall", [
        (-ROOM_HALF_W_FRONT, 0, 0), (ROOM_HALF_W_FRONT, 0, 0),
        (ROOM_HALF_W_FRONT, 0, CEILING), (-ROOM_HALF_W_FRONT, 0, CEILING),
    ], black)

    # Stage apron
    add_box("Stage", (SCREEN_W * 0.92, 2.2, SCREEN_BOTTOM), (0, 1.1, SCREEN_BOTTOM / 2), black)

    # Floor, ceiling, back wall, angled side walls
    add_plane("Floor", [
        (-ROOM_HALF_W_FRONT, 0, 0), (ROOM_HALF_W_FRONT, 0, 0),
        (ROOM_HALF_W_BACK, ROOM_DEPTH, 0), (-ROOM_HALF_W_BACK, ROOM_DEPTH, 0),
    ], carpet)
    add_plane("CeilingP", [
        (-ROOM_HALF_W_FRONT, 0, CEILING), (ROOM_HALF_W_FRONT, 0, CEILING),
        (ROOM_HALF_W_BACK, ROOM_DEPTH, CEILING), (-ROOM_HALF_W_BACK, ROOM_DEPTH, CEILING),
    ], ceilmat)
    add_plane("BackWall", [
        (-ROOM_HALF_W_BACK, ROOM_DEPTH, 0), (ROOM_HALF_W_BACK, ROOM_DEPTH, 0),
        (ROOM_HALF_W_BACK, ROOM_DEPTH, CEILING), (-ROOM_HALF_W_BACK, ROOM_DEPTH, CEILING),
    ], wall)
    for side in (-1, 1):
        add_plane(f"SideWall{side}", [
            (side * ROOM_HALF_W_FRONT, 0, 0), (side * ROOM_HALF_W_BACK, ROOM_DEPTH, 0),
            (side * ROOM_HALF_W_BACK, ROOM_DEPTH, CEILING), (side * ROOM_HALF_W_FRONT, 0, CEILING),
        ], wall)

    # Projection booth window on the back wall
    add_box("Booth", (3.0, 0.2, 1.2), (0, ROOM_DEPTH - 0.11, RAKE * (ROW_BACK - ROW_FRONT) + 4.5),
            make_material("BoothGlass", (0.02, 0.02, 0.03), roughness=0.2))

    # Side wall acoustic baffles
    wall_angle = math.atan2(ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT, ROOM_DEPTH)
    for side in (-1, 1):
        for i in range(8):
            t = (i + 0.5) / 8.0
            y = 4 + t * (ROOM_DEPTH - 8)
            half_w = ROOM_HALF_W_FRONT + (ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT) * (y / ROOM_DEPTH)
            add_box(f"Baffle{side}_{i}", (0.25, 2.2, 7.0), (side * (half_w - 0.15), y, 1.5 + 3.5),
                    baffle, rotation=(0, 0, -side * wall_angle))

    # Pleated curtains flanking the screen — alternating-depth flutes
    for side in (-1, 1):
        base_x = side * (SCREEN_W / 2 + 0.9)
        for i in range(7):
            fx = base_x + side * i * 0.22
            fy = 0.55 + (0.18 if i % 2 == 0 else 0.38)
            add_box(f"Curtain{side}_{i}", (0.24, 0.35, SCREEN_H + 1.2),
                    (fx, fy, SCREEN_BOTTOM + (SCREEN_H + 1.2) / 2 - 0.3), curtain)

    # Exit signs flanking the screen
    for side in (-1, 1):
        add_box(f"Exit{side}", (0.55, 0.12, 0.28), (side * (ROOM_HALF_W_FRONT - 1.2), 0.8, 2.6), exitgreen)

    # Ceiling downlights (sparse, warm, dim house lights)
    for iy in range(4):
        for ix in (-6.0, 0.0, 6.0):
            y = 12 + iy * 8
            add_box(f"Down{ix}_{iy}", (0.4, 0.4, 0.06), (ix, y, CEILING - 0.05), downlight)

    # --- Seating: curved raked rows with aisles ---
    detailed = build_seat_template("SeatDetailed", (fabric, frame), detailed=True)
    simple = build_seat_template("SeatSimple", (fabric, frame), detailed=False)
    # Park the templates out of sight; only their copies populate the house.
    detailed.location = (0, -100, 0)
    simple.location = (0, -100, 0)

    row_count = 0
    d = ROW_FRONT
    while d <= ROW_BACK + 0.01:
        rise = RAKE * (d - ROW_FRONT)
        half_row = min(ROOM_HALF_W_FRONT - 2.0, d * math.radians(32))  # cap by wall and arc
        template = detailed if abs(d - CAMERA_ROW) <= DETAIL_RADIUS else simple

        # Tier platform (a step per row) + aisle step lights every other row
        add_box(f"Tier{row_count}", (2 * half_row + 2.5, ROW_PITCH, max(rise, 0.04)),
                (0, d, max(rise, 0.04) / 2), carpet)
        if row_count % 2 == 0:
            for ax in AISLE_CENTERS:
                add_box(f"Step{row_count}_{ax}", (1.0, 0.1, 0.035),
                        (ax, d - ROW_PITCH / 2 + 0.06, rise + 0.017), amber)

        # Seats along an arc centered on the screen
        n = int((2 * half_row) / SEAT_PITCH)
        for i in range(n):
            arc = (i - (n - 1) / 2) * SEAT_PITCH
            theta = arc / d
            x = d * math.sin(theta)
            y = d * math.cos(theta)
            if any(abs(x - a) < AISLE_HALF + 0.3 for a in AISLE_CENTERS):
                continue
            if abs(d - CAMERA_ROW) < 0.6 and abs(x) < 0.6:
                continue  # the viewer's own seat stays empty
            place_seat(template, x, y, rise, math.pi - theta)
        d += ROW_PITCH
        row_count += 1

    # --- Camera at the Middle seat ---
    cam_rise = RAKE * (CAMERA_ROW - ROW_FRONT)
    bpy.ops.object.camera_add(location=(0, CAMERA_ROW, cam_rise + 1.25))
    cam = bpy.context.active_object
    cam.data.lens = 16
    cam.data.clip_end = 200
    look_z = SCREEN_BOTTOM + SCREEN_H * 0.45
    dz = look_z - (cam_rise + 1.25)
    cam.rotation_euler = (math.radians(90) + math.atan2(dz, CAMERA_ROW), 0, math.radians(180))
    bpy.context.scene.camera = cam

    # World: black void
    bpy.context.scene.world.use_nodes = True
    bg = bpy.context.scene.world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs[0].default_value = (0, 0, 0, 1)
        bg.inputs[1].default_value = 0.0


def render():
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 128
    scene.cycles.use_denoising = True
    try:
        # Prefer GPU when available; CPU otherwise.
        prefs = bpy.context.preferences.addons["cycles"].preferences
        for backend in ("OPTIX", "CUDA", "HIP"):
            try:
                prefs.compute_device_type = backend
                prefs.get_devices()
                if any(dev.type != "CPU" for dev in prefs.devices):
                    for dev in prefs.devices:
                        dev.use = True
                    scene.cycles.device = "GPU"
                    print(f"Cycles using GPU backend: {backend}")
                    break
            except Exception:
                continue
    except Exception as e:
        print(f"GPU setup skipped: {e}")

    scene.render.resolution_x = 1600
    scene.render.resolution_y = 900
    scene.view_settings.exposure = 0.15
    os.makedirs(os.path.dirname(RENDER_OUT), exist_ok=True)
    scene.render.filepath = RENDER_OUT
    bpy.ops.render.render(write_still=True)
    print(f"Rendered: {RENDER_OUT}")


build()
render()
