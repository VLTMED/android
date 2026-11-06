package me.champdev.weblock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One streaming site, full screen, with downloads, uploads, permissions, ad and pop-up blocking. */
public class WebActivity extends Activity {
    static final int REQ_FILE = 1, REQ_STORAGE = 5, REQ_PLAY = 6;
    // The chip that takes you back to the native player after Back
    static final String CHIP = "(()=>{if(document.getElementById('fp-native'))return;const b=document.createElement('button');b.id='fp-native';b.textContent='\u25B6 Native player';"
        + "b.style.cssText='position:fixed;top:8px;left:8px;z-index:2147483647;padding:5px 10px;font:600 13px sans-serif;color:#fff;background:#ff3b5c;border:0;border-radius:8px;opacity:.92';"
        + "b.onclick=e=>{e.stopPropagation();FMHY.openNative()};document.documentElement.appendChild(b)})()";
    // Remember <a download="name"> so blob downloads keep the page's filename
    static final String DL_HOOK = "if(!window.__wl){window.__wl=1;const c=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){window.__dl=this.download;return c.call(this)};"
        + "addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('a[download]');if(a)window.__dl=a.download},true);"
        // On any click, report the TMDB artwork of what was clicked (its card, or the hero it sits in), because
        // player pages often only carry the site logo
        + "const tm=s=>/image\\.tmdb\\.org|\\/t\\/p\\//.test(s);addEventListener('click',e=>{let src='';"
        + "for(let el=e.target,i=0;el&&el.querySelector&&i<6&&!src;el=el.parentElement,i++){const im=[...el.querySelectorAll('img')].find(m=>tm(m.currentSrc||m.src));"
        + "if(im)src=im.currentSrc||im.src;else{const u=/url\\([\"']?([^\"')]+)/.exec(getComputedStyle(el).backgroundImage);if(u&&tm(u[1]))src=u[1]}}"
        + "if(!src){let best=0;for(const m of document.images){const r=m.getBoundingClientRect(),s2=m.currentSrc||m.src;"
        + "if(tm(s2)&&r.bottom>0&&r.top<innerHeight&&r.width*r.height>best){best=r.width*r.height;src=s2}}}"
        + "if(src)FMHY.poster(new URL(src,location.href).href)},true)}";
    // Page title and its poster for Continue watching: a TMDB poster/backdrop (what these sites use) wins,
    // then the biggest picture or CSS background on screen, then og:image
    static final String CAPTURE = "(()=>{const m=n=>(document.querySelector(`meta[property=\"${n}\"],meta[name=\"${n}\"]`)||{}).content||'';"
        + "let img='',best=0;const consider=(s,r)=>{if(!/^https?:/.test(s)||r.width<120||r.height<80)return;"
        + "const a=r.width*r.height*(/image\\.tmdb\\.org|\\/t\\/p\\//.test(s)?20:1);if(a>best){best=a;img=s}};"
        + "for(const i of document.images)consider(i.currentSrc||i.src,i.getBoundingClientRect());"
        + "for(const e of document.querySelectorAll('[style*=background]')){const u=/url\\([\"']?([^\"')]+)/.exec(e.style.backgroundImage||'');if(u)consider(new URL(u[1],location.href).href,e.getBoundingClientRect())}"
        + "const og=m('og:image')||m('twitter:image');if(og&&/tmdb/.test(og)&&!/tmdb/.test(img))img=og;"
        + "return JSON.stringify({title:document.title||m('og:title'),image:img||og})})()";

    WebView web;
    ProgressBar progress;
    TextView status;
    String site, siteUrl;
    volatile String lastClicked, lastPoster;
    volatile long lastPosterAt;
    volatile long lastClickedAt;
    ValueCallback<Uri[]> fileCb;
    WebChromeClient.FileChooserParams fileParams;
    View fullscreen;
    WebChromeClient.CustomViewCallback fullscreenCb;
    String spatnav;
    Pointer pointer;
    final Sniffer sniffer = new Sniffer(this::onSeen, this::onStream);
    boolean resumed;
    // The native player lists these (found on the page when a stream starts) and asks us to press one
    static WebActivity active;
    final java.util.Map<String, String[]> servers = new java.util.LinkedHashMap<>(), qualities = new java.util.LinkedHashMap<>();
    String pendingServer;
    volatile boolean scanDone;
    // Title to type into the site's own search box (set when you tap Watch on a movie or episode); cleared once typed
    volatile String pendingQuery;
    int searchTries;

    static Intent open(Context c, String site, String siteUrl, String url) {
        return new Intent(c, WebActivity.class).putExtra("site", site).putExtra("siteUrl", siteUrl).putExtra("url", url);
    }

    final Runnable capture = () -> web.evaluateJavascript(CAPTURE, json -> {
        try {
            JSONObject o = new JSONObject(new JSONArray("[" + json + "]").getString(0));
            String image = o.optString("image");
            if (!History.isTmdb(image) && lastPoster != null && SystemClock.uptimeMillis() - lastPosterAt < 180000) image = lastPoster;
            History.record(site, siteUrl, web.getUrl(), o.optString("title"), image);
        } catch (Exception ignored) { }
    });

    final WebChromeClient chrome = new WebChromeClient() {
        @Override public void onProgressChanged(WebView v, int p) {
            progress.setProgress(p);
            progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
        }

        @Override public void onReceivedTitle(WebView v, String title) {
            scheduleCapture();
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            fileParams = p;
            openChooser();
            return true;
        }

        // A streaming app has no use for location, camera or microphone: sites asking for them are told no
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override public void onPermissionRequest(PermissionRequest r) {
            List<String> ok = new ArrayList<>();   // what needs no device permission, such as protected (DRM) video
            for (String res : r.getResources())
                if (!res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) ok.add(res);
            if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
        }

        // JS dialogs: with an application-context WebView the default ones can't show, so draw them here
        @Override public boolean onJsAlert(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(WebActivity.this).setMessage(message)
                .setPositiveButton("OK", (d, w) -> r.confirm()).setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsConfirm(WebView v, String url, String message, JsResult r) {
            new AlertDialog.Builder(WebActivity.this).setMessage(message)
                .setPositiveButton("OK", (d, w) -> r.confirm()).setNegativeButton("Cancel", (d, w) -> r.cancel())
                .setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsPrompt(WebView v, String url, String message, String def, JsPromptResult r) {
            EditText in = field(WebActivity.this, "", InputType.TYPE_CLASS_TEXT);
            in.setText(def);
            new AlertDialog.Builder(WebActivity.this).setMessage(message).setView(box(WebActivity.this, in))
                .setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString())).setNegativeButton("Cancel", (d, w) -> r.cancel())
                .setOnCancelListener(d -> r.cancel()).show();
            return true;
        }

        @Override public boolean onJsBeforeUnload(WebView v, String url, String message, JsResult r) {
            r.confirm();
            return true;
        }

        // Pop-up ads: only a clicked same-site target=_blank link opens (in this view); script pop-ups are dropped
        @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
            String link = clickedLink();
            if (gesture && link != null && sameSite(link, v.getUrl())) v.loadUrl(link);
            else toast("Pop-up blocked");
            return false;
        }

        // WebView draws a gray play-icon poster on <video> by default; Chrome shows nothing
        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override public void onShowCustomView(View v, CustomViewCallback cb) {
            if (fullscreen != null) { cb.onCustomViewHidden(); return; }
            fullscreen = v;
            fullscreenCb = cb;
            v.setBackgroundColor(Color.BLACK);
            decor().addView(v, new FrameLayout.LayoutParams(-1, -1));
            if (pointer != null) pointer.raise();
            decor().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override public void onHideCustomView() {
            if (fullscreen == null) return;
            decor().removeView(fullscreen);
            fullscreen = null;
            decor().setSystemUiVisibility(0);
            fullscreenCb.onCustomViewHidden();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        active = this;
        site = getIntent().getStringExtra("site");
        siteUrl = getIntent().getStringExtra("siteUrl");
        pendingQuery = getIntent().getStringExtra("q");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Home.BG);
        // Application context on purpose: Android's UiModeManager (which Chromium asks for) keeps whatever context it
        // was created from alive forever, and an activity context would leak the whole screen on every site visit.
        // Dialogs the page triggers are shown by our own client below, on this activity.
        web = new WebView(new android.view.ContextThemeWrapper(getApplicationContext(), R.style.AppTheme));
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(Home.ACCENT));
        root.addView(progress, new FrameLayout.LayoutParams(-1, Home.dp(this, 4), Gravity.TOP));
        status = new LocalizedTextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // 90/120 Hz phones would repaint an animated page twice as often for no visible gain: ask for 60 (less heat)
        android.view.WindowManager.LayoutParams wp = getWindow().getAttributes();
        wp.preferredRefreshRate = 60f;
        getWindow().setAttributes(wp);
        if (App.tv) pointer = new Pointer(this, new Pointer.Host() {
            @Override public View target() { return fullscreen != null ? fullscreen : web; }
            @Override public WebView web() { return web; }
            @Override public boolean deliver(KeyEvent e) { return WebActivity.super.dispatchKeyEvent(e); }
        });
        if (pointer != null && pointer.on && !App.prefs.getBoolean("pointerHint", false)) {
            App.prefs.edit().putBoolean("pointerHint", true).apply();
            LocalizedToast.makeText(this, "Cursor mode: arrows move it, OK taps. Hold OK 5 s to switch to focus mode.", Toast.LENGTH_LONG).show();
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Dark pages: API 29-32 force dark, 33+ algorithmic darkening (needs the dark AppTheme on the WebView's context, see below)
        if (android.os.Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(true);
        else if (android.os.Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_ON);
        // Look like plain Chrome so sites don't block the embedded browser
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            // Redirect ads: leaving the site needs a real click on a link to it; script jumps are blocked
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString(), link = clickedLink();
                String scheme = String.valueOf(r.getUrl().getScheme()).toLowerCase();
                if (scheme.matches("about|data|blob|javascript")) return false;
                if (scheme.matches("https?")) {
                    if (!r.isForMainFrame() || r.isRedirect() || sameSite(url, v.getUrl()) || sameSite(url, link)) return false;
                    toast("Blocked " + r.getUrl().getHost());
                } else if (url.equals(link)) {
                    openExternal(url);
                } else {
                    toast("Blocked " + scheme + ": link");
                }
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                sniffer.request(r.getUrl().toString(), r.getRequestHeaders());
                String host = r.getUrl().getHost();
                if (host == null || r.isForMainFrame() || !AdBlock.enabled() || !AdBlock.blocked(host) || sameSite("https://" + host, siteUrl)) return null;
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                sniffer.page(url);
                scheduleCapture();
            }

            @Override public void onPageFinished(WebView v, String url) {
                cloudflareCheck(v);
                v.evaluateJavascript(DL_HOOK, null);
                if (spatnav != null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(spatnav, null);
                if (pointer != null && pointer.on) v.evaluateJavascript("window.__fpClear&&__fpClear()", null);
                String q = pendingQuery;
                if (q != null && searchTries++ < 4) autosearch(v, q);
                scheduleCapture();
            }

            @Override public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler h, String host, String realm) {
                httpAuth(h, host, realm);
            }
        });
        web.setWebChromeClient(chrome);
        web.setDownloadListener(this::download);
        web.addJavascriptInterface(new Bridge(), "FMHY");

        // assets/frame.js: pauses the page's players and finds its server list, in every frame
        try {
            String frameHook = Sites.readAll(getAssets().open("frame.js"));
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, frameHook, Collections.singleton("*"));
        } catch (IOException ignored) { }
        if (App.tv) {
            try {
                // roles.js (what things are) goes first; spatnav.js (what to do about them) uses it
                spatnav = Sites.readAll(getAssets().open("roles.js")) + "\n" + Sites.readAll(getAssets().open("spatnav.js"));
            } catch (IOException ignored) { }
            if (spatnav != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, spatnav, Collections.singleton("*"));
        }

        // Don't let the first request leak out before the WARP tunnel is in place
        String url = getIntent().getStringExtra("url");
        if (Warp.state == Warp.State.CONNECTING) status.setText("Connecting WARP…");
        Warp.whenSettled(() -> {
            status.setVisibility(View.GONE);
            if (Warp.state == Warp.State.FAILED) toast("WARP failed, loading directly");
            Warp.route(!Warp.bypassed(siteOf(String.valueOf(Uri.parse(siteUrl).getHost()))));
            web.loadUrl(url);
            web.requestFocus();
        });
    }

    // Sites fill in the poster (og:image) a few seconds after the title, so look twice
    void scheduleCapture() {
        App.main.removeCallbacks(capture);
        App.main.postDelayed(capture, 2500);
        App.main.postDelayed(capture, 9000);
    }

    // A stream started on the page: hand it to the native player unless the user turned that off
    // A stream just appeared: look for the page's server list now, while the page is still awake
    void onSeen() {
        if (!resumed || isFinishing()) return;
        servers.clear();
        qualities.clear();
        scanDone = false;
        web.evaluateJavascript("postMessage({fp:'servers'},'*')", null);
    }

    // Hand the stream over, but let the server scan finish first (it needs the page awake; the player pauses it), at most 2 s
    void onStream() {
        if (!resumed || isFinishing()) return;
        if (!App.prefs.getBoolean("native", true)) { web.evaluateJavascript(CHIP, null); return; }
        if (scanDone) playNative(); else App.main.postDelayed(waitedHandoff, 2000);
    }

    final Runnable waitedHandoff = () -> { if (resumed && !isFinishing() && sniffer.best() != null) playNative(); };

    void playNative() {
        Sniffer.Hit h = sniffer.best();
        if (h == null) { toast("No video found on this page yet"); return; }
        sniffer.handedOff(h);
        if (fullscreen != null) chrome.onHideCustomView();
        // The CDN trusts the page that embedded the player, so replay what the WebView sent for the stream (Referer, Origin)
        Map<String, String> hd = new HashMap<>();
        hd.put("User-Agent", web.getSettings().getUserAgentString());
        String ref = header(h.headers, "Referer"), origin = header(h.headers, "Origin");
        if (ref == null) ref = web.getUrl();
        if (ref != null) hd.put("Referer", ref);
        if (origin == null && ref != null && !originOf(ref).equals(originOf(h.url))) origin = originOf(ref);
        if (origin != null) hd.put("Origin", origin);
        // Keep the page's own player quiet underneath
        web.evaluateJavascript("postMessage({fp:'pause'},'*');document.querySelectorAll('video,audio').forEach(m=>m.pause());var c=document.getElementById('fp-native');c&&c.remove()", null);
        Intent in = getIntent();
        startActivityForResult(PlayerActivity.open(this, h.url, h.dash, hd, sniffer.subtitles(), String.valueOf(web.getTitle()))
            .putExtra("pageTitle", String.valueOf(web.getTitle())).putExtra("pageUrl", String.valueOf(web.getUrl())).putExtra("site", site)
            .putExtra("imdb", in.getStringExtra("imdb")).putExtra("series", in.getBooleanExtra("series", false))
            .putExtra("season", in.getIntExtra("season", 0)).putExtra("episode", in.getIntExtra("episode", 0)), REQ_PLAY);
    }

    /** From the native player's Servers list: leave the player, press that button on the page, and go native again when its stream starts. */
    void switchServer(String key) {
        pendingServer = key;
    }

    static String header(Map<String, String> m, String name) {
        for (Map.Entry<String, String> e : m.entrySet()) if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue();
        return null;
    }

    static String originOf(String url) {
        Uri u = Uri.parse(url);
        return u.getScheme() + "://" + u.getAuthority();
    }

    String clickedLink() {
        if (lastClicked != null && SystemClock.uptimeMillis() - lastClickedAt < 3000) return lastClicked;
        WebView.HitTestResult h = web.getHitTestResult();
        return h.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE ? h.getExtra() : null;
    }

    // ponytail: "site" = last two host labels, so example.co.uk counts every *.co.uk as one site
    static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        String ha = Uri.parse(a).getHost(), hb = Uri.parse(b).getHost();
        return ha != null && hb != null && siteOf(ha).equals(siteOf(hb));
    }

    static String siteOf(String host) {
        if (host.matches("[\\d.:\\[\\]]+")) return host;
        String[] p = host.split("\\.");
        return p.length < 2 ? host : p[p.length - 2] + "." + p[p.length - 1];
    }

    // tel:, mailto:, intent://, market:// etc. go to the matching app, like Chrome does
    void openExternal(String url) {
        Intent i;
        try { i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME); } catch (Exception e) { return; }
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.setComponent(null);
        i.setSelector(null);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            String fallback = i.getStringExtra("browser_fallback_url");
            if (fallback != null && fallback.matches("(?i)https?://.*")) web.loadUrl(fallback);
            else toast("No app can open " + url);
        }
    }

    void httpAuth(HttpAuthHandler h, String host, String realm) {
        WebViewDatabase db = WebViewDatabase.getInstance(this);
        String[] saved = db.getHttpAuthUsernamePassword(host, realm);
        if (saved != null && h.useHttpAuthUsernamePassword()) { h.proceed(saved[0], saved[1]); return; }
        EditText user = field(this, "Username", InputType.TYPE_CLASS_TEXT);
        EditText pass = field(this, "Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new LocalizedDialogBuilder(this).setTitle("Sign in to " + host).setView(box(this, user, pass))
            .setPositiveButton("Sign in", (d, w) -> {
                String u = user.getText().toString(), p = pass.getText().toString();
                db.setHttpAuthUsernamePassword(host, realm, u, p);
                h.proceed(u, p);
            })
            .setNegativeButton("Cancel", (d, w) -> h.cancel())
            .setOnCancelListener(d -> h.cancel())
            .show();
    }

    void download(String url, String ua, String disposition, String mime, long length) {
        if (Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("Allow storage, then tap download again");
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mime);
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // DownloadManager can't fetch these; read them in the page and hand bytes to Bridge.save
            web.evaluateJavascript("fetch(" + JSONObject.quote(url) + ").then(r=>r.blob()).then(b=>{const f=new FileReader();"
                + "f.onload=()=>{FMHY.save(f.result,window.__dl||" + JSONObject.quote(name) + ");window.__dl=''};f.readAsDataURL(b)})", null);
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                .addRequestHeader("User-Agent", ua)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast("Downloading " + name);
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    /** Types the title into the site's search box (assets/autosearch.js); the page calls FMHY.searched() once it has. */
    void autosearch(WebView v, String q) {
        try {
            v.evaluateJavascript(Sites.readAll(getAssets().open("autosearch.js")) + "(" + JSONObject.quote(q) + ")", null);
        } catch (IOException ignored) { }
    }

    public class Bridge {
        // A frame found server buttons: keep them for the native player's Servers list
        @JavascriptInterface public void servers(String json) {
            try {
                JSONArray a = new JSONArray(json);
                App.main.post(() -> {
                    try {
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            ("q".equals(o.optString("kind")) ? qualities : servers).put(o.getString("k"), new String[]{o.getString("label"), o.optBoolean("on") ? "1" : ""});
                        }
                    } catch (Exception ignored) { }
                });
            } catch (Exception ignored) { }
        }

        // A frame saw a playlist go by (found by its content): treat it like a .m3u8 / .mpd request
        @JavascriptInterface public void stream(String url, boolean dash, String referer, String origin) {
            Map<String, String> h = new HashMap<>();
            if (!referer.isEmpty()) h.put("Referer", referer);
            if (!origin.isEmpty()) h.put("Origin", origin);
            sniffer.manifest(url, dash, h);
        }

        // The page's own frame has finished looking for servers
        @JavascriptInterface public void scanDone() {
            App.main.postDelayed(() -> {
                scanDone = true;
                if (App.main.hasCallbacks(waitedHandoff)) { App.main.removeCallbacks(waitedHandoff); waitedHandoff.run(); }
            }, 250);
        }

        @JavascriptInterface public void openNative() {
            runOnUiThread(WebActivity.this::playNative);
        }

        @JavascriptInterface public void searched() {
            pendingQuery = null;
        }

        // ponytail: whole file goes through a base64 string, very large blobs (100MB+) may OOM
        @JavascriptInterface public void save(String dataUrl, String name) {
            String safe = new File(name).getName();
            try {
                String mime = dataUrl.substring(5, dataUrl.indexOf(';'));
                byte[] bytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                try (OutputStream out = openDownload(safe, mime.isEmpty() ? "application/octet-stream" : mime)) {
                    out.write(bytes);
                }
                runOnUiThread(() -> toast("Saved " + safe + " to Downloads"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Download failed: " + e.getMessage()));
            }
        }

        @JavascriptInterface public void poster(String src) {
            lastPoster = src;
            lastPosterAt = SystemClock.uptimeMillis();
        }

        // Clean video mode: hide page layers over a playing video (a big stutter fix for 4K on weak TV chips)
        @JavascriptInterface public boolean cleanVideo() {
            return App.prefs.getBoolean("cleanvideo", true);
        }

        // Pointer mode: the page's remote-control highlight stays off
        @JavascriptInterface public boolean pointer() {
            return pointer != null && pointer.on;
        }

        // Remote-control clicks aren't touches, so the page tells us which link was clicked
        @JavascriptInterface public void clicked(String href) {
            lastClicked = href;
            lastClickedAt = SystemClock.uptimeMillis();
        }
    }

    OutputStream openDownload(String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            return getContentResolver().openOutputStream(getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv));
        }
        return new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name));
    }

    void openChooser() {
        try { startActivityForResult(Intent.createChooser(fileParams.createIntent(), null), REQ_FILE); } catch (ActivityNotFoundException e) { finishFile(null); }
    }

    void finishFile(Uri[] out) {
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PLAY) {
            // Back from the player: offer the way back (a server switch keeps the page going, it is about to load the new stream)
            // The episode the catalog opened may not be the one picked on the site from here on
            getIntent().removeExtra("season");
            getIntent().removeExtra("episode");
            web.evaluateJavascript(CHIP, null);
            if (pendingServer == null) web.evaluateJavascript("postMessage({fp:'pause'},'*')", null);
            return;
        }
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            ClipData clip = data == null ? null : data.getClipData();
            if (clip != null) {
                out = new Uri[clip.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = clip.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
        }
        finishFile(out);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && sniffer.has()) {
            if (e.getAction() == KeyEvent.ACTION_UP) playNative();
            return true;
        }
        return pointer != null ? pointer.dispatch(e) : super.dispatchKeyEvent(e);
    }

    // Menu on the remote: reload, WARP for this site, pointer / focus mode
    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (k != KeyEvent.KEYCODE_MENU) return super.onKeyUp(k, e);
        String key = siteKey();
        boolean off = Warp.bypassed(key);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add("Reload");
        acts.add(web::reload);
        if (sniffer.has()) { items.add("Play in native player"); acts.add(this::playNative); }
        boolean nat = App.prefs.getBoolean("native", true);
        items.add(nat ? "Native player: auto (switches when a video starts)" : "Native player: off");
        acts.add(() -> { App.prefs.edit().putBoolean("native", !nat).apply(); toast(nat ? "Native player off" : "Native player on"); });
        items.add(off ? "Use WARP for " + key : "Don't use WARP for " + key);
        acts.add(() -> { Warp.setBypassed(key, !off); Warp.route(off, web::reload); toast(off ? "WARP on for this site" : "Loading this site direct"); });
        if (pointer != null) {
            items.add(pointer.on ? "Switch to focus mode" : "Switch to pointer mode");
            acts.add(() -> { pointer.okSwitched = false; pointer.switchMode(); });
        }
        boolean clean = App.prefs.getBoolean("cleanvideo", true);
        items.add(clean ? "Smooth video: on (hides page layers over the video)" : "Smooth video: off");
        acts.add(() -> { App.prefs.edit().putBoolean("cleanvideo", !clean).apply(); toast(clean ? "Smooth video off" : "Smooth video on"); });
        items.add("Back to home");
        acts.add(this::finish);
        new LocalizedDialogBuilder(this).setTitle(site)
            .setItems(items.toArray(new String[0]), (d, i) -> acts.get(i).run()).show();
        return true;
    }

    String siteKey() {
        return siteOf(String.valueOf(Uri.parse(siteUrl).getHost()));
    }

    // Cloudflare's "Attention Required" is a hard block of WARP's shared address: retry this site direct, once, and remember it
    void cloudflareCheck(WebView v) {
        String title = String.valueOf(v.getTitle());
        String key = siteKey();
        if (title.startsWith("Attention Required") && !Warp.bypassed(key) && Warp.state == Warp.State.ON) {
            Warp.setBypassed(key, true);
            toast("Cloudflare blocked WARP, loading " + key + " direct");
            Warp.route(false, v::reload);
        } else if (title.startsWith("Just a moment")) {
            toast(App.tv ? "Cloudflare check: hold OK 5 s for pointer mode, then tick the box" : "Cloudflare check: tick the box");
        }
    }

    @Override public void onBackPressed() {
        if (fullscreen != null) chrome.onHideCustomView();
        else closePopupOrGoBack();
    }

    // An open pop-up on the page gets closed first (the page script knows); otherwise Back leaves as usual.
    // The answer comes back asynchronously, so a stuck page can't make Back dead: after 400 ms we go back anyway.
    void closePopupOrGoBack() {
        if (spatnav == null) { goBackOrFinish(); return; }
        final boolean[] answered = {false};
        web.evaluateJavascript("window.__fpBack ? __fpBack() : false", v -> {
            if (answered[0]) return;
            answered[0] = true;
            if (!"true".equals(v)) goBackOrFinish();
        });
        App.main.postDelayed(() -> {
            if (answered[0]) return;
            answered[0] = true;
            goBackOrFinish();
        }, 400);
    }

    void goBackOrFinish() {
        if (web.canGoBack()) web.goBack(); else finish();
    }

    @Override protected void onPause() {
        resumed = false;
        App.main.removeCallbacks(capture);
        capture.run();
        super.onPause();
        web.onPause();
        web.pauseTimers();       // a page that isn't on screen must not keep running its scripts and ads
        CookieManager.getInstance().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingServer != null) {
            String k = pendingServer;
            pendingServer = null;
            sniffer.fresh();
            web.evaluateJavascript("postMessage({fp:'click',k:" + JSONObject.quote(k) + "},'*')", null);
        }
        web.onResume();
        web.resumeTimers();
    }

    // A WebView still attached to the window can't be destroyed: Chromium keeps it, and with it this activity.
    // Detach first, drop every callback that points back here, then destroy.
    @Override protected void onDestroy() {
        if (active == this) active = null;
        Warp.route(true);
        App.main.removeCallbacks(capture);
        if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
        if (fullscreen != null) chrome.onHideCustomView();
        if (pointer != null) pointer.release();
        web.stopLoading();
        web.removeJavascriptInterface("FMHY");
        web.setWebChromeClient(null);
        web.setWebViewClient(new WebViewClient());
        web.setDownloadListener(null);
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) parent.removeView(web);
        web.removeAllViews();
        web.destroy();
        super.onDestroy();
    }

    boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    FrameLayout decor() {
        return (FrameLayout) getWindow().getDecorView();
    }

    static EditText field(Context c, String hint, int type) {
        EditText e = new LocalizedEditText(c);
        e.setHint(Localization.translate(c, hint));
        e.setInputType(type);
        e.setTypeface(Fonts.ui(c));
        return e;
    }

    static LinearLayout box(Context c, View... views) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = Home.dp(c, 20);
        l.setPadding(pad, pad / 2, pad, 0);
        for (View v : views) l.addView(v);
        return l;
    }

    void toast(String s) {
        LocalizedToast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
