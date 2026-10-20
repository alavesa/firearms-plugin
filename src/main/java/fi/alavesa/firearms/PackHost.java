package fi.alavesa.firearms;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Hosts Firearms-pack.zip on a tiny built-in HTTP server and hands it to every player: on join and again
 * right after /firearms pack, so a re-tuned pack is live for everyone without touching server.properties.
 * The pack is ADDED to whatever packs the server already sends (server.properties, BetterModel ...) under a
 * fixed id, so re-sending replaces only ours.
 */
public final class PackHost {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("fi.alavesa.firearms.pack".getBytes());
    private final FirearmsPlugin plugin;
    private HttpServer server;
    private String sha1 = "";

    public PackHost(FirearmsPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("pack.host.enabled", true); }
    public int port() { return plugin.getConfig().getInt("pack.host.port", 25580); }
    public File zip() { return new File(plugin.getDataFolder(), "Firearms-pack.zip"); }

    public void start() {
        if (!enabled() || server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port()), 8);
            server.createContext("/", ex -> {
                File f = zip();
                if (!f.exists() || !ex.getRequestURI().getPath().endsWith(".zip")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                byte[] data = Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            });
            server.setExecutor(null);
            server.start();
            refreshHash();
            plugin.getLogger().info("Pack host: serving Firearms-pack.zip on port " + port() + " (players get it on join and after /firearms pack; pack.host in config.yml).");
        } catch (Exception e) {
            plugin.getLogger().warning("Pack host could not start on port " + port() + ": " + e.getMessage() + " - set pack.host.port to a free, open port, or pack.host.enabled: false");
            server = null;
        }
    }

    public void stop() { if (server != null) { server.stop(0); server = null; } }

    public void refreshHash() {
        try {
            File f = zip();
            if (!f.exists()) { sha1 = ""; return; }
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(f.toPath())));
        } catch (Exception e) { sha1 = ""; }
    }

    public String sha1() { return sha1; }

    /** The host players reach us by: config pack.host.address, else the address this player connected with. */
    String addressFor(Player p) {
        String cfg = plugin.getConfig().getString("pack.host.address", "");
        if (cfg != null && !cfg.isBlank()) return cfg.trim();
        try { var vh = p.getVirtualHost(); if (vh != null && vh.getHostString() != null && !vh.getHostString().isBlank()) return vh.getHostString(); } catch (Throwable ignored) { }
        String ip = plugin.getServer().getIp();
        if (ip != null && !ip.isBlank()) return ip;
        try { return java.net.InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "localhost"; }
    }

    public String urlFor(Player p) { return "http://" + addressFor(p) + ":" + port() + "/Firearms-pack.zip?v=" + (sha1.isEmpty() ? "0" : sha1.substring(0, 12)); }

    public void send(Player p) {
        if (!enabled() || server == null) return;
        if (sha1.isEmpty()) refreshHash();
        if (sha1.isEmpty()) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(urlFor(p)), sha1);
            boolean required = plugin.getConfig().getBoolean("pack.host.required", false);
            Component prompt = Component.text(plugin.getConfig().getString("pack.host.prompt", "Firearms gun models"));
            p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(false).required(required).prompt(prompt).build());
        } catch (Exception e) { plugin.getLogger().warning("Could not send the pack to " + p.getName() + ": " + e); }
    }

    public void sendAll() {
        refreshHash();
        for (Player p : plugin.getServer().getOnlinePlayers()) send(p);
    }
}
