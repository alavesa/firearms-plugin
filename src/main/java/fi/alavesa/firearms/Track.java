package fi.alavesa.firearms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One animated channel (rotation, position or scale) of one bone: its keyframes sorted by time, sampled the way
 * Blockbench plays them back. Blockbench keeps four interpolation modes per keyframe and this follows its
 * BoneAnimator.interpolate rules:
 * <ul>
 *   <li>linear: straight lerp between the two neighbouring keyframes;</li>
 *   <li>catmullrom (smooth): a uniform Catmull-Rom spline through the keyframe BEFORE the segment, the two
 *       keyframes of the segment and the one AFTER it (end keyframes duplicated when there is no neighbour) -
 *       Blockbench builds a THREE.SplineCurve of those points and takes the value at the segment's parameter;</li>
 *   <li>step: the earlier keyframe's value holds until the next keyframe;</li>
 *   <li>bezier: a cubic Bezier in (time, value) with the keyframes' handles (left/right time and value), found
 *       by sampling the curve like Blockbench does.</li>
 * </ul>
 * Before the first keyframe the first value holds, after the last the last one; an empty track is the channel's
 * neutral value (0, or 1 for scale).
 */
public final class Track {
    public enum Mode { LINEAR, CATMULLROM, STEP, BEZIER }

    /** One keyframe. Bezier handles are optional (null = straight handles, i.e. linear-looking). */
    public static final class Key {
        public final double time;
        public final double[] value;
        public final Mode mode;
        public double[] leftTime, leftValue, rightTime, rightValue;   // bezier handles, per axis
        public Key(double time, double[] value, Mode mode) { this.time = time; this.value = value.clone(); this.mode = mode; }
    }

    private final List<Key> keys;
    private final double neutral;

    public Track(List<Key> keys, double neutral) {
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Double.compare(a.time, b.time));
        this.keys = Collections.unmodifiableList(sorted);
        this.neutral = neutral;
    }

    public static Track empty(double neutral) { return new Track(List.of(), neutral); }
    public boolean isEmpty() { return keys.isEmpty(); }
    public List<Key> keys() { return keys; }
    /** The keyframe times (sorted): where a clip changes direction - useful for choosing frames. */
    public double[] times() { double[] t = new double[keys.size()]; for (int i = 0; i < t.length; i++) t[i] = keys.get(i).time; return t; }

    /** The channel value at time t (seconds). */
    public double[] at(double t) {
        if (keys.isEmpty()) return new double[]{neutral, neutral, neutral};
        final double eps = 1.0 / 1200;
        // binary search for the last key with time <= t
        int lo = 0, hi = keys.size() - 1, idx = -1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; if (keys.get(mid).time <= t + eps) { idx = mid; lo = mid + 1; } else hi = mid - 1; }
        if (idx < 0) return keys.get(0).value.clone();
        Key before = keys.get(idx);
        if (idx == keys.size() - 1 || Math.abs(before.time - t) <= eps) return before.value.clone();
        Key after = keys.get(idx + 1);
        if (Math.abs(after.time - t) <= eps) return after.value.clone();
        if (before.mode == Mode.STEP) return before.value.clone();
        double alpha = (t - before.time) / (after.time - before.time);
        if (before.mode == Mode.CATMULLROM || after.mode == Mode.CATMULLROM) {
            Key bp = idx > 0 ? keys.get(idx - 1) : before;
            Key ap = idx + 2 < keys.size() ? keys.get(idx + 2) : after;
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) out[i] = catmullRom(alpha, bp.value[i], before.value[i], after.value[i], ap.value[i]);
            return out;
        }
        if (before.mode == Mode.BEZIER || after.mode == Mode.BEZIER) {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) out[i] = bezier(before, after, i, t);
            return out;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = before.value[i] + (after.value[i] - before.value[i]) * alpha;
        return out;
    }

    /** Uniform Catmull-Rom segment p1..p2 with neighbours p0, p3 (THREE.js CatmullRom). */
    static double catmullRom(double t, double p0, double p1, double p2, double p3) {
        double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5, t2 = t * t, t3 = t * t2;
        return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
    }

    /** Cubic Bezier between two keyframes in (time, value) space; the time gap clamps the handles as Blockbench does. */
    private static double bezier(Key a, Key b, int axis, double t) {
        double gap = b.time - a.time;
        double h1t = a.rightTime != null ? Math.max(0, Math.min(gap, a.rightTime[axis])) : gap / 3;
        double h1v = a.rightValue != null ? a.rightValue[axis] : 0;
        double h2t = b.leftTime != null ? Math.max(-gap, Math.min(0, b.leftTime[axis])) : -gap / 3;
        double h2v = b.leftValue != null ? b.leftValue[axis] : 0;
        double x0 = a.time, y0 = a.value[axis], x1 = a.time + h1t, y1 = y0 + h1v, x2 = b.time + h2t, y2 = b.value[axis] + h2v, x3 = b.time, y3 = b.value[axis];
        // the curve's x(u) is monotonic (handles clamped into the gap): bisect u for x(u) = t, then take y(u)
        double lo = 0, hi = 1;
        for (int i = 0; i < 40; i++) { double mid = (lo + hi) / 2; if (cubic(mid, x0, x1, x2, x3) < t) lo = mid; else hi = mid; }
        return cubic((lo + hi) / 2, y0, y1, y2, y3);
    }

    private static double cubic(double u, double p0, double p1, double p2, double p3) {
        double m = 1 - u;
        return m * m * m * p0 + 3 * m * m * u * p1 + 3 * m * u * u * p2 + u * u * u * p3;
    }

    public static Mode mode(String s) {
        if (s == null) return Mode.LINEAR;
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "catmullrom": case "smooth": return Mode.CATMULLROM;
            case "step": return Mode.STEP;
            case "bezier": return Mode.BEZIER;
            default: return Mode.LINEAR;
        }
    }
}
