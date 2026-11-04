# Firearms

Ray-based guns plugin for Paper 26.x (compiled against the 1.21.4 API, boot-tested on 26.2 and 26.3).

- LEFT-click fires; **hold** left on an AUTO gun to spray (client-side barrier "mining stream" hold detection), SEMI = one per click
- **F** reloads: magazine swap, or one round at a time for pump-action guns (shooting interrupts and keeps what was loaded)
- Ammo count shown on the item's durability bar
- Weapon weight (walk-speed penalty), accuracy (hip / aim / moving / sprint / air), packet-driven smooth recoil
- Bullets are **rays**: instant hitscan to `hitscan-range`, then a slowing, dropping projectile out to `range`; damage falls off in between
- Pass-through blocks (wood, wool, iron bars, water, lava, powder snow) when only ONE layer thick
- Magazines + ammo types: guns share magazine types, right-click a magazine to fill it from loose rounds
- Craters (bullet holes) as ItemDisplays you texture
- `.bbmodel` → resource pack: drop Blockbench files in `plugins/Firearms/models/`, run `/firearms pack`; first person is a client-side item_display rig (display mode, default since 0.27), so post effects reach the gun and `/viewmodel` moves it per player
- Smooth eased camera recoil (rotation packets only), camera shake; flashbang as a fading full-screen font glyph (no shaders anywhere)
- Grenades: frag, incendiary, smoke, flashbang; an `experimental` gun where new features (rhythm fire detection, camera shake) are tried first; `/firearms rig` + docs/VIEWMODEL.md for viewmodel troubleshooting

Commands: `/firearms list | give <id> [n] [player] | models | pack | mode | reload`, `/viewmodel <right> <up> <forward> | reset`

## RP Engine

Since 0.18 models are built and delivered through RP Engine; see [docs/RPENGINE.md](docs/RPENGINE.md) for how it works and how to set it up.

## Documentation

[docs/FIREARMS.md](docs/FIREARMS.md) is the full guide (install, controls, commands, guns.yml, models, troubleshooting); [docs/ANIMATIONS.md](docs/ANIMATIONS.md), [docs/HANDS.md](docs/HANDS.md) and [docs/RPENGINE.md](docs/RPENGINE.md) go deeper.
