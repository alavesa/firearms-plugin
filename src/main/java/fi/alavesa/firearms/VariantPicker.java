package fi.alavesa.firearms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which of a clip's baked variants (reload, reload_2, ... or inspect, inspect_2 ...) plays now.
 *
 * <p>By the magazine: an animation whose name says "empty" is for an empty magazine, one that says "full" or
 * "tactical" for a magazine that still holds rounds; a name with neither fits both. By health: among the fitting
 * variants the SHORTEST plays at full health and the LONGEST at low health (the reload takes as long as its clip),
 * the ones between spread evenly over the health bar.
 */
public final class VariantPicker {
    private VariantPicker() { }

    /** One baked variant: its clip key, the animation's own name and its length in ticks. */
    public record Variant(String key, String name, int ticks) { }

    enum Kind { EMPTY, TACTICAL, ANY }

    static Kind kind(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("empty")) return Kind.EMPTY;
        if (n.contains("full") || n.contains("tactical")) return Kind.TACTICAL;
        return Kind.ANY;
    }

    /** @param magazineEmpty no rounds left in the gun
     *  @param health 0..1, the player's health fraction */
    public static Variant pick(List<Variant> all, boolean magazineEmpty, double health) {
        if (all == null || all.isEmpty()) return null;
        Kind want = magazineEmpty ? Kind.EMPTY : Kind.TACTICAL;
        List<Variant> fit = new ArrayList<>();
        for (Variant v : all) { Kind k = kind(v.name()); if (k == want || k == Kind.ANY) fit.add(v); }
        if (fit.isEmpty()) fit = new ArrayList<>(all);
        fit.sort(Comparator.comparingInt(Variant::ticks).thenComparing(Variant::key));
        double h = Math.max(0, Math.min(1, Double.isNaN(health) ? 1 : health));
        int idx = (int) Math.round((1 - h) * (fit.size() - 1));
        return fit.get(Math.max(0, Math.min(fit.size() - 1, idx)));
    }
}
