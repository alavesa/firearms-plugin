# Firearms plugin

Ray-based guns for Paper 26.x: hold-to-spray, F reload, hold right-click to aim, magazines, hitscan plus slowing
projectiles, wall penetration, packet recoil, craters, blood, vests, grenades, and a resource-pack generator that
turns Blockbench models and animations into first-person gun models delivered through RP Engine.

## Install

1. Paper 26.2 or newer. RP Engine is optional but recommended (pack delivery, flinch emotes).
2. Drop `Firearms-<version>.jar` into `plugins/` - delete any older Firearms jar first, Paper loads only the first
   jar of a name.
3. Start once; `plugins/Firearms/` gets `config.yml`, `guns.yml`, `mags.yml`, `grenades.yml` and `models/`.
4. Put `.bbmodel` files into `models/` (one per gun, named like the gun's `model:` in guns.yml), run
   `/firearms pack`. With RP Engine installed the pack is merged into RP Engine's pack and sent to everyone; see
   [RPENGINE.md](RPENGINE.md). Without it, `pack.host.mode` chooses between an upload host, a built-in HTTP server
   or a URL of your own.

## Controls

| Input | Does |
| --- | --- |
| Left-click | fire (semi: one shot per click; auto: hold to spray) |
| Hold right-click | aim down the sight: zoom, tighter spread, the gun's aim pose |
| F | reload (magazine swap, or one round for pump guns); F again with a full gun = inspect |
| Sneak | tighter spread, like aiming |
| Right-click with a magazine | fill it from loose rounds |
| Left-click with a grenade | unpin and throw |

## Commands

| Command | Does |
| --- | --- |
| `/firearms list` | every gun, magazine, round, vest and grenade id |
| `/firearms give <id> [amount] [player]` | give an item |
| `/firearms pack` | build the resource pack from `models/` and deliver it |
| `/firearms check` | build without writing: just the warnings |
| `/firearms pose <gun> [state] offset|rotate|scale|reset ...` | where the gun sits per display state (first, third, gui, ground, fixed, head) |
| `/firearms clips <model>` | which animation clips the pack baked for a model |
| `/firearms models` | which model files are expected and found |
| `/firearms host` | pack delivery status |
| `/firearms reload` | re-read the configs |

Permissions: `firearms.use` (default), `firearms.give` and `firearms.admin` (ops).

## guns.yml

One entry per gun. The important keys:

| Key | Meaning |
| --- | --- |
| `name`, `model` | display name; the `.bbmodel` file name |
| `fire-mode` | `semi` or `auto` (fixed per gun) |
| `fire-rate`, `damage`, `pellets` | rounds per second, damage per round, rounds per trigger pull |
| `magazine`, `mag` | capacity and the magazine ids it accepts |
| `reload-seconds` | reload time used when the clip length is not (config `reload.use-clip-length`) |
| `accuracy`, `aim-accuracy`, `recoil` | hip spread, aimed spread (degrees), vertical kick |
| `hitscan-range`, `range`, `falloff-min` | instant-hit distance, maximum distance, damage multiplier at the end |
| `weight` | walk-speed penalty while held |
| `sound`, `reload-sound`, `equip-sound`, `empty-sound` | sound ids (Minecraft or `firearms:<file>`) |
| `muzzle`, `flash`, `casing`, `eject` | where the tracer starts, the baked flash cube, casing model and ejection |
| `display` | per display-state pose, written by `/firearms pose` |
| `anim-names`, `anim` | per-gun clip keywords and frame counts |

The file's header comments document every key.

## Models and animations

A gun model is a Blockbench file with bones. Each bone becomes its own sub-model so animations keep exact
rotations (no 22.5-degree rounding), every animation is baked into frames, and the pack selects the frames only
for first person: third person, the ground, item frames and the GUI show the rest pose without hands or flash.

Animation names map to clips by keywords (`anim.names` in config.yml, per gun `anim-names` in guns.yml):

| Clip | Keywords | Plays |
| --- | --- | --- |
| fire | fire, shoot, shot, recoil | every shot |
| reload | reload, rel, mag | reload (variants: see ANIMATIONS.md) |
| equip | equip, draw, deploy, pull, ready | when the gun is selected |
| pump | pump, rack, cock, bolt, cycle | pump-action guns between shots |
| inspect | inspect, look, check | F with a full gun (variants) |
| aim | aim, ads, scope, aiming, sight | its end pose is shown while aiming |

Details, hands painted with the player's skin, sound keyframes and the reload/inspect variant rules are in
[ANIMATIONS.md](ANIMATIONS.md) and [HANDS.md](HANDS.md).

`/firearms pack` prints what it could not do: frames missing from the pack, clips cut by `anim.max-frames`, parts
beyond the client's 80 px display limit, cubes hidden in Blockbench, cubes too large to fit after scaling, and a
self-check that compares every cube's final position with where Blockbench has it. A warning that names a part is
the thing to send along with the model file.

## Hits, blood, vests, grenades

- Bullets are traced instantly up to `hitscan-range`, then fly on as slowing projectiles with drop. One layer of
  the blocks listed under `ballistics.pass-through` is penetrated with reduced damage.
- Hit zones: head, body and legs multipliers (`damage.zones`); vests (leather chestplates) absorb a share of body
  hits and carry their own durability.
- Blood decals land on walls behind a victim; craters on blocks; shell casings fall with gravity and settle on the
  real surface of slabs, carpets and stairs.
- Grenades: frag, incendiary and smoke, thrown with left-click after an unpin animation.
- With RP Engine, a shot player plays a flinch emote chosen by hit zone and side (`hit-animations`).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| "update did nothing" | an older Firearms jar is still in `plugins/`; delete it and restart |
| every model purple | one bad item file rejects the whole items definition; run `/firearms check` and fix the warnings |
| a frame is purple while firing | the pack players hold is older than the server's animation index; run `/firearms pack` and let it re-send |
| the server pauses after `/firearms pack` | RP Engine rebuilding its pack on the main thread; see RPENGINE.md (batched reload) |
| a part floats or is twisted | run `/firearms pack`, read the self-check and range warnings, send the named part and the file |
| players do not get the pack | `/firearms host` shows the delivery mode and the last problem |
