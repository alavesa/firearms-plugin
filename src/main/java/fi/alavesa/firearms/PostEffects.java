package fi.alavesa.firearms;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Per-player screen post effects (Minecraft 26.3: /posteffect, ClientboundPostEffectsPacket). The server keeps a list
 * of post-effect ids per player and the client runs those shaders over its whole frame. Reflection on the server's own
 * ServerPlayer (addPostEffect / removePostEffect / sendPostEffects) so the plugin still compiles against the 1.21.4 API;
 * {@link #available()} is false on servers before 26.3 and callers fall back.
 */
final class PostEffects {
    private static boolean ok;
    private static String problem = "";
    private static Method add, remove, send, parse;

    static {
        try {
            Class<?> sp = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> ident = Class.forName("net.minecraft.resources.Identifier");
            add = sp.getMethod("addPostEffect", ident);
            remove = sp.getMethod("removePostEffect", ident);
            send = sp.getMethod("sendPostEffects");
            parse = ident.getMethod("parse", String.class);
            ok = true;
        } catch (Throwable t) { ok = false; problem = t.toString(); }
    }

    private PostEffects() { }

    static boolean available() { return ok; }
    static String problem() { return problem; }

    /** Add (or remove) one post effect id ("firearms:flash_3") on the player's list and send the list. */
    static boolean set(Player p, String id, boolean on) {
        if (!ok) return false;
        try {
            Object handle = NmsRecoil.getHandle(p);
            Object key = parse.invoke(null, id);
            (on ? add : remove).invoke(handle, key);
            send.invoke(handle);
            return true;
        } catch (Throwable t) { ok = false; problem = t.toString(); return false; }
    }

    /** Swap one effect for another in a single update. */
    static boolean swap(Player p, String from, String to) {
        if (!ok) return false;
        try {
            Object handle = NmsRecoil.getHandle(p);
            if (from != null) remove.invoke(handle, parse.invoke(null, from));
            if (to != null) add.invoke(handle, parse.invoke(null, to));
            send.invoke(handle);
            return true;
        } catch (Throwable t) { ok = false; problem = t.toString(); return false; }
    }
}
