package fi.alavesa.firearms.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a frag grenade explodes, once per grenade, BEFORE its damage is applied to living targets. It exists for things
 * that are not living and so never get a {@link FirearmsHitEvent} - doors, barricades, destructible props. Informational:
 * not cancellable. {@code radius} and {@code damage} are the grenade's own numbers from grenades.yml (damage = half-hearts at
 * the centre, falling off linearly to 0 at the radius); the listener decides what that means for its own objects.
 */
public final class FirearmsExplosionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player thrower;      // may be null (offline)
    private final Location location;
    private final double radius, damage;
    private final String sourceId;     // the grenade id

    public FirearmsExplosionEvent(Player thrower, Location location, double radius, double damage, String sourceId) {
        this.thrower = thrower;
        this.location = location;
        this.radius = radius;
        this.damage = damage;
        this.sourceId = sourceId == null ? "" : sourceId;
    }

    public Player thrower() { return thrower; }

    /** A copy: where the grenade went off. */
    public Location location() { return location.clone(); }

    public double radius() { return radius; }

    public double damage() { return damage; }

    public String sourceId() { return sourceId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
