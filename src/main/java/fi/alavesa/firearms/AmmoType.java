package fi.alavesa.firearms;

/** A loose round type (mags.yml ammo:): fills magazines, or loads pump-action guns directly. */
public record AmmoType(String id, String name, String model, int stack) { }
