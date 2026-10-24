# Firearms and RP Engine

Since 0.18 the plugin hands its models to [RP Engine](https://hangar.papermc.io/ResourcePackAI/RPEngine), which builds
one resource pack for the whole server, hosts it and sends it to players. BetterModel is not used anymore.

## What `/firearms pack` does

1. Every `.bbmodel` in `plugins/Firearms/models` is converted; each animation is baked into frames, one sub-model per
   bone with that frame's exact rotation and position (composite mode). Output: `plugins/Firearms/Firearms-pack.zip`
   and `pack-report.txt`.
2. With RP Engine installed (`pack.host.mode: auto` → `rpengine`) the zip is unpacked into
   `plugins/RPEngine/content/firearms/`: models and textures under `assets/`, the crossbow / brick / leather
   chestplate item definitions under `overrides/`, plus a `pack.yml`. Do not edit that folder; every build rewrites it.
3. RP Engine is reloaded: it merges all content packs into one bundle, rebuilds and hosts it and sends it to everyone
   online. Newcomers get it on join from RP Engine.
4. Build warnings are printed in chat: frames missing from the pack, clips capped by `anim.max-frames`, parts beyond
   the client's 80 px display limit (they float), cubes hidden in Blockbench that were left out, non-finite numbers
   repaired. Parts that do not move in a frame share the rest-pose file (fewer files to zip).

## The reload pause

RP Engine rebuilds and zips the whole merged pack inside its reload, on the main thread, so the server does not tick
while it works. Paper's watchdog prints "The server has not responded for 50 seconds" with a thread dump when it
takes that long; it is not a crash. Firearms batches the reload: it runs `pack.host.rpengine-reload-delay` seconds
(default 20) after the last build, so a tuning session with several `/firearms pose` changes costs one pause, and
admins are told when it is coming and how long it took. `0` reloads at once, `-1` never reloads from Firearms (the
files are in place; run `/rp reload` at a quiet time). An unchanged pack is not handed over again.

## Tuning where a gun sits

```
/firearms pose <gun>                                  list every display state
/firearms pose <gun> [state] offset <x> <y> <z>       model pixels: +x right, +y up, +z towards you
/firearms pose <gun> [state] rotate <x> <y> <z>       degrees
/firearms pose <gun> [state] scale <s>
/firearms pose <gun> [state] reset
```

States: `first` (default; firstperson_righthand + lefthand), `third`, `gui`, `ground`, `fixed`, `head`. `~` keeps a
value, `~2` adds 2. Values are saved to `guns.yml` under the gun's `display:` section, the pack is rebuilt and handed
over. A state that was never set uses the `.bbmodel`'s own Display values. Animations exist in first person only;
every other state shows the rest pose without hands or flash.

## Flinch animations (emotes)

Put a BetterModel-style player rig in `plugins/Firearms/models/players/player_rig.bbmodel` (bones `ph`, `pc`/`pw`/
`phip`, `pra`/`prfa`, `pla`/`plfa`, `prl`/`prfl`, `pll`/`plfl`, `player_root`). It is mirrored into
`plugins/RPEngine/content/firearms/players/` and RP Engine turns each animation into the emote `<rig>_<animation>`.
On a hit the first existing emote of `head_left → head → body_left → body` (per zone and side) is played; names are
in `config.yml` under `hit-animations`. Emotes need Minecraft 1.21.4+ clients; RP Engine ends an emote on movement or
vanilla damage, so keep flinches short.

## Setup

1. Install RP Engine (0.2.22+), remove BetterModel.
2. Delete the old Firearms jar, add the new one, restart.
3. Models in `plugins/Firearms/models`, the rig in `models/players`, then `/firearms pack`.
4. Players need nothing. `/rp push` re-sends the pack to a player who sees purple models.
