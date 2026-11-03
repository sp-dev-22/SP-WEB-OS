package com.sp.webos.template;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SP WEB OS template app — a mini-browser shell.
 * Loads assets/index.html in a full-screen WebView and gives the page
 * browser-like powers: camera/mic (WebRTC), geolocation, file upload,
 * downloads, HTML5 fullscreen video and notifications permission.
 * Every power is still granted at runtime, exactly like a browser:
 * the page asks, Android shows its permission dialog, the user decides.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WEB_PERMS = 1002;
    private static final int REQ_GEO_PERMS = 1003;
    private static final int REQ_NOTIFY_PERM = 1004;

    private WebView webView;
    private FrameLayout rootLayout;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private boolean notifyAsked = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // v5.3: features builder ke likhe assets/config.json se aate hain.
    // File na mile/kharab ho to sab defaults (purana Default template).
    private String cfgType = "html";
    private String cfgUrl = "";
    private boolean featFullscreen;
    private boolean featAdBlock;
    private boolean featHistory;
    private boolean featGestures;
    private boolean featDarkPage;
    private boolean featCustomCode;
    private String injectJsCache;
    private View historyOverlay;
    private JSONArray historyShown;
    private static final String HISTORY_PREFS = "sp_url_history";
    private static final String HISTORY_KEY = "entries";
    private static final int HISTORY_CAP = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();

        rootLayout = new FrameLayout(this);
        webView = new WebView(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")
                        || url.startsWith("file://") || url.startsWith("about:")) {
                    return false; // load inside the WebView
                }
                // tel:, mailto:, sms:, geo:, intent:, market: -> hand to Android
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Ad Block (v5.3): ad/tracker host ki sub-resource
                // requests khaali jawab se rok do. Page ka apna
                // main-frame document KABHI nahi rokte.
                if (featAdBlock && request != null && !request.isForMainFrame()) {
                    try {
                        Uri u = request.getUrl();
                        if (u != null && isAdHost(u.getHost())) {
                            return new WebResourceResponse("text/plain", "UTF-8",
                                    new ByteArrayInputStream(new byte[0]));
                        }
                    } catch (Exception ignored) { }
                }
                return null;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (featHistory && !isReload) recordHistory(url, view.getTitle());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Title ab pakka mil gaya hoga — history entry tazaa karo.
                if (featHistory) recordHistory(url, view.getTitle());
                // v5.3: page dark mode + custom JS/CSS injection.
                if (featDarkPage) injectDarkMode(view);
                if (featCustomCode) injectCustomCode(view);
                // Ask for the notifications permission once, like browsers do.
                if (!notifyAsked && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifyAsked = true;
                    requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY_PERM);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            // ---- Camera / mic for WebRTC (getUserMedia) ----
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                List<String> androidPerms = new ArrayList<>();
                boolean grantedSomethingDirect = false;
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        androidPerms.add(Manifest.permission.CAMERA);
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        androidPerms.add(Manifest.permission.RECORD_AUDIO);
                    } else {
                        grantedSomethingDirect = true; // e.g. protected media / midi
                    }
                }
                if (androidPerms.isEmpty()) {
                    request.grant(request.getResources());
                    return;
                }
                if (allGranted(androidPerms)) {
                    request.grant(request.getResources());
                    return;
                }
                if (pendingWebRequest != null) {
                    // One prompt at a time; deny extras (page can retry).
                    if (!grantedSomethingDirect) request.deny();
                    else request.grant(request.getResources());
                    return;
                }
                pendingWebRequest = request;
                requestPermissions(androidPerms.toArray(new String[0]), REQ_WEB_PERMS);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebRequest == request) pendingWebRequest = null;
            }

            // ---- Location for navigator.geolocation ----
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoCallback = callback;
                pendingGeoOrigin = origin;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO_PERMS);
            }

            // ---- File upload: <input type="file"> ----
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(
                            Intent.createChooser(intent, "File chuno"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            // ---- HTML5 fullscreen (video) ----
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                rootLayout.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        // ---- Downloads (links that point to files) ----
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                    req.setTitle(fileName);
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                    Toast.makeText(MainActivity.this,
                            "⬇️ Download shuru: " + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Download nahi ho paya: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });

        // ---- SpWebOS JS bridge: notifications + background keep-alive ----
        NotifyHelper.ensureChannel(this);
        webView.addJavascriptInterface(new SpWebOsBridge(), "SpWebOS");

        // ---- v5.3: ek hi template; vyavhaar assets/config.json ke
        // flags se tay hota hai (builder har build me config likhta hai).
        // config na mile to sab band = purana Default template jaisa.
        if (featFullscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyImmersive();
        }
        if ("url".equals(cfgType)) {
            webView.loadUrl(cfgUrl.isEmpty() ? "https://example.com" : cfgUrl);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
        if (featGestures) installEdgeGestures();
        if (featHistory) addSecretDot();
    }


    // ---------------- Features: gestures + secret history (v5.2, v5.3 me flag-gated)

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Kinare se swipe: left edge se rightward swipe = Back (<),
     *  right edge se leftward swipe = Forward (>). Android ki back key
     *  sirf peeche le jati hai — aage ke liye yahi gesture hai.
     *  Listener sirf dekhta hai aur hamesha false lautata hai; sirf jab
     *  gesture pakda jata hai tab touch consume hota hai (warna wohi
     *  tap page tak click ban kar jata). Scroll, click, file chooser —
     *  kisi par koi asar nahi. */
    private void installEdgeGestures() {
        final float edgePx = dp(28);
        final float thresholdPx = dp(80);
        final float[] downX = new float[1];
        final int[] downEdge = new int[1]; // 0 = beech me, 1 = left, 2 = right
        webView.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = ev.getX();
                    if (ev.getX() <= edgePx) downEdge[0] = 1;
                    else if (ev.getX() >= v.getWidth() - edgePx) downEdge[0] = 2;
                    else downEdge[0] = 0;
                    return false;
                case MotionEvent.ACTION_UP:
                    if (downEdge[0] != 0) {
                        float dx = ev.getX() - downX[0];
                        if (downEdge[0] == 1 && dx >= thresholdPx && webView.canGoBack()) {
                            downEdge[0] = 0;
                            webView.goBack();
                            return true;
                        }
                        if (downEdge[0] == 2 && dx <= -thresholdPx && webView.canGoForward()) {
                            downEdge[0] = 0;
                            webView.goForward();
                            return true;
                        }
                    }
                    downEdge[0] = 0;
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    downEdge[0] = 0;
                    return false;
                default:
                    return false;
            }
        });
    }

    /** Top-right kone me ek lagbhag adrishya chhota sa dot "•" (8sp) —
     *  yahi secret history ka darwaza hai. Glyph chhota hai, lekin
     *  aas-paas padding dekar touch area aaramdayak rakha gaya hai. */
    private void addSecretDot() {
        TextView dot = new TextView(this);
        dot.setText("•");
        dot.setTextSize(8);
        dot.setTextColor(0x55000000);
        dot.setContentDescription(null);
        int pad = dp(15);
        dot.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        dot.setOnClickListener(v -> showHistoryOverlay());
        rootLayout.addView(dot, lp);
    }

    /** Ek page-visit history me note karta hai (sirf http/https).
     *  Lagatar ek hi URL dobara aaye to usi entry ka title/samaya
     *  tazaa hota hai; purana (non-consecutive) repeat sabse aakhir
     *  chala jata hai. Cap 200 — purane apne aap hat-te hain. */
    private void recordHistory(String url, String title) {
        if (url == null) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        try {
            JSONArray arr = loadHistoryArray();
            long now = System.currentTimeMillis();
            if (arr.length() > 0) {
                JSONObject last = arr.getJSONObject(arr.length() - 1);
                if (url.equals(last.optString("url"))) {
                    if (title != null && !title.isEmpty()) last.put("title", title);
                    last.put("ts", now);
                    saveHistoryArray(arr);
                    return;
                }
            }
            for (int i = arr.length() - 1; i >= 0; i--) {
                if (url.equals(arr.getJSONObject(i).optString("url"))) {
                    arr.remove(i);
                    break;
                }
            }
            JSONObject e = new JSONObject();
            e.put("url", url);
            e.put("title", title == null ? "" : title);
            e.put("ts", now);
            arr.put(e);
            while (arr.length() > HISTORY_CAP) arr.remove(0);
            saveHistoryArray(arr);
        } catch (Exception ignored) { }
    }

    private JSONArray loadHistoryArray() {
        try {
            String s = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
                    .getString(HISTORY_KEY, "[]");
            return new JSONArray(s == null ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveHistoryArray(JSONArray arr) {
        try {
            getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
                    .edit().putString(HISTORY_KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Dot dabane par khulne wala pura-screen history page. */
    private void showHistoryOverlay() {
        if (historyOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFFFAFAFA);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(6), dp(8));
        TextView hTitle = new TextView(this);
        hTitle.setText("🕓 History");
        hTitle.setTextSize(17);
        hTitle.setTextColor(0xFF212121);
        hTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(hTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView hClose = new TextView(this);
        hClose.setText("✖");
        hClose.setTextSize(16);
        hClose.setTextColor(0xFF212121);
        hClose.setPadding(dp(14), dp(8), dp(14), dp(8));
        hClose.setOnClickListener(v -> closeHistoryOverlay());
        header.addView(hClose);
        col.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        col.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        FrameLayout middle = new FrameLayout(this);
        final ListView list = new ListView(this);
        list.setDividerHeight(1);
        middle.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView empty = new TextView(this);
        empty.setText("अभी कोई history नहीं है।");
        empty.setTextSize(14);
        empty.setTextColor(0xFF757575);
        empty.setGravity(Gravity.CENTER);
        middle.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(middle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button clear = new Button(this);
        clear.setText("🗑️ सारी history साफ़ करो");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            try {
                getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
                        .edit().remove(HISTORY_KEY).apply();
            } catch (Exception ignored) { }
            bindHistoryList(list, empty);
        });
        col.addView(clear);

        list.setOnItemClickListener((parent, view, position, id) -> {
            try {
                JSONObject e = historyShown == null
                        ? null : historyShown.optJSONObject(position);
                if (e != null) {
                    String u = e.optString("url");
                    closeHistoryOverlay();
                    if (u != null && !u.isEmpty()) webView.loadUrl(u);
                }
            } catch (Exception ignored) { }
        });

        overlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        historyOverlay = overlay;
        bindHistoryList(list, empty);
    }

    /** Stored history ko ulta (sabse nayi pehle) karke list me bharta hai. */
    private void bindHistoryList(ListView list, TextView empty) {
        try {
            JSONArray stored = loadHistoryArray();
            historyShown = new JSONArray();
            for (int i = stored.length() - 1; i >= 0; i--) {
                JSONObject e = stored.optJSONObject(i);
                if (e != null) historyShown.put(e);
            }
            list.setAdapter(new HistoryAdapter(historyShown));
            boolean none = historyShown.length() == 0;
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
        } catch (Exception ignored) { }
    }

    private void closeHistoryOverlay() {
        if (historyOverlay != null) {
            rootLayout.removeView(historyOverlay);
            historyOverlay = null;
        }
    }

    /** History ki ek row: pehli line title (na ho to URL), dusri line
     *  samaya + URL. */
    private class HistoryAdapter extends BaseAdapter {
        private final JSONArray data;

        HistoryAdapter(JSONArray data) {
            this.data = data;
        }

        @Override
        public int getCount() { return data.length(); }

        @Override
        public Object getItem(int position) { return data.optJSONObject(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView t1, t2;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                t1 = (TextView) row.getChildAt(0);
                t2 = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(9), dp(14), dp(9));
                t1 = new TextView(MainActivity.this);
                t1.setTextSize(15);
                t1.setTextColor(0xFF212121);
                t1.setMaxLines(1);
                t1.setEllipsize(TextUtils.TruncateAt.END);
                t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setTextColor(0xFF757575);
                t2.setMaxLines(2);
                t2.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(t1);
                row.addView(t2);
            }
            JSONObject e = data.optJSONObject(position);
            String url = e == null ? "" : e.optString("url");
            String ttl = e == null ? "" : e.optString("title");
            long ts = e == null ? 0 : e.optLong("ts");
            t1.setText(ttl == null || ttl.isEmpty() ? url : ttl);
            String when = ts > 0
                    ? new SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
                            .format(new Date(ts))
                    : "";
            t2.setText(when.isEmpty() ? url : when + "  •  " + url);
            return row;
        }
    }

    /** Asset file ko poora text padhta hai; na mile to null. */
    private String readAssetText(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Builder ke likhe assets/config.json ko padhta hai (v5.3).
     *  File na mile ya kharab ho to defaults rehte hain: type html,
     *  saare features band — bilkul purane Default template jaisa. */
    private void loadConfig() {
        try {
            String text = readAssetText("config.json");
            if (text == null) return;
            JSONObject cfg = new JSONObject(text);
            cfgType = cfg.optString("type", "html");
            cfgUrl = cfg.optString("url", "").trim();
            featFullscreen = cfg.optBoolean("fullscreen", false);
            featAdBlock = cfg.optBoolean("adBlock", false);
            featHistory = cfg.optBoolean("history", false);
            featGestures = cfg.optBoolean("gestures", false);
            featDarkPage = cfg.optBoolean("darkPage", false);
            featCustomCode = cfg.optBoolean("customCode", false);
        } catch (Exception ignored) { }
    }

    // ---------------- Feature: ad block (v5.3)

    /** Mashhoor ad/tracker networks ke hosts. Host barabar ho YA
     *  inka subdomain ho, tabhi block — page ka apna main-frame
     *  document waise bhi kabhi nahi rukta. */
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "ads.yahoo.com", "adnxs.com", "taboola.com", "outbrain.com",
            "popads.net", "popcash.net", "adcash.com", "mgid.com", "revcontent.com",
            "zedo.com", "adsterra.com", "propellerads.com", "exoclick.com",
            "juicyads.com", "trafficjunky.com", "adskeeper.co.uk", "ad-maven.com",
            "hilltopads.com", "clickadu.com", "adexnetworks.com",
            "smartadserver.com", "criteo.com", "rubiconproject.com", "pubmatic.com",
            "openx.net", "casalemedia.com", "advertising.com", "adsrvr.org",
            "moatads.com", "scorecardresearch.com", "quantserve.com",
            "inmobi.com", "vungle.com", "applovin.com", "unityads.unity3d.com",
            "adcolony.com", "chartboost.com", "mopub.com", "smaato.net",
            "bidvertiser.com", "evadav.com", "pushground.com", "notix.io",
            "push.house", "exdynsrv.com", "tsyndicate.com", "a-ads.com"
    };

    private static boolean isAdHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : AD_HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    // ---------------- Feature: page dark mode + custom code (v5.3)

    /** Page Dark Mode: poore page ko invert karke dark bana deta hai;
     *  tasveerein/video/canvas dobara invert hokar seedhe rehte hain.
     *  Har page par sirf EK baar (window flag guard). */
    private void injectDarkMode(WebView view) {
        try {
            view.evaluateJavascript(
                    "(function(){try{"
                    + "if(window.__spDarkApplied)return;"
                    + "window.__spDarkApplied=1;"
                    + "var st=document.createElement('style');"
                    + "st.textContent='img,video,canvas,[style*=\"background-image\"]"
                    + "{filter:invert(1) hue-rotate(180deg) !important;}';"
                    + "(document.head||document.documentElement).appendChild(st);"
                    + "document.documentElement.style.filter='invert(1) hue-rotate(180deg)';"
                    + "}catch(e){}})();", null);
        } catch (Exception ignored) { }
    }

    /** Custom JS/CSS: builder ke rakhe assets/inject.js (user ka JS +
     *  CSS wrapper) ko har page load ke baad chalata hai. */
    private void injectCustomCode(WebView view) {
        try {
            if (injectJsCache == null) {
                String t = readAssetText("inject.js");
                injectJsCache = t == null ? "" : t;
            }
            if (!injectJsCache.isEmpty()) view.evaluateJavascript(injectJsCache, null);
        } catch (Exception ignored) { }
    }

    /** Fullscreen template: system bars chhupakar immersive mode. */
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Dialog / permission prompt ke baad bars wapas aa jate hain —
        // fullscreen template me focus milte hi immersive dobara lagao.
        if (hasFocus && featFullscreen) applyImmersive();
    }

    /**
     * JavaScript API available to the page as window.SpWebOS:
     *   SpWebOS.notify(title, body)                    -> notification abhi
     *   SpWebOS.scheduleNotify(seconds, title, body)   -> notification kuch der baad
     *                                                     (app band ho tab bhi aayega)
     *   SpWebOS.keepAlive(true/false)                  -> background foreground-service
     *   SpWebOS.toast(text)                            -> chhota sa toast
     */
    public class SpWebOsBridge {

        @JavascriptInterface
        public void notify(String title, String body) {
            NotifyHelper.post(MainActivity.this,
                    (int) (System.currentTimeMillis() % Integer.MAX_VALUE), title, body);
        }

        @JavascriptInterface
        public void scheduleNotify(double delaySeconds, String title, String body) {
            long when = System.currentTimeMillis() + (long) (delaySeconds * 1000);
            NotifyHelper.schedule(MainActivity.this, when, title, body);
        }

        @JavascriptInterface
        public void keepAlive(boolean on) {
            Intent svc = new Intent(MainActivity.this, WebAppService.class);
            if (on) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } else {
                stopService(svc);
            }
        }

        @JavascriptInterface
        public void toast(String text) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }
    }

    private boolean allGranted(List<String> perms) {
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WEB_PERMS && pendingWebRequest != null) {
            PermissionRequest req = pendingWebRequest;
            pendingWebRequest = null;
            List<String> granted = new ArrayList<>();
            for (String res : req.getResources()) {
                String need = null;
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) need = Manifest.permission.CAMERA;
                else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) need = Manifest.permission.RECORD_AUDIO;
                if (need == null) {
                    granted.add(res);
                } else if (checkSelfPermission(need) == PackageManager.PERMISSION_GRANTED) {
                    granted.add(res);
                }
            }
            if (granted.isEmpty()) req.deny();
            else req.grant(granted.toArray(new String[0]));
        } else if (requestCode == REQ_GEO_PERMS && pendingGeoCallback != null) {
            boolean ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK) {
                    results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        rootLayout.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        // Fullscreen template me screen-on hamesha rehna chahiye.
        if (!featFullscreen) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (historyOverlay != null) {
            closeHistoryOverlay();
        } else if (customView != null) {
            exitFullscreen();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
