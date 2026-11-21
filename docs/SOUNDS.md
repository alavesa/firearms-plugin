# Sounds: guns, Blockbench keyframes and sound files

Everything here is done by `/firearms pack`. It reads `plugins/Firearms/models/`, puts the sounds into the resource
pack and writes which sound plays at which moment of which animation. Run it again after every change.

## 1. Where the files go

Minecraft plays **only Ogg Vorbis (`.ogg`)**. Convert `.wav` / `.mp3` first (Audacity: File > Export > OGG, or
`ffmpeg -i in.wav -c:a libvorbis out.ogg`). Mono files are heard from where the gun is; stereo files are heard
"inside the head" at full volume wherever the shooter is.

Put the `.ogg` files **anywhere under `plugins/Firearms/models/`**. The folder becomes part of the sound's name:

| File | Sound id |
|---|---|
| `models/sounds/m4_shot.ogg` | `firearms:m4_shot` |
| `models/sounds/m4a1/mag_in.ogg` | `firearms:m4a1.mag_in` |
| `models/m4/sounds/bolt.ogg` | `firearms:m4.sounds.bolt` |

Names are lower case; anything but `a-z 0-9 _` becomes `_`. The pack report (`plugins/Firearms/pack-report.txt`)
lists every sound that went in.

## 2. Sounds in the animations (Blockbench)

1. In Blockbench open the animation, add the **Effects** animator (the speaker icon above the timeline) and put a
   **Sound** keyframe where the sound should start.
2. In the keyframe pick the sound file. Blockbench stores the **file name** (and the path on your own computer) in
   the `.bbmodel`. The audio itself is **not** inside the `.bbmodel`.
3. Copy that `.ogg` to the server, anywhere under `models/` (section 1), with the **same file name**.
4. `/firearms pack`.

How the keyframe is matched to a file: the plugin takes the **file name** of the keyframe (only the last part of the
path, whether it was written on Windows or Mac), then the keyframe's **effect name** if there is no file, lower-cases
it and looks for an `.ogg` with that name under `models/`. An effect name such as `tacz:m4a1/m4a1_reload_start` is
matched by its last part, `m4a1_reload_start`.

When a keyframe's file is not found the pack report says so, per sound:

```
! m4/m4a1: sound 'm4a1_inspect_magout' (animation 'inspect') not found - put m4a1_inspect_magout.ogg anywhere under models/
```

and when the keyframe points at a `.wav`/`.mp3`, it says to convert it.

**What was wrong before 0.34.** Up to 0.33.1 the plugin looked only in `models/sounds/` and next to the `.bbmodel`, and
it read the keyframe's file path as if it were a path on the server. Blockbench on Windows writes
`C:\Users\...\shot.ogg`; on the server the backslashes are not separators, so the whole path counted as the name and
nothing ever matched. Every keyframe sound of a model animated on Windows was silently dropped.

## 3. When the animation sounds play

| Animation (clip) | When |
|---|---|
| `fire` / `shoot` | every shot |
| `reload*` | reload (the plugin picks empty / tactical / extended variants as before) |
| `equip` / `draw` | switching to the gun |
| `inspect*` | F with a full gun |

A clip's sounds play at their keyframe times, stretched with the clip when guns.yml makes a reload longer or shorter,
from the shooter's position, for everyone in hearing range. Switching away from the gun cancels the rest.

## 4. Sounds without keyframes

Any packed sound can be named in guns.yml instead:

```yaml
rifle:
  sound: firearms:m4_shot          # the shot (played on top of a fire-clip keyframe sound, so use one or the other)
  reload-sound: firearms:m4a1.mag_in
  equip-sound: firearms:m4a1.raise
  empty-sound: firearms:dry_fire
```

Or drop `models/sounds/<model>_gunshot.ogg`, `<model>_reload.ogg`, `<model>_inspect.ogg` next to the models and they
are used for that model automatically.

## 5. Checking

1. `/firearms pack` and read `plugins/Firearms/pack-report.txt`: every sound found is listed; every keyframe that
   was not found is a `!` line.
2. `/firearms clips <gun>` lists the clips and their sound keyframes (tick: sound id).
3. In game: `/playsound firearms:<id> master @s` plays a packed sound directly. If that is silent, the pack did not
   load on your client (or the file is not Ogg Vorbis); if it plays but the animation is silent, the keyframe was
   not matched (step 1).
