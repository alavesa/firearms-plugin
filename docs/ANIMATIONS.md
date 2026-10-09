# Firearms – adding guns, animations and sounds from Blockbench

Everything below is driven by one folder on the server: `plugins/Firearms/models/`. After any change there, run `/firearms pack`, then upload the new `plugins/Firearms/Firearms-pack.zip` to the players (and delete any old Firearms jar before updating the plugin).

## 1. A new gun in five steps

1. In `plugins/Firearms/guns.yml` add the gun with a `model:` name, e.g. `model: m4`.
2. Save the Blockbench file as `plugins/Firearms/models/m4.bbmodel` (same name, lower-case, no spaces).
3. Run `/firearms pack`. Read the chat output: it lists warnings per model.
4. Upload `Firearms-pack.zip`.
5. `/firearms give m4`.

Optional files next to the model, all picked up automatically:

| File | Purpose |
|---|---|
| `m4_casing.bbmodel` | the shell this gun ejects |
| `m4_icon.png` | the inventory icon (otherwise a label card is generated) |
| `sounds/<name>.ogg` | sounds used by the animations (see §4) |

## 2. Modelling rules the converter needs

- Cubes only. Meshes are skipped (the pack report tells you how many).
- Keep the model inside Blockbench's Java range (-16..32 on every axis); bigger models are scaled down automatically and the scale is compensated in the display settings.
- Rotate groups and cubes freely. Multiples of 90° are exact; any other angle is rounded to the nearest 22.5° step (a Java limit). The report lists every rounding.
- Put the whole gun under one top-level group (e.g. `gun`). Its animation becomes the first-person display motion (exact, any angle). Sub-groups (`slide`, `mag`, `hammer`, `bolt`) are baked into the cubes.
- Blockbench **Display** settings (first-person right/left hand etc.) are used if the file has them. They can be overridden per gun in `guns.yml`:

```yaml
    display:
      firstperson_righthand: { rotation: [0, -5, 0], translation: [2, 0, 1], scale: [0.6, 0.6, 0.6] }
```

- Muzzle flash: name a cube or a group `flash` (or `muzzle_flash`). It is hidden normally and shown only on the first fire frame(s), with whatever texture you gave it. Without one, a flash cube is generated at the front of the model (`flash: x,y,z` in guns.yml to move it).

## 3. Animations

Make animations in Blockbench as usual (rotation / position keyframes on groups). The converter bakes every animation into single frames; nothing is done by hand.

Which animation does what is decided by its **name**:

| Clip | Default names that match | When it plays |
|---|---|---|
| `fire` | fire, shoot, shot, recoil | every shot |
| `reload` | reload, rel, mag | F (magazine guns) |
| `pump` | pump, rack, cock, bolt, cycle | each round loaded on pump-action guns |
| `equip` | equip, draw, deploy, pull, ready | taking the gun out |
| `inspect` | inspect, look, check, idle | F when the gun is full or nothing can be loaded |

An exact name wins over a "contains" match, so `rel` is reload and `reload_fast` is reload too. Add your own words:

- for every gun in `config.yml`:
  ```yaml
  anim:
    names:
      reload: [reload, rel, r]
      fire: [fire, bang]
  ```
- for one gun in `guns.yml`:
  ```yaml
    anim-names: { reload: [rel], fire: [bang] }
  ```

**Frames per animation.** Each animation gets its own number of frames:

1. `guns.yml` → `anim: { reload: { frames: 12 }, fire: { frames: 4 } }` if you set it,
2. otherwise the animation's own Blockbench *snapping* value (keyframes per second) × its length,
3. otherwise length × 20 (one frame per tick).

The result is capped by `anim.max-frames` (config, or per gun `anim: { max-frames: 30 }`). The frames are spread evenly over the animation's length, so a 1 s reload with 12 frames shows a new frame every 1.67 ticks.

**Draw speed.** `equip-seconds: 1.0` in guns.yml stretches or compresses the equip animation to that time; the gun cannot fire until it is over.

## 4. Sounds

In Blockbench open the animation, add the **Effects** animator and put a **sound** keyframe where the sound should start. The keyframe stores the effect name (e.g. `m4_shot`) and the path of the .ogg on your computer; the audio itself is not inside the .bbmodel.

Copy that file to the server as `plugins/Firearms/models/sounds/m4_shot.ogg` (same name as the effect, lower-case). `/firearms pack` adds it to the pack as `firearms:m4_shot` and the plugin plays it at the keyframe time whenever the clip plays. A missing file is reported as a warning.

The gunshot itself (`sound:` / `pitch:` in guns.yml) is a normal Minecraft or pack sound id and does not need a keyframe.

## 4b. Grenades

`plugins/Firearms/grenades.yml` defines the grenades (frag, incendiary, smoke by default). Each has a `model:` (`grenade_frag.bbmodel` etc., placeholder if missing) and uses two clips:

| Clip | Names that match | When |
|---|---|---|
| `unpin` | unpin, pin, arm | right-click (pull the pin; with `cook: true` the fuse starts now) |
| `throw` | throw, toss, lob | left-click; the grenade leaves the hand when the clip ends |

Hands with the player's skin work in grenade models exactly like in guns (see HANDS.md). Icons: `models/grenade_frag_icon.png` or the generated label.

## 5. Checking without rebuilding

`/firearms check` runs the whole conversion in memory and prints the per-model sizes and every warning, without writing the pack. `plugins/Firearms/pack-report.txt` (written by `/firearms pack`) lists the models folder, every custom_model_data → model path mapping and the warnings.

## 6. Common problems

| Symptom | Cause / fix |
|---|---|
| Every gun purple/black | A model name with a capital letter or space; fixed automatically since 0.2, but check the report. Or the players still have an old pack: re-upload. |
| Gun purple only while firing | Pack older than the server's animation index: re-upload the pack after every `/firearms pack`. |
| Parts floating / torn | Rotations rounded to 22.5° steps; see the report. Multiples of 90° are exact. |
| Animation barely visible | Too few frames or the motion sits on a sub-group instead of the top-level gun group. |
| No sound | File missing in `models/sounds/`, or the effect name differs from the file name. |
