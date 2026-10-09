package fi.alavesa.firearms;

/** A grenade (grenades.yml). kind = frag | incendiary | smoke. */
public record GrenadeType(
    String id,
    String name,
    String model,
    String kind,
    double fuse,        // seconds from unpin (cook) or from the throw to the effect
    boolean cook,       // true: the fuse starts when you unpin (right-click); false: when thrown
    double radius,      // effect radius in blocks
    double damage,      // frag: damage at the centre (half-hearts), linear falloff to the edge
    double duration,    // incendiary: seconds the fire burns; smoke: seconds the cloud lasts
    double speed        // throw speed, blocks per tick
) { }
