package com.wallgames.mathduel;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Math Duel - a full-screen wrapper that runs the game (assets/index.html)
 * on the interactive wall projector. Touches and ball hits on the wall
 * arrive as normal screen touches.
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TuioReceiver tuio;

    /** Sends wall hits from the IR camera (TUIO on port 3333) into the game. */
    private final TuioReceiver.Listener wallListener = new TuioReceiver.Listener() {
        public void onHit(final float x, final float y, final int id) {
            ui.post(new Runnable() {
                public void run() {
                    if (web != null) {
                        web.evaluateJavascript(String.format(Locale.US,
                                "window.wallHit&&window.wallHit(%.4f,%.4f,%d)", x, y, id), null);
                    }
                }
            });
        }
    };

    /** Shows the camera link status in the game's test bar once a second. */
    private final Runnable statusTick = new Runnable() {
        public void run() {
            if (web != null && tuio != null) {
                String t = tuio.error != null ? "error: " + tuio.error.replace("'", "")
                        : "pkts " + tuio.packets + " hits " + tuio.hits + " on-wall " + tuio.contacts + " last " + tuio.lastPos;
                web.evaluateJavascript("window.tuioStatus&&window.tuioStatus('" + t + "')", null);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebViewClient(new WebViewClient());
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF140A3E);

        web.addJavascriptInterface(new AppStore(), "AppStore");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        tuio = new TuioReceiver(wallListener);
        web.requestFocus();
        hideSystemBars();
    }

    /**
     * The wall camera is processed by the supplier's "Capture_S" app (com.myt.mytclient).
     * Its CameraService is exported and normally only started when a supplier game is
     * launched; we start it ourselves so the wall works without opening a supplier game.
     */
    private void startWallCamera() {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.myt.mytclient", "com.myt.mytclient.camera.CameraService"));
            startService(i);
        } catch (Throwable t) {
            // Capture_S not installed or blocked: the wall simply won't send hits
        }
    }

    /** Lets the game save settings (e.g. wall calibration) that survive restarts. */
    private class AppStore {
        private SharedPreferences prefs() { return getSharedPreferences("mathduel", MODE_PRIVATE); }

        @JavascriptInterface
        public String load(String key) { return prefs().getString(key, null); }

        @JavascriptInterface
        public void save(String key, String value) { prefs().edit().putString(key, value).apply(); }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        decor.setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
        startWallCamera();
        if (tuio != null) {
            tuio.start();
        }
        ui.removeCallbacks(statusTick);
        ui.postDelayed(statusTick, 1000);
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        // release port 3333 so the supplier's games get the wall hits again
        if (tuio != null) {
            tuio.stop();
        }
        ui.removeCallbacks(statusTick);
        if (web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
