package fi.alavesa.firearms;

/** A magazine type (mags.yml): holds rounds of one ammo type; any gun with mag: id accepts it. */
public record MagType(String id, String name, String ammoId, int capacity, String model) { }
