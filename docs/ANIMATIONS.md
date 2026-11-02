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
- Rotate groups and cubes freely - nothing is rounded. A cube whose total rotation is one axis at ±22.5° or ±45° is written as a plain Java element; every other rotation (two axes at once, 30°, 7°, 90°, 112.5° ...) is carried **exactly by the display transform** of a sub-model that holds every cube of that group sharing that rotation. The self-check in `/firearms pack` compares every cube corner with Blockbench and names any part that is off.
- Blockbench 4.x and Blockbench 5 files both work (5.x keeps group pivots and rotations in a separate `groups` list; read since 0.21 - before that every group of a Blockbench 5 file lost its pivot and rotation, which broke the rifle model and the shotgun animations).
- Put the whole gun under one top-level group (e.g. `gun`) and animate it for the gun's own motion. Groups that no animation moves are folded into the nearest animated group above them, so only the groups you actually animate cost anything in the pack (a 139-group rifle with 16 animated groups ends up with 16 bone parts plus one part per distinct free rotation).
- Blockbench **scale** keyframes work: scale 0 hides a group (gun packs hide spare magazines and bullets with it).
- An animation named `static_idle` (or `idle`, `static`; `anim.names.base`) is the **base pose**: it is never played as a clip but layered under the rest model and every frame - this is how gun packs made for other systems show/hide their attachment variants.
- Gun packs converted from other systems carry every attachment at once (three magazines, two stocks, folded and raised sights). Leave the spare ones out per gun in `guns.yml`: `hide-groups: [mag_extended_1, mag_extended_2, stock_folded*, "sight_folded"]` (the pack report lists every group that holds cubes), and drop animation variants you do not need with `skip-animations: ["*_semi*"]`.
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

An exact name wins over a "contains" match, so `rel` is reload and `reload_fast` is reload too. If two animations end up on the same clip, the better-matching name wins and the other is reported (`animation ignored: ...`) - name the one you want exactly `inspect`, `fire`, etc. Add your own words:

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

**Sounds without keyframes.** Every `.ogg` you drop into `plugins/Firearms/models/sounds/` is packed by `/firearms pack` and becomes the sound id `firearms:<file name>` - nothing to edit in the pack by hand. Use those ids in guns.yml: `sound:` (the shot), `reload-sound:`, `equip-sound:`, `empty-sound:`. The pack report lists the packed names.

## 4a. The inspect animation

Name an animation `inspect` (or `look`, `check`, `idle`, or your own word under `anim.names.inspect`). It plays when the player presses **F** and nothing can be loaded: the magazine is full, or there is no magazine / no loose round to load. While a reload or the draw animation is running, F does nothing.

Check it is there: `/firearms clips <model>` must list `inspect: N frames x T ticks`. If it does not, the animation name did not match - `/firearms pack` prints a `clips:` line per model showing what was detected.

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
| Parts floating / torn | Read the `SELF-CHECK` and range warnings of `/firearms pack`; nothing is rounded any more (0.21). A Blockbench 5 file built with a plugin older than 0.21 lost every group pivot: rebuild. |
| Every gun is a plain crossbow after adding one model | One bad items-file case drops the whole file. Since 0.22 a failed conversion is rolled back and every case is validated; the warning names the model and the cause - send that file. |
| Three magazines / two stocks drawn at once | A gun pack with attachment variants: `hide-groups:` per gun in guns.yml (the report lists the group names). |
| Pack is huge, build takes minutes | See *Pack size and frames* below: `anim.fps.inspect`, `skip-animations`, `hide-groups`, `anim.max-frames`. |
| Animation barely visible | Too few frames or the motion sits on a sub-group instead of the top-level gun group. |
| No sound | File missing in `models/sounds/`, or the effect name differs from the file name. |

## Third person

Animations are first-person only. Each frame is a `minecraft:composite` of the gun's bones, every bone a sub-model
whose display rotation/translation is that bone's pose in the frame (per-part rotation data, never the whole gun as
one rigid object). The items file selects those frame parts only for `firstperson_righthand` / `firstperson_lefthand`;
`thirdperson_*`, `ground`, `fixed`, `head` and the GUI fallback get the rest pose, without hands or flash. Hands
modelled in the .bbmodel (see HANDS.md) are therefore invisible to other players. `third-person.animate: true` in
config.yml shows the frames in third person too.

## Aiming (0.20)

Hold right-click to aim. The gun's **aim pose** is ONE model baked from the end of the `.bbmodel` animation named
`aim` (keywords aim / ads / scope / aiming / sight): while the player holds right-click the plugin shows that model
to their client only, through a hotbar slot packet, and zooms through a movement-speed modifier. No frames are
baked for aiming; animations named `aim_in` / `aim_out` are recognised and skipped. Firing or reloading while aiming
plays that clip from the rest pose (the gun comes off the centre, as the clip is modelled) and snaps back onto the
sight when it ends. A gun with no `aim` animation keeps its rest pose while aiming (zoom only).

## Reload and inspect variants (0.20)

Every animation matching the reload keywords is baked (`reload`, `reload_2`, `reload_3` ... in score and name
order); the same for inspect. Which one plays is chosen by the magazine and the player's health:

| Name contains | Plays when |
| --- | --- |
| `empty` | the magazine is empty |
| `full` or `tactical` | the magazine still holds rounds |
| none of those | either case |

Among the fitting clips the **shortest** plays at full health and the **longest** at low health; with
`reload.use-clip-length: true` (default) the reload takes exactly as long as the clip that plays, so a fast
tactical reload really is faster. Other clips keep the best-matching animation only; `anim.random-variants` in
config.yml lists the clips that get variants (default reload, inspect).

## Sounds named after the model

Drop `models/sounds/<model>_gunshot.ogg`, `<model>_reload.ogg` and `<model>_inspect.ogg` next to the models:
they are packed as `firearms:<model>_gunshot` etc. and used for that gun automatically unless guns.yml names
another sound. Keyframe sounds in the Effects animator still work as before.

## Pack size and frames (0.21) - and why there is no interpolation

**Fact check.** Vanilla Minecraft cannot interpolate between two item models. An item model is a fixed set of
cubes with a fixed display transform per context; the item model definition (`minecraft:select`, `composite`,
`condition`) only chooses which fixed models to draw. The only client-side interpolation vanilla has is for
*textures* (`.mcmeta` `interpolate: true` cross-fades texture frames) and for *display entities*
(`item_display` / `block_display` with `interpolation_duration`, which blend translation, rotation and scale
over a number of ticks). Neither applies to the item in a player's hand, so a first-person animation made of
item models is always a sequence of baked frames - every vanilla-compatible gun system (OBJMC/viewmodel data
packs, TaCZ-style packs converted to custom_model_data) does exactly that. Display entities could animate a gun
with real interpolation, but they are world entities: they clip into walls near the camera, scale with the FOV
setting, show up floating in F5 for everyone, cannot use the player's skin for hands, and need a stream of
packets per bone per player. That is a different renderer, not a smaller pack, so the plugin stays with item
models and makes the frames cheap instead.

**How frames are stored now.** A frame is a `minecraft:composite` list of parts; a part is a tiny wrapper file
(`{"parent": geometry, "display": {...}}`). Since 0.21:

- the geometry of each part is written once, and every *distinct pose* of a part is one wrapper file shared by
  every frame of every clip that shows the part like that;
- only `firstperson_righthand` is written into frame wrappers (the client uses it for the left hand too);
- a sampled frame identical to the previous one is not written - the previous frame holds, and
  `models/anim-index.yml` says when each written frame starts (`frame-at`), which the plugin follows;
- groups no animation moves are folded into the animated group above them, so the part count is the number of
  animated groups plus one per distinct free cube rotation, not the number of groups;
- clips are sampled at `anim.fps` (default 20 = one per server tick, the most a client can be shown; inspect
  clips 10), capped by `anim.max-frames` (200); animations that map to no clip the plugin plays (`switch_auto`,
  `static_semi`...) are not baked unless `anim.bake-unknown: true`.

The rifle and shotgun packs sent by the team (TaCZ exports: 1004 and 764 cubes, 139 and 87 groups, 17 and 31
animations) measured on Paper 26.3:

| | files | zip | raw | build |
| --- | --- | --- | --- | --- |
| 0.20 | 444 000 | 227 MB | 495 MB | 3.5 min |
| 0.21, stock settings | 131 000 | 42 MB | 42 MB | 5 s |
| 0.21 + `skip-animations` (`*_xmag`, `*_semi*`) + `hide-groups` (spare mags, folded sights, second handguard/adapter) | 66 000 | 21 MB | 21 MB | 4 s |

The file count matters twice: in the zip, and for whoever reads the files. Since 0.23 the zip is served by the plugin
itself (same-port), not unpacked into RP Engine's content folder, because RP Engine reads every file on the main
thread. Most of a 0.21 pack is zip bookkeeping for the wrapper files (about 190 bytes per file for 130 bytes of
content), so the levers are the number of frames (fps, skipped animations, `anim.max-frames`) and of parts
(hidden groups). The pack report's `build time` and `zip:` lines show where the time goes.
