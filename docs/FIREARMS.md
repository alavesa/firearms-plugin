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

Pack delivery (0.23): `pack.host.mode: auto` serves `Firearms-pack.zip` on the Minecraft port itself, so there is
no port to open and nothing to upload; the client downloads it as a second pack next to the server's own (RP Engine's,
if installed). Behind a Velocity/Bungee proxy, which forwards only the game protocol, set `upload`. The `rpengine`
mode still exists but is not for animated packs: RP Engine reads every file of its content folder on the main
thread at every reload and start, which with 100 000 frame files freezes the server for a minute.

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

A gun model is a Blockbench file (4.x or 5) with groups. Every animated group becomes its own sub-model whose
display transform carries its exact pose per frame, cubes rotated by angles a Java element cannot hold get their
rotation from a display transform too (nothing is rounded), every animation is baked into frames, and the pack
selects the frames only for first person: third person, the ground, item frames and the GUI show the rest pose
without hands or flash. Vanilla cannot interpolate between item models, so frames are the only way; 0.21 stores
them as one file per distinct part pose, holds unchanged frames and samples inspect clips at 10 fps
(`anim.fps`), which brought the team's rifle+shotgun pack from 227 MB to 42 MB as is and 21 MB with the per-gun skip and hide
lists below (ANIMATIONS.md explains).

Gun packs converted from other systems (TaCZ and the like) carry every attachment variant and every fire-mode
variant of each animation. Per gun in guns.yml: `hide-groups: [mag_extended_1, stock_folded*]` leaves groups
out (the pack report lists them), `skip-animations: ["*_semi*"]` leaves animations out, and an animation named
`static_idle` is used as the base pose under everything (scale 0 hides a group).

Animation names map to clips by keywords (`anim.names` in config.yml, per gun `anim-names` in guns.yml):

| Clip | Keywords | Plays |
| --- | --- | --- |
| fire | fire, shoot, shot, recoil | every shot |
| reload | reload, rel, mag | reload (variants: see ANIMATIONS.md) |
| equip | equip, draw, deploy, pull, ready | when the gun is selected |
| pump | pump, rack, cock, bolt, cycle | pump-action guns between shots |
| inspect | inspect, look, check | F with a full gun (variants) |
| aim | aim, ads, scope, aiming, sight | its end pose is shown while aiming |

Knockback (0.22): every shot pushes the shooter back a little against the aim, per gun `knockback` in guns.yml
(blocks per tick per shot, default `knockback.default` 0.08, halved while aiming). It is added to the player's own
motion, so a sprinting player keeps sprinting and only loses a touch of speed for a tick. The stat shows in the gun's
lore next to recoil (scale `stats.max.knockback`); guns already in inventories get the new lore when re-given.

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
| guns are purple-and-black cubes after a rebuild, or the pack only updates after a rejoin | until 0.23 the pack was handed to RP Engine, which reads every one of its 100 000+ files on the main thread at each reload and start: a minute of frozen server, a watchdog restart, a half-delivered pack. Since 0.23 the zip is served by Firearms itself on the Minecraft port (`pack.host.mode: auto` = same-port) as a second pack; RP Engine keeps only the emote rigs |
| adding one more gun turns every gun purple-and-black on clients | client memory: the client bakes every frame of every gun, roughly cubes x faces x frames x 200 bytes (ANIMATIONS.md, *Client memory*); the pack report's `client memory:` line and the CLIENT MEMORY warning say how far over it is; trim with `skip-animations`, `hide-groups`, `anim.fps` |
| adding one model turns every gun into a plain crossbow | one bad case drops the whole items file on the client; since 0.22 a model that fails to convert is rolled back to a placeholder and every case is validated before it is written - read the warning, which names the model and the cause, and send that .bbmodel |
| a Blockbench 5 model is scrambled, its animations swing wildly | plugin older than 0.21 ignored the `groups` list of Blockbench 5 files (every pivot became 0,0,0); update and rebuild |
| the pack is hundreds of MB or the build takes minutes | `anim.fps.inspect: 10` (default), per gun `skip-animations` / `hide-groups`, `anim.max-frames`; see ANIMATIONS.md *Pack size and frames* |
| three magazines or two stocks show at once | an attachment pack: `hide-groups:` per gun in guns.yml |
| players do not get the pack | `/firearms host` shows the delivery mode and the last problem |
