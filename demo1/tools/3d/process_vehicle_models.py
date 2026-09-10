"""Create web-ready, vehicle-only GLBs with Blender."""

import json
import os
import sys

import bpy
from mathutils import Matrix, Vector


def args():
    values = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    if len(values) != 3 or values[0] not in {
        "optimize-peterbilt",
        "extract-ural",
        "extract-cybertruck",
    }:
        raise SystemExit(
            "usage: blender -b --python process_vehicle_models.py -- "
            "{optimize-peterbilt|extract-ural|extract-cybertruck} INPUT_GLB OUTPUT_GLB"
        )
    return values[0], os.path.abspath(values[1]), os.path.abspath(values[2])


def mesh_objects():
    return [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]


def counts(objects):
    return {
        "objects": len(objects),
        "vertices": sum(len(obj.data.vertices) for obj in objects),
        "polygons": sum(len(obj.data.polygons) for obj in objects),
    }


def bounds(objects):
    points = [obj.matrix_world @ Vector(corner) for obj in objects for corner in obj.bound_box]
    minimum = Vector(tuple(min(point[i] for point in points) for i in range(3)))
    maximum = Vector(tuple(max(point[i] for point in points) for i in range(3)))
    return minimum, maximum


def apply_decimate(obj, ratio):
    if len(obj.data.polygons) < 2000 or ratio >= 0.999:
        return
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    modifier = obj.modifiers.new(name="WebDecimate", type="DECIMATE")
    modifier.decimate_type = "COLLAPSE"
    modifier.ratio = ratio
    modifier.use_collapse_triangulate = True
    bpy.ops.object.modifier_apply(modifier=modifier.name)
    obj.select_set(False)


def remove_object_tree(root):
    for child in list(root.children):
        remove_object_tree(child)
    bpy.data.objects.remove(root, do_unlink=True)


def optimize_peterbilt():
    ratios = {
        "SM_Carroceria": 0.60,
        "SM_Logo": 0.12,
        "SM_LLantas": 0.18,
        # Thin chrome stacks are sensitive to aggressive collapse and showed
        # broken highlights below this ratio in the rendered QA pass.
        "SM_Exhosto": 0.40,
        "SM_Chasis": 0.25,
        "SM_Resorte": 0.30,
        "SM_Cabina": 0.35,
    }
    for obj in mesh_objects():
        ratio = next((value for prefix, value in ratios.items() if obj.name.startswith(prefix)), 0.30)
        apply_decimate(obj, ratio)

    resized = []
    for image in bpy.data.images:
        width, height = int(image.size[0]), int(image.size[1])
        if max(width, height) > 512:
            scale = 512 / max(width, height)
            new_width = max(1, round(width * scale))
            new_height = max(1, round(height * scale))
            image.scale(new_width, new_height)
            resized.append({"name": image.name, "from": [width, height], "to": [new_width, new_height]})
    return {"resizedImages": resized}


def extract_ural():
    vehicle_names = {
        "Cylinder.029_texture_0",
        "Cylinder.028_texture_0",
        "Cylinder.009_texture_0",
        "Plane.005_texture_0",
        "Plane.005_emission_0",
        "Plane.005_textureglass_0",
        "Cube.013_texture_0",
        "Cube.012_texture_0",
        "Cube.011_texture_0",
        "Cube.010_texture_0",
        "Cylinder.008_texture_0",
        "Cylinder.007_texture_0",
        "Plane.004_texture_0",
        "Plane.004_emission_0",
        "Plane.003_texture_0",
        "Plane.003_emission_0",
        "Cube.009_texture_0",
        "Cylinder.006_texture_0",
        "Cylinder.005_texture_0",
        "Cube.008_texture_0",
    }
    present = {obj.name for obj in mesh_objects()}
    missing = sorted(vehicle_names - present)
    if missing:
        raise RuntimeError(f"Ural source structure changed; missing vehicle meshes: {missing}")

    kept = [obj for obj in mesh_objects() if obj.name in vehicle_names]
    removed_names = sorted(obj.name for obj in mesh_objects() if obj.name not in vehicle_names)
    for obj in kept:
        world = obj.matrix_world.copy()
        obj.parent = None
        obj.matrix_world = world
    for obj in list(bpy.context.scene.objects):
        if obj not in kept:
            bpy.data.objects.remove(obj, do_unlink=True)

    minimum, maximum = bounds(kept)
    offset = Vector((-(minimum.x + maximum.x) / 2, -(minimum.y + maximum.y) / 2, -minimum.z))
    translation = Matrix.Translation(offset)
    for obj in kept:
        obj.matrix_world = translation @ obj.matrix_world

    root = bpy.data.objects.new("UralTruck", None)
    bpy.context.scene.collection.objects.link(root)
    for obj in kept:
        world = obj.matrix_world.copy()
        obj.parent = root
        obj.matrix_world = world
    return {"removedObjects": removed_names, "originOffset": [round(value, 6) for value in offset]}


def extract_cybertruck():
    # The source includes props for a short crash animation. In the map these
    # detached balls look like two rocks travelling alongside the truck, while
    # the breakaway glass and light-placement empties are not part of the
    # operational vehicle either.
    removable_roots = {
        "ball01",
        "ball02",
        "glass_break3",
        "glass_break4",
        "lights_truck_grp",
    }
    present = {obj.name for obj in bpy.context.scene.objects}
    missing = sorted(removable_roots - present)
    if missing:
        raise RuntimeError(f"Cybertruck source structure changed; missing removable roots: {missing}")
    if "cybertruck_mini_grp" not in present:
        raise RuntimeError("Cybertruck source structure changed; vehicle root is missing")

    removed_names = []
    for name in sorted(removable_roots):
        root = bpy.data.objects.get(name)
        removed_names.extend(obj.name for obj in root.children_recursive)
        removed_names.append(root.name)
        remove_object_tree(root)

    # The only clip drives the removed crash props and rocks, plus a body jolt.
    # A route-driven map vehicle must remain in its authored rest pose.
    for obj in bpy.context.scene.objects:
        obj.animation_data_clear()
    removed_actions = sorted(action.name for action in bpy.data.actions)
    for action in list(bpy.data.actions):
        bpy.data.actions.remove(action)

    kept = mesh_objects()
    minimum, maximum = bounds(kept)
    offset = Vector((-(minimum.x + maximum.x) / 2, -(minimum.y + maximum.y) / 2, -minimum.z))
    roots = [obj for obj in bpy.context.scene.objects if obj.parent is None]
    if len(roots) != 1:
        raise RuntimeError(f"Expected one Cybertruck scene root, found {[obj.name for obj in roots]}")
    roots[0].matrix_world = Matrix.Translation(offset) @ roots[0].matrix_world

    return {
        "removedObjects": sorted(removed_names),
        "removedActions": removed_actions,
        "originOffset": [round(value, 6) for value in offset],
    }


mode, input_path, output_path = args()
os.makedirs(os.path.dirname(output_path), exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=input_path)
before = counts(mesh_objects())

if mode == "optimize-peterbilt":
    details = optimize_peterbilt()
elif mode == "extract-ural":
    details = extract_ural()
else:
    details = extract_cybertruck()
after = counts(mesh_objects())

bpy.ops.export_scene.gltf(
    filepath=output_path,
    export_format="GLB",
    export_apply=True,
    export_animations=False,
    export_extras=True,
    export_image_format="WEBP" if mode == "optimize-peterbilt" else "AUTO",
    export_image_quality=82,
    export_meshopt_compression_enable=True,
    export_meshopt_extension="EXT_meshopt_compression",
    export_shared_accessors=True,
    export_unused_images=False,
    export_unused_textures=False,
)

print(
    "MODEL_PROCESS_JSON="
    + json.dumps(
        {
            "mode": mode,
            "input": input_path,
            "output": output_path,
            "before": before,
            "after": after,
            "outputBytes": os.path.getsize(output_path),
            **details,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
)
