package fi.alavesa.firearms;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Gets Firearms-pack.zip to every player - on join and again right after /firearms pack - ADDED to whatever
 * packs the server already sends, under a fixed id so re-sending replaces only ours. Three ways (pack.host.mode):
 *   self   - a built-in HTTP server on pack.host.port (the port must be reachable by players; it can NOT be the
 *            Minecraft port, that one is taken by the server itself)
 *   upload - after every pack build the zip is uploaded to a public file host (catbox.moe, 0x0.st as fallback)
 *            and that link is sent; no port to open
 *   url    - you host the zip yourself at pack.host.url (re-upload after each /firearms pack)
 */
public final class PackHost implements org.bukkit.event.Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("fi.alavesa.firearms.pack".getBytes(StandardCharsets.UTF_8));
    private final FirearmsPlugin plugin;
    private HttpServer server;
    private String sha1 = "";
    private String uploadedUrl = "", uploadedSha1 = "";
    private String lastProblem = "";
    private long lastFailLog;

    public PackHost(FirearmsPlugin plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("pack.host.enabled", true); }
    /** Configured mode; "auto" (the default) = upload, falling back to the built-in server if uploading fails. */
    public String configuredMode() { return plugin.getConfig().getString("pack.host.mode", "auto").toLowerCase(Locale.ROOT).trim(); }
    private boolean autoFellBack;
    /** The mode in effect right now. */
    public String mode() {
        String m = configuredMode();
        if (m.equals("auto")) return autoFellBack ? "self" : "upload";
        return m;
    }
    public int port() { return plugin.getConfig().getInt("pack.host.port", 25580); }
    public File zip() { return new File(plugin.getDataFolder(), "Firearms-pack.zip"); }
    public String sha1() { return sha1; }

    public void start() {
        if (!enabled()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refreshHash();
        switch (mode()) {
            case "self" -> startServer();
            case "upload" -> { plugin.getLogger().info("Pack host: " + (configuredMode().equals("auto") ? "auto mode - " : "upload mode - ") + "the pack is uploaded to a public file host after each build and that link is sent to players (no port to open)."); plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::prepareAsync); }
            case "url" -> plugin.getLogger().info("Pack host: url mode - players get " + plugin.getConfig().getString("pack.host.url", "") + " (re-upload the zip there after each /firearms pack).");
            default -> plugin.getLogger().warning("pack.host.mode must be auto, self, upload or url (is '" + mode() + "') - nothing is sent.");
        }
    }

    private void startServer() {
        if (server != null) return;
        if (port() == plugin.getServer().getPort()) {
            lastProblem = "pack.host.port " + port() + " is the Minecraft port itself - the game server owns it, the pack host cannot use it. Pick another port (and open it), or set pack.host.mode: upload.";
            plugin.getLogger().severe("Pack host NOT started: " + lastProblem);
            return;
        }
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
            lastProblem = "";
            plugin.getLogger().info("Pack host: serving Firearms-pack.zip on port " + port() + " - this port must be open to players (firewall / hosting panel). No way to open a port? Set pack.host.mode: upload.");
        } catch (Exception e) {
            lastProblem = "port " + port() + " could not be opened: " + e.getMessage() + " (in use? pick a free port, or set pack.host.mode: upload)";
            plugin.getLogger().severe("Pack host NOT started: " + lastProblem);
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

    /** Off the main thread, after a pack build: refresh the hash and, in upload mode, upload the new zip. */
    public void prepareAsync() {
        refreshHash();
        if (!mode().equals("upload") || sha1.isEmpty() || sha1.equals(uploadedSha1)) return;
        File f = zip();
        String url = null;
        try { url = uploadCatbox(f); } catch (Exception e) { plugin.getLogger().warning("catbox.moe upload failed: " + e.getMessage()); }
        if (url == null) try { url = upload0x0(f); } catch (Exception e) { plugin.getLogger().warning("0x0.st upload failed: " + e.getMessage()); }
        if (url == null) {
            if (configuredMode().equals("auto") && !autoFellBack) {
                autoFellBack = true;
                plugin.getLogger().warning("Pack host: upload failed - falling back to the built-in server on port " + port() + " (that port must then be open to players).");
                plugin.getServer().getScheduler().runTask(plugin, () -> { startServer(); sendAll(); });
                return;
            }
            lastProblem = "the pack could not be uploaded to a file host (no internet from the server? try again with /firearms pack, or use pack.host.mode: self / url)"; plugin.getLogger().severe("Pack host: " + lastProblem); return;
        }
        uploadedUrl = url; uploadedSha1 = sha1; lastProblem = "";
        plugin.getLogger().info("Pack host: uploaded Firearms-pack.zip -> " + url);
        plugin.getServer().getScheduler().runTask(plugin, this::sendAll);   // whoever joined while it was uploading
    }

    private static String multipart(String boundary, String field, String filename, byte[] data, String... extra) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < extra.length; i += 2) sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(extra[i]).append("\"\r\n\r\n").append(extra[i + 1]).append("\r\n");
        sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field).append("\"; filename=\"").append(filename).append("\"\r\nContent-Type: application/zip\r\n\r\n");
        return sb.toString();
    }

    private String post(String url, String field, File f, String... extra) throws Exception {
        String boundary = "----firearms" + Long.toHexString(System.nanoTime());
        byte[] data = Files.readAllBytes(f.toPath());
        byte[] head = multipart(boundary, field, "Firearms-pack.zip", data, extra).getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length); System.arraycopy(data, 0, body, head.length, data.length); System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("User-Agent", "Firearms-plugin/" + plugin.getPluginMeta().getVersion() + " (Minecraft resource pack host)")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body().trim());
        String out = res.body().trim();
        if (!out.startsWith("http")) throw new IllegalStateException("unexpected reply: " + out);
        return out;
    }

    private String uploadCatbox(File f) throws Exception { return post("https://catbox.moe/user/api.php", "fileToUpload", f, "reqtype", "fileupload"); }
    private String upload0x0(File f) throws Exception { return post("https://0x0.st", "file", f); }

    /** The host players reach us by: config pack.host.address, else the address this player connected with. */
    String addressFor(Player p) {
        String cfg = plugin.getConfig().getString("pack.host.address", "");
        if (cfg != null && !cfg.isBlank()) return cfg.trim();
        try { var vh = p.getVirtualHost(); if (vh != null && vh.getHostString() != null && !vh.getHostString().isBlank()) return vh.getHostString(); } catch (Throwable ignored) { }
        String ip = plugin.getServer().getIp();
        if (ip != null && !ip.isBlank()) return ip;
        try { return java.net.InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "localhost"; }
    }

    /** The URL this player should download, or null (with lastProblem set) when nothing can be sent. */
    public String urlFor(Player p) {
        switch (mode()) {
            case "self" -> { if (server == null) return null; return "http://" + addressFor(p) + ":" + port() + "/Firearms-pack.zip?v=" + (sha1.isEmpty() ? "0" : sha1.substring(0, 12)); }
            case "upload" -> { if (uploadedUrl.isEmpty() || !uploadedSha1.equals(sha1)) return null; return uploadedUrl; }
            case "url" -> { String u = plugin.getConfig().getString("pack.host.url", ""); return u == null || u.isBlank() ? null : u.trim(); }
            default -> { return null; }
        }
    }

    public void send(Player p) {
        if (!enabled()) return;
        if (sha1.isEmpty()) refreshHash();
        if (sha1.isEmpty()) return;
        String url = urlFor(p);
        if (url == null) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), sha1);
            boolean required = plugin.getConfig().getBoolean("pack.host.required", false);
            Component prompt = Component.text(plugin.getConfig().getString("pack.host.prompt", "Firearms gun models"));
            p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).replace(false).required(required).prompt(prompt).build());
        } catch (Exception e) { plugin.getLogger().warning("Could not send the pack to " + p.getName() + ": " + e); }
    }

    public void sendAll() { for (Player p : plugin.getServer().getOnlinePlayers()) send(p); }

    /** One line for /firearms pack and the console: what is being sent and what is wrong. */
    public String status() {
        if (!enabled()) return "pack host disabled (pack.host.enabled: false) - install the pack manually.";
        if (!lastProblem.isEmpty()) return "pack host PROBLEM: " + lastProblem;
        return switch (mode()) {
            case "self" -> server == null ? "pack host not running." : "pack host: built-in server on port " + port() + " (open it to players; address = the one they connect with" + (plugin.getConfig().getString("pack.host.address", "").isBlank() ? "" : ", pack.host.address=" + plugin.getConfig().getString("pack.host.address")) + ").";
            case "upload" -> uploadedUrl.isEmpty() ? "pack host: upload mode, nothing uploaded yet (uploading / run /firearms pack)." : "pack host: " + (configuredMode().equals("auto") ? "auto (upload)" : "upload mode") + ", players get " + uploadedUrl + (uploadedSha1.equals(sha1) ? "" : " (OUTDATED - run /firearms pack)");
            case "url" -> "pack host: url mode, players get " + plugin.getConfig().getString("pack.host.url", "");
            default -> "pack host: unknown mode '" + mode() + "'";
        };
    }

    /** A client told us how our pack went: a failed download is the admin's problem to see, not the player's. */
    @org.bukkit.event.EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (e.getID() == null || !e.getID().equals(PACK_ID)) return;
        var st = e.getStatus();
        if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED || st == PlayerResourcePackStatusEvent.Status.ACCEPTED || st == PlayerResourcePackStatusEvent.Status.DOWNLOADED) return;
        if (st == PlayerResourcePackStatusEvent.Status.DECLINED) return;
        String url = urlFor(e.getPlayer());
        String hint = switch (mode()) {
            case "self" -> "the client could not fetch " + url + " - port " + port() + " is not reachable from outside (open it in the firewall / hosting panel, or set pack.host.address to the public host/IP), or switch to pack.host.mode: upload (no port needed).";
            case "upload" -> "the client could not fetch " + url + " - the file host may be blocked for that player; try pack.host.mode: self or url.";
            default -> "the client could not fetch " + url + " - check that the URL serves the CURRENT Firearms-pack.zip (sha1 " + sha1 + ").";
        };
        lastProblem = st + " for " + e.getPlayer().getName() + ": " + hint;
        if (System.currentTimeMillis() - lastFailLog > 10_000) {
            lastFailLog = System.currentTimeMillis();
            plugin.getLogger().warning("Resource pack " + st + " for " + e.getPlayer().getName() + ": " + hint);
            for (Player a : plugin.getServer().getOnlinePlayers()) if (a.hasPermission("firearms.admin"))
                a.sendMessage(Component.text("[Firearms] pack " + st.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " for " + e.getPlayer().getName() + ": " + hint, NamedTextColor.YELLOW));
        }
    }
}
