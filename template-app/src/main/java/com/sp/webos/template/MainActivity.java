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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
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

        // ---- Template kind (v5.1): har flavour apna TEMPLATE_KIND lata hai.
        // "default" / "js3d" -> assets/index.html (js3d ke saath bandle
        // three.min.js bhi assets me hota hai). "url" -> assets/url.txt me
        // likhi website. "full" -> index.html + immersive fullscreen +
        // screen hamesha on (kiosk style). Bridge aur file chooser sab
        // flavours me bilkul ek jaise rehte hain.
        String kind = BuildConfig.TEMPLATE_KIND;
        if ("full".equals(kind)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyImmersive();
        }
        if ("url".equals(kind)) {
            webView.loadUrl(readUrlFromAssets());
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    /** URL template: builder assets/url.txt me website ka pata likhta hai.
     *  File na mile ya khaali ho to example.com khulta hai. */
    private String readUrlFromAssets() {
        try (InputStream in = getAssets().open("url.txt")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            String u = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!u.isEmpty()) return u;
        } catch (Exception ignored) {
        }
        return "https://example.com";
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
        if (hasFocus && "full".equals(BuildConfig.TEMPLATE_KIND)) applyImmersive();
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
        if (!"full".equals(BuildConfig.TEMPLATE_KIND)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
