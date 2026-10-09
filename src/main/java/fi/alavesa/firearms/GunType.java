package fi.alavesa.firearms;

/** One gun definition (guns.yml). Immutable; the Registry rebuilds these on reload. */
public record GunType(
    String id,
    String name,
    String model,
    double weight,          // kg -> walk-speed penalty while held
    boolean auto,           // default fire mode: auto (hold to spray) or semi (one per click)
    boolean switchable,     // fire-mode: "semi,auto" -> right-click toggles the mode per gun item
    double fireRate,        // rounds per second
    double damage,          // at full strength (inside hitscan range), per pellet
    int magazine,           // rounds the gun holds
    String magId,           // magazine type, or "" for round-by-round (pump) guns
    String ammoId,          // round type used when magId is "" (pump/tube guns)
    boolean pumpAction,     // reload one round at a time, interruptible by firing
    double reloadSeconds,   // mag swap time, or seconds per round for pump-action
    double hitscanRange,    // instant-hit distance
    double range,           // max travel distance
    double speed,           // projectile blocks/tick after the hitscan range
    double accuracy,        // hip spread, degrees
    double aimAccuracy,     // sneaking spread, degrees
    double recoil,          // vertical kick, degrees
    double hRecoil,         // horizontal kick, +-degrees
    double falloffMin,      // damage multiplier at max range
    int pellets,
    String sound,
    float pitch,
    double[] muzzle,        // where the tracer / flash starts: right, up, forward (blocks) from the eye
    double[] flashAt,       // muzzle flash position in MODEL pixels for the baked fire frames, or null = auto
    org.bukkit.configuration.ConfigurationSection display,  // optional first/third-person display override (guns.yml display:)
    String casing,          // casing model (custom_model_data string), "" = no casings
    double[] eject,         // ejection port: right, up, forward (blocks) from the eye
    org.bukkit.configuration.ConfigurationSection arms,     // per-gun first-person arms override (guns.yml arms:)
    double dropStart,       // blocks the bullet flies dead straight before gravity starts to arch it
    double drop,            // per-tick downward velocity gain once arching (severity of the arch)
    double equipSeconds     // draw time: the equip clip is stretched/compressed to this and the gun can't fire meanwhile (0 = clip length / 0.5 s)
) {
    public long shotIntervalMs() { return fireRate <= 0 ? 1000 : Math.round(1000.0 / fireRate); }
    public boolean usesMag() { return magId != null && !magId.isEmpty() && !magId.equalsIgnoreCase("none"); }
}
