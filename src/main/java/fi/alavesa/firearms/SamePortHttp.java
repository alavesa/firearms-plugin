package fi.alavesa.firearms;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Serves Firearms-pack.zip on the Minecraft port itself, so nothing has to be opened and no file host is needed.
 * <p>
 * A Minecraft client downloading a resource pack is a plain HTTP client. The game server's listening socket is a
 * netty channel; a handler added at the front of every accepted connection looks at the first bytes: a Minecraft
 * handshake starts with a packet length byte, an HTTP download with {@code GET } or {@code HEAD }. For the latter the
 * game's own handlers are taken out of that connection and the zip is written back as an HTTP response; everything
 * else is passed on untouched, so players, pings and proxies see no difference. (RP Engine's "same-port" mode is the
 * same idea.) Does not work behind a Velocity / BungeeCord proxy, which only forwards the Minecraft protocol.
 */
final class SamePortHttp {
    private static final String PARENT = "firearms_http_accept", CHILD = "firearms_http";
    private static final int CHUNK = 256 * 1024;
    private final FirearmsPlugin plugin;
    private final Supplier<File> zip;
    private final List<Channel> parents = new ArrayList<>();
    private volatile long served;

    SamePortHttp(FirearmsPlugin plugin, Supplier<File> zip) { this.plugin = plugin; this.zip = zip; }

    /** Hook the server's listening channel(s). Returns null on success, else why not. */
    @SuppressWarnings("unchecked")
    String install() {
        try {
            Object server = plugin.getServer();
            Object mc = server.getClass().getMethod("getServer").invoke(server);
            Object conn = mc.getClass().getMethod("getConnection").invoke(mc);
            if (conn == null) return "the server has no network listener yet";
            Field f = null;
            for (Class<?> c = conn.getClass(); c != null && f == null; c = c.getSuperclass())
                for (Field x : c.getDeclaredFields()) if (List.class.isAssignableFrom(x.getType()) && x.getGenericType().getTypeName().contains("ChannelFuture")) { f = x; break; }
            if (f == null) return "ServerConnectionListener has no channel list (server version not supported)";
            f.setAccessible(true);
            List<ChannelFuture> list = (List<ChannelFuture>) f.get(conn);
            int n = 0;
            synchronized (list) {
                for (ChannelFuture cf : list) {
                    Channel ch = cf.channel();
                    if (ch == null || !ch.isOpen()) continue;
                    if (ch.pipeline().get(PARENT) == null) ch.pipeline().addFirst(PARENT, new Acceptor());
                    parents.add(ch);
                    n++;
                }
            }
            return n == 0 ? "no open listening channel found" : null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    void uninstall() {
        for (Channel ch : parents) try { if (ch.pipeline().get(PARENT) != null) ch.pipeline().remove(PARENT); } catch (Throwable ignored) { }
        parents.clear();
    }

    long served() { return served; }

    /** On the listening channel: every accepted connection gets the sniffer in front of the game's handlers. */
    @ChannelHandler.Sharable
    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                try { if (child.pipeline().get(CHILD) == null) child.pipeline().addFirst(CHILD, new Sniffer()); } catch (Throwable ignored) { }
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** On a connection: decide from the first bytes whether this is a download, and if so answer it. */
    private final class Sniffer extends ChannelInboundHandlerAdapter {
        private ByteBuf head;
        private boolean http, answered;

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) { ctx.fireChannelRead(msg); return; }
            if (!http) {
                if (head == null && buf.readableBytes() >= 4) {
                    if (!looksLikeHttp(buf)) { ctx.pipeline().remove(this); ctx.fireChannelRead(buf); return; }
                    http = true;
                } else if (head == null) {
                    // fewer than 4 bytes so far: keep them until we can tell (a handshake is never this small for long)
                    head = Unpooled.buffer(); head.writeBytes(buf); buf.release();
                    if (head.readableBytes() >= 4) { if (!looksLikeHttp(head)) { ByteBuf h = head; head = null; ctx.pipeline().remove(this); ctx.fireChannelRead(h); return; } http = true; }
                    else return;
                }
            }
            if (head == null) head = Unpooled.buffer();
            if (buf.isReadable()) head.writeBytes(buf);
            buf.release();
            if (answered) return;
            String text = head.toString(StandardCharsets.US_ASCII);
            int end = text.indexOf("\r\n\r\n");
            if (end < 0) { if (head.readableBytes() > 16384) ctx.close(); return; }
            answered = true;
            String line = text.substring(0, text.indexOf("\r\n"));
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "", path = parts.length > 1 ? parts[1] : "";
            head.release(); head = null;
            // the game's handlers must never see this connection again (and their read timeout must not kill the download)
            ChannelPipeline p = ctx.pipeline();
            for (String name : new ArrayList<>(p.names())) if (!name.equals(CHILD) && p.get(name) != null) try { p.remove(name); } catch (Throwable ignored) { }
            File f = zip.get();
            if (!path.contains("/firearms") || !path.endsWith(".zip") || f == null || !f.isFile()) { reply(ctx, "404 Not Found", 0, null, method.equals("HEAD")); return; }
            reply(ctx, "200 OK", f.length(), f, method.equals("HEAD"));
        }

        private boolean looksLikeHttp(ByteBuf b) {
            int i = b.readerIndex();
            return (b.getByte(i) == 'G' && b.getByte(i + 1) == 'E' && b.getByte(i + 2) == 'T' && b.getByte(i + 3) == ' ')
                || (b.getByte(i) == 'H' && b.getByte(i + 1) == 'E' && b.getByte(i + 2) == 'A' && b.getByte(i + 3) == 'D');
        }

        private void reply(ChannelHandlerContext ctx, String status, long length, File body, boolean headOnly) {
            String headers = "HTTP/1.1 " + status + "\r\nContent-Type: application/zip\r\nContent-Length: " + length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
            ChannelFuture hf = ctx.writeAndFlush(Unpooled.copiedBuffer(headers, StandardCharsets.US_ASCII));
            if (body == null || headOnly || length == 0) { hf.addListener(x -> ctx.close()); return; }
            try {
                RandomAccessFile raf = new RandomAccessFile(body, "r");
                FileChannel fc = raf.getChannel();
                served++;
                hf.addListener(x -> writeChunk(ctx, raf, fc));
            } catch (Exception e) { ctx.close(); }
        }

        /** One chunk at a time, the next queued when this one has left the socket: no 40 MB buffer per download. */
        private void writeChunk(ChannelHandlerContext ctx, RandomAccessFile raf, FileChannel fc) {
            try {
                if (!ctx.channel().isActive()) { raf.close(); return; }
                ByteBuffer bb = ByteBuffer.allocate(CHUNK);
                int n = fc.read(bb);
                if (n <= 0) { raf.close(); ctx.close(); return; }
                bb.flip();
                ctx.writeAndFlush(Unpooled.wrappedBuffer(bb)).addListener(x -> { if (x.isSuccess()) writeChunk(ctx, raf, fc); else { raf.close(); ctx.close(); } });
            } catch (Exception e) { try { raf.close(); } catch (Exception ignored) { } ctx.close(); }
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { if (http) ctx.close(); else ctx.fireExceptionCaught(cause); }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) { if (head != null) { head.release(); head = null; } }
    }
}
