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
- `/firearms mode`: switch a `semi,auto` gun between auto and semi (right-click aims since 0.19).


## Viewmodel (0.27)

`/viewmodel <right> <up> <forward>` moves every gun on your own screen; each value is -5..5, one unit = one sixteenth
of a block (a model pixel). `/viewmodel reset` and `/viewmodel show`. It is one offset for all guns: where each gun
sits relative to the others is the model's business (`/firearms pose`, baked into the pack for everybody). The setting
is the player's own, saved in `viewmodels.yml`, and applied at once with no pack change, because the display rig
computes the placement per player - which is why the rig is the default first person since 0.27. A gun opted out of
the rig (`anim: { mode: composite }`) cannot follow it.

## Post effects, the rig and core shaders (0.27)

Since Minecraft 26.3 the item in your hand is drawn into its own "3D HUD" layer *after* the screen post effects
(`GameRenderer.render3dHud` into `hud3DTarget`, composited after `applyPostEffects`). Whatever a post effect does to
the view - night-vision goggles through `/posteffect`, the flashbang - the vanilla held item ignores it, which is the
"rigid gun" in the screenshot. An item_display entity is part of the world and gets every post effect, so the
display rig (ANIMATIONS.md *Display mode*) is now the default for all guns: `anim.mode: display`, the plugin flips an
older config once, then `/firearms pack`. Frames mode stays available per gun.

The pack carries core shaders (`pack.shaders: true`, 26.3 clients):

- `assets/minecraft/shaders/core/item.vsh` - vanilla's item vertex shader plus the Firearms signal. A gun with
  `shader: true` has every non-skin face of its rig parts tinted by a `dyed_color` the plugin writes: `0xA5 GG BB`,
  GG = the game tick of the last shot (mod 256, latency-compensated), BB = `0xC0 | strength`. With `GameTime` from the
  Globals block the shader knows each frame how long ago the shot was and computes the kick (up, toward the camera,
  settling over 7 ticks) and a slow idle sway *per vertex, at the client's frame rate* - dynamic vertex manipulation
  with one item update per shot. The tint is removed again inside the shader (the part is drawn white = its texture).
  Hands carry skin tints instead of the signal, so the server applies the same curve to them at tick rate
  (`DisplayRig.fxMatrix`, mirrored in the shader - keep both the same). Camera shake is `shake:` in guns.yml: a core
  shader cannot move *your* camera without a per-client signal the world shaders never get, so the camera is still
  rotation packets.
- `assets/firearms/post_effect/flash_1..N.json` + `shaders/post/flash.fsh` - the flashbang's whiteness levels.

A client that rejects the shader (26.2 and older do not know `#include`) shows signalled parts tinted; everything
else is unaffected. `pack.shaders: false` builds a pack without any of it.

## Flashbang (0.27)

`grenades.yml kind: flash`: everyone within `radius` with a line of sight gets a white screen, strongest near and
facing it, fading over `duration` seconds (scaled by how hard it hit; nothing through a wall; a turned back takes a
third). The white is the post effect from the pack switched through its levels tick by tick (config `flashbang:`),
on a server without post effects (before 26.3) Blindness for the same time.

## The experimental gun (0.27)

`experimental` in guns.yml is where new features are tried first: `fire-detect: rhythm`, `shader: true`, `shake:`.
`/firearms give experimental`; model it as `models/experimental.bbmodel` (a placeholder until then). Nothing on it is
promised to the other guns before it has been seen working.

## Tap or hold - what the server can actually see

The Minecraft client tells the server about the left button like this: one `ServerboundSwingPacket` (and a
`PlayerInteract`) when the button goes DOWN on air or on an entity, and *nothing at all* while it stays down. Only
when the crosshair is on a block does the client keep sending - a swing every tick plus `START_DESTROY_BLOCK` /
`ABORT_DESTROY_BLOCK` / `STOP_DESTROY_BLOCK` - because it is mining. There is no "button released" packet for air.
So a server sees, per press, one timestamped packet; from timestamps alone it can tell *rhythm* - how fast you tap,
double taps, bursts - but it cannot tell a long hold from a single tap: both are one packet.

Two ways to turn that into semi/auto without a client mod:

- `fire-detect: barrier` (default): a client-side-only barrier is kept ahead on the crosshair, so a held button is a
  mining stream the server sees every tick; release = the stream stops. Real hold detection, at the price of the
  block outline wireframe ahead of you.
- `fire-detect: rhythm` (experimental gun): no barrier. One tap = one shot. A second tap within
  `hold.rhythm-window-ticks` (6 = 300 ms) of the first is read as "held" and the gun keeps firing at its fire rate for
  as long as taps keep arriving inside the window; a gap longer than the window = released. It is tap cadence, not a
  hold: a player who holds the button gets exactly one shot.

Try rhythm on the experimental gun; if it feels right it can be set on any gun with `fire-detect: rhythm`.

## Commands

| Command | Does |
| --- | --- |
| `/firearms list` | every gun, magazine, round, vest and grenade id |
| `/firearms give <id> [amount] [player]` | give an item |
| `/viewmodel <right> <up> <forward>` | your own gun offset on screen, every gun, -5..5 (sixteenths of a block); `reset`, `show` |
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

Two first-person modes (`anim.mode`, per gun `anim: { mode: ... }`): `composite` swaps baked frames of the held item
(works like vanilla, 20 fps at most, every frame costs client memory); `display` draws the gun as a rig of
client-side item_display entities that the client interpolates itself (smooth at any frame rate, tiny pack, but a
world entity: clips into walls, scales with FOV, visible in F5 to the player). ANIMATIONS.md has the details.

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
| why the invisible barrier for holding the trigger | the Minecraft client reports a held left button only while it is mining a block under the crosshair (clicking air or an entity sends one swing per press, holding sends nothing more - checked in the client: attacks happen on key presses, only block mining repeats). A client-side, unbreakable barrier kept ahead on the crosshair ray is therefore the only vanilla way to know the button is still down. Everything about it is tunable under `hold:` in config.yml; set `hold.distance: 0` for tap-fire only |
| an auto rifle fires one shot per click | guns.yml `fire-mode: "semi,auto"`: since right-click became aiming (0.19) such guns were read as semi; since 0.26 they are AUTO, and `/firearms mode` toggles the held gun between auto and semi |
| `/viewmodel` changed nothing | before 0.27 it only applied to guns in display mode and the default was frames (and the rig itself failed to start on 26.3: the entity-type lookup, fixed in 0.27). Update, let the config flip to `anim.mode: display`, `/firearms pack` |
| the gun is not affected by night vision / a post effect | the vanilla held item is drawn after the post effects since 26.3; a gun on the display rig (default since 0.27) is a world entity and gets them |
| blood splatters / craters glow in the dark | until 0.27 decals had a fixed brightness; now they take the light of the block they lie on. Not a texture setting: `ambientocclusion` only affects block models |
| aiming stops while shooting | fixed in 0.27: while the trigger is held the client is mining the hold barrier and sends no right-clicks, so the sight timed out; a live trigger stream now keeps the aim (it is released a few ticks after you stop firing) |
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
