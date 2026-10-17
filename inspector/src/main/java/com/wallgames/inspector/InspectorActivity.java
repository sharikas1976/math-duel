package com.wallgames.inspector;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wall Inspector - finds out how the interactive wall's sensor delivers hits,
 * and exports a report + the supplier's app files to the USB stick for analysis.
 */
public class InspectorActivity extends Activity {

    private static final int[] UDP_PORTS = {3333, 3334, 3335, 3336, 3337, 3338, 3339, 3340,
            5000, 5001, 6000, 7000, 7001, 8000, 8001, 8080, 8888, 9000, 9001, 9999, 10000, 12345, 20000};

    private TextView status;
    private TextView report;
    private TouchView touchView;
    private FrameLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder touchLog = new StringBuilder();
    private final StringBuilder udpLog = new StringBuilder();
    private String reportText = "";
    private int touchEvents = 0;
    private volatile boolean busy = false;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(20, 10, 62));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 16, 24, 16);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(button("1. TOUCH TEST", new View.OnClickListener() {
            public void onClick(View v) { startTouchTest(); }
        }));
        bar.addView(button("2. LISTEN UDP (20s)", new View.OnClickListener() {
            public void onClick(View v) { listenUdp(); }
        }));
        bar.addView(button("3. EXPORT TO USB", new View.OnClickListener() {
            public void onClick(View v) { exportAll(); }
        }));
        bar.addView(button("REFRESH", new View.OnClickListener() {
            public void onClick(View v) { buildReportAsync(); }
        }));
        col.addView(bar);

        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 201, 41));
        status.setTextSize(20);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(4, 12, 4, 12);
        status.setText("Collecting device information...");
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        report = new TextView(this);
        report.setTextColor(Color.WHITE);
        report.setTextSize(13);
        report.setTypeface(Typeface.MONOSPACE);
        sv.addView(report);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col);
        touchView = new TouchView(this);
        touchView.setVisibility(View.GONE);
        root.addView(touchView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        buildReportAsync();
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(18);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(8, 0, 8, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void setStatus(final String s) {
        ui.post(new Runnable() { public void run() { status.setText(s); } });
    }

    // ------------------------------------------------------------------ event capture (all sources)

    private String now() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private void logMotion(String kind, MotionEvent e) {
        InputDevice d = e.getDevice();
        String line = now() + " " + kind + " action=" + MotionEvent.actionToString(e.getActionMasked())
                + " src=" + sources(e.getSource())
                + " tool=" + e.getToolType(0)
                + " x=" + Math.round(e.getX()) + " y=" + Math.round(e.getY())
                + " pointers=" + e.getPointerCount()
                + " dev=" + (d != null ? d.getName() + "#" + d.getId() : "null");
        synchronized (touchLog) {
            if (touchLog.length() < 200000) touchLog.append(line).append('\n');
        }
        touchEvents++;
        if (touchView.getVisibility() == View.VISIBLE) touchView.add(e.getX(), e.getY(), line);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        logMotion("TOUCH", e);
        if (touchView.getVisibility() == View.VISIBLE) return true;
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        logMotion("GENERIC", e);
        if (touchView.getVisibility() == View.VISIBLE) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent e) {
        logMotion("TRACKBALL", e);
        return super.dispatchTrackballEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        InputDevice d = e.getDevice();
        String line = now() + " KEY " + KeyEvent.keyCodeToString(e.getKeyCode()) + " action=" + e.getAction()
                + " src=" + sources(e.getSource()) + " dev=" + (d != null ? d.getName() : "null");
        synchronized (touchLog) {
            if (touchLog.length() < 200000) touchLog.append(line).append('\n');
        }
        if (touchView.getVisibility() == View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP && (e.getKeyCode() == KeyEvent.KEYCODE_BACK
                    || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                stopTouchTest();
            } else {
                touchView.add(-1, -1, line);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void startTouchTest() {
        touchEvents = 0;
        touchView.reset();
        touchView.setVisibility(View.VISIBLE);
        setStatus("TOUCH TEST running");
    }

    private void stopTouchTest() {
        touchView.setVisibility(View.GONE);
        setStatus("Touch test finished: " + touchEvents + " events captured. Now press 3. EXPORT TO USB.");
    }

    /** Full-screen canvas that draws every hit and the last event lines. */
    private class TouchView extends View {
        private final List<float[]> pts = new ArrayList<float[]>();
        private final List<String> lines = new ArrayList<String>();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);

        TouchView(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(10, 5, 35));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(6);
            ring.setColor(Color.rgb(255, 201, 41));
            txt.setColor(Color.WHITE);
            txt.setTextSize(22);
            txt.setTypeface(Typeface.MONOSPACE);
            big.setColor(Color.rgb(255, 201, 41));
            big.setTextSize(44);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            big.setTextAlign(Paint.Align.CENTER);
        }

        void reset() { pts.clear(); lines.clear(); invalidate(); }

        void add(float x, float y, String line) {
            if (x >= 0) { pts.add(new float[]{x, y}); if (pts.size() > 60) pts.remove(0); }
            lines.add(line); if (lines.size() > 14) lines.remove(0);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawText("TOUCH TEST - touch the wall / throw balls.  Events: " + touchEvents, getWidth() / 2f, 70, big);
            c.drawText("Press OK or BACK on the remote to finish", getWidth() / 2f, 125, big);
            for (float[] p : pts) c.drawCircle(p[0], p[1], 40, ring);
            float y = getHeight() - 20 - lines.size() * 28;
            for (String l : lines) { c.drawText(l, 20, y, txt); y += 28; }
        }
    }

    // ------------------------------------------------------------------ report

    private void buildReportAsync() {
        new Thread(new Runnable() {
            public void run() {
                final String r = buildReport();
                reportText = r;
                ui.post(new Runnable() {
                    public void run() {
                        report.setText(r);
                        status.setText("Ready. Do 1 (touch test, with wall + balls), 2 (UDP listen), then 3 (export to USB stick).");
                    }
                });
            }
        }).start();
    }

    private String buildReport() {
        StringBuilder s = new StringBuilder();
        s.append("WALL INSPECTOR REPORT  ").append(new Date()).append("\n");

        h(s, "DEVICE");
        s.append("model=").append(Build.MODEL).append(" device=").append(Build.DEVICE).append(" board=").append(Build.BOARD)
                .append(" hw=").append(Build.HARDWARE).append("\nandroid=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append("\nfingerprint=").append(Build.FINGERPRINT).append('\n');

        h(s, "DISPLAYS");
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            for (Display d : dm.getDisplays()) {
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                s.append("id=").append(d.getDisplayId()).append(" name=").append(d.getName())
                        .append(" ").append(m.widthPixels).append("x").append(m.heightPixels)
                        .append(" flags=").append(d.getFlags()).append('\n');
            }
            s.append("this app is on display id=").append(getWindowManager().getDefaultDisplay().getDisplayId()).append('\n');
        } catch (Throwable t) { s.append("error ").append(t).append('\n'); }

        h(s, "INPUT DEVICES (Android)");
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null) continue;
                s.append("#").append(id).append(" '").append(d.getName()).append("' vendor=0x")
                        .append(Integer.toHexString(d.getVendorId())).append(" product=0x")
                        .append(Integer.toHexString(d.getProductId())).append(" virtual=").append(d.isVirtual())
                        .append("\n    sources=").append(sources(d.getSources())).append('\n');
            }
        } catch (Throwable t) { s.append("error ").append(t).append('\n'); }

        h(s, "/proc/bus/input/devices");
        s.append(readFile("/proc/bus/input/devices", 20000));

        h(s, "USB DEVICES");
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> list = um.getDeviceList();
            if (list.isEmpty()) s.append("(none visible to apps)\n");
            for (UsbDevice d : list.values()) {
                s.append(d.getDeviceName()).append(" vid=0x").append(Integer.toHexString(d.getVendorId()))
                        .append(" pid=0x").append(Integer.toHexString(d.getProductId()))
                        .append(" class=").append(d.getDeviceClass())
                        .append(" mfr=").append(d.getManufacturerName()).append(" product=").append(d.getProductName())
                        .append(" interfaces=").append(d.getInterfaceCount()).append('\n');
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    s.append("    if").append(i).append(" class=").append(d.getInterface(i).getInterfaceClass())
                            .append(" sub=").append(d.getInterface(i).getInterfaceSubclass()).append('\n');
                }
            }
        } catch (Throwable t) { s.append("error ").append(t).append('\n'); }

        h(s, "CAMERAS");
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                s.append("camera ").append(id).append(" facing=").append(f).append('\n');
            }
        } catch (Throwable t) { s.append("camera2 error ").append(t).append('\n'); }
        File dev = new File("/dev");
        String[] dl = dev.list();
        if (dl != null) for (String n : dl) if (n.startsWith("video") || n.startsWith("tty") && (n.contains("USB") || n.contains("ACM") || n.startsWith("ttyS")) || n.startsWith("hidraw") || n.startsWith("uinput")) s.append("/dev/").append(n).append('\n');

        h(s, "HOME / LAUNCHER");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                s.append(ri.activityInfo.packageName).append("/").append(ri.activityInfo.name).append('\n');
            }
        } catch (Throwable t) { s.append("error ").append(t).append('\n'); }

        h(s, "APPS (non-Google, non-AOSP)");
        PackageManager pm = getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!interesting(ai.packageName)) continue;
            s.append("\n* ").append(ai.packageName).append("  label='").append(pm.getApplicationLabel(ai)).append("'")
                    .append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? " [SYSTEM]" : "").append('\n');
            s.append("  apk=").append(ai.sourceDir).append("  size=").append(new File(ai.sourceDir).length() / 1024).append("KB\n");
            s.append("  uid=").append(ai.uid).append(" nativeLibs=").append(listDir(ai.nativeLibraryDir)).append('\n');
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                        | PackageManager.GET_ACTIVITIES | PackageManager.GET_PERMISSIONS | PackageManager.GET_PROVIDERS);
                s.append("  version=").append(pi.versionName).append('\n');
                if (pi.activities != null) for (ActivityInfo a : pi.activities) s.append("  activity ").append(a.name).append('\n');
                if (pi.services != null) for (ServiceInfo sv : pi.services) s.append("  service  ").append(sv.name).append(sv.exported ? " (exported)" : "").append('\n');
                if (pi.receivers != null) for (ActivityInfo r : pi.receivers) s.append("  receiver ").append(r.name).append('\n');
                if (pi.requestedPermissions != null) for (String p : pi.requestedPermissions) s.append("  perm ").append(p).append('\n');
            } catch (Throwable t) { s.append("  details error ").append(t).append('\n'); }
        }

        h(s, "RUNNING SERVICES");
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningServiceInfo> rs = am.getRunningServices(400);
            for (ActivityManager.RunningServiceInfo r : rs) {
                s.append(r.service.flattenToShortString()).append(" pid=").append(r.pid).append(" proc=").append(r.process)
                        .append(r.foreground ? " FOREGROUND" : "").append('\n');
            }
        } catch (Throwable t) { s.append("error ").append(t).append('\n'); }

        h(s, "NETWORK SOCKETS (who listens where)");
        s.append(sockets("/proc/net/udp", "UDP"));
        s.append(sockets("/proc/net/udp6", "UDP6"));
        s.append(sockets("/proc/net/tcp", "TCP"));
        s.append(sockets("/proc/net/tcp6", "TCP6"));

        h(s, "STORAGE");
        s.append("/sdcard: ").append(listDir(Environment.getExternalStorageDirectory().getAbsolutePath())).append('\n');
        for (File f : getExternalFilesDirs(null)) if (f != null) s.append("export dir: ").append(f.getAbsolutePath()).append('\n');

        return s.toString();
    }

    private boolean interesting(String p) {
        return !(p.startsWith("com.android.") || p.startsWith("com.google.") || p.equals("android")
                || p.startsWith("android.") || p.equals(getPackageName()));
    }

    private void h(StringBuilder s, String t) {
        s.append("\n==================== ").append(t).append(" ====================\n");
    }

    private String sockets(String path, String proto) {
        StringBuilder s = new StringBuilder();
        PackageManager pm = getPackageManager();
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line = br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 8) continue;
                String local = f[1], remote = f[2], st = f[3];
                int port = Integer.parseInt(local.substring(local.indexOf(':') + 1), 16);
                int rport = Integer.parseInt(remote.substring(remote.indexOf(':') + 1), 16);
                int uid = Integer.parseInt(f[7]);
                if (proto.startsWith("TCP") && !st.equals("0A")) continue; // only LISTEN for TCP
                String owner = pm.getNameForUid(uid);
                s.append(proto).append(" port ").append(port).append(rport > 0 ? " -> remote port " + rport : "")
                        .append(" uid=").append(uid).append(" (").append(owner).append(")\n");
            }
            br.close();
        } catch (Throwable t) { s.append(proto).append(" read error ").append(t).append('\n'); }
        return s.toString();
    }

    private String readFile(String path, int max) {
        StringBuilder s = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null && s.length() < max) s.append(line).append('\n');
            br.close();
        } catch (Throwable t) { s.append("(cannot read: ").append(t).append(")\n"); }
        return s.toString();
    }

    private String listDir(String path) {
        if (path == null) return "-";
        String[] l = new File(path).list();
        if (l == null) return "(none)";
        StringBuilder s = new StringBuilder();
        for (String n : l) s.append(n).append(' ');
        return s.toString();
    }

    private static String sources(int src) {
        StringBuilder s = new StringBuilder();
        Object[][] m = {
                {InputDevice.SOURCE_TOUCHSCREEN, "TOUCHSCREEN"}, {InputDevice.SOURCE_MOUSE, "MOUSE"},
                {InputDevice.SOURCE_STYLUS, "STYLUS"}, {InputDevice.SOURCE_TOUCHPAD, "TOUCHPAD"},
                {InputDevice.SOURCE_JOYSTICK, "JOYSTICK"}, {InputDevice.SOURCE_GAMEPAD, "GAMEPAD"},
                {InputDevice.SOURCE_DPAD, "DPAD"}, {InputDevice.SOURCE_KEYBOARD, "KEYBOARD"},
                {InputDevice.SOURCE_TRACKBALL, "TRACKBALL"}, {InputDevice.SOURCE_TOUCH_NAVIGATION, "TOUCH_NAV"},
                {InputDevice.SOURCE_HDMI, "HDMI"},
        };
        for (Object[] e : m) if ((src & (Integer) e[0]) == (Integer) e[0]) s.append(e[1]).append('|');
        return s.length() == 0 ? "0x" + Integer.toHexString(src) : s.toString() + "0x" + Integer.toHexString(src);
    }

    // ------------------------------------------------------------------ UDP listener

    private void listenUdp() {
        if (busy) return;
        busy = true;
        setStatus("Listening for network touch data for 20 s - touch the wall and throw balls NOW...");
        new Thread(new Runnable() {
            public void run() {
                WifiManager.MulticastLock lock = null;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    lock = wm.createMulticastLock("inspector");
                    lock.acquire();
                } catch (Throwable ignored) { }
                final Map<Integer, int[]> counts = new HashMap<Integer, int[]>();
                List<Thread> threads = new ArrayList<Thread>();
                final long end = System.currentTimeMillis() + 20000;
                synchronized (udpLog) { udpLog.append("\n=== UDP listen ").append(new Date()).append(" ===\n"); }
                for (final int port : UDP_PORTS) {
                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            DatagramSocket sock = null;
                            try {
                                sock = new DatagramSocket(null);
                                sock.setReuseAddress(true);
                                sock.setBroadcast(true);
                                sock.bind(new InetSocketAddress(port));
                                sock.setSoTimeout(500);
                                byte[] buf = new byte[4096];
                                int n = 0;
                                while (System.currentTimeMillis() < end) {
                                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                                    try { sock.receive(p); } catch (java.net.SocketTimeoutException te) { continue; }
                                    n++;
                                    if (n <= 15) {
                                        synchronized (udpLog) {
                                            udpLog.append("port ").append(port).append(" from ").append(p.getAddress()).append(":")
                                                    .append(p.getPort()).append(" len=").append(p.getLength()).append("\n  ")
                                                    .append(dump(buf, p.getLength())).append('\n');
                                        }
                                    }
                                }
                                synchronized (counts) { counts.put(port, new int[]{n}); }
                            } catch (Throwable e) {
                                synchronized (udpLog) { udpLog.append("port ").append(port).append(" bind failed: ").append(e.getMessage()).append('\n'); }
                            } finally {
                                if (sock != null) sock.close();
                            }
                        }
                    });
                    t.start();
                    threads.add(t);
                }
                for (Thread t : threads) { try { t.join(); } catch (InterruptedException ignored) { } }
                if (lock != null) try { lock.release(); } catch (Throwable ignored) { }
                StringBuilder sum = new StringBuilder("UDP done. Packets: ");
                int total = 0;
                for (Map.Entry<Integer, int[]> e : counts.entrySet()) if (e.getValue()[0] > 0) { sum.append(e.getKey()).append("=").append(e.getValue()[0]).append(" "); total += e.getValue()[0]; }
                if (total == 0) sum.append("none received");
                synchronized (udpLog) { udpLog.append(sum).append('\n'); }
                setStatus(sum.toString() + "  - now press 3. EXPORT TO USB");
                busy = false;
            }
        }).start();
    }

    private static String dump(byte[] b, int len) {
        StringBuilder hex = new StringBuilder(), asc = new StringBuilder();
        for (int i = 0; i < Math.min(len, 96); i++) {
            hex.append(String.format(Locale.US, "%02x ", b[i] & 0xff));
            asc.append(b[i] >= 32 && b[i] < 127 ? (char) b[i] : '.');
        }
        return hex + " | " + asc;
    }

    // ------------------------------------------------------------------ export

    private void exportAll() {
        if (busy) return;
        busy = true;
        setStatus("Exporting... please wait");
        new Thread(new Runnable() {
            public void run() {
                List<File> targets = new ArrayList<File>();
                for (File f : getExternalFilesDirs(null)) if (f != null) targets.add(f);
                File pub = new File(Environment.getExternalStorageDirectory(), "WallInspector");
                targets.add(pub);
                // also try the root of any USB stick (works on many Android 7 boxes)
                File[] storage = new File("/storage").listFiles();
                if (storage != null) for (File s : storage) {
                    if (s.getName().equals("emulated") || s.getName().equals("self")) continue;
                    targets.add(new File(s, "WallInspector"));
                }
                if (reportText.length() == 0) reportText = buildReport();
                String full = reportText + "\n\n==================== TOUCH/KEY EVENTS ====================\n"
                        + touchLog + "\n==================== UDP ====================\n" + udpLog;
                StringBuilder done = new StringBuilder();
                PackageManager pm = getPackageManager();
                for (File dir : targets) {
                    try {
                        dir.mkdirs();
                        if (!dir.canWrite()) continue;
                        write(new File(dir, "wall-report.txt"), full);
                        File apks = new File(dir, "apks");
                        apks.mkdirs();
                        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                            if (!interesting(ai.packageName) || ai.sourceDir == null) continue;
                            setStatus("Copying " + ai.packageName + " to " + dir.getAbsolutePath());
                            copy(new File(ai.sourceDir), new File(apks, ai.packageName + ".apk"));
                        }
                        done.append(dir.getAbsolutePath()).append("\n");
                    } catch (Throwable t) {
                        done.append("FAILED ").append(dir.getAbsolutePath()).append(": ").append(t.getMessage()).append("\n");
                    }
                }
                setStatus("Export finished. Saved to:\n" + done + "On your Mac, open the USB stick and send me the WallInspector folder (or Android/data/com.wallgames.inspector/files).");
                busy = false;
            }
        }).start();
    }

    private static void write(File f, String text) throws Exception {
        OutputStream o = new FileOutputStream(f);
        o.write(text.getBytes("UTF-8"));
        o.close();
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }
}
