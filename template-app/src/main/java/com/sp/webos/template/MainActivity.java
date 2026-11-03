package com.sp.webos.template;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Rational;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

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

    // v5.3 tak features builder ke likhe assets/config.json se aate the.
    // v6.0: config.json sirf type/url + PANEL DEFAULTS deta hai — asal
    // on/off haalat ab app ke andar ke secret panel (SharedPreferences
    // "sp_panel") me rehti hai aur LIVE badalti hai. Purane flat keys
    // (fullscreen/adBlock/...) config me milein to woh bhi seed ban
    // jate hain, taaki v5.3 se bane apps bilkul waise hi chalein.
    private String cfgType = "html";
    private String cfgUrl = "";
    private boolean featCustomCode;
    private JSONObject cfgPanelDefaults;
    private JSONArray cfgDefaultScripts;
    private boolean cfgSeedFull, cfgSeedAd, cfgSeedHist, cfgSeedGest, cfgSeedNight;
    private boolean cfgSeedHasFull, cfgSeedHasAd, cfgSeedHasHist, cfgSeedHasGest, cfgSeedHasNight;

    // ---- Panel ki LIVE haalat (sp_panel prefs ke mirror fields) ----
    private static final String PANEL_PREFS = "sp_panel";
    private boolean pNight, pFull, pBg, pPip, pAd, pGest, pHist, pDesk;
    private final Set<String> pApplied = new LinkedHashSet<>();
    private JSONArray pUserScripts = new JSONArray();
    private JSONArray presetsCache;

    private String injectJsCache;
    private String defaultUa;
    private boolean inPip;
    private boolean bgServiceOn;
    private boolean panelReloadNeeded;
    private View panelOverlay;
    private int panelTab = 0; // 0=History, 1=Scripts, 2=Script jodo
    private LinearLayout panelBtnGrid;
    private FrameLayout panelContent;
    private ListView panelHistList;
    private TextView panelHistEmpty;
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
                // Ad Block (v6.0: panel ki LIVE haalat dekhta hai):
                // ad/tracker host ki sub-resource requests typed khaali
                // jawab se rok do. Page ka main-frame document KABHI
                // nahi rukta, aur video ki asal media (googlevideo)
                // bhi nahi — warna YouTube player ad ke intezaar me
                // atak jata tha (purana stall wala bug).
                if (pAd && request != null && !request.isForMainFrame()) {
                    try {
                        Uri u = request.getUrl();
                        if (u != null) {
                            String host = u.getHost();
                            if (host != null && !host.endsWith("googlevideo.com")
                                    && isAdHost(host)) {
                                return emptyAdResponse(u.getPath());
                            }
                        }
                    } catch (Exception ignored) { }
                }
                return null;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (pHist && !isReload) recordHistory(url, view.getTitle());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Title ab pakka mil gaya hoga — history entry tazaa karo.
                if (pHist) recordHistory(url, view.getTitle());
                // v6.0: night mode + build-time custom code + panel scripts.
                if (pNight) injectDarkMode(view);
                if (featCustomCode) injectCustomCode(view);
                injectAppliedScripts(view);
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

        // ---- v6.0: panel state seed/load, phir LIVE vyavhaar ----
        seedPanelIfNeeded();
        loadPanelState();
        defaultUa = s.getUserAgentString();
        if (pDesk && "url".equals(cfgType)) {
            s.setUserAgentString(DESKTOP_UA);
        }
        if (pFull) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyImmersive();
        }
        if ("url".equals(cfgType)) {
            webView.loadUrl(cfgUrl.isEmpty() ? "https://example.com" : cfgUrl);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
        installEdgeGestures();  // listener hamesha; gate panel state par hai
        addSecretDot();         // dot ab HAR app me — yahi panel ka darwaza hai
        updateBgService();      // BG Play default ON ho to service shuru
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
                    if (!pGest) { downEdge[0] = 0; return false; }
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
     *  v6.0 me yahi secret CONTROL PANEL ka darwaza hai (history bhi
     *  usi ke andar hai). Glyph chhota hai, lekin aas-paas padding
     *  dekar touch area aaramdayak rakha gaya hai. */
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
        dot.setOnClickListener(v -> showPanel());
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

    // ---------------- v6.0: secret control panel (• dot)

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";

    private boolean isUrlType() { return "url".equals(cfgType); }

    private LinearLayout.LayoutParams lpWrap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    /** Dot dabane par khulne wala full-screen control panel: upar
     *  chhote toggle buttons ki grid, neeche History / Scripts /
     *  Script-jodo views. Jo cheez is app ke type par lagu nahi hoti,
     *  woh DHUNDLI (alpha 0.35) aur band (non-clickable) dikhti hai —
     *  chhupati kabhi nahi. */
    private void showPanel() {
        if (panelOverlay != null) return;
        panelTab = isUrlType() ? 0 : 1;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFFFAFAFA);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(6), dp(8));
        TextView hTitle = new TextView(this);
        hTitle.setText("🎛️ SP Control Panel");
        hTitle.setTextSize(16);
        hTitle.setTextColor(0xFF212121);
        hTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(hTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView hClose = new TextView(this);
        hClose.setText("✖");
        hClose.setTextSize(16);
        hClose.setTextColor(0xFF212121);
        hClose.setPadding(dp(14), dp(8), dp(14), dp(8));
        hClose.setOnClickListener(v -> closePanel());
        header.addView(hClose);
        col.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        col.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        panelBtnGrid = new LinearLayout(this);
        panelBtnGrid.setOrientation(LinearLayout.VERTICAL);
        panelBtnGrid.setPadding(dp(8), dp(8), dp(8), dp(4));
        col.addView(panelBtnGrid);

        View divider2 = new View(this);
        divider2.setBackgroundColor(0xFFE0E0E0);
        col.addView(divider2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        panelContent = new FrameLayout(this);
        col.addView(panelContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        overlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        panelOverlay = overlay;
        showPanelTab(panelTab);
    }

    private void closePanel() {
        if (panelOverlay != null) {
            rootLayout.removeView(panelOverlay);
            panelOverlay = null;
            panelBtnGrid = null;
            panelContent = null;
            panelHistList = null;
            panelHistEmpty = null;
        }
        if (panelReloadNeeded) {
            panelReloadNeeded = false;
            try { webView.reload(); } catch (Exception ignored) { }
        }
    }

    /** Panel ka ek chhota button (11sp, compact padding). */
    private Button panelButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(6), dp(7), dp(6), dp(7));
        return b;
    }

    /** Toggle button ki haalat-wali sajawat: ON = gulabi bhara hua,
     *  OFF = halka grey. available na ho to dhundla + band. */
    private void stylePanelToggle(Button b, boolean on, boolean available) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        if (on) {
            g.setColor(0xFFF8BBD0);
            g.setStroke(dp(1), 0xFFE91E63);
            b.setTextColor(0xFFC2185B);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            g.setColor(0xFFEEEEEE);
            g.setStroke(dp(1), 0xFFBDBDBD);
            b.setTextColor(0xFF424242);
            b.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        b.setBackground(g);
        b.setEnabled(available);
        b.setAlpha(available ? 1f : 0.35f);
    }

    /** History/Scripts/Add — ye teen "view badalne" wale buttons hain:
     *  chuna hua = pink bhara, baaki halke neele. */
    private void stylePanelTabBtn(Button b, boolean selected, boolean available) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        if (selected) {
            g.setColor(0xFFE91E63);
            b.setTextColor(0xFFFFFFFF);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            g.setColor(0xFFE3F2FD);
            g.setStroke(dp(1), 0xFF90CAF9);
            b.setTextColor(0xFF1565C0);
            b.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        b.setBackground(g);
        b.setEnabled(available);
        b.setAlpha(available ? 1f : 0.35f);
    }

    /** Upar wali button-grid dobara banata hai — har toggle ke baad
     *  rang/label taaza dikhta hai. */
    private void refreshPanelButtons() {
        if (panelBtnGrid == null) return;
        panelBtnGrid.removeAllViews();
        final boolean url = isUrlType();
        List<Button> btns = new ArrayList<>();

        Button bNight = panelButton("🌙 Night" + (pNight ? " • ON" : ""));
        stylePanelToggle(bNight, pNight, true);
        bNight.setOnClickListener(v -> {
            pNight = !pNight;
            savePanelState();
            panelReloadNeeded = true; // night page dobara load par hi saaf lagu/band hota hai
            refreshPanelButtons();
        });
        btns.add(bNight);

        Button bFull = panelButton("⛶ Full Screen" + (pFull ? " • ON" : ""));
        stylePanelToggle(bFull, pFull, true);
        bFull.setOnClickListener(v -> { toggleFullScreen(); refreshPanelButtons(); });
        btns.add(bFull);

        Button bBg = panelButton("📺 BG Play" + (pBg ? " • ON" : ""));
        stylePanelToggle(bBg, pBg, true);
        bBg.setOnClickListener(v -> {
            pBg = !pBg;
            savePanelState();
            updateBgService();
            refreshPanelButtons();
        });
        btns.add(bBg);

        Button bPip = panelButton("🖼️ PiP" + (pPip ? " • ON" : ""));
        stylePanelToggle(bPip, pPip, true);
        bPip.setOnClickListener(v -> {
            pPip = !pPip;
            savePanelState();
            refreshPanelButtons();
            if (pPip && customView != null
                    && Build.VERSION.SDK_INT >= 26) {
                closePanel();
                enterPip();
            } else if (pPip) {
                Toast.makeText(this,
                        "PiP ON — video ko full screen par chalakar Home dabao, chhoti window khul jayegi",
                        Toast.LENGTH_LONG).show();
            }
        });
        btns.add(bPip);

        Button bAd = panelButton("🚫 Ad Block" + (pAd ? " • ON" : ""));
        stylePanelToggle(bAd, pAd, url);
        if (url) bAd.setOnClickListener(v -> {
            pAd = !pAd;
            savePanelState();
            panelReloadNeeded = true;
            refreshPanelButtons();
        });
        btns.add(bAd);

        Button bGest = panelButton("👆 Gestures" + (pGest ? " • ON" : ""));
        stylePanelToggle(bGest, pGest, url);
        if (url) bGest.setOnClickListener(v -> {
            pGest = !pGest;
            savePanelState();
            refreshPanelButtons();
        });
        btns.add(bGest);

        Button bDesk = panelButton("🖥️ Desktop" + (pDesk ? " • ON" : ""));
        stylePanelToggle(bDesk, pDesk, url);
        if (url) bDesk.setOnClickListener(v -> {
            pDesk = !pDesk;
            savePanelState();
            try {
                webView.getSettings().setUserAgentString(pDesk ? DESKTOP_UA : defaultUa);
            } catch (Exception ignored) { }
            panelReloadNeeded = true;
            refreshPanelButtons();
        });
        btns.add(bDesk);

        Button tHist = panelButton("🤫 History");
        stylePanelTabBtn(tHist, panelTab == 0, url);
        if (url) tHist.setOnClickListener(v -> showPanelTab(0));
        btns.add(tHist);

        Button tScripts = panelButton("📜 Scripts");
        stylePanelTabBtn(tScripts, panelTab == 1, true);
        tScripts.setOnClickListener(v -> showPanelTab(1));
        btns.add(tScripts);

        Button tAdd = panelButton("💾 Script जोड़ो");
        stylePanelTabBtn(tAdd, panelTab == 2, true);
        tAdd.setOnClickListener(v -> showPanelTab(2));
        btns.add(tAdd);

        // 3-3 ke rows (wrap-grid ka seedha tareeka)
        LinearLayout row = null;
        for (int i = 0; i < btns.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = dp(6);
                panelBtnGrid.addView(row, rp);
            }
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 3 > 0) bp.leftMargin = dp(6);
            row.addView(btns.get(i), bp);
        }
    }

    private void showPanelTab(int tab) {
        panelTab = tab;
        refreshPanelButtons();
        if (panelContent == null) return;
        panelContent.removeAllViews();
        View child;
        if (tab == 0) child = buildPanelHistoryView();
        else if (tab == 1) child = buildPanelScriptsView();
        else child = buildPanelAddView();
        panelContent.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Panel ke andar wali History view — purani secret history hi
     *  hai (wahi list, wahi saaf-karo button). HTML apps me sirf ek
     *  dhundla note dikhta hai. */
    private View buildPanelHistoryView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!isUrlType()) {
            TextView note = new TextView(this);
            note.setText("History सिर्फ़ URL apps में होती है");
            note.setTextSize(14);
            note.setTextColor(0xFF757575);
            note.setAlpha(0.6f);
            note.setGravity(Gravity.CENTER);
            note.setPadding(dp(20), dp(30), dp(20), dp(30));
            col.addView(note);
            return col;
        }
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
                    closePanel();
                    if (u != null && !u.isEmpty()) webView.loadUrl(u);
                }
            } catch (Exception ignored) { }
        });

        panelHistList = list;
        panelHistEmpty = empty;
        bindHistoryList(list, empty);
        return col;
    }

    /** Panel ki Scripts view: 12 presets + user ke save kiye scripts,
     *  har ek ke saath ON/OFF toggle (kitne bhi ek saath ON). */
    private View buildPanelScriptsView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(8), dp(10), dp(12));
        scroll.addView(col);

        TextView hint = new TextView(this);
        hint.setText("Ek saath kitne bhi scripts ON kar sakte ho. Badlav panel "
                + "band hote hi lagu hoga (page ek baar refresh hoga).");
        hint.setTextSize(12);
        hint.setTextColor(0xFF757575);
        col.addView(hint);

        JSONArray presets = loadPresets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject p = presets.optJSONObject(i);
            if (p != null) {
                col.addView(scriptRow(p.optString("id"), p.optString("title"),
                        p.optString("desc"), p.optString("power"), false), lpWrap(8));
            }
        }
        if (pUserScripts.length() > 0) {
            TextView uh = new TextView(this);
            uh.setText("💾 Mere scripts");
            uh.setTextSize(14);
            uh.setTextColor(0xFF212121);
            uh.setTypeface(null, android.graphics.Typeface.BOLD);
            col.addView(uh, lpWrap(14));
            for (int i = 0; i < pUserScripts.length(); i++) {
                JSONObject u = pUserScripts.optJSONObject(i);
                if (u != null) {
                    col.addView(scriptRow(u.optString("id"), u.optString("title"),
                            "Mera banaya hua script", null, true), lpWrap(8));
                }
            }
        }
        return scroll;
    }

    private GradientDrawable panelCardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0xFFE0E0E0);
        return g;
    }

    /** Scripts view ki ek row. Power scripts (bgPlay/pip) ka toggle
     *  asal native flag badalta hai — koi alag script-flag nahi banta. */
    private View scriptRow(final String id, String title, String desc,
                           final String power, final boolean isUser) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(panelCardBg());
        row.setPadding(dp(10), dp(8), dp(8), dp(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(0xFF212121);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(t);
        TextView d = new TextView(this);
        d.setText(desc == null ? "" : desc);
        d.setTextSize(11.5f);
        d.setTextColor(0xFF757575);
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean on = power != null
                ? ("bgPlay".equals(power) ? pBg : pPip)
                : pApplied.contains(id);
        Button tog = panelButton(on ? "⏻ ON" : "⏻ OFF");
        stylePanelToggle(tog, on, true);
        tog.setOnClickListener(v -> {
            if (power != null) {
                if ("bgPlay".equals(power)) { pBg = !pBg; updateBgService(); }
                else { pPip = !pPip; }
                savePanelState();
            } else {
                if (pApplied.contains(id)) pApplied.remove(id);
                else pApplied.add(id);
                savePanelState();
                panelReloadNeeded = true;
            }
            showPanelTab(1);
        });
        row.addView(tog);

        if (isUser) {
            Button del = panelButton("🗑");
            stylePanelToggle(del, false, true);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            dp2.leftMargin = dp(6);
            del.setOnClickListener(v -> {
                removeUserScript(id);
                panelReloadNeeded = true;
                showPanelTab(1);
            });
            row.addView(del, dp2);
        }
        return row;
    }

    /** Panel ki "Script jodo" view: title + JS + CSS → is app ke
     *  apne sp_panel store me save. */
    private View buildPanelAddView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(10), dp(12), dp(12));
        scroll.addView(col);

        final EditText titleIn = panelField("Script ka naam — jaise 'Mera YouTube Fix'");
        titleIn.setSingleLine(true);
        col.addView(titleIn, lpWrap(2));
        final EditText jsIn = panelField("JavaScript code (optional)");
        jsIn.setMinLines(5);
        jsIn.setGravity(Gravity.TOP);
        col.addView(jsIn, lpWrap(8));
        final EditText cssIn = panelField("CSS code (optional)");
        cssIn.setMinLines(4);
        cssIn.setGravity(Gravity.TOP);
        col.addView(cssIn, lpWrap(8));

        Button save = new Button(this);
        save.setText("💾 Save करो");
        save.setAllCaps(false);
        save.setOnClickListener(v -> {
            String title = titleIn.getText().toString().trim();
            String js = jsIn.getText().toString().trim();
            String css = cssIn.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(this, "Pehle script ka naam likho",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (js.isEmpty() && css.isEmpty()) {
                Toast.makeText(this, "JS ya CSS — kam se kam ek code to likho",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                JSONObject u = new JSONObject();
                u.put("id", "user-" + System.currentTimeMillis());
                u.put("title", title);
                u.put("js", js);
                u.put("css", css);
                pUserScripts.put(u);
                savePanelState();
                Toast.makeText(this, "💾 Script save ho gaya: " + title,
                        Toast.LENGTH_SHORT).show();
                showPanelTab(1);
            } catch (Exception e) {
                Toast.makeText(this, "Save nahi hua: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        col.addView(save, lpWrap(10));

        TextView note = new TextView(this);
        note.setText("Save hua script '📜 Scripts' list me milega — wahan se ON "
                + "karke chalao. Ye script sirf ISI app me rehta hai.");
        note.setTextSize(12);
        note.setTextColor(0xFF757575);
        col.addView(note, lpWrap(8));
        return scroll;
    }

    private EditText panelField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(0xFF212121);
        e.setHintTextColor(0xFF9E9E9E);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), 0xFFBDBDBD);
        e.setBackground(g);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
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

    /** Builder ke likhe assets/config.json ko padhta hai.
     *  File na mile ya kharab ho to defaults rehte hain: type html —
     *  bilkul purane Default template jaisa. v6 me config sirf
     *  type/url, inject.js ka switch (customCode), panel ke seed
     *  defaults (panelDefaults) aur default-ON scripts deta hai.
     *  v5.3 ke flat feature keys bhi padhe jate hain — seed ke liye,
     *  aur sirf tab jab config me sach me maujood hon. */
    private void loadConfig() {
        try {
            String text = readAssetText("config.json");
            if (text == null) return;
            JSONObject cfg = new JSONObject(text);
            cfgType = cfg.optString("type", "html");
            cfgUrl = cfg.optString("url", "").trim();
            featCustomCode = cfg.optBoolean("customCode", false);
            cfgSeedHasFull = cfg.has("fullscreen");
            cfgSeedFull = cfg.optBoolean("fullscreen", false);
            cfgSeedHasAd = cfg.has("adBlock");
            cfgSeedAd = cfg.optBoolean("adBlock", false);
            cfgSeedHasHist = cfg.has("history");
            cfgSeedHist = cfg.optBoolean("history", false);
            cfgSeedHasGest = cfg.has("gestures");
            cfgSeedGest = cfg.optBoolean("gestures", false);
            cfgSeedHasNight = cfg.has("darkPage");
            cfgSeedNight = cfg.optBoolean("darkPage", false);
            cfgPanelDefaults = cfg.optJSONObject("panelDefaults");
            cfgDefaultScripts = cfg.optJSONArray("defaultScripts");
        } catch (Exception ignored) { }
    }

    // ---------------- v6.0: panel state (SharedPreferences "sp_panel")

    /** Pehli baar chalne par panel ki haalat config se seed hoti hai.
     *  Order: (1) type ke defaults — URL apps me adBlock/gestures/
     *  history ON, baaki OFF; (2) v5.3 flat keys, agar config me
     *  maujood hain to woh jeette hain (v5.3-bane apps ka vyavhaar
     *  bilkul waisa hi rehta hai); (3) v6 ka panelDefaults object;
     *  (4) defaultScripts — power wale (bg-play/pip-mode) flags ban
     *  jate hain, baaki applied set me jate hain. */
    private void seedPanelIfNeeded() {
        SharedPreferences sp = getSharedPreferences(PANEL_PREFS, MODE_PRIVATE);
        if (sp.getBoolean("inited", false)) return;
        boolean url = isUrlType();
        boolean night = false, full = false, bg = false, pip = false;
        boolean ad = url, gest = url, hist = url, desk = false;
        if (cfgSeedHasNight) night = cfgSeedNight;
        if (cfgSeedHasFull) full = cfgSeedFull;
        if (cfgSeedHasAd) ad = cfgSeedAd;
        if (cfgSeedHasGest) gest = cfgSeedGest;
        if (cfgSeedHasHist) hist = cfgSeedHist;
        Set<String> applied = new LinkedHashSet<>();
        if (cfgPanelDefaults != null) {
            night = cfgPanelDefaults.optBoolean("nightMode", night);
            full = cfgPanelDefaults.optBoolean("fullScreen", full);
            bg = cfgPanelDefaults.optBoolean("bgPlay", bg);
            pip = cfgPanelDefaults.optBoolean("pip", pip);
            ad = cfgPanelDefaults.optBoolean("adBlock", ad);
            gest = cfgPanelDefaults.optBoolean("gestures", gest);
            hist = cfgPanelDefaults.optBoolean("historyRec", hist);
            desk = cfgPanelDefaults.optBoolean("desktopMode", desk);
        }
        if (cfgDefaultScripts != null) {
            for (int i = 0; i < cfgDefaultScripts.length(); i++) {
                String id = cfgDefaultScripts.optString(i);
                if ("bg-play".equals(id)) bg = true;
                else if ("pip-mode".equals(id)) pip = true;
                else if (!id.isEmpty()) applied.add(id);
            }
        }
        try {
            sp.edit()
                    .putBoolean("inited", true)
                    .putBoolean("night", night)
                    .putBoolean("full", full)
                    .putBoolean("bg", bg)
                    .putBoolean("pip", pip)
                    .putBoolean("ad", ad)
                    .putBoolean("gest", gest)
                    .putBoolean("hist", hist)
                    .putBoolean("desk", desk)
                    .putString("applied", new JSONArray(new ArrayList<>(applied)).toString())
                    .putString("user_scripts", "[]")
                    .apply();
        } catch (Exception ignored) { }
    }

    private void loadPanelState() {
        SharedPreferences sp = getSharedPreferences(PANEL_PREFS, MODE_PRIVATE);
        pNight = sp.getBoolean("night", false);
        pFull = sp.getBoolean("full", false);
        pBg = sp.getBoolean("bg", false);
        pPip = sp.getBoolean("pip", false);
        pAd = sp.getBoolean("ad", false);
        pGest = sp.getBoolean("gest", false);
        pHist = sp.getBoolean("hist", false);
        pDesk = sp.getBoolean("desk", false);
        pApplied.clear();
        try {
            JSONArray arr = new JSONArray(sp.getString("applied", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i);
                if (!id.isEmpty()) pApplied.add(id);
            }
        } catch (Exception ignored) { }
        try {
            pUserScripts = new JSONArray(sp.getString("user_scripts", "[]"));
        } catch (Exception e) {
            pUserScripts = new JSONArray();
        }
    }

    /** Panel me koi bhi badlav — turant save. */
    private void savePanelState() {
        try {
            getSharedPreferences(PANEL_PREFS, MODE_PRIVATE).edit()
                    .putBoolean("night", pNight)
                    .putBoolean("full", pFull)
                    .putBoolean("bg", pBg)
                    .putBoolean("pip", pPip)
                    .putBoolean("ad", pAd)
                    .putBoolean("gest", pGest)
                    .putBoolean("hist", pHist)
                    .putBoolean("desk", pDesk)
                    .putString("applied",
                            new JSONArray(new ArrayList<>(pApplied)).toString())
                    .putString("user_scripts", pUserScripts.toString())
                    .apply();
        } catch (Exception ignored) { }
    }

    private void removeUserScript(String id) {
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < pUserScripts.length(); i++) {
                JSONObject u = pUserScripts.optJSONObject(i);
                if (u != null && !id.equals(u.optString("id"))) out.put(u);
            }
            pUserScripts = out;
            pApplied.remove(id);
            savePanelState();
        } catch (Exception ignored) { }
    }

    /** Full Screen toggle: immersive + screen-on turant lagta/hattata hai. */
    private void toggleFullScreen() {
        pFull = !pFull;
        savePanelState();
        if (pFull) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyImmersive();
        } else {
            if (customView == null) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** BG Play ka keep-alive service panel state ke hisaab se chalta
     *  hai (wahi WebAppService jo SpWebOS.keepAlive bridge chalata hai). */
    private void updateBgService() {
        try {
            Intent svc = new Intent(this, WebAppService.class);
            if (pBg) {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
                bgServiceOn = true;
            } else if (bgServiceOn) {
                stopService(svc);
                bgServiceOn = false;
            }
        } catch (Exception ignored) { }
    }

    /** PiP me jaana — sirf API 26+ par, aur callers pehle khud bhi
     *  SDK check karte hain (purane Android par ye method chhoo bhi
     *  nahi hota, taaki PiP wali classes load hi na hon). */
    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            b.setAspectRatio(new Rational(16, 9));
            enterPictureInPictureMode(b.build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Home dabate hi (video fullscreen ho to) PiP khul jata hai.
        if (pPip && customView != null && Build.VERSION.SDK_INT >= 26) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        inPip = isInPictureInPictureMode;
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

    /** 1x1 transparent GIF (43 bytes) — blocked image requests ke
     *  liye valid jawab, taaki page ke layout/player na atkein. */
    private static final byte[] TINY_GIF = new byte[]{
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
            (byte) 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x21, (byte) 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02,
            0x02, 0x44, 0x01, 0x00, 0x3B
    };

    /** Blocked ad request ka typed khaali jawab: .js ke liye khaali
     *  JS, tasveeron ke liye 1x1 GIF, .css ke liye khaali CSS, baaki
     *  ke liye khaali text. Bare empty body (purana tareeka) se kuchh
     *  players/loaders atak jate the — ye usi ka ilaaj hai. */
    private static WebResourceResponse emptyAdResponse(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".js")) {
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        }
        if (p.endsWith(".gif") || p.endsWith(".png") || p.endsWith(".jpg")
                || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".svg")) {
            return new WebResourceResponse("image/gif", null,
                    new ByteArrayInputStream(TINY_GIF));
        }
        if (p.endsWith(".css")) {
            return new WebResourceResponse("text/css", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        }
        return new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream(new byte[0]));
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

    // ---------------- v6.0: script engine (presets + user scripts)

    /** assets/scripts.json ke preset scripts (ek baar parse, cache). */
    private JSONArray loadPresets() {
        if (presetsCache != null) return presetsCache;
        presetsCache = new JSONArray();
        try {
            String text = readAssetText("scripts.json");
            if (text != null) {
                JSONArray arr = new JSONObject(text).optJSONArray("scripts");
                if (arr != null) presetsCache = arr;
            }
        } catch (Exception ignored) { }
        return presetsCache;
    }

    private JSONObject findPreset(String id) {
        JSONArray arr = loadPresets();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    private JSONObject findUserScript(String id) {
        for (int i = 0; i < pUserScripts.length(); i++) {
            JSONObject u = pUserScripts.optJSONObject(i);
            if (u != null && id.equals(u.optString("id"))) return u;
        }
        return null;
    }

    /** Har page load ke baad (inject.js ke baad): ON hue scripts ka
     *  JS chalata hai aur CSS lagata hai — presets scripts.json ke
     *  order me, phir user scripts. Power scripts (bgPlay/pip) yahan
     *  nahi chalte; unka kaam native flags karte hain. Har script
     *  apne try/catch me band hai — ek kharab script baakiyon ko
     *  nahi rok sakti. */
    private void injectAppliedScripts(WebView view) {
        if (pApplied.isEmpty()) return;
        try {
            List<String> ordered = new ArrayList<>();
            JSONArray presets = loadPresets();
            for (int i = 0; i < presets.length(); i++) {
                JSONObject p = presets.optJSONObject(i);
                if (p != null && pApplied.contains(p.optString("id"))) {
                    ordered.add(p.optString("id"));
                }
            }
            for (String id : pApplied) {
                if (!ordered.contains(id)) ordered.add(id);
            }
            for (String id : ordered) {
                JSONObject sc = findPreset(id);
                if (sc == null) sc = findUserScript(id);
                if (sc == null || sc.has("power")) continue;
                String css = sc.optString("css", "");
                if (!css.isEmpty()) {
                    view.evaluateJavascript(cssWrapperJs(id, css), null);
                }
                String js = sc.optString("js", "");
                if (!js.isEmpty()) {
                    view.evaluateJavascript(
                            "(function(){try{\n" + js + "\n}catch(e){}})();", null);
                }
            }
        } catch (Exception ignored) { }
    }

    /** CSS ko <style> lagane wala JS — builder ke buildInjectJs wala
     *  hi wrapper, bas ek id guard ke saath (page me dobara na lage). */
    private static String cssWrapperJs(String id, String css) {
        String esc = css.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "").replace("\n", "\\n");
        return "(function(){try{"
                + "if(document.getElementById('sp-style-" + id + "'))return;"
                + "var st=document.createElement('style');"
                + "st.id='sp-style-" + id + "';"
                + "st.textContent='" + esc + "';"
                + "(document.head||document.documentElement).appendChild(st);"
                + "}catch(e){}})();";
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
        if (hasFocus && pFull) applyImmersive();
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
        // Panel ka Full Screen ON ho to screen-on hamesha rehna chahiye.
        if (!pFull) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // BG Play OFF: aam browser ki tarah WebView pause ho jata hai
        // (media ruk jata hai). ON: WebView ko pause NAHI karte aur
        // keep-alive service process ko zinda rakhta hai — media
        // background me bajta rehta hai (best effort: OS/OEM battery
        // manager bhaari dabav me app band kar sakta hai).
        if (!pBg) {
            try { webView.onPause(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { webView.onResume(); } catch (Exception ignored) { }
        if (pFull) applyImmersive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // PiP band karke (X dabakar) app chhode to fullscreen video
        // saaf band ho. PiP ke andar rehte hue activity onStop par
        // aati hi nahi, isliye ye PiP ko nahi kaat-ta.
        if (customView != null && !inPip) exitFullscreen();
    }

    @Override
    public void onBackPressed() {
        if (panelOverlay != null) {
            closePanel();
        } else if (customView != null) {
            exitFullscreen();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
