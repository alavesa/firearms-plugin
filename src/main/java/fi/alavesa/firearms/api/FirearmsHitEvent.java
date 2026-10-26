package fi.alavesa.firearms.api;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired AFTER Firearms has applied bullet or grenade damage to a living target (never for vehicles).
 * Other plugins (injury systems, stats, hit markers) listen to this instead of EntityDamageEvent, because
 * player victims are normally hurt "quietly" (health lowered directly, no Bukkit damage event at all).
 * Not cancellable: the damage already happened.
 */
public final class FirearmsHitEvent extends Event {

    public enum Kind { BULLET, FRAG }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Kind kind;
    private final Player shooter;        // may be null (offline / unknown)
    private final LivingEntity victim;
    private final String sourceId;       // gun id or grenade id
    private final String ammoId;         // round type the gun fires ("" when unknown / grenades)
    private final String zone;           // head | body | legs  (grenades: body)
    private final double damage;         // final amount applied, after vests, falloff, zones
    private final Location point;        // the wound (bullet impact) or the victim's location (grenade)
    private final int penetrations;

    public FirearmsHitEvent(Kind kind, Player shooter, LivingEntity victim, String sourceId, String ammoId,
                            String zone, double damage, Location point, int penetrations) {
        this.kind = kind; this.shooter = shooter; this.victim = victim; this.sourceId = sourceId == null ? "" : sourceId;
        this.ammoId = ammoId == null ? "" : ammoId; this.zone = zone == null ? "body" : zone; this.damage = damage;
        this.point = point; this.penetrations = penetrations;
    }

    public Kind kind() { return kind; }
    public Player shooter() { return shooter; }
    public LivingEntity victim() { return victim; }
    public String sourceId() { return sourceId; }
    public String ammoId() { return ammoId; }
    public String zone() { return zone; }
    public double damage() { return damage; }
    public Location point() { return point; }
    public int penetrations() { return penetrations; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
