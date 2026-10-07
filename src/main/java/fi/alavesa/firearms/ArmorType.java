package fi.alavesa.firearms;

/** A ballistic vest (config.yml armor:). Worn in the chest slot. Durability = absorb-hearts of bullet damage
 *  soaked before it breaks; while worn it adds `hearts` of max health; `absorb` = share of each bullet's
 *  damage the vest takes instead of the wearer. */
public record ArmorType(String id, String name, String model, double hearts, double absorbHearts, double absorb, int color) { }
