package fi.alavesa.firearms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static fi.alavesa.firearms.VariantPicker.Variant;

class VariantPickerTest {
    final List<Variant> v = List.of(
        new Variant("reload", "reload", 20), new Variant("reload_2", "reload tactical", 12),
        new Variant("reload_3", "reload empty", 30), new Variant("reload_4", "reload empty slow", 44));

    @Test
    void emptyMagazineUsesTheEmptyVariants() {
        assertEquals("reload", VariantPicker.pick(v, true, 1.0).key());     // full health: the shortest that fits (a neutral name fits both)
        assertEquals("reload_4", VariantPicker.pick(v, true, 0.0).key());   // low health: the longest empty-mag clip
        assertNotEquals("reload_2", VariantPicker.pick(v, true, 0.6).key(), "a tactical clip never plays on an empty magazine");
    }

    @Test
    void loadedMagazineUsesTacticalAndNeutralOnes() {
        assertEquals("reload_2", VariantPicker.pick(v, false, 1.0).key());  // tactical is the fastest
        assertEquals("reload", VariantPicker.pick(v, false, 0.0).key());    // the neutral one is the slowest fitting
    }

    @Test
    void healthSpreadsOverTheFittingClips() {
        List<Variant> three = List.of(new Variant("a", "r1", 10), new Variant("b", "r2", 20), new Variant("c", "r3", 30));
        assertEquals("a", VariantPicker.pick(three, false, 1.0).key());
        assertEquals("b", VariantPicker.pick(three, false, 0.5).key());
        assertEquals("c", VariantPicker.pick(three, false, 0.1).key());
    }

    @Test
    void fallsBackWhenNoVariantFitsTheMagazine() {
        List<Variant> onlyEmpty = List.of(new Variant("x", "reload empty", 10));
        assertEquals("x", VariantPicker.pick(onlyEmpty, false, 1.0).key());
        assertNull(VariantPicker.pick(List.of(), true, 1.0));
    }
}
