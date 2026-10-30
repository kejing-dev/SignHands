"""Renders the baked hand GLB at a few letters to check the pose animation (Blender headless):
blender -b --factory-startup -P tools/render_check.py -- <glb> <out_dir> "A,F,Y,G"
Camera looks from the viewer side (opposite), the hand frame's +Z (back of hand) faces the camera."""
import math
import sys

import bpy

argv = sys.argv[sys.argv.index("--") + 1:]
glb, out_dir, letters = argv[0], argv[1], argv[2].split(",")
side = argv[3] if len(argv) > 3 else "self"
ORDER = ["OPEN"] + [chr(c) for c in range(ord("A"), ord("Z") + 1)]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=glb)
scene = bpy.context.scene
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = 480
scene.render.resolution_y = 480
scene.render.fps = 24
arm = next(o for o in scene.objects if o.type == "ARMATURE")
for o in scene.objects:
    if o.type == "MESH":
        mats = list(o.data.materials)
        palette = {"hand_thumb": (0.9, 0.4, 0.3, 1), "hand_index": (0.3, 0.7, 0.4, 1), "hand_middle": (0.3, 0.5, 0.9, 1),
                   "hand_ring": (0.8, 0.7, 0.2, 1), "hand_pinky": (0.7, 0.3, 0.8, 1), "hand_palm": (0.8, 0.8, 0.8, 1)}
        for m in mats:
            m.use_nodes = True
            bsdf = m.node_tree.nodes.get("Principled BSDF")
            if bsdf and m.name.split(".")[0] in palette:
                bsdf.inputs["Base Color"].default_value = palette[m.name.split(".")[0]]
# glTF +Y up -> Blender +Z up; glTF +Z (back of hand) -> Blender -Y. Camera on Blender -Y looking +Y.
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
scene.collection.objects.link(cam)
if side == "self":
    cam.location = (0, -0.45, 0.08)
    cam.rotation_euler = (math.radians(90), 0, 0)
else:
    cam.location = (0, 0.45, 0.08)
    cam.rotation_euler = (math.radians(90), 0, math.radians(180))
cam.data.lens = 50
scene.camera = cam
light = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
light.data.energy = 3
light.rotation_euler = (math.radians(60), 0, math.radians(20))
scene.collection.objects.link(light)
world = bpy.data.worlds.new("w")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.15, 0.16, 0.2, 1)
scene.world = world
action = arm.animation_data.action if arm.animation_data else None
print("ACTION", action.name if action else None, action.frame_range if action else None)
for letter in letters:
    k = ORDER.index(letter)
    t = 2.0 * k + 1.5
    scene.frame_set(int(round(t * scene.render.fps)))
    scene.render.filepath = f"{out_dir}/{side}_{letter}.png"
    bpy.ops.render.render(write_still=True)
