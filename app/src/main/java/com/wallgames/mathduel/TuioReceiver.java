package com.wallgames.mathduel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listens for TUIO 1.1 (OSC over UDP) messages that the wall's camera service
 * ("Capture_S") sends to 127.0.0.1:3333, and reports each NEW contact once as a hit.
 * Supports the 2Dcur, 2Dobj and 2Dblb profiles. Coordinates are normalised 0..1.
 */
public class TuioReceiver {

    public interface Listener {
        /** A new contact appeared at (x, y), both 0..1. Called on the receiver thread. */
        void onHit(float x, float y, int id);
    }

    public static final int PORT = 3333;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private final Listener listener;
    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    // contacts currently on the wall, per profile ("2Dcur:12")
    private final Set<String> down = new HashSet<String>();
    private final Map<String, Set<String>> aliveByProfile = new HashMap<String, Set<String>>();

    public volatile long packets = 0;
    public volatile long hits = 0;
    public volatile int contacts = 0;
    public volatile String lastPos = "-";
    public volatile String error = null;

    public TuioReceiver(Listener l) { listener = l; }

    public synchronized void start() {
        if (running) return;
        running = true;
        error = null;
        thread = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "tuio");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        synchronized (down) { down.clear(); aliveByProfile.clear(); }
    }

    private void loop() {
        byte[] buf = new byte[65536];
        while (running) {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(PORT));
                error = null;
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    packets++;
                    try { parse(buf, 0, p.getLength()); } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                if (running) {
                    error = String.valueOf(t.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
            } finally {
                if (socket != null) socket.close();
            }
        }
    }

    // ---------------------------------------------------------------- OSC parsing

    private void parse(byte[] b, int off, int len) {
        if (len >= 16 && startsWith(b, off, "#bundle")) {
            int i = off + 16; // "#bundle\0" + 8-byte time tag
            int end = off + len;
            while (i + 4 <= end) {
                int size = readInt(b, i);
                i += 4;
                if (size <= 0 || i + size > end) break;
                parse(b, i, size);
                i += size;
            }
        } else {
            message(b, off, len);
        }
    }

    private void message(byte[] b, int off, int len) {
        int end = off + len;
        int[] pos = {off};
        String addr = readString(b, pos, end);
        if (!addr.startsWith("/tuio/")) return;
        String profile = addr.substring(6); // 2Dcur, 2Dobj, 2Dblb ...
        if (pos[0] >= end) return;
        String tags = readString(b, pos, end);
        if (!tags.startsWith(",")) return;
        List<Object> args = new ArrayList<Object>();
        for (int t = 1; t < tags.length(); t++) {
            char c = tags.charAt(t);
            switch (c) {
                case 'i': args.add(readInt(b, pos[0])); pos[0] += 4; break;
                case 'f': args.add(Float.intBitsToFloat(readInt(b, pos[0]))); pos[0] += 4; break;
                case 's': args.add(readString(b, pos, end)); break;
                case 'h': case 't': pos[0] += 8; args.add(0); break;
                case 'd': {
                    long hi = readInt(b, pos[0]) & 0xffffffffL, lo = readInt(b, pos[0] + 4) & 0xffffffffL;
                    args.add((float) Double.longBitsToDouble((hi << 32) | lo)); pos[0] += 8; break;
                }
                case 'b': { int n = readInt(b, pos[0]); pos[0] += 4 + ((n + 3) & ~3); args.add(0); break; }
                default: args.add(0); break; // T F N I carry no data
            }
        }
        if (args.isEmpty() || !(args.get(0) instanceof String)) return;
        String cmd = (String) args.get(0);

        if (cmd.equals("alive")) {
            Set<String> alive = new HashSet<String>();
            for (int k = 1; k < args.size(); k++) alive.add(profile + ":" + args.get(k));
            synchronized (down) {
                aliveByProfile.put(profile, alive);
                // contacts that left the wall
                List<String> gone = new ArrayList<String>();
                for (String id : down) if (id.startsWith(profile + ":") && !alive.contains(id)) gone.add(id);
                down.removeAll(gone);
                int n = 0;
                for (Set<String> s : aliveByProfile.values()) n += s.size();
                contacts = n;
            }
        } else if (cmd.equals("set") && args.size() >= 4) {
            int id = toInt(args.get(1));
            float x, y;
            if (profile.equals("2Dobj") && args.size() >= 5) { x = toFloat(args.get(3)); y = toFloat(args.get(4)); }
            else { x = toFloat(args.get(2)); y = toFloat(args.get(3)); }
            lastPos = String.format(java.util.Locale.US, "%.2f,%.2f", x, y);
            String key = profile + ":" + id;
            boolean isNew;
            synchronized (down) { isNew = down.add(key); }
            if (isNew && x >= 0 && x <= 1 && y >= 0 && y <= 1) {
                hits++;
                listener.onHit(x, y, id);
            }
        }
    }

    private static int toInt(Object o) { return o instanceof Integer ? (Integer) o : (int) toFloat(o); }
    private static float toFloat(Object o) {
        if (o instanceof Float) return (Float) o;
        if (o instanceof Integer) return (Integer) o;
        return 0f;
    }

    private static boolean startsWith(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) if (b[off + i] != s.charAt(i)) return false;
        return true;
    }

    private static int readInt(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    /** Reads a null-terminated OSC string and advances pos to the next 4-byte boundary. */
    private static String readString(byte[] b, int[] pos, int end) {
        int start = pos[0], i = start;
        while (i < end && b[i] != 0) i++;
        String s = new String(b, start, i - start, ASCII);
        int consumed = (i - start) + 1;
        pos[0] = start + ((consumed + 3) & ~3);
        return s;
    }
}
