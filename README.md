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
- `.bbmodel` → resource pack: drop Blockbench files in `plugins/Firearms/models/`, run `/firearms pack`; every animation is baked to frames automatically

Commands: `/firearms list | give <id> [n] [player] | models | pack | reload`
