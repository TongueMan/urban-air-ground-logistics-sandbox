"""Inspect a GLB in Blender and render neutral multi-angle previews."""

import json
import math
import os
import sys

import bpy
from mathutils import Vector


def script_args():
    args = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    if len(args) != 2:
        raise SystemExit("usage: blender -b --python inspect_glb.py -- INPUT_GLB OUTPUT_DIR")
    return os.path.abspath(args[0]), os.path.abspath(args[1])


def world_bounds(objects):
    points = [obj.matrix_world @ Vector(corner) for obj in objects for corner in obj.bound_box]
    if not points:
        return Vector((0, 0, 0)), Vector((0, 0, 0))
    return (
        Vector(tuple(min(point[i] for point in points) for i in range(3))),
        Vector(tuple(max(point[i] for point in points) for i in range(3))),
    )


def vector_list(value):
    return [round(float(component), 6) for component in value]


def add_camera(center, size, direction):
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    distance = max(size) * 2.2
    camera.location = center + Vector(direction).normalized() * distance
    camera.rotation_euler = (center - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = max(size) * 1.25
    bpy.context.scene.camera = camera
    return camera


def add_lights(center, size):
    bpy.ops.object.light_add(type="AREA", location=center + Vector((max(size), -max(size), max(size) * 1.5)))
    bpy.context.object.data.energy = 1800
    bpy.context.object.data.shape = "DISK"
    bpy.context.object.data.size = max(size) * 1.5
    bpy.ops.object.light_add(type="AREA", location=center + Vector((-max(size), max(size), max(size))))
    bpy.context.object.data.energy = 900
    bpy.context.object.data.size = max(size)
    bpy.ops.object.light_add(type="SUN", location=center + Vector((0, 0, max(size) * 2)))
    bpy.context.object.rotation_euler = (math.radians(25), math.radians(-20), math.radians(135))
    bpy.context.object.data.energy = 2.0


input_path, output_dir = script_args()
os.makedirs(output_dir, exist_ok=True)
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=input_path)

mesh_objects = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
minimum, maximum = world_bounds(mesh_objects)
center = (minimum + maximum) / 2
size = maximum - minimum

objects = []
for obj in mesh_objects:
    obj_min, obj_max = world_bounds([obj])
    objects.append(
        {
            "name": obj.name,
            "parent": obj.parent.name if obj.parent else None,
            "vertices": len(obj.data.vertices),
            "edges": len(obj.data.edges),
            "polygons": len(obj.data.polygons),
            "materials": [slot.material.name if slot.material else None for slot in obj.material_slots],
            "boundsMin": vector_list(obj_min),
            "boundsMax": vector_list(obj_max),
            "boundsSize": vector_list(obj_max - obj_min),
            "boundsCenter": vector_list((obj_min + obj_max) / 2),
        }
    )

images = []
for image in bpy.data.images:
    images.append(
        {
            "name": image.name,
            "width": int(image.size[0]),
            "height": int(image.size[1]),
            "channels": int(image.channels),
            "fileFormat": image.file_format,
            "packedBytes": image.packed_file.size if image.packed_file else 0,
        }
    )

report = {
    "input": input_path,
    "blenderVersion": bpy.app.version_string,
    "boundsMin": vector_list(minimum),
    "boundsMax": vector_list(maximum),
    "boundsSize": vector_list(size),
    "meshObjectCount": len(mesh_objects),
    "vertices": sum(len(obj.data.vertices) for obj in mesh_objects),
    "polygons": sum(len(obj.data.polygons) for obj in mesh_objects),
    "materials": len(bpy.data.materials),
    "animations": len(bpy.data.actions),
    "objects": objects,
    "images": images,
}
print("MODEL_AUDIT_JSON=" + json.dumps(report, ensure_ascii=False, separators=(",", ":")))

scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 640
scene.render.resolution_y = 480
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
if scene.world is None:
    scene.world = bpy.data.worlds.new("Model Audit World")
scene.world.color = (0.015, 0.02, 0.03)
scene.view_settings.look = "AgX - Medium High Contrast"
add_lights(center, size)

directions = {
    "perspective": (1.4, -1.7, 0.9),
    "side": (1.0, 0.0, 0.25),
    "top": (0.01, -0.01, 1.0),
}
camera = None
for label, direction in directions.items():
    if camera:
        bpy.data.objects.remove(camera, do_unlink=True)
    camera = add_camera(center, size, direction)
    scene.render.filepath = os.path.join(output_dir, f"{label}.png")
    bpy.ops.render.render(write_still=True)
