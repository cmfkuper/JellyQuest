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
DETAIL_RADIUS = 2.5   # rows within this of the camera get the high-LOD seat

# AI-generated hero seats (Higgsfield multi-image-to-3D from the reference
# sheet), generated NATIVELY at each LOD's polycount with should_remesh —
# no local decimation or texture re-baking, which only ever butchered them.
_REF = os.path.dirname(os.path.abspath(__file__))
AI_SEAT_NEAR_PATH = os.path.join(_REF, "seat_ai_near.glb")  # ~5k tris
AI_SEAT_FAR_PATH = os.path.join(_REF, "seat_ai_far.glb")    # ~1.2k tris
# Real cinema-seat envelope; the AI model is normalized per-axis to this,
# because its native proportions are much chunkier than a real chair.
AI_SEAT_DIMS = (0.72, 0.80, 1.05)  # width, depth, height in meters
SEAT_ARC_PITCH = 0.70     # spacing tuned to the normalized width (shared-arm look)

# The app's three seat positions — each gets an empty spot at the aisle center
# so the viewer never spawns inside a seat.
VIEWER_ROWS = [19.5, 28.0, 38.0]

_HERE = os.path.dirname(os.path.abspath(__file__))
RENDER_OUT = os.path.join(_HERE, "renders", "imax_preview.png")
GLB_OUT = os.path.normpath(os.path.join(_HERE, "..", "app", "src", "main", "assets", "cinema_imax.glb"))

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
    # cushion (simple seats stay sharp-edged — poly budget goes to near rows)
    parts.append(add_box(
        f"{name}_cushion", (0.52, 0.5, 0.14), (0, 0.0, 0.42), fabric,
        bevel=0.06 if detailed else 0.0,
    ))
    # backrest, tilted back ~10 deg
    parts.append(add_box(
        f"{name}_back", (0.52, 0.14, 0.72), (0, 0.28, 0.80), fabric,
        rotation=(math.radians(-10), 0, 0),
        bevel=0.06 if detailed else 0.0,
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


AI_CURTAIN_PATH = os.path.join(_REF, "curtain_ai.glb")
# Panel sizing per the reference sheet (folds 4-6" apart) scaled to flank the
# IMAX screen; vertical stretch is invisible on vertical folds.
# Width fills the gap between screen edge and side wall (~2m).
CURTAIN_DIMS = (2.4, 0.5, SCREEN_BOTTOM + SCREEN_H + 1.4)

AI_SCONCE_PATH = os.path.join(_REF, "sconce_ai.glb")
# Scaled up from the authentic 6" so the fixture reads at its 10.5m mount
# height — otherwise the glow floats sourceless.
SCONCE_DIMS = (0.24, 0.12, 0.24)
# Per the late-90s Regal reference: sconces mount HIGH on the upper wall,
# above the baffle band, crowning the room — short fans reaching toward the
# ceiling, long colorful throws washing down the upper wall.
SCONCE_HEIGHT = 10.5
SCONCE_ROWS = [8.0, 14.5, 21.0, 27.5, 34.0]


def load_ai_seat_template(name, glb_path, dims=None, reorient_flat=False):
    """Import an AI asset GLB and normalize it: origin at floor center,
    per-axis real-world dimensions. With reorient_flat, the mesh's thin axis
    is DETECTED (smallest bbox extent = thickness) and rotated to face -y,
    tallest extent up — instead of trusting the model's authored orientation."""
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=glb_path)
    imported = [o for o in bpy.data.objects if o not in before]
    meshes = [o for o in imported if o.type == "MESH"]
    for o in imported:
        o.select_set(o in meshes)
    bpy.context.view_layer.objects.active = meshes[0]
    if len(meshes) > 1:
        bpy.ops.object.join()
    obj = bpy.context.view_layer.objects.active
    for o in imported:
        if o.type != "MESH" and o.name in bpy.data.objects:
            bpy.data.objects.remove(o, do_unlink=True)
    obj.name = name

    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

    def extents():
        exs = [v.co.x for v in obj.data.vertices]
        eys = [v.co.y for v in obj.data.vertices]
        ezs = [v.co.z for v in obj.data.vertices]
        return (max(exs) - min(exs), max(eys) - min(eys), max(ezs) - min(ezs))

    if reorient_flat:
        ex, ey, ez = extents()
        thin = min((ex, "x"), (ey, "y"), (ez, "z"))[1]
        if thin == "z":
            obj.rotation_euler = (math.radians(90), 0, 0)   # face-up -> face -y
            bpy.ops.object.transform_apply(rotation=True)
        elif thin == "x":
            obj.rotation_euler = (0, 0, math.radians(90))   # edge-on -> face -y
            bpy.ops.object.transform_apply(rotation=True)
        ex, ey, ez = extents()
        if ex > ez:  # tallest remaining extent goes vertical
            obj.rotation_euler = (0, math.radians(90), 0)
            bpy.ops.object.transform_apply(rotation=True)
        print(f"reorient_flat '{name}': thin axis was {thin}, extents now "
              f"{tuple(round(e, 3) for e in extents())}")

    xs = [v.co.x for v in obj.data.vertices]
    ys = [v.co.y for v in obj.data.vertices]
    zs = [v.co.z for v in obj.data.vertices]
    obj.location = (-(max(xs) + min(xs)) / 2, -(max(ys) + min(ys)) / 2, -min(zs))
    bpy.ops.object.transform_apply(location=True)
    # Per-axis normalization to a real seat envelope — the AI model's native
    # proportions are far too wide and deep for row spacing.
    dims = dims or AI_SEAT_DIMS
    obj.scale = (
        dims[0] / (max(xs) - min(xs)),
        dims[1] / (max(ys) - min(ys)),
        dims[2] / (max(zs) - min(zs)),
    )
    bpy.ops.object.transform_apply(scale=True)
    tris = sum(len(p.vertices) - 2 for p in obj.data.polygons)
    print(f"AI asset '{name}': native tris={tris} dims normalized to {dims}")
    return obj


def rotated_single_user(template, name, yaw_rad):
    """Copy with its own mesh data and the yaw BAKED INTO THE VERTICES —
    the glTF export drops object rotations on shared-mesh copies, which is
    how wall panels ended up facing down the room instead of into it."""
    import mathutils
    obj = template.copy()
    obj.data = template.data.copy()
    obj.name = name
    bpy.context.collection.objects.link(obj)
    # Rotate the MESH DATA directly — context-free, unlike the selection-based
    # transform_apply operator, which silently misses freshly linked objects
    # in background mode.
    obj.data.transform(mathutils.Matrix.Rotation(yaw_rad, 4, "Z"))
    obj.data.update()
    obj.location = (0, -100, 0)
    return obj


def add_fan_blade(name, apex, angle_deg, spread_deg, length, material, up):
    """One light-blade triangle in the wall plane. Angles are measured from
    vertical, tilting along the wall (room-depth) axis; matches the reference
    sheet's beam chart."""
    a = math.radians(angle_deg)
    s = math.radians(spread_deg)
    zsign = 1.0 if up else -1.0

    def corner(theta):
        return (
            apex[0],
            apex[1] + length * math.sin(theta),
            apex[2] + zsign * length * math.cos(theta),
        )

    verts = [apex, corner(a - s), corner(a + s)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], [[0, 1, 2]])
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(material)
    return obj


def soft_box(name, size, location, material, rotation=(0, 0, 0), corner=0.03, subdiv=1):
    """Softly rounded box (bevel + subsurf, baked in). Ported from seat_design."""
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
    s = obj.modifiers.new("Subsurf", "SUBSURF")
    s.levels = subdiv
    s.render_levels = subdiv
    bpy.ops.object.modifier_apply(modifier=s.name)
    bpy.ops.object.shade_smooth()
    return obj


def bake_fabric_textures(tex_size=512):
    """Bake a woven acoustic-fabric material (procedural noise) into diffuse
    and tangent-normal images for use in the exported GLB."""
    bpy.ops.mesh.primitive_plane_add(size=1, location=(0, -200, 0))
    plane = bpy.context.active_object
    mat = bpy.data.materials.new("FabricBakeSrc")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    bsdf = nodes["Principled BSDF"]

    noise = nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 210.0
    noise.inputs["Detail"].default_value = 6.0
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.045, 0.065, 0.125, 1)
    ramp.color_ramp.elements[1].color = (0.085, 0.11, 0.185, 1)
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.5
    bump.inputs["Distance"].default_value = 0.004
    links.new(noise.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])
    plane.data.materials.append(mat)

    imgs = {}
    for kind, bake_type, pass_filter in (
        ("diffuse", "DIFFUSE", {"COLOR"}),
        ("normal", "NORMAL", None),
    ):
        img = bpy.data.images.new(f"baffle_{kind}", tex_size, tex_size)
        tex_node = nodes.new("ShaderNodeTexImage")
        tex_node.image = img
        nodes.active = tex_node
        bpy.context.scene.render.engine = "CYCLES"
        bpy.context.scene.cycles.samples = 16
        bpy.ops.object.select_all(action="DESELECT")
        plane.select_set(True)
        bpy.context.view_layer.objects.active = plane
        kwargs = {"type": bake_type, "use_selected_to_active": False}
        if pass_filter:
            kwargs["pass_filter"] = pass_filter
        bpy.ops.object.bake(**kwargs)
        img.pack()
        imgs[kind] = img
    bpy.data.objects.remove(plane, do_unlink=True)
    return imgs


def build_baffles():
    """Acoustic panels. Preferred: the AI-generated hyper-detail panel
    (reference-sheet colorway chosen via PANEL_VARIANT env: red|grey),
    normalized per-axis and stacked along the walls. Fallback: the framed
    procedural panel with baked woven fabric."""
    variant = os.environ.get("PANEL_VARIANT", "red")
    ai_path = os.path.join(_REF, f"panel_ai_{variant}.glb")
    if os.path.exists(ai_path):
        base = load_ai_seat_template("BaffleTemplate", ai_path,
                                     dims=(2.2, 0.05, 6.6), reorient_flat=True)
        base.location = (0, -100, 0)
        # Pre-rotated per wall, yaw baked into vertices (see rotated_single_user)
        tmpl = {
            -1: rotated_single_user(base, "BaffleTmplL", math.radians(90)),
            1: rotated_single_user(base, "BaffleTmplR", math.radians(-90)),
        }
        bpy.data.objects.remove(base, do_unlink=True)
        idx = 0
        for side in (-1, 1):
            for i in range(8):
                t = (i + 0.5) / 8.0
                y = 4 + t * (ROOM_DEPTH - 8)
                half_w = ROOM_HALF_W_FRONT + (ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT) * (y / ROOM_DEPTH)
                panel = tmpl[side].copy()
                panel.name = f"Baffle{idx}"
                panel.location = (side * (half_w - 0.10), y, 1.6)
                bpy.context.collection.objects.link(panel)
                idx += 1
        for o in tmpl.values():
            bpy.data.objects.remove(o, do_unlink=True)
        print(f"Baffles: AI panel variant '{variant}'")
        return

    imgs = bake_fabric_textures()

    frame_mat = make_material("BaffleFrame", (0.028, 0.028, 0.032), roughness=0.6)
    face_mat = bpy.data.materials.new("BaffleFabric")
    face_mat.use_nodes = True
    nodes = face_mat.node_tree.nodes
    links = face_mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    set_input(bsdf, "Roughness", 0.95)
    dtex = nodes.new("ShaderNodeTexImage")
    dtex.image = imgs["diffuse"]
    links.new(dtex.outputs["Color"], bsdf.inputs["Base Color"])
    ntex = nodes.new("ShaderNodeTexImage")
    ntex.image = imgs["normal"]
    ntex.image.colorspace_settings.name = "Non-Color"
    nmap = nodes.new("ShaderNodeNormalMap")
    links.new(ntex.outputs["Color"], nmap.inputs["Color"])
    links.new(nmap.outputs["Normal"], bsdf.inputs["Normal"])

    wall_angle = math.atan2(ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT, ROOM_DEPTH)
    idx = 0
    for side in (-1, 1):
        for i in range(8):
            t = (i + 0.5) / 8.0
            y = 4 + t * (ROOM_DEPTH - 8)
            half_w = ROOM_HALF_W_FRONT + (ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT) * (y / ROOM_DEPTH)
            rot = (0, 0, -side * wall_angle)
            x = side * (half_w - 0.16)
            # Frame: shallow box proud of the wall
            add_box(f"BaffleFrame{idx}", (0.20, 2.4, 7.0), (x, y, 5.0),
                    frame_mat, rotation=rot, bevel=0.02)
            # Fabric face: softly domed, floats just proud of the frame
            face = soft_box(f"Baffle{idx}", (0.14, 2.15, 6.75),
                            (x - side * 0.09, y, 5.0), face_mat,
                            rotation=rot, corner=0.05, subdiv=1)
            idx += 1


def build_concrete_floor():
    """Poured-concrete floor: the reference texture sampled at two scales and
    rotations, blended through noise, BAKED into one continuous 2048px sheet
    over the whole footprint — no visible tiling, subtle aberration like a
    real slab. Returns the concrete material so the tiers can wear it too."""
    tex_path = os.path.join(_REF, "reference", "floor_tex.png")
    if not os.path.exists(tex_path):
        return None
    img = bpy.data.images.load(tex_path)
    img.pack()

    # The floor slab (gets the baked result); Tier prefix keeps it in export.
    verts = [(-15.5, -1.0, 0.02), (15.5, -1.0, 0.02), (15.5, 44.0, 0.02), (-15.5, 44.0, 0.02)]
    mesh = bpy.data.meshes.new("TierFloorMesh")
    mesh.from_pydata(verts, [], [[0, 1, 2, 3]])
    mesh.update()
    uv = mesh.uv_layers.new()
    for i, c in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
        uv.data[i].uv = c
    obj = bpy.data.objects.new("TierFloor", mesh)
    bpy.context.collection.objects.link(obj)

    # Bake-source material: two samples blended by noise, mottled brightness
    src = bpy.data.materials.new("ConcreteBakeSrc")
    src.use_nodes = True
    n = src.node_tree.nodes
    l = src.node_tree.links
    bsdf = n["Principled BSDF"]
    coord = n.new("ShaderNodeTexCoord")
    map1 = n.new("ShaderNodeMapping")
    map1.inputs["Scale"].default_value = (5.0, 7.0, 1.0)
    map2 = n.new("ShaderNodeMapping")
    map2.inputs["Scale"].default_value = (8.0, 11.0, 1.0)
    map2.inputs["Rotation"].default_value = (0, 0, math.radians(37))
    t1 = n.new("ShaderNodeTexImage"); t1.image = img
    t2 = n.new("ShaderNodeTexImage"); t2.image = img
    noise = n.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 3.0
    mix = n.new("ShaderNodeMix"); mix.data_type = "RGBA"
    l.new(coord.outputs["UV"], map1.inputs["Vector"])
    l.new(coord.outputs["UV"], map2.inputs["Vector"])
    l.new(map1.outputs["Vector"], t1.inputs["Vector"])
    l.new(map2.outputs["Vector"], t2.inputs["Vector"])
    l.new(noise.outputs["Fac"], mix.inputs["Factor"])
    l.new(t1.outputs["Color"], mix.inputs[6])
    l.new(t2.outputs["Color"], mix.inputs[7])
    # Large-scale mottle for slab-like unevenness
    mottle = n.new("ShaderNodeTexNoise")
    mottle.inputs["Scale"].default_value = 0.8
    ramp = n.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].color = (0.82, 0.82, 0.82, 1)
    ramp.color_ramp.elements[1].color = (1.05, 1.05, 1.05, 1)
    mult = n.new("ShaderNodeMix"); mult.data_type = "RGBA"; mult.blend_type = "MULTIPLY"
    set_input(mult, ["Factor", "Fac"], 1.0)
    l.new(mottle.outputs["Fac"], ramp.inputs["Fac"])
    l.new(mix.outputs[2], mult.inputs[6])
    l.new(ramp.outputs["Color"], mult.inputs[7])
    l.new(mult.outputs[2], bsdf.inputs["Base Color"])
    obj.data.materials.append(src)

    # Bake to one continuous sheet
    baked = bpy.data.images.new("concrete_baked", 2048, 2048)
    bake_node = n.new("ShaderNodeTexImage")
    bake_node.image = baked
    n.active = bake_node
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 16
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.bake(type="DIFFUSE", pass_filter={"COLOR"}, use_selected_to_active=False)
    baked.pack()

    # Final material: the baked sheet + the reference's semi-polished response
    concrete = bpy.data.materials.new("Concrete")
    concrete.use_nodes = True
    cn = concrete.node_tree.nodes
    cl = concrete.node_tree.links
    cbsdf = cn["Principled BSDF"]
    set_input(cbsdf, "Roughness", 0.35)
    ct = cn.new("ShaderNodeTexImage")
    ct.image = baked
    cl.new(ct.outputs["Color"], cbsdf.inputs["Base Color"])
    obj.data.materials.clear()
    obj.data.materials.append(concrete)
    return concrete


def build_sconces():
    """90s-theater wall sconces (AI-generated wedge) with rainbow light fans:
    red/blue up at +/-15deg, green/amber down at +/-40deg."""
    if not os.path.exists(AI_SCONCE_PATH):
        print("Sconce GLB missing — skipping sconces")
        return
    base_sconce = load_ai_seat_template("SconceTemplate", AI_SCONCE_PATH, dims=SCONCE_DIMS)
    base_sconce.location = (0, -100, 0)
    sconce_tmpl = {
        -1: rotated_single_user(base_sconce, "SconceTmplL", math.radians(90)),
        1: rotated_single_user(base_sconce, "SconceTmplR", math.radians(-90)),
    }
    bpy.data.objects.remove(base_sconce, do_unlink=True)

    # Cycles-simulated glow decal (see sconce_glow_bake.py): a warm 3500K
    # core through colored gels, path-traced against a wall for real
    # penumbra, blending, and falloff — applied as an emissive alpha decal.
    glow_path = os.path.join(_REF, "sconce_glow.png")
    glow_img = bpy.data.images.load(glow_path)
    glow_img.pack()
    glow_mat = bpy.data.materials.new("SconceGlow")
    glow_mat.use_nodes = True
    glow_mat.blend_method = "BLEND"
    nodes = glow_mat.node_tree.nodes
    links = glow_mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    set_input(bsdf, "Base Color", (0, 0, 0, 1))
    set_input(bsdf, "Roughness", 1.0)
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = glow_img
    links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"]
              if bsdf.inputs.get("Emission Color") else bsdf.inputs["Emission"])
    set_input(bsdf, "Emission Strength", 2.5)
    links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])

    # Decal geometry layout must match the bake: 4.2 x 5.6m, fixture 2.0m
    # above the bottom edge.
    GLOW_W, GLOW_H, FIXTURE_V = 4.2, 5.6, 2.0

    def add_glow_quad(name, x, y0, inward_sign):
        zb = SCONCE_HEIGHT - FIXTURE_V
        verts = [
            (x, y0 - GLOW_W / 2, zb),
            (x, y0 + GLOW_W / 2, zb),
            (x, y0 + GLOW_W / 2, zb + GLOW_H),
            (x, y0 - GLOW_W / 2, zb + GLOW_H),
        ]
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(verts, [], [[0, 1, 2, 3]])
        mesh.update()
        uv = mesh.uv_layers.new()
        for i, coord in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
            uv.data[i].uv = coord
        obj = bpy.data.objects.new(name, mesh)
        bpy.context.collection.objects.link(obj)
        obj.data.materials.append(glow_mat)
        return obj

    idx = 0
    for side in (-1, 1):
        for y0 in SCONCE_ROWS:
            half_w = ROOM_HALF_W_FRONT + (ROOM_HALF_W_BACK - ROOM_HALF_W_FRONT) * (y0 / ROOM_DEPTH)
            x = side * (half_w - 0.10)
            sconce = sconce_tmpl[side].copy()
            sconce.location = (x, y0, SCONCE_HEIGHT)
            sconce.name = f"Sconce{idx}"
            bpy.context.collection.objects.link(sconce)

            add_glow_quad(f"Glow{idx}", side * (half_w - 0.135), y0, -side)
            idx += 1
    for o in sconce_tmpl.values():
        bpy.data.objects.remove(o, do_unlink=True)


def bake_seat_lod(source, name, decimate_ratio, tex_size=1024):
    """Decimated LOD with CLEAN UVs and the original texture re-baked onto
    them. Decimation shreds the AI mesh's UV atlas, so sampling the original
    texture through surviving UVs renders as torn confetti — instead the LOD
    gets a fresh Smart-UV layout and a Cycles selected-to-active diffuse bake
    from the pristine source mesh."""
    lod = source.copy()
    lod.data = source.data.copy()
    lod.name = name
    bpy.context.collection.objects.link(lod)
    bpy.ops.object.select_all(action="DESELECT")
    lod.select_set(True)
    bpy.context.view_layer.objects.active = lod

    mod = lod.modifiers.new("Decimate", "DECIMATE")
    mod.ratio = decimate_ratio
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.ops.object.shade_smooth()

    # Fresh UVs for the low-poly mesh
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.003)
    bpy.ops.object.mode_set(mode="OBJECT")

    # Bake target image + material. Fill with the fabric's average red so any
    # missed ray or seam bleeds red, not black slashes.
    img = bpy.data.images.new(f"{name}_bake", tex_size, tex_size)
    img.generated_color = (0.30, 0.05, 0.05, 1.0)
    img.source = "GENERATED"
    mat = bpy.data.materials.new(f"{name}_mat")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    tex_node = nodes.new("ShaderNodeTexImage")
    tex_node.image = img
    nodes.active = tex_node
    mat.node_tree.links.new(
        tex_node.outputs["Color"],
        nodes["Principled BSDF"].inputs["Base Color"],
    )
    lod.data.materials.clear()
    lod.data.materials.append(mat)

    # Selected-to-active bake: source (with its intact atlas) -> LOD image
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 16
    source.select_set(True)
    bpy.context.view_layer.objects.active = lod
    bpy.ops.object.bake(
        type="DIFFUSE",
        pass_filter={"COLOR"},
        use_selected_to_active=True,
        # Generous cage: the decimated surface sags well below the source in
        # concave areas — a tight cage leaves black ray-miss slashes.
        cage_extrusion=0.08,
        max_ray_distance=0.25,
        margin=24,
        use_clear=False,  # keep the red fill under everything
    )
    img.pack()
    source.select_set(False)

    tris = sum(len(p.vertices) - 2 for p in lod.data.polygons)
    print(f"AI seat '{name}': ratio={decimate_ratio} tris={tris} (rebaked {tex_size}px)")
    return lod


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
    # Bright enough to exist under dim lit ambient — near-black carpet
    # rendered as a void beneath the seats.
    carpet = make_material("Carpet", (0.17, 0.105, 0.085), roughness=1.0)
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

    # Side wall acoustic baffles — framed, fabric-faced, textured (see
    # build_baffles); exported in the lit GLB so they shade with the room.
    build_baffles()

    # Pleated curtains flanking the screen — alternating-depth flutes
    for side in (-1, 1):
        base_x = side * (SCREEN_W / 2 + 0.9)
        for i in range(7):
            fx = base_x + side * i * 0.22
            fy = 0.55 + (0.18 if i % 2 == 0 else 0.38)
            add_box(f"Curtain{side}_{i}", (0.24, 0.35, SCREEN_H + 1.2),
                    (fx, fy, SCREEN_BOTTOM + (SCREEN_H + 1.2) / 2 - 0.3), curtain)

    # AI velvet curtains flanking the screen (replace the box-flute stand-ins)
    if os.path.exists(AI_CURTAIN_PATH):
        curtain = load_ai_seat_template("CurtainTemplate", AI_CURTAIN_PATH, dims=CURTAIN_DIMS)
        curtain.location = (0, -100, 0)
        for ci, side in enumerate((-1, 1)):
            panel = curtain.copy()
            panel.name = f"Curtain{ci}"
            panel.location = (side * (SCREEN_W / 2 + 0.15 + CURTAIN_DIMS[0] / 2), 0.75, 0)
            bpy.context.collection.objects.link(panel)
        bpy.data.objects.remove(curtain, do_unlink=True)
        # Remove the old procedural flutes from the scene
        for obj in list(bpy.data.objects):
            if obj.name.startswith("Curtain-") or obj.name.startswith("Curtain1_") or obj.name.startswith("Curtain-1_"):
                bpy.data.objects.remove(obj, do_unlink=True)

    # Recessed ceiling can lights (AI fixture per the reference sheet), in a
    # grid echoing the in-context photo, each with a warm emissive face.
    can_path = os.path.join(_REF, "canlight_ai.glb")
    if os.path.exists(can_path):
        can = load_ai_seat_template("CanTemplate", can_path,
                                    dims=(0.152, 0.152, 0.178))
        can.location = (0, -100, 0)
        warm_face = make_material("CanFace", (0.3, 0.2, 0.1),
                                  emission=(1.0, 0.72, 0.42), emission_strength=5.0)
        ci = 0
        for ry in (8.0, 14.5, 21.0, 27.5, 34.0, 40.0):
            for cx in (-6.5, 0.0, 6.5):
                fixture = can.copy()
                fixture.name = f"Sconce_can{ci}"  # Sconce prefix -> kept in export
                # Recessed: body up into the ceiling, trim just below the slab face
                fixture.location = (cx, ry, CEILING - 0.075 - 0.02)
                bpy.context.collection.objects.link(fixture)
                # Warm lit face: small emissive disc at the fixture mouth
                bpy.ops.mesh.primitive_circle_add(vertices=16, radius=0.051,
                                                  fill_type="NGON",
                                                  location=(cx, ry, CEILING - 0.075 - 0.015),
                                                  rotation=(math.radians(180), 0, 0))
                disc = bpy.context.active_object
                disc.name = f"Glow_can{ci}"
                disc.data.materials.append(warm_face)
                ci += 1
        bpy.data.objects.remove(can, do_unlink=True)

    # Wall sconces with rainbow fans (the 90s-theater signature)
    build_sconces()

    # Exit signs flanking the screen
    for side in (-1, 1):
        add_box(f"Exit{side}", (0.55, 0.12, 0.28), (side * (ROOM_HALF_W_FRONT - 1.2), 0.8, 2.6), exitgreen)

    # Ceiling downlights (sparse, warm, dim house lights)
    for iy in range(4):
        for ix in (-6.0, 0.0, 6.0):
            y = 12 + iy * 8
            add_box(f"Down{ix}_{iy}", (0.4, 0.4, 0.06), (ix, y, CEILING - 0.05), downlight)

    # Poured-concrete floor (baked, continuous) — tiers wear it too.
    concrete_mat = build_concrete_floor()
    tier_mat = concrete_mat if concrete_mat else carpet

    # --- Seating: curved raked rows with aisles ---
    if os.path.exists(AI_SEAT_NEAR_PATH) and os.path.exists(AI_SEAT_FAR_PATH):
        detailed = load_ai_seat_template("SeatDetailed", AI_SEAT_NEAR_PATH)
        simple = load_ai_seat_template("SeatSimple", AI_SEAT_FAR_PATH)
    else:
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
                (0, d, max(rise, 0.04) / 2), tier_mat)
        if row_count % 2 == 0:
            for ax in AISLE_CENTERS:
                add_box(f"Step{row_count}_{ax}", (1.0, 0.1, 0.035),
                        (ax, d - ROW_PITCH / 2 + 0.06, rise + 0.017), amber)

        # Seats along an arc centered on the screen
        n = int((2 * half_row) / SEAT_ARC_PITCH)
        for i in range(n):
            arc = (i - (n - 1) / 2) * SEAT_ARC_PITCH
            theta = arc / d
            x = d * math.sin(theta)
            y = d * math.cos(theta)
            if any(abs(x - a) < AISLE_HALF + 0.3 for a in AISLE_CENTERS):
                continue
            # Each app seat position (Front/Middle/Back) gets an empty spot.
            if abs(x) < 0.75 and any(abs(d - vr) < 0.6 for vr in VIEWER_ROWS):
                continue
            # Template faces -y (toward screen) at rotation 0; -theta aims each
            # seat at the screen center along the row's arc.
            place_seat(template, x, y, rise, -theta)
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


def export_glb():
    """Export the theater for the app.

    Stripped for runtime use: the emissive content plane goes away (the app
    renders the actual movie there as a compositor panel; the black canvas
    stays as masking), and the parked seat templates are deleted. Blender's
    +Y (toward the back of the house) maps to glTF -Z, matching the app's
    'origin at screen wall, extends -Z toward viewer' convention.
    """
    # The app renders this GLB unlit so its textures work reliably — which
    # also means nothing in it can react to the ambient light sim. So ONLY
    # the textured seats and the tiers they sit on are exported; every other
    # surface (walls, baffles, curtains, strips, lights, stage) spawns
    # procedurally in the app where the tint system and the dimmer own it.
    keep_prefixes = ("SeatDetailed", "SeatSimple", "Tier", "Sconce", "Glow", "Curtain", "Baffle")
    for obj in list(bpy.data.objects):
        is_template = obj.name in ("SeatDetailed", "SeatSimple")  # parked originals
        if is_template or not obj.name.startswith(keep_prefixes):
            bpy.data.objects.remove(obj, do_unlink=True)

    os.makedirs(os.path.dirname(GLB_OUT), exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=GLB_OUT,
        export_format="GLB",
        export_apply=True,   # bake the bevel modifiers into the meshes
        export_cameras=False,
        export_lights=False,
    )
    print(f"Exported: {GLB_OUT}")


build()
render()
export_glb()
