package com.livesound.console;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.webkit.WebViewAssetLoader;
import java.util.ArrayList;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int FILE_REQ = 77;

    private WebView web;
    private PermissionRequest pending;
    private ValueCallback<Uri[]> filePathCb;

    // In-app YouTube player: used when the embedded stage player is refused (Error 153 etc.)
    private FrameLayout ytRoot;
    private WebView ytWeb;
    private View ytCustom;
    private WebChromeClient.CustomViewCallback ytCustomCb;

    /** Exposed to the page as window.AndroidApp */
    private class Bridge {
        @JavascriptInterface public void openYouTube(final String id) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
            runOnUiThread(new Runnable() { @Override public void run() { showYouTube(id); } });
        }
        @JavascriptInterface public boolean isApp() { return true; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        goImmersive();
        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // a link that would take the app page away: YouTube -> in-app player, others -> browser
                String id = ytId(u);
                if (id != null) { showYouTube(id); return true; }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                boolean multi = params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                // The stock intent only allows the FIRST accept type (audio), so video files stay greyed out.
                // Build our own intent that allows audio AND video.
                String[] actions = { Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT };
                for (String act : actions) {
                    try {
                        Intent i = new Intent(act);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ "audio/*", "video/*" });
                        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivityForResult(i, FILE_REQ);
                        diag("file picker opened (" + (act.endsWith("OPEN_DOCUMENT") ? "documents" : "get-content") + ")");
                        return true;
                    } catch (Exception e) {
                        diag("file picker " + act + " failed: " + e.getClass().getSimpleName());
                    }
                }
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                    diag("file picker opened (stock intent)");
                    return true;
                } catch (Exception e) {
                    filePathCb = null;
                    diag("no file picker available on this phone");
                    return false;
                }
            }
            @Override public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pending = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, 1);
                    }
                } });
            }
            private View custom; private CustomViewCallback cb;
            @Override public void onShowCustomView(View v, CustomViewCallback c) { custom = v; cb = c; setContentView(v); }
            @Override public void onHideCustomView() { setContentView(web); if (cb != null) cb.onCustomViewHidden(); custom = null; }
        });
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    /** Send a line to the page's on-screen diagnostics panel (tap the logo 5 times). */
    private void diag(final String msg) {
        if (web == null) return;
        web.post(new Runnable() { @Override public void run() {
            web.evaluateJavascript("window.__diag&&window.__diag(" + JSONObject.quote(msg) + ")", null);
        } });
    }

    private static String ytId(Uri u) {
        String h = u.getHost();
        if (h == null) return null;
        h = h.toLowerCase();
        String id = null;
        if (h.equals("youtu.be")) {
            id = u.getLastPathSegment();
        } else if (h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com")) {
            id = u.getQueryParameter("v");
            if (id == null) {
                java.util.List<String> seg = u.getPathSegments();
                if (seg.size() >= 2 && (seg.get(0).equals("embed") || seg.get(0).equals("shorts") || seg.get(0).equals("live") || seg.get(0).equals("v"))) id = seg.get(1);
            }
        }
        return (id != null && id.matches("[A-Za-z0-9_-]{11}")) ? id : null;
    }

    private void showYouTube(String id) {
        closeYouTube();
        ytRoot = new FrameLayout(this);
        ytRoot.setBackgroundColor(Color.BLACK);
        ytWeb = new WebView(this);
        WebSettings s = ytWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        ytWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch)); // block hand-offs to the YouTube app
            }
        });
        ytWeb.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View v, CustomViewCallback c) {
                ytCustom = v; ytCustomCb = c;
                if (ytRoot != null) ytRoot.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            @Override public void onHideCustomView() { hideYtCustom(); }
        });
        ytRoot.addView(ytWeb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView close = new TextView(this);
        close.setText("\u2715 Close");
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(0xCC0B1424);
        close.setTextSize(16);
        close.setPadding(36, 18, 36, 18);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeYouTube(); } });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, 16, 16, 0);
        ytRoot.addView(close, lp);
        addContentView(ytRoot, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ytWeb.loadUrl("https://m.youtube.com/watch?v=" + id);
        diag("in-app YouTube player opened");
    }

    private void hideYtCustom() {
        if (ytCustom != null && ytRoot != null) ytRoot.removeView(ytCustom);
        ytCustom = null;
        if (ytCustomCb != null) { try { ytCustomCb.onCustomViewHidden(); } catch (Exception e) { } ytCustomCb = null; }
    }

    private boolean closeYouTube() {
        if (ytRoot == null) return false;
        hideYtCustom();
        try { ytWeb.loadUrl("about:blank"); ytWeb.stopLoading(); ytWeb.destroy(); } catch (Exception e) { }
        ViewGroup p = (ViewGroup) ytRoot.getParent();
        if (p != null) p.removeView(ytRoot);
        ytRoot = null; ytWeb = null;
        return true;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQ) {
            if (filePathCb != null) {
                Uri[] r = null;
                if (res == RESULT_OK && data != null) {
                    ArrayList<Uri> uris = new ArrayList<Uri>();
                    ClipData cd = data.getClipData();
                    if (cd != null) {
                        for (int k = 0; k < cd.getItemCount(); k++) uris.add(cd.getItemAt(k).getUri());
                    } else if (data.getData() != null) {
                        uris.add(data.getData());
                    }
                    r = uris.toArray(new Uri[0]);
                }
                filePathCb.onReceiveValue(r);
                filePathCb = null;
                diag("file picker returned " + (r == null ? "nothing (cancelled)" : r.length + " file(s)"));
            }
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (pending != null) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) pending.grant(pending.getResources());
            else pending.deny();
            pending = null;
        }
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) goImmersive(); }
    @Override public void onBackPressed() {
        if (ytRoot != null) { if (ytCustom != null) hideYtCustom(); else closeYouTube(); return; }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
    @Override protected void onPause() { super.onPause(); }
}
