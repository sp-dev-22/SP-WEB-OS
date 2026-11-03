package com.sp.webos.builder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.RecoverableSecurityException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SP WEB OS — single-file HTML ko phone par hi APK banane wala app.
 * Flow: HTML file chuno ya code paste karo -> app ka naam do (optional)
 * -> type + features chuno -> "APK Banao" -> "Install Karo".
 * v5.1 policy: app ke andar kuch save NAHI hota. Har build ki files
 * (HTML, APK, icon) Downloads/SP WEB OS/<AppName>/ me jati hain —
 * Settings ke Auto Save toggles ke hisaab se.
 * Alag naam = alag package id = kai apps ek saath phone par reh sakte hain.
 *
 * v5 UI: neeche 4 tabs — Home (build flow), HTMLs, APKs (dono
 * Downloads-based), Settings (toggles + Help Center). Minimal pink design.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_HTML = 42;
    private static final int REQ_WRITE_PERM = 77;
    private static final int REQ_APK_DELETE = 101;
    private static final String INSTALL_ACTION = "com.sp.webos.INSTALL_RESULT";

    // Minimal pink-accent palette. PINK brand accent dono theme me same
    // rehta hai; baaki rang instance fields hain taaki onCreate me dark-mode
    // ki values bhari ja saken (neeche loadTheme dekho). Default = light.
    private static final int PINK = 0xFFE91E63;
    private static final int PINK_DARK = 0xFFC2185B;
    private static final int RED = 0xFFD32F2F;

    private static final String PREFS_NAME = "sp_web_os_prefs";
    private static final String PREF_DARK_MODE = "dark_mode";
    private static final String PREF_AUTO_SAVE_HTML = "auto_save_html";
    private static final String PREF_AUTO_SAVE_APK = "auto_save_apk";
    private static final String PREF_AUTO_SAVE_ICON = "auto_save_icon";
    private static final String PREF_AUTO_SAVE_SCRIPTS = "auto_save_scripts";
    // Type (v5.3 se): choice prefs me yaad rehti hai aur har build me
    // assets/config.json bankar generated app me jaati hai.
    private static final String PREF_APP_TYPE = "app_type";
    // v6.0: Library selection (HTML apps ke liye, build-time) +
    // default-ON script selection + manual code — sab prefs me.
    private static final String PREF_LIB_THREE = "lib_three";
    private static final String PREF_LIB_CHART = "lib_chart";
    private static final String PREF_LIB_KATEX = "lib_katex";
    private static final String PREF_SEL_SCRIPTS = "selected_scripts";
    private static final String PREF_MANUAL_JS = "manual_js";
    private static final String PREF_MANUAL_CSS = "manual_css";

    private boolean darkMode;
    private int DARK_TEXT = 0xFF212121;   // mukhya text
    private int GREY_TEXT = 0xFF616161;   // halka text / meta
    private int FAINT_TEXT = 0xFF9E9E9E;  // hint text
    private int PAGE_BG = 0xFFFAFAFA;     // page background
    private int CARD_BG = 0xFFFFFFFF;     // cards / rows / inputs / tab bar
    private int BORDER = 0xFFE0E0E0;      // borders / divider
    private int PINK_TEXT = PINK_DARK;    // headings + pink text (dark me PINK)

    /** Saved dark-mode preference padhta hai aur agar ON ho to palette ki
     *  dark values bhar deta hai. onCreate me UI banne se pehle call hota hai. */
    private void loadTheme() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        darkMode = prefs.getBoolean(PREF_DARK_MODE, false);
        if (darkMode) {
            DARK_TEXT = 0xFFF5F5F5;
            GREY_TEXT = 0xFFB0B0B0;
            FAINT_TEXT = 0xFF8A8A8A;
            PAGE_BG = 0xFF121212;
            CARD_BG = 0xFF1E1E1E;
            BORDER = 0xFF333333;
            PINK_TEXT = PINK; // gehre bg par C2185B dhundhla padta hai
        }
    }

    /** Status bar ka rang theme se milata hai + window background, taaki
     *  dark mode me kahin safed jhalak na dikhe. Dono theme me status bar
     *  gehra rang (pink / kala) hai, isliye icons safed hi rahenge —
     *  LIGHT_STATUS_BAR flag (kaale icons wala) band rakha jata hai. */
    private void applyWindowTheme() {
        Window w = getWindow();
        w.setStatusBarColor(darkMode ? PAGE_BG : PINK);
        w.setBackgroundDrawable(new ColorDrawable(PAGE_BG));
        View decor = w.getDecorView();
        decor.setSystemUiVisibility(
                decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private TextView status;
    private TextView packagePreview;
    private EditText codeBox;
    private EditText nameBox;
    private EditText urlBox;
    private Button[] typeBtns;
    private String appType = "html";
    // v6.0 Home: libraries chips + scripts row (purane feature
    // checkboxes hata diye gaye — features ab app ke panel me hain).
    private LinearLayout libRow;
    private TextView libNote;
    private Button[] libBtns;
    private boolean[] libSel = new boolean[3];
    private Button scriptsBtn;
    // v6.0: full-screen overlay pages (picker / features list) —
    // Icon Maker wale contentFrame pattern par hi.
    private View overlayPage;
    private EditText pageJsBox, pageCssBox;
    private Button installBtn;
    private ImageView iconPreview;
    private TextView iconStatus;
    private byte[] pickedHtml;
    private String pickedName;
    private File builtApk;
    private String builtLabel;
    private String builtPackage;

    // Activity ka content frame (charon tab isi ke andar rehte hain).
    // Icon Maker page bhi isi frame par overlay hokar khulta hai (v5.3.1).
    private FrameLayout contentFrame;

    // Icon Maker page (v5.3.1): khula ho to page + uska WebView in
    // fields me rehte hain, band ho to dono null.
    private View iconMakerPage;
    private WebView iconMakerWebView;

    // Scripts tab (v6.0)
    private LinearLayout scriptsTabList;

    // Tabs
    private static final int TAB_HOME = 0;
    private static final int TAB_HTML = 1;
    private static final int TAB_APK = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_SCRIPTS = 4;
    private ScrollView[] tabViews;
    private Button[] tabBtns;
    private int currentTab = TAB_HOME;

    // HTMLs tab
    private EditText htmlSearch;
    private TextView htmlHeader;
    private LinearLayout htmlList;

    // APKs tab
    private EditText apkSearch;
    private TextView apkHeader;
    private LinearLayout apkList;

    // ---------------------------------------------------------- chhote helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBg(int fill, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    /** Upar margin ke saath full-width layout params. */
    private LinearLayout.LayoutParams lp(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private void stylePrimary(Button b) {
        b.setBackground(roundedBg(PINK, 10, 0));
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private void styleOutline(Button b) {
        b.setBackground(roundedBg(CARD_BG, 10, PINK));
        b.setTextColor(PINK_TEXT);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    /** List rows ke chhote action buttons. */
    private void styleSmall(Button b) {
        b.setBackground(roundedBg(CARD_BG, 8, BORDER));
        b.setTextColor(DARK_TEXT);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
    }

    private void styleField(EditText e) {
        e.setBackground(roundedBg(CARD_BG, 10, BORDER));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setTextColor(DARK_TEXT);
        e.setHintTextColor(FAINT_TEXT);
        e.setTextSize(14);
    }

    private TextView heading(String text, float sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(PINK_TEXT);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView bodyText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(GREY_TEXT);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(new Date(ms));
    }

    // ---------------------------------------------------------- onCreate / tabs

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadTheme();
        applyWindowTheme();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);

        FrameLayout content = new FrameLayout(this);
        contentFrame = content;
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView homeScroll = new ScrollView(this);
        homeScroll.addView(buildHomeTab());
        content.addView(homeScroll);

        ScrollView htmlScroll = new ScrollView(this);
        htmlScroll.addView(buildHtmlTab());
        content.addView(htmlScroll);

        ScrollView apkScroll = new ScrollView(this);
        apkScroll.addView(buildApkTab());
        content.addView(apkScroll);

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(buildSettingsTab());
        content.addView(settingsScroll);

        ScrollView scriptsScroll = new ScrollView(this);
        scriptsScroll.addView(buildScriptsTab());
        content.addView(scriptsScroll);

        tabViews = new ScrollView[]{homeScroll, htmlScroll, apkScroll, settingsScroll,
                scriptsScroll};

        // Tab bar ke upar patli si border line
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(CARD_BG);
        tabBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.addView(tabBar);

        String[] labels = {"🏠 Home", "📄 HTMLs", "📦 APKs", "⚙️ Settings", "📜 Scripts"};
        tabBtns = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(12);
            b.setMinHeight(0);
            b.setMinWidth(0);
            b.setPadding(dp(4), dp(9), dp(4), dp(9));
            b.setOnClickListener(v -> showTab(idx));
            tabBar.addView(b, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tabBtns[i] = b;
        }

        setContentView(root);
        int startTab = TAB_HOME;
        if (savedInstanceState != null) {
            // Dark-mode toggle par recreate() hota hai — wahi tab par wapas aao.
            startTab = savedInstanceState.getInt("sp_tab", TAB_HOME);
            if (startTab < 0 || startTab >= tabViews.length) startTab = TAB_HOME;
        }
        showTab(startTab);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("sp_tab", currentTab);
    }

    /** Android Back button: Icon Maker page khula ho to pehle wahi band
     *  hota hai; warna default behaviour. (Is app me iske alawa koi
     *  custom Back handling hai hi nahi — Help Center waghera apne
     *  Dialog hain, un par Android khud Back sambhalta hai.) */
    @Override
    public void onBackPressed() {
        if (overlayPage != null) {
            closeOverlayPage();
            return;
        }
        if (iconMakerPage != null) {
            closeIconMaker();
            return;
        }
        super.onBackPressed();
    }

    private void showTab(int idx) {
        // Koi overlay page (Icon Maker / Scripts picker / Features
        // list) khula ho to tab badalne se pehle use band karo, warna
        // page naye tab ke upar chipka reh jayega.
        if (overlayPage != null) closeOverlayPage();
        if (iconMakerPage != null) closeIconMaker();
        currentTab = idx;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean sel = i == idx;
            tabBtns[i].setBackground(roundedBg(sel ? PINK : 0x00000000, 12, 0));
            tabBtns[i].setTextColor(sel ? 0xFFFFFFFF : GREY_TEXT);
            tabBtns[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (idx == TAB_HTML) refreshHtmlList();
        if (idx == TAB_APK) refreshApkList();
        if (idx == TAB_SCRIPTS) refreshScriptsTab();
    }

    /** Ek tab ke content ka base column (padding ke saath). */
    private LinearLayout tabColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(20));
        return col;
    }

    // ---------------------------------------------------------- Home tab (build flow)

    private LinearLayout buildHomeTab() {
        LinearLayout root = tabColumn();

        TextView title = new TextView(this);
        title.setText("SP WEB OS");
        title.setTextSize(26);
        title.setTextColor(PINK);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = bodyText("Single-file HTML → APK, seedha phone par. No PC, no server.");
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub, lp(2));

        Button pickBtn = new Button(this);
        pickBtn.setText("📄 HTML File Chuno");
        stylePrimary(pickBtn);
        pickBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain"});
            startActivityForResult(i, REQ_PICK_HTML);
        });
        root.addView(pickBtn, lp(14));

        codeBox = new EditText(this);
        codeBox.setHint("...ya yahan HTML code paste karo");
        codeBox.setMinLines(8);
        codeBox.setGravity(Gravity.TOP);
        codeBox.setTextSize(13);
        styleField(codeBox);
        root.addView(codeBox, lp(10));

        nameBox = new EditText(this);
        nameBox.setHint("App ka naam (optional) — khaali chhodo to HTML title / file naam se apne aap banega");
        nameBox.setSingleLine(true);
        styleField(nameBox);
        root.addView(nameBox, lp(8));

        packagePreview = new TextView(this);
        packagePreview.setTextSize(12);
        packagePreview.setTextColor(GREY_TEXT);
        root.addView(packagePreview, lp(6));

        TextWatcher previewWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                updatePackagePreview();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        codeBox.addTextChangedListener(previewWatcher);
        nameBox.addTextChangedListener(previewWatcher);
        updatePackagePreview();

        // ---- Type + Features (v5.3): ek hi template; type aur features
        // config.json me jaakar generated app ka vyavhaar tay karte hain.
        root.addView(heading("🧩 App Type", 15), lp(12));
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeBtns = new Button[2];
        final String[] typeVals = {"html", "url"};
        String[] typeLabels = {"📄 HTML App", "🌐 URL App"};
        for (int i = 0; i < 2; i++) {
            final String t = typeVals[i];
            Button b = new Button(this);
            b.setText(typeLabels[i]);
            styleSmall(b);
            b.setTextSize(13);
            b.setOnClickListener(v -> selectType(t));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tp.leftMargin = dp(6);
            typeRow.addView(b, tp);
            typeBtns[i] = b;
        }
        root.addView(typeRow, lp(6));

        urlBox = new EditText(this);
        urlBox.setHint("Website ka URL — jaise https://example.com");
        urlBox.setSingleLine(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        styleField(urlBox);
        urlBox.setVisibility(View.GONE);
        root.addView(urlBox, lp(8));

        // ---- v6.0: Libraries (build-time chunav; HTML apps ke liye
        // hi — ye akeli cheez hai jo APK ka size sach me badhati hai).
        root.addView(heading("📚 Libraries", 15), lp(12));
        libRow = new LinearLayout(this);
        libRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] libLabels = {"🎮 Three.js", "📊 Chart.js", "∑ KaTeX"};
        libBtns = new Button[3];
        SharedPreferences lprefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        libSel[0] = lprefs.getBoolean(PREF_LIB_THREE, false);
        libSel[1] = lprefs.getBoolean(PREF_LIB_CHART, false);
        libSel[2] = lprefs.getBoolean(PREF_LIB_KATEX, false);
        for (int i = 0; i < 3; i++) {
            final int li = i;
            Button b = new Button(this);
            b.setText(libLabels[i]);
            styleSmall(b);
            b.setTextSize(12);
            b.setOnClickListener(v -> {
                libSel[li] = !libSel[li];
                String key = li == 0 ? PREF_LIB_THREE
                        : li == 1 ? PREF_LIB_CHART : PREF_LIB_KATEX;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(key, libSel[li]).apply();
                refreshLibUi();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bp.leftMargin = dp(6);
            libRow.addView(b, bp);
            libBtns[i] = b;
        }
        root.addView(libRow, lp(6));
        libNote = new TextView(this);
        libNote.setTextSize(12);
        libNote.setTextColor(GREY_TEXT);
        root.addView(libNote, lp(4));

        // ---- v6.0: Scripts (default-ON chunav + apna code). Features
        // ab builder me tick NAHI hote — woh generated app ke andar
        // secret • panel se LIVE badalte hain.
        root.addView(heading("📜 Scripts", 15), lp(12));
        scriptsBtn = new Button(this);
        styleOutline(scriptsBtn);
        scriptsBtn.setOnClickListener(v -> openScriptsPicker());
        root.addView(scriptsBtn, lp(6));
        root.addView(bodyText("Features (Night, Full Screen, Ad Block, "
                + "Gestures…) ab APK banate samay tick nahi hote — woh "
                + "har generated app ke andar chhupe hue • dot wale "
                + "Control Panel se on/off hote hain. Yahan sirf woh "
                + "scripts chuno jo app me pehle din se ON rahein."), lp(4));

        String savedType = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_APP_TYPE, "html");
        selectType("url".equals(savedType) ? "url" : "html");
        refreshLibUi();
        refreshScriptsBtn();

        Button iconBtn = new Button(this);
        iconBtn.setText("🎨 App Icon Banao");
        styleOutline(iconBtn);
        iconBtn.setOnClickListener(v -> openIconMaker());
        root.addView(iconBtn, lp(12));

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview = new ImageView(this);
        int iconPx = dp(52);
        iconRow.addView(iconPreview, new LinearLayout.LayoutParams(iconPx, iconPx));
        iconStatus = new TextView(this);
        iconStatus.setTextSize(12);
        iconStatus.setTextColor(GREY_TEXT);
        iconStatus.setPadding(dp(10), 0, 0, 0);
        iconRow.addView(iconStatus);
        root.addView(iconRow, lp(8));
        refreshIconPreview();

        Button buildBtn = new Button(this);
        buildBtn.setText("🚀 APK Banao");
        stylePrimary(buildBtn);
        buildBtn.setOnClickListener(v -> build());
        root.addView(buildBtn, lp(14));

        installBtn = new Button(this);
        installBtn.setText("📲 Bana Hua APK Install Karo");
        styleOutline(installBtn);
        installBtn.setEnabled(false);
        installBtn.setOnClickListener(v -> install());
        root.addView(installBtn, lp(8));

        status = new TextView(this);
        status.setText("Taiyaar. HTML file chuno ya code paste karke APK banao.");
        status.setTextSize(13);
        status.setTextColor(DARK_TEXT);
        root.addView(status, lp(12));

        return root;
    }

    // ---------------------------------------------------------- Settings tab

    private LinearLayout buildSettingsTab() {
        LinearLayout root = tabColumn();
        root.addView(heading("⚙️ Settings / Jankari", 20));

        String version = "5.3";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        root.addView(bodyText("SP WEB OS  •  Version " + version
                + "\nKaam: single-file HTML ko phone par hi APK banana — no PC, no server."), lp(8));

        root.addView(heading("🌙 Dark Mode", 16), lp(16));
        root.addView(bodyText(
                "Raat ko ya kam roshni me dark mode aankhon ke liye asaan rehta hai. "
                + "Pink rang dono theme me wahi rehta hai."), lp(6));
        Switch darkSwitch = new Switch(this);
        darkSwitch.setText(darkMode ? "Dark Mode: ON" : "Dark Mode: OFF");
        darkSwitch.setTextSize(15);
        darkSwitch.setTextColor(DARK_TEXT);
        darkSwitch.setChecked(darkMode);
        darkSwitch.setPadding(0, dp(6), 0, dp(6));
        darkSwitch.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_DARK_MODE, checked).apply();
            recreate(); // poora UI naye theme ke saath dobara banta hai
        });
        root.addView(darkSwitch, lp(4));

        root.addView(heading("💾 Auto Save (Downloads)", 16), lp(16));
        root.addView(bodyText(
                "Har build ke baad files app ke andar NAHI, seedha "
                + "'Downloads/SP WEB OS/<AppName>/' folder me save hoti hain. "
                + "Kya-kya save ho, ye chaar toggles tay karte hain (chaaron default ON). Script save karne par uski Downloads copy (Scripts folder) chauthe toggle se banti hai."), lp(6));
        addSaveToggle(root, "Auto Save HTMLs", PREF_AUTO_SAVE_HTML);
        addSaveToggle(root, "Auto Save Apks", PREF_AUTO_SAVE_APK);
        addSaveToggle(root, "Auto Save Icons", PREF_AUTO_SAVE_ICON);
        addSaveToggle(root, "Auto Save Scripts", PREF_AUTO_SAVE_SCRIPTS);

        Button featBtn = new Button(this);
        featBtn.setText("📋 All Features List");
        stylePrimary(featBtn);
        featBtn.setOnClickListener(v -> openFeaturesList());
        root.addView(featBtn, lp(16));

        root.addView(heading("📖 Kaise istemaal karen", 16), lp(16));
        root.addView(bodyText(
                "1.  Home tab me HTML file chuno ya code paste karo.\n"
                + "2.  App ka naam do (optional). Naam se alag Package ID banta hai, "
                + "taaki kai apps ek saath phone par reh saken. Naam khaali ho to HTML ke "
                + "<title> ya file ke naam se apne aap ban jata hai. Phir App Type "
                + "(📄 HTML / 🌐 URL) chuno. Features ab yahan tick nahi hote — "
                + "woh bane hue app ke andar • Control Panel se LIVE badalte "
                + "hain. Chaho to 📚 Libraries (sirf HTML) aur 📜 Scripts "
                + "(default-ON) chun lo.\n"
                + "3.  Chaho to '🎨 App Icon Banao' se apna icon design karo — background "
                + "colour aur pattern tum khud chunte ho; purana bana icon 'Saved "
                + "Icons' se bhi chun sakte ho.\n"
                + "4.  '🚀 APK Banao' dabao. APK taiyaar hoga aur Auto Save toggles ke "
                + "hisaab se Downloads me save hoga.\n"
                + "5.  '📲 Install Karo' dabakar app install karo.\n\n"
                + "📄 HTMLs tab — Downloads me save hue saare HTML: kholo (build ke liye load), "
                + "naam badlo, ya delete karo.\n"
                + "📦 APKs tab — bane hue saare APK: install, share ya delete karo."), lp(6));

        root.addView(heading("📥 Downloads folder", 16), lp(16));
        root.addView(bodyText(
                "Har app ki files 'Downloads/SP WEB OS/<AppName>/' folder me milti hain — "
                + "us app ki HTML, APK aur icon.png. App ke andar kuch save nahi hota, "
                + "isliye builder uninstall ho jaye to bhi files tumhare paas rehti hain. "
                + "HTMLs aur APKs tab wahi folders dikhate hain."), lp(6));

        Button helpBtn = new Button(this);
        helpBtn.setText("❓ Help Center");
        stylePrimary(helpBtn);
        helpBtn.setOnClickListener(v -> openHelpCenter());
        root.addView(helpBtn, lp(16));

        Button gotoApks = new Button(this);
        gotoApks.setText("📦 Bane hue APK dekho");
        styleOutline(gotoApks);
        gotoApks.setOnClickListener(v -> showTab(TAB_APK));
        root.addView(gotoApks, lp(16));

        Button gotoHtmls = new Button(this);
        gotoHtmls.setText("📄 Saved HTML dekho");
        styleOutline(gotoHtmls);
        gotoHtmls.setOnClickListener(v -> showTab(TAB_HTML));
        root.addView(gotoHtmls, lp(8));

        return root;
    }

    /** Settings ka ek Auto Save toggle (SharedPreferences, default ON). */
    private void addSaveToggle(LinearLayout root, final String label, final String prefKey) {
        final Switch sw = new Switch(this);
        boolean on = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(prefKey, true);
        sw.setText(label + ": " + (on ? "ON" : "OFF"));
        sw.setTextSize(15);
        sw.setTextColor(DARK_TEXT);
        sw.setChecked(on);
        sw.setPadding(0, dp(4), 0, dp(4));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(prefKey, checked).apply();
            sw.setText(label + ": " + (checked ? "ON" : "OFF"));
        });
        root.addView(sw, lp(2));
    }

    // ---------------------------------------------------------- Help Center

    /** Ek sawal-jawab jodi Help Center me jodta hai. */
    private void helpQa(LinearLayout col, String q, String a) {
        col.addView(heading(q, 14), lp(12));
        col.addView(bodyText(a), lp(3));
    }

    /** Monospace code block + uska apna Copy button (bridge formats ke liye). */
    private void helpCodeBlock(LinearLayout col, final String code) {
        TextView t = new TextView(this);
        t.setText(code);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12.5f);
        t.setTextColor(DARK_TEXT);
        t.setBackground(roundedBg(CARD_BG, 8, BORDER));
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.addView(t, lp(6));
        Button copy = new Button(this);
        copy.setText("📋 Copy");
        styleSmall(copy);
        copy.setTextColor(PINK_TEXT);
        copy.setOnClickListener(v -> copyToClipboard(code));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(4);
        col.addView(copy, cp);
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SP WEB OS", text));
        }
        Toast.makeText(this, "📋 Copy ho gaya ✓", Toast.LENGTH_SHORT).show();
    }

    /** ❓ Help Center — apna alag full-screen page (Settings saaf rahe,
     *  isliye sab sawal/tips/bridge-format yahan rakhe hain). */
    private void openHelpCenter() {
        final Dialog dialog = new Dialog(this);
        LinearLayout col = tabColumn();
        col.setBackgroundColor(PAGE_BG);

        col.addView(heading("❓ Help Center", 20));
        col.addView(bodyText("Aam sawalon ke jawab, kaam ke tips, aur AI bridge ka "
                + "poora code format — sab ek jagah."), lp(6));

        col.addView(heading("❓ आम सवाल (Q&A)", 17), lp(14));
        helpQa(col, "Q1. App kaise banayein?",
                "Home tab me HTML file chuno ya code paste karo → chaaho to app ka "
                + "naam aur icon do → '🚀 APK Banao' dabao → '📲 Install Karo' se "
                + "install karo. Bas, app taiyaar!");
        helpQa(col, "Q2. Apna icon kaise lagayein?",
                "'🎨 App Icon Banao' dabao → poori screen par icon page "
                + "khulega → background rang aur pattern chuno (13 patterns "
                + "hain) → '✅ यह icon इस्तेमाल करो' dabao → '⬅ वापस' dabakar "
                + "Home par aao → phir APK banao. Wahi design launcher par "
                + "icon banega. Purana bana hua icon lagana ho to icon page "
                + "ke neeche '🖼️ Saved Icons से चुनो' dabao.");
        helpQa(col, "Q3. App Type kya hai? Features ab kahan gaye?",
                "Do Type hain: 📄 HTML App (single-file HTML se app) aur 🌐 URL "
                + "App (website ke URL se app). v6.0 se FEATURES builder me "
                + "tick NAHI hote — Night Mode, Full Screen, Ad Block, "
                + "Gestures, BG Play, PiP, Desktop Mode, History — ye sab "
                + "har generated app ke ANDAR chhupe hue chhote '•' dot "
                + "(top-right kone) ko dabane par khulne wale Control Panel "
                + "se LIVE on/off hote hain, aur app ki apni memory me save "
                + "rehte hain. Builder me ab sirf Type, 📚 Libraries aur "
                + "📜 Scripts (default-ON) chune jate hain.");
        helpQa(col, "Q4. Files kahan save hoti hain? App ke andar kuch kyun nahi?",
                "v5.1 ki policy: builder app apne andar kuch save NAHI karta. Har "
                + "app ki files Downloads/SP WEB OS/<AppName>/ folder me milti "
                + "hain — <AppName>.apk, <AppName>.html aur icon.png. Builder "
                + "uninstall ho jaye to bhi files tumhare paas rehti hain.");
        helpQa(col, "Q5. Auto Save toggles kya karte hain?",
                "Settings ke chaar toggles — Auto Save HTMLs / Apks / Icons / "
                + "Scripts — tay karte hain Downloads me kya-kya save ho. "
                + "Chaaron default ON hain. Koi OFF ho to woh file Downloads "
                + "me save nahi hogi (APK phir bhi banta hai, Install bhi "
                + "hota hai; scripts ki in-app copy hamesha banti hai).");
        helpQa(col, "Q6. Ek saath kai files (jaise gaane) select nahi ho rahe?",
                "Ye Android file picker ka tareeka hai: pehli file par LONG-PRESS "
                + "karo, phir baaki files tap karke select karo. SP WEB OS ke apps "
                + "me <input type=\"file\" multiple> ka support pehle se hai.");
        helpQa(col, "Q7. Bana hua APK share kaise karoon?",
                "📦 APKs tab kholo → app ke saamne '↗ Share' dabao → WhatsApp ya "
                + "koi chat app chuno. Ya Files app se Downloads/SP WEB OS folder "
                + "me jaakar wahan se share karo.");
        helpQa(col, "Q8. Purane app ko update kaise karoon — data udta to nahi?",
                "Usi naam se dobara APK banao aur install karo. Package ID aur "
                + "signing key hamesha wahi rehte hain, isliye naya version purane "
                + "ke upar update ki tarah chadhta hai — data safe rehta hai.");
        helpQa(col, "Q9. Web app me notification / reminder kaam nahi kar raha?",
                "Browser wala Notification API is shell me nahi chalta — SpWebOS "
                + "bridge istemaal karo: SpWebOS.notify(...) turant ke liye aur "
                + "SpWebOS.scheduleNotify(seconds, ...) baad ke liye. Poora code "
                + "format neeche 'AI Bridge' section me hai.");
        helpQa(col, "Q10. Dark mode kaise on karoon?",
                "⚙️ Settings tab → '🌙 Dark Mode' switch ON karo. Poora app dark "
                + "theme me badal jayega, pink rang waisa hi rahega.");
        helpQa(col, "Q11. 🧩 Extensions (Custom JS/CSS) kya hai?",
                "Sach ye hai: asli browser extensions (Chrome wale) WebView "
                + "apps ke andar NAHI chal sakte — ye Android ki seema hai. "
                + "Uske do built-in badle hain: (1) 📜 Preset Scripts — 12 "
                + "taiyaar scripts (YouTube Ad Skip, Reader Mode, Speed "
                + "Control…) jo har app ke andar • panel se on/off hote "
                + "hain; (2) ✍️ Apna code — Home ke '📜 Scripts चुनो' page "
                + "ke neeche JS/CSS boxes me apna code paste karo; woh "
                + "assets/inject.js bankar har page load ke baad chalega. "
                + "Code naa aata ho to AI ko likhne ko kaho aur yahan paste "
                + "kar do.");
        helpQa(col, "Q12. 🚫 Ad Block kaise kaam karta hai?",
                "URL apps me panel ka Ad Block ON ho to mashhoor ad/tracker "
                + "networks (jaise DoubleClick, PopAds, Taboola, Adsterra…) "
                + "ki requests rok di jati hain — page saaf, halka aur tez "
                + "banta hai. Blocked requests ko khaali-but-valid jawab "
                + "milta hai aur video ki asal stream (googlevideo) kabhi "
                + "nahi roki jati, isliye player atakta nahi. Kabhi koi site "
                + "ajeeb behave kare to usi app ke panel se Ad Block OFF "
                + "kar do — dobara APK banane ki zaroorat nahi.");
        helpQa(col, "Q13. 📚 Libraries (Three.js / Chart.js / KaTeX) ka istemaal kaise karoon?",
                "📄 HTML App chuno aur Home ke '📚 Libraries' row se library "
                + "tick karo — woh file APK ke andar jud jayegi. HTML me bas "
                + "relative path se jodo: Three.js ke liye "
                + "<script src=\"three.min.js\"></script>, Chart.js ke liye "
                + "<script src=\"chart.min.js\"></script>, KaTeX ke liye "
                + "<link rel=\"stylesheet\" href=\"lib/katex/katex.min.css\"> "
                + "aur <script src=\"lib/katex/katex.min.js\"></script>. Phir "
                + "poora code chalega — bina kuch download kiye, offline bhi. "
                + "Dhyaan do: libraries hi APK ka size badhati hain (Three.js "
                + "~654 KB, Chart.js ~201 KB, KaTeX ~546 KB) — jo zaroori ho "
                + "wahi chuno. URL apps me libraries nahi judti.");

        helpQa(col, "Q14. 📜 Scripts system kya hai — presets, save, default-ON?",
                "Har generated app ke andar 12 preset scripts pehle se hote "
                + "hain (assets/scripts.json) — app ke • panel ki '📜 Scripts' "
                + "list se kitne bhi ek saath ON kar sakte ho. Builder me: "
                + "Home ke '📜 Scripts चुनो' page se jo tick karoge woh naye "
                + "app me pehle din se ON rahenge. Apna code wahan script "
                + "banakar save bhi kar sakte ho — woh builder ke andar "
                + "hamesha save rehta hai, aur Settings ka 'Auto Save "
                + "Scripts' ON ho to Downloads/SP WEB OS/Scripts/ me .js/"
                + ".css copy bhi banti hai. 📜 Scripts tab se apne scripts "
                + "Use/Delete kar sakte ho. Generated app ke andar bhi "
                + "panel ki '💾 Script जोड़ो' se naya script ban sakta hai "
                + "— woh sirf usi app me rehta hai.");
        helpQa(col, "Q15. Panel me kuch buttons dhundhle (dim) kyun hain?",
                "Dim matlab: woh cheez is app ke TYPE par lagu nahi hoti. "
                + "HTML apps me History, Ad Block, Gestures aur Desktop Mode "
                + "ka koi matlab nahi (koi website browse hi nahi hoti), "
                + "isliye woh dim aur band dikhte hain — chhupaye nahi jate, "
                + "taaki tumhe pata rahe kya-kya maujood hai. URL apps me "
                + "sab kuch available hota hai. Yahi neeyam builder ke "
                + "Libraries row par bhi hai (URL type par dim).");

        col.addView(heading("💡 Tips", 17), lp(18));
        col.addView(bodyText(
                "💡 App ka naam hamesha do — naam se alag Package ID banta hai. "
                + "Bina naam wale apps ek-doosre ko replace kar dete hain.\n"
                + "💡 Ek hi naam = update. Purani app par naya version chadh jata "
                + "hai, data nahi udta.\n"
                + "💡 Icon me saaf pattern aur tez rang sabse achha dikhta hai — purana design 'Saved Icons' se dobara bhi lag sakta hai.\n"
                + "💡 Game/3D ke liye HTML App me 📚 Libraries se Three.js "
                + "chuno — alag se download karne ki zaroorat nahi.\n"
                + "💡 Badi HTML file ho to code paste ki jagah 'HTML File Chuno' "
                + "istemaal karo — zyada reliable hai.\n"
                + "💡 APK share karne se pehle ek baar khud install karke test "
                + "kar lo."), lp(6));

        col.addView(heading("🤖 AI Bridge — Code Format", 17), lp(18));
        col.addView(bodyText(
                "Bane hue apps ke andar web page ko ek khaas bridge milta hai: "
                + "window.SpWebOS. Isi se notifications aur reminders bante hain. "
                + "Neeche ke formats copy karke apni web app me lagao — ya AI ko "
                + "de do, woh tumhare liye code likh dega."), lp(6));

        col.addView(heading("1) Bridge hai ya nahi — check", 14), lp(12));
        helpCodeBlock(col,
                "if (window.SpWebOS) {\n"
                + "  // SP WEB OS ke andar ho — bridge taiyaar hai\n"
                + "}");
        col.addView(heading("2) Turant notification", 14), lp(12));
        helpCodeBlock(col,
                "SpWebOS.notify(\"Padhai ka time\", \"Polity ke notes revise karo!\");");
        col.addView(heading("3) Baad me notification (reminder)", 14), lp(12));
        col.addView(bodyText("Pehla number = kitne SECOND baad. App band ho tab "
                + "bhi notification aayega. (1 ghanta = 3600, 1 din = 86400)"), lp(3));
        helpCodeBlock(col,
                "SpWebOS.scheduleNotify(3600, \"Reminder\", \"Ek ghanta ho gaya!\");");
        col.addView(heading("4) App ko background me zinda rakho", 14), lp(12));
        helpCodeBlock(col,
                "SpWebOS.keepAlive(true);   // band karne ke liye: false");
        col.addView(heading("5) Chhota toast message", 14), lp(12));
        helpCodeBlock(col,
                "SpWebOS.toast(\"Save ho gaya!\");");

        col.addView(heading("📋 AI ko dene wala poora instruction", 14), lp(14));
        col.addView(bodyText("Ye paragraph copy karke kisi bhi AI (ChatGPT, "
                + "Gemini, Muse…) ko apni maang ke saath de do — woh bridge wala "
                + "sahi code likhega:"), lp(3));
        helpCodeBlock(col,
                "This web app will run inside SP WEB OS, an Android WebView shell. "
                + "For notifications and reminders do NOT use the web Notification "
                + "API — use the built-in bridge instead. First check "
                + "`if (window.SpWebOS)`. Then: `SpWebOS.notify(title, body)` "
                + "shows a notification immediately; "
                + "`SpWebOS.scheduleNotify(secondsFromNow, title, body)` "
                + "schedules one (the first argument is SECONDS, e.g. 3600 = 1 "
                + "hour, and it fires even when the app is closed); "
                + "`SpWebOS.keepAlive(true)` keeps the app alive in the "
                + "background; `SpWebOS.toast(text)` shows a small toast.");

        Button closeBtn = new Button(this);
        closeBtn.setText("Band Karo");
        stylePrimary(closeBtn);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        col.addView(closeBtn, lp(20));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.addView(col);
        dialog.setContentView(scroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(PAGE_BG));
        }
        dialog.show();
    }

    // ---------------------------------------------------------- HTMLs tab

    private LinearLayout buildHtmlTab() {
        LinearLayout root = tabColumn();
        htmlHeader = heading("📄 Saved HTML", 18);
        root.addView(htmlHeader);

        htmlSearch = new EditText(this);
        htmlSearch.setHint("🔍 Naam se khojo...");
        htmlSearch.setSingleLine(true);
        styleField(htmlSearch);
        root.addView(htmlSearch, lp(10));
        htmlSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                refreshHtmlList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        htmlList = new LinearLayout(this);
        htmlList.setOrientation(LinearLayout.VERTICAL);
        root.addView(htmlList, lp(6));
        return root;
    }

    /** Downloads/SP WEB OS/<AppName>/ ke andar ki ek HTML file ka record. */
    private static class HtmlItem {
        String appName;   // sub-folder ka naam (khali = seedha SP WEB OS folder)
        String fileName;  // display name (.html ke saath)
        long size;
        long modifiedMs;
        Uri uri;          // API 29+ MediaStore
        File local;       // API 26-28 direct file
    }

    /** Downloads ke app-folders se saari .html files ikatthi karta hai.
     *  API 29+ par MediaStore, purane Android par folder walk. Har jagah
     *  try/catch — tab kabhi crash nahi hota. */
    private List<HtmlItem> queryDownloadHtmlItems() {
        List<HtmlItem> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Cursor c = null;
            try {
                Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] proj = {
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED,
                        MediaStore.Downloads.RELATIVE_PATH
                };
                String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] args = {Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS%"};
                c = getContentResolver().query(coll, proj, sel, args,
                        MediaStore.Downloads.DATE_MODIFIED + " DESC");
                if (c != null) {
                    int idI = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    int nameI = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                    int sizeI = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                    int dateI = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
                    int relI = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH);
                    while (c.moveToNext()) {
                        String nm = c.getString(nameI);
                        if (nm == null || !nm.toLowerCase(Locale.ROOT).endsWith(".html")) continue;
                        HtmlItem it = new HtmlItem();
                        it.appName = appFolderFromRelativePath(c.getString(relI));
                        it.fileName = nm;
                        it.size = c.getLong(sizeI);
                        it.modifiedMs = c.getLong(dateI) * 1000L;
                        it.uri = ContentUris.withAppendedId(coll, c.getLong(idI));
                        out.add(it);
                    }
                }
            } catch (Exception ignored) {
                // Folder na mile / permission na ho — khaali list hi sahi.
            } finally {
                if (c != null) c.close();
            }
        } else {
            try {
                File base = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "SP WEB OS");
                collectDownloadFiles(base, "", ".html", out, null);
            } catch (Exception ignored) { }
        }
        out.sort((a, b) -> Long.compare(b.modifiedMs, a.modifiedMs));
        return out;
    }

    /** "Download/SP WEB OS/<app>/" jaisi RELATIVE_PATH se app folder ka naam. */
    private static String appFolderFromRelativePath(String rel) {
        if (rel == null) return "";
        String prefix = Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS/";
        if (!rel.startsWith(prefix)) return "";
        String rest = rel.substring(prefix.length());
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    /** API 26-28 ke liye: SP WEB OS folder aur uske app sub-folders me
     *  di gayi extension ki files dhoondhta hai (ek level gehra). */
    private void collectDownloadFiles(File dir, String appName, String ext,
                                      List<HtmlItem> htmlOut, List<ApkItem> apkOut) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (appName.isEmpty()) collectDownloadFiles(f, f.getName(), ext, htmlOut, apkOut);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(ext)) {
                if (htmlOut != null) {
                    HtmlItem it = new HtmlItem();
                    it.appName = appName;
                    it.fileName = f.getName();
                    it.size = f.length();
                    it.modifiedMs = f.lastModified();
                    it.local = f;
                    htmlOut.add(it);
                } else if (apkOut != null) {
                    ApkItem it = new ApkItem();
                    it.label = f.getName();
                    it.size = f.length();
                    it.modifiedMs = f.lastModified();
                    it.local = f;
                    apkOut.add(it);
                }
            }
        }
    }

    private void refreshHtmlList() {
        if (htmlList == null) return;
        List<HtmlItem> all;
        try {
            all = queryDownloadHtmlItems();
        } catch (Exception e) {
            all = new ArrayList<>();
        }
        long total = 0;
        for (HtmlItem it : all) total += it.size;
        htmlHeader.setText("📄 Saved HTML — " + all.size() + " files • " + fmtSize(total));

        String q = htmlSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        htmlList.removeAllViews();

        List<HtmlItem> shown = new ArrayList<>();
        for (HtmlItem it : all) {
            if (q.isEmpty()
                    || it.fileName.toLowerCase(Locale.ROOT).contains(q)
                    || it.appName.toLowerCase(Locale.ROOT).contains(q)) shown.add(it);
        }
        if (shown.isEmpty()) {
            TextView empty = bodyText(all.isEmpty()
                    ? "Abhi Downloads me koi HTML save nahi hai.\nAPK banao — 'Auto Save HTMLs' ON ho to HTML Downloads/SP WEB OS/<AppName>/ me save hoga."
                    : "Is naam se koi HTML nahi mila. Kuch aur khoj kar dekho.");
            htmlList.addView(empty, lp(10));
        } else {
            for (HtmlItem it : shown) htmlList.addView(htmlDownloadRow(it), lp(8));
        }

        // Purani internal library (v5.0 tak app ke andar save hoti thi) —
        // sirf dikhayi jati hai taaki Use/Delete ho sake; nayi files wahan
        // NAHI jati. Search chal raha ho to ye group chhup jata hai.
        if (q.isEmpty()) {
            File[] legacy = libraryDir().listFiles((d, n) -> n.endsWith(".html"));
            if (legacy != null && legacy.length > 0) {
                Arrays.sort(legacy, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                htmlList.addView(heading("🗄️ पुरानी Library (app के अंदर)", 15), lp(18));
                htmlList.addView(bodyText(
                        "Ye files purane versions me app ke andar save hui thi. "
                        + "Use ya Delete kar sakte ho."), lp(4));
                for (File f : legacy) htmlList.addView(legacyRow(f), lp(8));
            }
        }
    }

    private LinearLayout htmlDownloadRow(final HtmlItem it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView name = new TextView(this);
        name.setText(it.fileName.replaceAll("(?i)\\.html$", ""));
        name.setTextSize(15);
        name.setTextColor(DARK_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        row.addView(name);

        TextView meta = new TextView(this);
        meta.setText((it.appName.isEmpty() ? "SP WEB OS" : "App: " + it.appName)
                + " • " + fmtSize(it.size) + " • " + fmtDate(it.modifiedMs));
        meta.setTextSize(12);
        meta.setTextColor(GREY_TEXT);
        row.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button use = new Button(this);
        use.setText("▶ खोलो / Use");
        styleSmall(use);
        use.setTextColor(PINK_TEXT);
        use.setOnClickListener(v -> useDownloadHtml(it));
        btns.addView(use);

        Button rename = new Button(this);
        rename.setText("✏️ Rename");
        styleSmall(rename);
        rename.setOnClickListener(v -> renameDownloadHtmlDialog(it));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(8);
        btns.addView(rename, rp);

        Button del = new Button(this);
        del.setText("🗑️ Delete");
        styleSmall(del);
        del.setTextColor(RED);
        del.setOnClickListener(v -> confirmDeleteDownloadHtml(it));
        btns.addView(del, rp);

        row.addView(btns, lp(8));
        return row;
    }

    /** Downloads wali HTML ko padhkar Home ke build flow me load karta hai. */
    private void useDownloadHtml(HtmlItem it) {
        try {
            byte[] bytes;
            if (it.uri != null) {
                try (InputStream in = getContentResolver().openInputStream(it.uri)) {
                    if (in == null) throw new Exception("file khul nahi payi");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                    bytes = baos.toByteArray();
                }
            } else {
                try (InputStream in = new FileInputStream(it.local)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                    bytes = baos.toByteArray();
                }
            }
            pickedHtml = bytes;
            pickedName = it.fileName;
            codeBox.setText("");
            updatePackagePreview();
            setStatus("Downloads se load ho gaya: " + it.fileName + " (" + bytes.length
                    + " bytes)\nAb naam dekar (ya bina naam) 'APK Banao' dabao.");
            showTab(TAB_HOME);
        } catch (Exception e) {
            Toast.makeText(this, "File padhne me dikkat: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renameDownloadHtmlDialog(final HtmlItem it) {
        String base = it.fileName.replaceAll("(?i)\\.html$", "");
        final EditText input = new EditText(this);
        input.setText(base);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        styleField(input);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("✏️ HTML ka naam badlo")
                .setView(wrap)
                .setPositiveButton("Save", (dialog, which) -> {
                    String typed = input.getText().toString().trim()
                            .replaceAll("(?i)\\.html$", "")
                            .replaceAll("[\\\\/:*?\"<>|]+", "_");
                    if (typed.isEmpty()) {
                        Toast.makeText(this, "Naam khaali nahi ho sakta", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (typed.length() > 60) typed = typed.substring(0, 60);
                    String newName = typed + ".html";
                    if (newName.equals(it.fileName)) return;
                    if (it.uri != null) {
                        // MediaStore: DISPLAY_NAME badalna hi rename hai.
                        try {
                            ContentValues cv = new ContentValues();
                            cv.put(MediaStore.Downloads.DISPLAY_NAME, newName);
                            int rows = getContentResolver().update(it.uri, cv, null, null);
                            Toast.makeText(this, rows > 0 ? "Naam badal gaya ✓"
                                    : "Rename nahi ho paya", Toast.LENGTH_LONG).show();
                        } catch (RecoverableSecurityException e) {
                            try {
                                startIntentSenderForResult(
                                        e.getUserAction().getActionIntent().getIntentSender(),
                                        REQ_APK_DELETE, null, 0, 0, 0);
                            } catch (Exception e2) {
                                Toast.makeText(this, "Rename ke liye Files app use karo",
                                        Toast.LENGTH_LONG).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "Rename nahi ho paya: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (it.local != null) {
                        File dest = new File(it.local.getParentFile(), newName);
                        if (dest.exists()) {
                            Toast.makeText(this, "Is naam ki file pehle se hai",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(this, it.local.renameTo(dest)
                                ? "Naam badal gaya ✓" : "Rename nahi ho paya",
                                Toast.LENGTH_LONG).show();
                    }
                    refreshHtmlList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteDownloadHtml(final HtmlItem it) {
        new AlertDialog.Builder(this)
                .setTitle("🗑️ Delete karo?")
                .setMessage(it.fileName + "\nYe HTML Downloads/SP WEB OS folder se hat jayega. "
                        + "Bana hua APK safe rahega.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (it.uri != null) {
                        try {
                            int rows = getContentResolver().delete(it.uri, null, null);
                            Toast.makeText(this, rows > 0 ? "Delete ho gaya 🗑️"
                                    : "Delete nahi hua — Files app se try karo",
                                    Toast.LENGTH_LONG).show();
                        } catch (RecoverableSecurityException e) {
                            try {
                                startIntentSenderForResult(
                                        e.getUserAction().getActionIntent().getIntentSender(),
                                        REQ_APK_DELETE, null, 0, 0, 0);
                            } catch (Exception e2) {
                                Toast.makeText(this, "Delete ke liye Files app use karo",
                                        Toast.LENGTH_LONG).show();
                            }
                        } catch (SecurityException e) {
                            Toast.makeText(this, "Delete nahi ho paya — Files app se delete karo",
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            Toast.makeText(this, "Delete nahi ho paya: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (it.local != null) {
                        //noinspection ResultOfMethodCallIgnored
                        boolean ok = it.local.delete();
                        Toast.makeText(this, ok ? "Delete ho gaya 🗑️" : "Delete nahi ho paya",
                                Toast.LENGTH_SHORT).show();
                    }
                    refreshHtmlList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Purani internal library ki ek row — sirf Use / Delete. */
    private LinearLayout legacyRow(final File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        String pretty = fileBaseName(f.getName());
        TextView name = new TextView(this);
        name.setText(pretty != null ? pretty : f.getName());
        name.setTextSize(15);
        name.setTextColor(DARK_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        row.addView(name);

        TextView meta = new TextView(this);
        meta.setText(fmtSize(f.length()) + " • " + fmtDate(f.lastModified()));
        meta.setTextSize(12);
        meta.setTextColor(GREY_TEXT);
        row.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button use = new Button(this);
        use.setText("▶ खोलो / Use");
        styleSmall(use);
        use.setTextColor(PINK_TEXT);
        use.setOnClickListener(v -> {
            loadFromLibrary(f);
            showTab(TAB_HOME);
        });
        btns.addView(use);

        Button del = new Button(this);
        del.setText("🗑️ Delete");
        styleSmall(del);
        del.setTextColor(RED);
        del.setOnClickListener(v -> confirmDeleteHtml(f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(8);
        btns.addView(del, rp);

        row.addView(btns, lp(8));
        return row;
    }


    private void confirmDeleteHtml(final File f) {
        String pretty = fileBaseName(f.getName());
        new AlertDialog.Builder(this)
                .setTitle("🗑️ Delete karo?")
                .setMessage((pretty != null ? pretty : f.getName())
                        + "\nYe HTML library se hat jayega. Bana hua APK safe rahega.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    //noinspection ResultOfMethodCallIgnored
                    boolean ok = f.delete();
                    Toast.makeText(this, ok ? "Delete ho gaya 🗑️" : "Delete nahi ho paya",
                            Toast.LENGTH_SHORT).show();
                    refreshHtmlList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------- APKs tab

    /** Ek bane hue APK ka record — MediaStore Uri wala ya local file wala
     *  (dono hi Downloads/SP WEB OS ke andar ke hain). */
    private static class ApkItem {
        String label;
        long size;
        long modifiedMs;
        Uri uri;      // MediaStore (Downloads) items ke liye
        File local;   // purane Android par Downloads ki direct file
    }

    private LinearLayout buildApkTab() {
        LinearLayout root = tabColumn();
        apkHeader = heading("📦 Bane hue APK", 18);
        root.addView(apkHeader);

        apkSearch = new EditText(this);
        apkSearch.setHint("🔍 App ke naam se khojo...");
        apkSearch.setSingleLine(true);
        styleField(apkSearch);
        root.addView(apkSearch, lp(10));
        apkSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                refreshApkList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        apkList = new LinearLayout(this);
        apkList.setOrientation(LinearLayout.VERTICAL);
        root.addView(apkList, lp(6));
        return root;
    }

    /** Downloads/SP WEB OS ke saare APK (app sub-folders samet) —
     *  MediaStore / purane Android par folder walk. v5.1 se app ke andar
     *  koi APK save nahi hota, isliye koi pinned "latest build" row nahi.
     *  Sab jagah try/catch, tab kabhi crash nahi. */
    private List<ApkItem> queryApkItems() {
        List<ApkItem> out = new ArrayList<>();
        List<ApkItem> downloads = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+ — Downloads/SP WEB OS ko MediaStore se padho.
            // RELATIVE_PATH par "%" wala prefix match sub-folders ko bhi
            // pakad leta hai (purani seedhi files bhi, naye app folders bhi).
            Cursor c = null;
            try {
                Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] proj = {
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_MODIFIED
                };
                String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] args = {Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS%"};
                c = getContentResolver().query(coll, proj, sel, args,
                        MediaStore.Downloads.DATE_MODIFIED + " DESC");
                if (c != null) {
                    int idI = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    int nameI = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                    int sizeI = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                    int dateI = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED);
                    while (c.moveToNext()) {
                        String nm = c.getString(nameI);
                        if (nm == null || !nm.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                        ApkItem it = new ApkItem();
                        it.label = nm;
                        it.size = c.getLong(sizeI);
                        it.modifiedMs = c.getLong(dateI) * 1000L; // MediaStore seconds deta hai
                        it.uri = ContentUris.withAppendedId(coll, c.getLong(idI));
                        downloads.add(it);
                    }
                }
            } catch (Exception ignored) {
                // Folder na mile / permission na ho — khaali list hi sahi.
            } finally {
                if (c != null) c.close();
            }
        } else {
            // Purane Android — public Downloads folder seedha padho
            // (SP WEB OS ke andar ke app sub-folders samet).
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "SP WEB OS");
                collectDownloadFiles(dir, "", ".apk", null, downloads);
            } catch (Exception ignored) { }
        }
        downloads.sort((a, b) -> Long.compare(b.modifiedMs, a.modifiedMs));
        out.addAll(downloads);
        return out;
    }

    private void refreshApkList() {
        if (apkList == null) return;
        List<ApkItem> all;
        try {
            all = queryApkItems();
        } catch (Exception e) {
            all = new ArrayList<>();
        }
        long total = 0;
        for (ApkItem it : all) total += it.size;
        apkHeader.setText("📦 Bane hue APK — " + all.size() + " apps • " + fmtSize(total));

        String q = apkSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        apkList.removeAllViews();

        List<ApkItem> shown = new ArrayList<>();
        for (ApkItem it : all) {
            if (q.isEmpty() || it.label.toLowerCase(Locale.ROOT).contains(q)) shown.add(it);
        }
        if (shown.isEmpty()) {
            TextView empty = bodyText(all.isEmpty()
                    ? "Abhi koi APK nahi bana.\nHome tab se pehla APK banao — bane hue APK yahan dikhenge, aur Downloads/SP WEB OS folder me bhi milenge."
                    : "Is naam ka koi APK nahi mila. Kuch aur khoj kar dekho.");
            apkList.addView(empty, lp(10));
            return;
        }
        for (ApkItem it : shown) apkList.addView(apkRow(it), lp(8));
    }

    private LinearLayout apkRow(final ApkItem it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView name = new TextView(this);
        name.setText("📦 " + it.label.replaceAll("(?i)\\.apk$", ""));
        name.setTextSize(15);
        name.setTextColor(DARK_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        row.addView(name);

        TextView meta = new TextView(this);
        meta.setText(fmtSize(it.size) + " • " + fmtDate(it.modifiedMs)
                + " • Downloads/SP WEB OS");
        meta.setTextSize(12);
        meta.setTextColor(GREY_TEXT);
        row.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button install = new Button(this);
        install.setText("📲 Install");
        styleSmall(install);
        install.setTextColor(PINK_TEXT);
        install.setOnClickListener(v -> openApk(it));
        btns.addView(install);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(8);

        if (it.uri != null) {
            Button share = new Button(this);
            share.setText("↗ Share");
            styleSmall(share);
            share.setOnClickListener(v -> shareApk(it));
            btns.addView(share, rp);
        }

        Button del = new Button(this);
        del.setText("🗑️ Delete");
        styleSmall(del);
        del.setTextColor(RED);
        del.setOnClickListener(v -> confirmDeleteApk(it));
        btns.addView(del, rp);

        row.addView(btns, lp(8));
        return row;
    }

    /** MediaStore APK: system installer intent. Local file (purana Android):
     *  maujooda PackageInstaller wala install() hi istemaal hota hai. */
    private void openApk(ApkItem it) {
        if (it.uri != null) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(it.uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Install khul nahi paya: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        } else if (it.local != null) {
            builtApk = it.local;
            if (builtLabel == null) {
                builtLabel = it.label.replaceAll("(?i)\\.apk$", "");
            }
            install();
        }
    }

    private void shareApk(ApkItem it) {
        if (it.uri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_STREAM, it.uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "APK share karo"));
        } catch (Exception e) {
            Toast.makeText(this, "Share nahi ho paya: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteApk(final ApkItem it) {
        new AlertDialog.Builder(this)
                .setTitle("🗑️ Delete karo?")
                .setMessage(it.label + "\nYe APK Downloads/SP WEB OS folder se "
                        + "hat jayega. Install hua app safe rahega.")
                .setPositiveButton("Delete", (dialog, which) -> deleteApk(it))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteApk(final ApkItem it) {
        if (it.uri != null) {
            // MediaStore (Android 10+): dusri app ki file delete karne par
            // system khud permission dialog dikha sakta hai.
            try {
                int rows = getContentResolver().delete(it.uri, null, null);
                Toast.makeText(this, rows > 0 ? "Delete ho gaya 🗑️"
                        : "Delete nahi hua — Files app se try karo", Toast.LENGTH_LONG).show();
                refreshApkList();
            } catch (RecoverableSecurityException e) {
                try {
                    startIntentSenderForResult(
                            e.getUserAction().getActionIntent().getIntentSender(),
                            REQ_APK_DELETE, null, 0, 0, 0);
                } catch (Exception e2) {
                    Toast.makeText(this, "Delete ke liye Files app use karo",
                            Toast.LENGTH_LONG).show();
                }
            } catch (SecurityException e) {
                Toast.makeText(this, "Delete nahi ho paya — Files app se delete karo",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Delete nahi ho paya: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        } else if (it.local != null) {
            //noinspection ResultOfMethodCallIgnored
            boolean ok = it.local.delete();
            if (ok && builtApk != null && it.local.equals(builtApk)) {
                builtApk = null;
                installBtn.setEnabled(false);
            }
            Toast.makeText(this, ok ? "Delete ho gaya 🗑️" : "Delete nahi ho paya",
                    Toast.LENGTH_SHORT).show();
            refreshApkList();
        }
    }

    /** App name -> unique package id. Latin letters/digits survive;
     *  anything else falls back to a number so every name works. */
    static String packageForName(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        if (cleaned.length() > 30) cleaned = cleaned.substring(0, 30);
        if (cleaned.isEmpty()) {
            cleaned = "app" + (System.currentTimeMillis() / 1000 % 100000);
        } else if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "app" + cleaned;
        }
        return "com.sp.webos.app." + cleaned;
    }

    private String typedName() {
        return nameBox.getText().toString().trim();
    }

    // ------------------------------------------------- type + features (v5.3)

    /** Type chunna: choice yaad rakhna + UI update (gating samet). */
    private void selectType(String type) {
        appType = type;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_APP_TYPE, type).apply();
        updateTypeUi();
    }

    /** Type ke hisaab se UI: type buttons ki highlight, URL field
     *  sirf URL type par, aur jo feature is type par nahi chalta woh
     *  apne aap OFF + disable (halka) ho jata hai. */
    private void updateTypeUi() {
        boolean url = "url".equals(appType);
        if (typeBtns != null) {
            for (int i = 0; i < typeBtns.length; i++) {
                boolean sel = (i == 1) == url;
                typeBtns[i].setBackground(
                        roundedBg(sel ? PINK : CARD_BG, 10, sel ? 0 : BORDER));
                typeBtns[i].setTextColor(sel ? 0xFFFFFFFF : DARK_TEXT);
                typeBtns[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        if (urlBox != null) {
            urlBox.setVisibility(url ? View.VISIBLE : View.GONE);
        }
        refreshLibUi();
    }

    // ---------------------------------------------------- v6.0: libraries

    /** Library chips ki haalat: chuni hui = pink bhari; URL type par
     *  poori row dhundli + band (libraries sirf HTML apps ke liye —
     *  brief ki dimmed-availability policy builder me bhi waisi hi). */
    private void refreshLibUi() {
        boolean url = "url".equals(appType);
        if (libBtns != null) {
            String[] sizes = {"~654 KB", "~201 KB", "~546 KB"};
            String[] names = {"🎮 Three.js", "📊 Chart.js", "∑ KaTeX"};
            for (int i = 0; i < libBtns.length; i++) {
                boolean sel = libSel[i];
                libBtns[i].setText(names[i] + (sel ? " ✓" : "") + "\n" + sizes[i]);
                libBtns[i].setBackground(roundedBg(sel ? PINK : CARD_BG, 10, sel ? 0 : BORDER));
                libBtns[i].setTextColor(sel ? 0xFFFFFFFF : DARK_TEXT);
                libBtns[i].setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                libBtns[i].setEnabled(!url);
            }
        }
        if (libRow != null) libRow.setAlpha(url ? 0.4f : 1f);
        if (libNote != null) {
            libNote.setText(url
                    ? "Libraries सिर्फ़ HTML apps के लिए — URL app me ye band (dim) rehti hain."
                    : "Chuni hui libraries APK ke andar jud jayengi — size utna hi badhega.");
        }
    }

    // --------------------------------------- v6.0: script selection (prefs)

    private Set<String> loadSelectedScriptIds() {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray arr = new JSONArray(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_SEL_SCRIPTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i);
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void saveSelectedScriptIds(Set<String> ids) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_SEL_SCRIPTS,
                            new JSONArray(new ArrayList<>(ids)).toString())
                    .apply();
        } catch (Exception ignored) { }
    }

    private void refreshScriptsBtn() {
        if (scriptsBtn != null) {
            scriptsBtn.setText("📜 Scripts चुनो (" + loadSelectedScriptIds().size() + " चुने)");
        }
    }

    // -------------------------------------- v6.0: preset scripts (assets)

    private JSONArray presetsCacheB;

    /** Builder ke apne assets/scripts.json (template wali hi file —
     *  byte-identical) se preset list. */
    private JSONArray loadPresets() {
        if (presetsCacheB != null) return presetsCacheB;
        presetsCacheB = new JSONArray();
        try {
            byte[] b = readAssetBytes("scripts.json");
            if (b != null) {
                JSONArray arr = new JSONObject(new String(b, StandardCharsets.UTF_8))
                        .optJSONArray("scripts");
                if (arr != null) presetsCacheB = arr;
            }
        } catch (Exception ignored) { }
        return presetsCacheB;
    }

    private JSONObject findPreset(String id) {
        JSONArray arr = loadPresets();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    // --------------------------------- v6.0: user script store (in-app)

    private File scriptsDir() {
        File d = new File(getFilesDir(), "scripts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** File-naam ke liye surakshit roop — HTMLs tab wale sanitize
     *  pattern par (akshar/ank/_/- hi bachte hain). */
    private static String scriptFileBase(String title) {
        String base = title.trim().replaceAll("[^A-Za-z0-9 _\\-]", "").trim()
                .replaceAll("\\s+", "_");
        return base.isEmpty() ? "Script" : base;
    }

    /** User scripts ki list: filesDir/scripts/*.json (primary) +
     *  Downloads/SP WEB OS/Scripts/*.js (mirror) — title se dedupe,
     *  in-app wali copy jeetti hai. Har item: {id,title,js,css}. */
    private List<JSONObject> loadUserScripts() {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File[] files = scriptsDir().listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                try {
                    String text = new String(readFileBytes(f), StandardCharsets.UTF_8);
                    JSONObject o = new JSONObject(text);
                    String title = o.optString("title").trim();
                    if (title.isEmpty()) continue;
                    JSONObject u = new JSONObject();
                    u.put("id", "user:" + scriptFileBase(title));
                    u.put("title", title);
                    u.put("js", o.optString("js"));
                    u.put("css", o.optString("css"));
                    out.add(u);
                    seen.add(title.toLowerCase(Locale.ROOT));
                } catch (Exception ignored) { }
            }
        }
        for (File f : listDownloadsScriptFiles()) {
            String name = f.getName();
            String title = name.substring(0, name.length() - 3).replace('_', ' ');
            if (seen.contains(title.toLowerCase(Locale.ROOT))) continue;
            try {
                JSONObject u = new JSONObject();
                u.put("id", "user:" + scriptFileBase(title));
                u.put("title", title);
                u.put("js", new String(readFileBytes(f), StandardCharsets.UTF_8));
                File cssF = new File(f.getParentFile(),
                        name.substring(0, name.length() - 3) + ".css");
                u.put("css", cssF.exists()
                        ? new String(readFileBytes(cssF), StandardCharsets.UTF_8) : "");
                out.add(u);
                seen.add(title.toLowerCase(Locale.ROOT));
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Downloads/SP WEB OS/Scripts/ ke .js files (API 29+ par
     *  MediaStore query, neeche direct file) — HTMLs tab wale pattern
     *  par. Downloads wali asal reading MediaStore URI se hoti hai;
     *  ye helper seedha path try karta hai (file wahi milti hai jahan
     *  humne likhi thi — public Downloads har app ko path se dikhta
     *  hai jab storage permission/access ho; na mile to list khaali). */
    private List<File> listDownloadsScriptFiles() {
        List<File> out = new ArrayList<>();
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SP WEB OS/Scripts");
            File[] files = dir.listFiles((d, n) -> n.endsWith(".js"));
            if (files != null) {
                Arrays.sort(files);
                out.addAll(Arrays.asList(files));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private byte[] readFileBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private JSONObject findUserScriptB(String id) {
        for (JSONObject u : loadUserScripts()) {
            if (id.equals(u.optString("id"))) return u;
        }
        return null;
    }

    /** User script save: in-app copy HAMESHA; "Auto Save Scripts" ON
     *  ho to Downloads/SP WEB OS/Scripts/<Title>.js (+ .css) mirror bhi. */
    private void saveUserScript(String title, String js, String css) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("js", js == null ? "" : js);
        o.put("css", css == null ? "" : css);
        o.put("updatedAt", System.currentTimeMillis());
        File f = new File(scriptsDir(), scriptFileBase(title) + ".json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        boolean mirror = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_AUTO_SAVE_SCRIPTS, true);
        if (mirror) {
            writeToDownloads("Scripts", scriptFileBase(title) + ".js",
                    "text/javascript",
                    (js == null ? "" : js).getBytes(StandardCharsets.UTF_8), null);
            if (css != null && !css.trim().isEmpty()) {
                writeToDownloads("Scripts", scriptFileBase(title) + ".css",
                        "text/css", css.getBytes(StandardCharsets.UTF_8), null);
            }
        }
    }

    /** User script delete: in-app copy pakki delete; Downloads copy
     *  best-effort (MediaStore consent/system delete pattern humare
     *  paas APK delete jaisa maujood nahi, isliye sirf direct-path try). */
    private void deleteUserScript(JSONObject u) {
        String base = scriptFileBase(u.optString("title"));
        try { new File(scriptsDir(), base + ".json").delete(); } catch (Exception ignored) { }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SP WEB OS/Scripts");
            new File(dir, base + ".js").delete();
            new File(dir, base + ".css").delete();
        } catch (Exception ignored) { }
        Set<String> sel = loadSelectedScriptIds();
        if (sel.remove(u.optString("id"))) saveSelectedScriptIds(sel);
        refreshScriptsBtn();
    }

    // --------------------------- v6.0: full-screen overlay pages (generic)

    /** contentFrame par ek full-screen scrollable page kholta hai
     *  (v5.3.1 Icon Maker pattern, aam banaya hua). Header me title +
     *  ⬅ वापस; wapas dabane par onClose (agar diya ho) chalta hai. */
    private LinearLayout openPage(String title, final Runnable onClose) {
        closeOverlayPage();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(CARD_BG);
        header.setPadding(dp(8), dp(8), dp(14), dp(8));
        Button back = new Button(this);
        back.setText("⬅ वापस");
        styleSmall(back);
        back.setOnClickListener(v -> closeOverlayPage());
        header.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16.5f);
        t.setTextColor(DARK_TEXT);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(12), 0, 0, 0);
        header.addView(t);
        page.addView(header);
        View div = new View(this);
        div.setBackgroundColor(BORDER);
        page.addView(div, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = tabColumn();
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        contentFrame.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlayPage = page;
        page.setTag(onClose);
        return col;
    }

    private void closeOverlayPage() {
        if (overlayPage == null) return;
        View page = overlayPage;
        overlayPage = null;
        Object tag = page.getTag();
        contentFrame.removeView(page);
        if (tag instanceof Runnable) {
            try { ((Runnable) tag).run(); } catch (Exception ignored) { }
        }
    }

    // -------------------------------------- v6.0: scripts picker page

    /** "📜 Scripts चुनो" page: presets + user scripts ke checkboxes
     *  (multi-select, prefs me turant save), neeche manual JS/CSS
     *  boxes + "script banakar save" button. */
    private void openScriptsPicker() {
        // Manual boxes ke purane text ko pehle prefs se bharo (page
        // band hone par wapas save hota hai — closeOverlayPage ka
        // onClose hook).
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String oldJs = prefs.getString(PREF_MANUAL_JS, "");
        final String oldCss = prefs.getString(PREF_MANUAL_CSS, "");
        LinearLayout col = openPage("📜 Scripts चुनो", () -> {
            if (pageJsBox != null && pageCssBox != null) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_MANUAL_JS, pageJsBox.getText().toString())
                        .putString(PREF_MANUAL_CSS, pageCssBox.getText().toString())
                        .apply();
            }
            pageJsBox = null;
            pageCssBox = null;
            refreshScriptsBtn();
        });
        col.addView(bodyText("Jo scripts yahan tick karoge, woh naye app me "
                + "PEHLE DIN SE ON rahenge. Baad me app ke andar • panel se "
                + "kabhi bhi on/off ho jayenge — dobara APK banane ki "
                + "zaroorat nahi. Power scripts (📺 BG Play, 🖼️ PiP) asal "
                + "me native switch hain — woh bhi yahi se default-ON ho "
                + "sakti hain."));
        final Set<String> sel = loadSelectedScriptIds();

        col.addView(heading("⭐ Preset scripts", 15), lp(12));
        JSONArray presets = loadPresets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject p = presets.optJSONObject(i);
            if (p == null) continue;
            col.addView(scriptCheckRow(sel, p.optString("id"),
                    p.optString("title"), p.optString("desc")), lp(4));
        }

        List<JSONObject> users = loadUserScripts();
        if (!users.isEmpty()) {
            col.addView(heading("💾 Mere save kiye scripts", 15), lp(14));
            for (JSONObject u : users) {
                col.addView(scriptCheckRow(sel, u.optString("id"),
                        u.optString("title"), "Mera banaya hua script"), lp(4));
            }
        }

        col.addView(heading("✍️ अपना code (optional)", 15), lp(16));
        col.addView(bodyText("Ye code hamesha ON rehta hai (assets/inject.js "
                + "bankar) — panel scripts se alag. Pehle jaisa hi v5.3 "
                + "wala Custom JS/CSS."));
        pageJsBox = new EditText(this);
        pageJsBox.setHint("JS code (optional) — har page load ke baad chalega");
        pageJsBox.setMinLines(4);
        pageJsBox.setGravity(Gravity.TOP);
        pageJsBox.setTextSize(12);
        pageJsBox.setText(oldJs);
        styleField(pageJsBox);
        col.addView(pageJsBox, lp(6));
        pageCssBox = new EditText(this);
        pageCssBox.setHint("CSS code (optional) — page ki styling badlega");
        pageCssBox.setMinLines(4);
        pageCssBox.setGravity(Gravity.TOP);
        pageCssBox.setTextSize(12);
        pageCssBox.setText(oldCss);
        styleField(pageCssBox);
        col.addView(pageCssBox, lp(6));

        Button saveAs = new Button(this);
        saveAs.setText("💾 ये code script बनाकर save करो");
        styleOutline(saveAs);
        saveAs.setOnClickListener(v -> {
            final String js = pageJsBox.getText().toString();
            final String css = pageCssBox.getText().toString();
            if (js.trim().isEmpty() && css.trim().isEmpty()) {
                Toast.makeText(this, "Pehle JS ya CSS box me kuch code likho",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final EditText titleIn = new EditText(this);
            titleIn.setHint("Script ka naam — jaise 'Mera YouTube Fix'");
            titleIn.setSingleLine(true);
            new AlertDialog.Builder(this)
                    .setTitle("💾 Script save करो")
                    .setView(titleIn)
                    .setPositiveButton("Save", (d, w) -> {
                        String title = titleIn.getText().toString().trim();
                        if (title.isEmpty()) {
                            Toast.makeText(this, "Naam khaali hai — script save nahi hua",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            saveUserScript(title, js, css);
                            Toast.makeText(this, "💾 Script save ho gaya: " + title,
                                    Toast.LENGTH_SHORT).show();
                            openScriptsPicker(); // list refresh
                        } catch (Exception e) {
                            Toast.makeText(this, "❌ Save nahi hua: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        col.addView(saveAs, lp(8));
    }

    /** Picker page ki ek checkbox row (toggle -> prefs me turant save). */
    private View scriptCheckRow(final Set<String> sel, final String id,
                                String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(10), dp(6), dp(6), dp(6));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(DARK_TEXT);
        t.setTypeface(null, Typeface.BOLD);
        texts.addView(t);
        TextView d = new TextView(this);
        d.setText(desc == null ? "" : desc);
        d.setTextSize(11.5f);
        d.setTextColor(GREY_TEXT);
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        CheckBox cb = new CheckBox(this);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(PINK));
        cb.setChecked(sel.contains(id));
        cb.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) sel.add(id); else sel.remove(id);
            saveSelectedScriptIds(sel);
        });
        row.addView(cb);
        return row;
    }

    // ------------------------------------------- v6.0: Scripts tab

    /** 5th tab "📜 Scripts" ka khaali khaana — asal list
     *  refreshScriptsTab() bharta hai (tab khulte hi taza). */
    private LinearLayout buildScriptsTab() {
        LinearLayout root = tabColumn();
        root.addView(heading("📜 Scripts Library", 20));
        root.addView(bodyText("⭐ Preset scripts (12) har generated app ke "
                + "andar pehle se hote hain. 💾 User scripts yahan se manage "
                + "karo — naye app me default-ON karne ke liye Home ke "
                + "'📜 Scripts चुनो' page se tick karo."));
        scriptsTabList = new LinearLayout(this);
        scriptsTabList.setOrientation(LinearLayout.VERTICAL);
        root.addView(scriptsTabList, lp(8));
        return root;
    }

    private void refreshScriptsTab() {
        if (scriptsTabList == null) return;
        scriptsTabList.removeAllViews();
        scriptsTabList.addView(heading("⭐ Preset scripts (view only)", 15), lp(4));
        JSONArray presets = loadPresets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject p = presets.optJSONObject(i);
            if (p == null) continue;
            scriptsTabList.addView(scriptInfoRow(
                    p.optString("title"),
                    p.optString("desc") + "  •  " + worksOnLabel(p.optString("worksOn"))),
                    lp(4));
        }
        scriptsTabList.addView(heading("💾 Mere scripts", 15), lp(14));
        List<JSONObject> users = loadUserScripts();
        if (users.isEmpty()) {
            scriptsTabList.addView(bodyText("Abhi koi user script nahi hai. "
                    + "Home → '📜 Scripts चुनो' → apna code likhkar "
                    + "'💾 ये code script बनाकर save करो' dabao."), lp(4));
        }
        for (final JSONObject u : users) {
            scriptsTabList.addView(userScriptRow(u), lp(4));
        }
    }

    private static String worksOnLabel(String w) {
        if ("youtube".equals(w)) return "YouTube par";
        if ("sites".equals(w)) return "websites par";
        return "sab jagah";
    }

    private View scriptInfoRow(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(DARK_TEXT);
        t.setTypeface(null, Typeface.BOLD);
        row.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11.5f);
        d.setTextColor(GREY_TEXT);
        row.addView(d);
        return row;
    }

    private View userScriptRow(final JSONObject u) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        TextView t = new TextView(this);
        t.setText(u.optString("title"));
        t.setTextSize(13.5f);
        t.setTextColor(DARK_TEXT);
        t.setTypeface(null, Typeface.BOLD);
        row.addView(t);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button use = new Button(this);
        use.setText("✅ Use करो");
        styleSmall(use);
        use.setOnClickListener(v -> {
            Set<String> sel = loadSelectedScriptIds();
            sel.add(u.optString("id"));
            saveSelectedScriptIds(sel);
            refreshScriptsBtn();
            Toast.makeText(this, "✅ '" + u.optString("title")
                    + "' agle build ke liye chun liya — Home par jao", Toast.LENGTH_LONG).show();
            showTab(TAB_HOME);
        });
        btns.addView(use);
        Button del = new Button(this);
        del.setText("🗑 Delete");
        styleSmall(del);
        del.setTextColor(RED);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dp2.leftMargin = dp(8);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Script delete करूं?")
                .setMessage("'" + u.optString("title") + "' hamesha ke liye "
                        + "delete ho jayega (in-app copy; Downloads copy "
                        + "best-effort).")
                .setPositiveButton("Delete", (d, w) -> {
                    deleteUserScript(u);
                    refreshScriptsTab();
                })
                .setNegativeButton("Cancel", null)
                .show());
        btns.addView(del, dp2);
        row.addView(btns, lp(6));
        return row;
    }

    // --------------------------------- v6.0: All Features List page

    /** Settings ka "📋 All Features List" — poori sachchai ek page
     *  par: builder ki shaaqtiyaan, build-time chunav (asli sizes ke
     *  saath), generated app ke panel ki poori soochi + availability
     *  matrix, preset scripts, native bridge, aur imaandaar seemayein. */
    private void openFeaturesList() {
        LinearLayout col = openPage("📋 All Features List", null);

        col.addView(heading("1) 🛠️ Builder की शक्तियाँ", 16), lp(4));
        col.addView(bodyText(
                "• Single-file HTML → APK, phone par hi (koi PC/server nahi)\n"
                + "• 📄 HTMLs / 📦 APKs / 📜 Scripts tabs — saari files "
                + "Downloads/SP WEB OS/ me, app ke andar kuch save nahi "
                + "(scripts ki in-app copy iska ek apvaad hai — woh "
                + "library hai, output nahi)\n"
                + "• 🎨 Icon Maker: apna design kiya icon (13 patterns) + "
                + "Saved Icons picker\n"
                + "• 🌙 Dark Mode, ❓ Help Center, Auto Save toggles "
                + "(HTMLs / APKs / Icons / Scripts)"));

        col.addView(heading("2) 🎛️ बनते समय चुनने वाली चीज़ें", 16), lp(16));
        col.addView(bodyText(
                "• App Type: 📄 HTML (apni file) ya 🌐 URL (koi website)\n"
                + "• 📚 Libraries — SIRF HTML apps ke liye, aur sirf yahi "
                + "cheez APK ka size sach me badhati hai:\n"
                + "    🎮 Three.js (three.min.js) — ~654 KB — HTML me: "
                + "<script src=\"three.min.js\"></script>\n"
                + "    📊 Chart.js (chart.min.js) — ~201 KB — HTML me: "
                + "<script src=\"chart.min.js\"></script>\n"
                + "    ∑ KaTeX (lib/katex/) — ~546 KB — HTML me: "
                + "<link rel=\"stylesheet\" href=\"lib/katex/katex.min.css\"> "
                + "+ <script src=\"lib/katex/katex.min.js\"></script>\n"
                + "• 📜 Scripts default-ON chunav — chune hue scripts naye "
                + "app me pehle din se ON; baad me app ke panel se badal "
                + "sakte ho\n"
                + "• ✍️ अपना code (manual JS/CSS) — assets/inject.js bankar "
                + "hamesha ON rehta hai"));

        col.addView(heading("3) 📱 हर generated app के अंदर — secret • panel", 16), lp(16));
        col.addView(bodyText(
                "App ke top-right kone me chhupa chhota sa '•' dot dabane "
                + "par Control Panel khulta hai. Sab kuch LIVE badalta "
                + "hai aur app ki apni memory me save rehta hai:\n"
                + "🌙 Night Mode (page ulta-rang/dark) — URL ✓ HTML ✓\n"
                + "⛶ Full Screen — URL ✓ HTML ✓\n"
                + "📺 Background Play — URL ✓ HTML ✓\n"
                + "🖼️ PiP Mode — URL ✓ HTML ✓\n"
                + "🚫 Ad Block — URL ✓ HTML ✗ (dim)\n"
                + "👆 Swipe Gestures — URL ✓ HTML ✗ (dim)\n"
                + "🖥️ Desktop Mode — URL ✓ HTML ✗ (dim)\n"
                + "🤫 Browse History — URL ✓ HTML ✗ (dim)\n"
                + "📜 Scripts (12 presets + apne) — URL ✓ HTML ✓\n"
                + "💾 Script जोड़ो (app ke andar naya script) — URL ✓ HTML ✓\n"
                + "Jo cheez kisi type par lagu nahi hoti woh panel me "
                + "DHUNDLI (dim) dikhti hai — chhupati kabhi nahi."));

        col.addView(heading("4) 📜 Preset scripts (12)", 16), lp(16));
        JSONArray presets = loadPresets();
        for (int i = 0; i < presets.length(); i++) {
            JSONObject p = presets.optJSONObject(i);
            if (p == null) continue;
            col.addView(bodyText("• " + p.optString("title") + " — "
                    + p.optString("desc") + " (" + worksOnLabel(p.optString("worksOn")) + ")"), lp(2));
        }

        col.addView(heading("5) 🔌 Generated app की native शक्तियाँ (SpWebOS bridge)", 16), lp(16));
        col.addView(bodyText(
                "• SpWebOS.notify(title, body) — turant notification\n"
                + "• SpWebOS.scheduleNotify(seconds, title, body) — app "
                + "band ho tab bhi time par reminder\n"
                + "• SpWebOS.keepAlive(true/false) — app ko background me "
                + "zinda rakhne wala service\n"
                + "• SpWebOS.toast(text) — chhota sa message"));

        col.addView(heading("6) ⚠️ सीमाएँ — ईमानदारी से", 16), lp(16));
        col.addView(bodyText(
                "• 📺 Background Play best-effort hai: WebView pause nahi "
                + "hota + keep-alive service chalta hai, lekin OS/OEM "
                + "battery manager bhaari dabav me app ko band kar sakta "
                + "hai — ye Android ki seema hai.\n"
                + "• 🖼️ PiP tabhi khulta hai jab video HTML5 fullscreen "
                + "me chal raha ho (Home dabane par); har site PiP allow "
                + "nahi karti.\n"
                + "• ⬇️ Video Download script sirf DIRECT file wale "
                + "video par kaam karta hai. YouTube jaise streaming "
                + "(blob/DASH) video download nahi ho sakte — script "
                + "wahan saaf bata deta hai.\n"
                + "• 🧩 Asli browser extensions (Chrome wale) WebView me "
                + "NAHI chal sakte. Uska badla: Custom JS/CSS + ye preset "
                + "scripts.\n"
                + "• 📚 Libraries ke alawa scripts/features bahut chhote "
                + "hain — saare mila kar bhi app ~5 MB ke andar rehta hai."));
    }

    /** Bundled asset maujood hai ya nahi (template APKs ke liye). */
    private boolean assetExists(String name) {
        try (InputStream in = getAssets().open(name)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------- app name sources

    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** HTML ke <title> tag se app ka naam. Na mile to null. */
    static String titleFromHtml(byte[] html) {
        if (html == null) return null;
        String head = new String(html, 0, Math.min(html.length, 65536), StandardCharsets.UTF_8);
        Matcher m = TITLE_PATTERN.matcher(head);
        if (!m.find()) return null;
        String t = m.group(1)
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.length() > 50) t = t.substring(0, 50).trim();
        return t.isEmpty() ? null : t;
    }

    /** File ke path/naam se saaf naam: folder, extension aur library ka
     *  timestamp suffix hata kar. Na bane to null. */
    static String fileBaseName(String name) {
        if (name == null) return null;
        String s = name;
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        if (cut >= 0) s = s.substring(cut + 1);
        s = s.replaceAll("(?i)\\.(html?|txt)$", "");
        s = s.replaceAll("_\\d{8}-\\d{6}$", ""); // library stamp
        s = s.replaceAll("[_]+", " ").trim();
        if (s.length() > 50) s = s.substring(0, 50).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Is build ka final app naam aur uska source.
     * Priority: 1) user input  2) HTML <title>  3) file ka naam.
     * Kuch na mile to null (purana default "Web App").
     * @return {label, sourceText} ya null
     */
    private String[] effectiveName() {
        String typed = typedName();
        if (!typed.isEmpty()) return new String[]{typed, "aapke diye naam se"};
        String fromTitle = titleFromHtml(currentHtml());
        if (fromTitle != null) return new String[]{fromTitle, "HTML title se"};
        String fromFile = fileBaseName(pickedName);
        if (fromFile != null) return new String[]{fromFile, "file ke naam se"};
        return null;
    }

    private void updatePackagePreview() {
        String[] eff = effectiveName();
        if (eff == null) {
            packagePreview.setText("Naam kisi source se nahi mila = default 'Web App' (purana wala replace hoga)");
        } else {
            packagePreview.setText("App ka naam (" + eff[1] + "): " + eff[0]
                    + "\nPackage ID: " + packageForName(eff[0])
                    + "\nYe app alag se install hoga — baaki apps safe rahenge.");
        }
    }

    // ---------------------------------------------------------- HTML library

    private File libraryDir() {
        File dir = new File(getFilesDir(), "html_library");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }



    private void loadFromLibrary(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            pickedHtml = baos.toByteArray();
            pickedName = f.getName();
            codeBox.setText("");
            updatePackagePreview();
            setStatus("Library se load ho gaya: " + pickedName + " (" + pickedHtml.length
                    + " bytes)\nAb naam dekar (ya bina naam) 'APK Banao' dabao.");
        } catch (Exception e) {
            setStatus("Library file padhne me dikkat: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------- Downloads output (v5.1)

    /** Downloads ke sub-folder ke liye app naam ki safai — bilkul
     *  packageForName wali (lowercase, sirf a-z0-9, max 30). Isse ek hi
     *  app ki files hamesha ek hi folder me jama hoti hain. */
    static String folderNameFor(String label) {
        String cleaned = (label == null ? "" : label).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
        if (cleaned.length() > 30) cleaned = cleaned.substring(0, 30);
        if (cleaned.isEmpty()) cleaned = "app";
        return cleaned;
    }

    /**
     * Build ke baad ki files Downloads/SP WEB OS/<AppName>/ me likhta hai —
     * Auto Save toggles ke hisaab se: <AppName>.html, <AppName>.apk aur
     * icon.png (jo icon APK ke andar asal me laga hai, wahi nikalkar).
     * App ke andar kuch save NAHI hota. Status ke liye chhota sa summary
     * text lautata hai. Background thread se call hota hai.
     */
    private String saveOutputsToDownloads(byte[] html, File apk, String displayLabel) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean saveHtml = prefs.getBoolean(PREF_AUTO_SAVE_HTML, true);
        boolean saveApk = prefs.getBoolean(PREF_AUTO_SAVE_APK, true);
        boolean saveIcon = prefs.getBoolean(PREF_AUTO_SAVE_ICON, true);
        if (!saveHtml && !saveApk && !saveIcon) {
            return "\n📥 Auto Save: teeno toggles OFF hain — Downloads me kuch save nahi hua.";
        }
        String folder = folderNameFor(displayLabel);
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (saveHtml && html != null) { // URL apps ke paas HTML hota hi nahi
            if (writeToDownloads(folder, folder + ".html", "text/html", html, null)) {
                done.add(folder + ".html");
            } else {
                failed.add(folder + ".html");
            }
        }
        if (saveApk) {
            if (writeToDownloads(folder, folder + ".apk",
                    "application/vnd.android.package-archive", null, apk)) {
                done.add(folder + ".apk");
            } else {
                failed.add(folder + ".apk");
            }
        }
        if (saveIcon) {
            byte[] icon = ApkMaker.extractIconPng(apk);
            if (icon != null) {
                if (writeToDownloads(folder, "icon.png", "image/png", icon, null)) {
                    done.add("icon.png");
                } else {
                    failed.add("icon.png");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!done.isEmpty()) {
            sb.append("\n📥 Save ho gaya — Downloads/SP WEB OS/").append(folder)
                    .append("/ : ").append(joinStrings(done));
        }
        if (!failed.isEmpty()) {
            sb.append("\n⚠️ Save nahi ho paya: ").append(joinStrings(failed))
                    .append(" (storage permission check karo)");
        }
        return sb.toString();
    }

    private static String joinStrings(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(xs.get(i));
        }
        return b.toString();
    }

    /** Ek file Downloads/SP WEB OS/<folder>/ me likhta hai. API 29+ par
     *  MediaStore (purani same-naam wali entry pehle hatakar, taaki
     *  duplicate "(1)" wali files na banein), API 26-28 par seedhi file. */
    private boolean writeToDownloads(String folder, String fileName, String mime,
                                     byte[] data, File srcFile) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String relPath = Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS/" + folder;
                deleteExistingDownload(relPath + "/", fileName);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        try { getContentResolver().delete(uri, null, null); }
                        catch (Exception ignored) { }
                        return false;
                    }
                    writeBytesOrFile(out, data, srcFile);
                } catch (Exception e) {
                    try { getContentResolver().delete(uri, null, null); }
                    catch (Exception ignored) { }
                    return false;
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return true;
            }
            // API 26-28: seedhi file (WRITE_EXTERNAL_STORAGE chahiye)
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_PERM));
                return false;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SP WEB OS/" + folder);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, fileName);
            try (OutputStream out = new FileOutputStream(dest)) {
                writeBytesOrFile(out, data, srcFile);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same folder + same naam wali purani MediaStore entry hatata hai,
     *  taaki nayi file usi naam se bane (duplicate "(1)" na bane). */
    private void deleteExistingDownload(String relPathWithSlash, String fileName) {
        Cursor c = null;
        try {
            Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String sel = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                    + MediaStore.Downloads.DISPLAY_NAME + "=?";
            c = getContentResolver().query(coll,
                    new String[]{MediaStore.Downloads._ID}, sel,
                    new String[]{relPathWithSlash, fileName}, null);
            if (c != null) {
                int idI = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                while (c.moveToNext()) {
                    try {
                        getContentResolver().delete(
                                ContentUris.withAppendedId(coll, c.getLong(idI)), null, null);
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static void writeBytesOrFile(OutputStream out, byte[] data, File srcFile)
            throws Exception {
        if (data != null) {
            out.write(data);
            return;
        }
        try (InputStream in = new FileInputStream(srcFile)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------- custom app icon

    /** Working icon sirf cache me rehta hai (v5.1: app ke andar kuch
     *  permanent save nahi hota). Build ke baad asal icon APK se nikaal
     *  kar Downloads me icon.png ki tarah save ho jata hai. */
    private File customIconFile() {
        return new File(getCacheDir(), "custom_icon.png");
    }

    private void refreshIconPreview() {
        if (iconPreview == null || iconStatus == null) return;
        File f = customIconFile();
        Bitmap bmp = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (bmp != null) {
            iconPreview.setImageBitmap(bmp);
            iconStatus.setText("Custom icon \u2713 \u2014 agle APK me yahi lagega.\nBadalna ho to phir se 'App Icon Banao' dabao.");
        } else {
            iconPreview.setImageBitmap(null);
            iconStatus.setText("Abhi default icon lagega (gulabi W).\nApna design banana ho to '\uD83C\uDFA8 App Icon Banao' dabao.");
        }
    }

    /** Icon Maker (assets/icon_maker.html) — v5.3.1 HOTFIX: ab yeh koi
     *  dialog NAHI hai; activity ke andar ek FULL-SCREEN PAGE hai.
     *
     *  Kyun: v5.2 me designer WebView dialog ke andar height 0 + weight 1
     *  par sikud gaya tha (sirf neeche wali button row dikhti thi).
     *  v5.3 me dialog window ko show() ke baad ~85% height di gayi, lekin
     *  user ke Infinix (Android 12 Go) par woh ilaaj bhi kaam nahi kiya —
     *  device par wahi symptom wapas aaya. Isliye ab dialog-measurement
     *  ki koi aur variation NAHI: page ko activity ke content frame me
     *  MATCH_PARENT x MATCH_PARENT joda jata hai. Activity-level layout
     *  ki height pakki (definite) hoti hai, isliye weight-1 WebView ko
     *  asli height milti hi hai — designer ab chhup hi nahi sakta.
     *
     *  Page ki banawat (upar se neeche): header row (title + "⬅ वापस"),
     *  designer WebView (weight 1), aur purani wali neeche ki button row
     *  (Saved Icons picker + "✖ बंद करो"). Page ke neeche Home tab waisa
     *  ka waisa rehta hai — page band hote hi wohi dikhta hai. */
    private void openIconMaker() {
        closeIconMaker(); // pehle se khula ho to use band karke fresh banao

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAGE_BG);
        page.setPadding(dp(16), dp(16), dp(16), dp(16));
        // Page content frame ko poora dhakta hai; khaali jagah ke tap
        // neeche wale tab content tak na jayein, isliye clickable.
        page.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading("🎨 App Icon Banao", 20),
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button backBtn = new Button(this);
        backBtn.setText("⬅ वापस");
        styleSmall(backBtn);
        backBtn.setTextColor(PINK_TEXT);
        backBtn.setOnClickListener(v -> closeIconMaker());
        header.addView(backBtn);
        page.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new IconBridge(), "IconBridge");
        // Keyboard ke liye (v5.1 wala sabak): WebView ko touch focus
        // milna chahiye, warna andar tap karne par keyboard nahi khulta.
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.loadUrl("file:///android_asset/icon_maker.html");
        LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wvLp.topMargin = dp(10);
        page.addView(wv, wvLp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);
        Button savedBtn = new Button(this);
        savedBtn.setText("🖼️ Saved Icons से चुनो");
        styleSmall(savedBtn);
        savedBtn.setTextColor(PINK_TEXT);
        Button closeBtn = new Button(this);
        closeBtn.setText("✖ बंद करो");
        styleSmall(closeBtn);
        btnRow.addView(savedBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(8);
        btnRow.addView(closeBtn, half2);
        page.addView(btnRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        savedBtn.setOnClickListener(v -> showSavedIconsPicker());
        closeBtn.setOnClickListener(v -> closeIconMaker());

        iconMakerPage = page;
        iconMakerWebView = wv;
        contentFrame.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        wv.requestFocus();
        wv.requestFocusFromTouch();
    }

    /** Icon Maker page band karta hai: WebView saaf karke page view
     *  content frame se hata deta hai — neeche Home tab waisa ka waisa
     *  dikhne lagta hai — aur icon preview tazaa kar deta hai. Page
     *  khula hi na ho to kuch nahi hota. */
    private void closeIconMaker() {
        if (iconMakerPage == null) return;
        if (iconMakerWebView != null) {
            iconMakerWebView.stopLoading();
            iconMakerWebView.destroy();
            iconMakerWebView = null;
        }
        contentFrame.removeView(iconMakerPage);
        iconMakerPage = null;
        refreshIconPreview();
    }

    /** icon_maker.html ka JavaScript bridge: design kiya hua icon save/clear. */
    public class IconBridge {
        @JavascriptInterface
        public void saveIcon(String dataUrl) {
            try {
                int comma = dataUrl.indexOf(',');
                byte[] bytes = android.util.Base64.decode(
                        dataUrl.substring(comma + 1), android.util.Base64.DEFAULT);
                try (OutputStream out = new FileOutputStream(customIconFile())) {
                    out.write(bytes);
                }
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "\uD83C\uDFA8 Icon save ho gaya \u2713", Toast.LENGTH_SHORT).show();
                    refreshIconPreview();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Icon save nahi hua: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void clearIcon() {
            //noinspection ResultOfMethodCallIgnored
            customIconFile().delete();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "\u21A9 Default icon lagega", Toast.LENGTH_SHORT).show();
                refreshIconPreview();
            });
        }
    }


    // ------------------------------------------------------ saved icons picker

    /** Downloads/SP WEB OS/<AppName>/icon.png ka ek record. */
    private static class SavedIconItem {
        String appName;   // sub-folder ka naam (khali = seedha SP WEB OS folder)
        Uri uri;          // API 29+ MediaStore
        File local;       // API 26-28 direct file
    }

    /** Downloads ke app-folders se saare saved icon.png ikatthe karta hai.
     *  HTMLs/APKs tabs wala hi tareeka: API 29+ MediaStore, purane Android
     *  par folder walk. Har jagah try/catch — picker kabhi crash nahi hota. */
    private List<SavedIconItem> querySavedIcons() {
        List<SavedIconItem> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Cursor c = null;
            try {
                Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] proj = {
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.RELATIVE_PATH
                };
                String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] args = {Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS%"};
                c = getContentResolver().query(coll, proj, sel, args,
                        MediaStore.Downloads.DATE_MODIFIED + " DESC");
                if (c != null) {
                    int idI = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    int nameI = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                    int relI = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH);
                    while (c.moveToNext()) {
                        String nm = c.getString(nameI);
                        if (nm == null || !nm.equalsIgnoreCase("icon.png")) continue;
                        SavedIconItem it = new SavedIconItem();
                        it.appName = appFolderFromRelativePath(c.getString(relI));
                        it.uri = ContentUris.withAppendedId(coll, c.getLong(idI));
                        out.add(it);
                    }
                }
            } catch (Exception ignored) {
                // Folder na mile / permission na ho — khaali list hi sahi.
            } finally {
                if (c != null) c.close();
            }
        } else {
            try {
                File base = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "SP WEB OS");
                collectSavedIcons(base, "", out);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** API 26-28: SP WEB OS folder aur uske app sub-folders me icon.png
     *  dhoondhta hai (ek level gehra). */
    private void collectSavedIcons(File dir, String appName, List<SavedIconItem> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (appName.isEmpty()) collectSavedIcons(f, f.getName(), out);
            } else if (f.getName().equalsIgnoreCase("icon.png")) {
                SavedIconItem it = new SavedIconItem();
                it.appName = appName;
                it.local = f;
                out.add(it);
            }
        }
    }

    private byte[] readSavedIconBytes(SavedIconItem it) throws Exception {
        InputStream raw = it.uri != null
                ? getContentResolver().openInputStream(it.uri)
                : new FileInputStream(it.local);
        if (raw == null) throw new Exception("file khul nahi payi");
        try (InputStream in = raw) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    /** Saved Icons picker kholta hai: Downloads me bane hue apps ke
     *  icon.png ki list. Kisi icon par tap = wohi is build ka custom
     *  icon ban jata hai (cache wali file me jaakar). */
    private void showSavedIconsPicker() {
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);
        final AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle("🖼️ Saved Icons")
                .setView(scroll)
                .setNegativeButton("✖ बंद करो", null)
                .create();
        fillSavedIconsList(listBox, picker);
        picker.show();
    }

    private void fillSavedIconsList(final LinearLayout listBox,
                                    final Dialog picker) {
        try {
            listBox.removeAllViews();
            List<SavedIconItem> icons = querySavedIcons();
            if (icons.isEmpty()) {
                TextView empty = bodyText("कोई saved icon नहीं मिला — पहले किसी app का "
                        + "icon बनाकर build करो।");
                empty.setPadding(dp(16), dp(16), dp(16), dp(16));
                listBox.addView(empty);
                return;
            }
            for (final SavedIconItem it : icons) {
                listBox.addView(savedIconRow(it, picker, listBox), lp(6));
            }
        } catch (Exception e) {
            try {
                TextView err = bodyText("Icons load nahi ho paye: " + e.getMessage());
                err.setPadding(dp(16), dp(16), dp(16), dp(16));
                listBox.addView(err);
            } catch (Exception ignored) { }
        }
    }

    private LinearLayout savedIconRow(final SavedIconItem it, final Dialog picker,
                                      final LinearLayout listBox) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(roundedBg(CARD_BG, 10, BORDER));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        ImageView thumb = new ImageView(this);
        try {
            byte[] bytes = readSavedIconBytes(it);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                if (bmp.getWidth() > 96 || bmp.getHeight() > 96) {
                    bmp = Bitmap.createScaledBitmap(bmp, 96, 96, true);
                }
                thumb.setImageBitmap(bmp);
            }
        } catch (Exception ignored) { }
        row.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView name = new TextView(this);
        name.setText(it.appName.isEmpty() ? "SP WEB OS" : it.appName);
        name.setTextSize(15);
        name.setTextColor(DARK_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(10);
        row.addView(name, np);

        Button del = new Button(this);
        del.setText("🗑");
        styleSmall(del);
        del.setOnClickListener(v ->
                confirmDeleteSavedIcon(it, listBox, picker));
        row.addView(del);

        row.setOnClickListener(v -> useSavedIcon(it, picker));
        row.setOnLongClickListener(v -> {
            confirmDeleteSavedIcon(it, listBox, picker);
            return true;
        });
        return row;
    }

    /** Chuna hua saved icon cache wali custom-icon file me daal deta hai;
     *  phir picker band, Icon Maker page band, Home par wapas — aur
     *  preview tazaa (closeIconMaker khud bhi kar deta hai). */
    private void useSavedIcon(SavedIconItem it, Dialog picker) {
        try {
            byte[] bytes = readSavedIconBytes(it);
            try (OutputStream out = new FileOutputStream(customIconFile())) {
                out.write(bytes);
            }
            refreshIconPreview();
            Toast.makeText(this, "🎨 Icon चुन लिया: "
                    + (it.appName.isEmpty() ? "SP WEB OS" : it.appName),
                    Toast.LENGTH_SHORT).show();
            if (picker != null) picker.dismiss();
            closeIconMaker();
        } catch (Exception e) {
            Toast.makeText(this, "Icon load nahi ho paya: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteSavedIcon(final SavedIconItem it,
                                        final LinearLayout listBox,
                                        final Dialog picker) {
        new AlertDialog.Builder(this)
                .setTitle("🗑️ Icon delete karo?")
                .setMessage((it.appName.isEmpty() ? "SP WEB OS" : it.appName)
                        + " ka saved icon.png Downloads se hat jayega. "
                        + "Bane hue APK aur app par koi asar nahi padega.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean gone = false;
                    if (it.uri != null) {
                        try {
                            gone = getContentResolver().delete(it.uri, null, null) > 0;
                        } catch (RecoverableSecurityException e) {
                            // System ka permission dialog khulega; is beech
                            // picker band kar do taaki purani list na dikhe.
                            if (picker != null) picker.dismiss();
                            try {
                                startIntentSenderForResult(
                                        e.getUserAction().getActionIntent().getIntentSender(),
                                        REQ_APK_DELETE, null, 0, 0, 0);
                            } catch (Exception e2) {
                                Toast.makeText(this, "Delete ke liye Files app use karo",
                                        Toast.LENGTH_LONG).show();
                            }
                            return;
                        } catch (Exception e) {
                            Toast.makeText(this, "Delete nahi ho paya: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                    } else if (it.local != null) {
                        //noinspection ResultOfMethodCallIgnored
                        gone = it.local.delete();
                    }
                    Toast.makeText(this, gone ? "Delete ho gaya 🗑️" : "Delete nahi ho paya",
                            Toast.LENGTH_SHORT).show();
                    fillSavedIconsList(listBox, picker);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private byte[] readCustomIcon() {
        File f = customIconFile();
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------- pick / build / install

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_APK_DELETE) {
            // MediaStore delete/rename ka system permission dialog wapas
            // aaya — user ne haan kaha ho to badlav ho gaya hoga; dono
            // lists tazaa kar do (APKs + HTMLs, jo bhi tha).
            refreshApkList();
            refreshHtmlList();
            return;
        }
        if (requestCode == REQ_PICK_HTML && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                pickedHtml = baos.toByteArray();
                pickedName = uri.getLastPathSegment();
                updatePackagePreview();
                setStatus("File mil gayi: " + pickedName + " (" + pickedHtml.length + " bytes)\nAb 'APK Banao' dabao.");
            } catch (Exception e) {
                setStatus("File padhne mein dikkat: " + e.getMessage());
            }
        }
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private byte[] currentHtml() {
        String typed = codeBox.getText().toString().trim();
        if (!typed.isEmpty()) return typed.getBytes(StandardCharsets.UTF_8);
        return pickedHtml;
    }

    private void build() {
        final boolean isUrl = "url".equals(appType);
        final byte[] html;
        final String urlText;
        if (isUrl) {
            String u = urlBox.getText().toString().trim();
            if (u.isEmpty()) {
                Toast.makeText(this, "Pehle website ka URL likho", Toast.LENGTH_LONG).show();
                return;
            }
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
            urlText = u;
            html = null;
        } else {
            html = currentHtml();
            if (html == null || html.length == 0) {
                Toast.makeText(this, "Pehle HTML file chuno ya code paste karo",
                        Toast.LENGTH_LONG).show();
                return;
            }
            urlText = null;
        }
        String[] eff = effectiveName();
        final String label = eff == null ? null : eff[0];
        final String pkg = eff == null ? null : packageForName(eff[0]);
        final String nameSource = eff == null ? "default" : eff[1];
        final byte[] iconBytes = readCustomIcon();

        // Ek hi template (v5.3): CI wala template.apk.
        if (!assetExists("template.apk")) {
            setStatus("❌ Template (template.apk) is build me nahi mila.\n"
                    + "SP WEB OS ka latest version install karke phir try karo.");
            return;
        }

        // ---- v6.0 selections (UI thread par hi padh lo): libraries
        // (URL par force-none), default-ON scripts, manual code.
        // Features yahan NAHI padhe jate — woh app ke panel me hain.
        SharedPreferences bprefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final boolean libThree = !isUrl && libSel[0];
        final boolean libChart = !isUrl && libSel[1];
        final boolean libKatex = !isUrl && libSel[2];
        final List<String> defScripts = new ArrayList<>(loadSelectedScriptIds());
        final String jsCode = bprefs.getString(PREF_MANUAL_JS, "");
        final String cssCode = bprefs.getString(PREF_MANUAL_CSS, "");
        final boolean fCustom = jsCode != null && cssCode != null
                && (!jsCode.trim().isEmpty() || !cssCode.trim().isEmpty());
        List<String> on = new ArrayList<>();
        if (libThree) on.add("Three.js");
        if (libChart) on.add("Chart.js");
        if (libKatex) on.add("KaTeX");
        if (!defScripts.isEmpty()) on.add(defScripts.size() + " scripts default-ON");
        if (fCustom) on.add("Apna code (inject.js)");
        final String featSummary = on.isEmpty() ? "koi nahi" : joinStrings(on);

        setStatus("APK ban raha hai... ⏳");
        installBtn.setEnabled(false);
        new Thread(() -> {
            try {
                Map<String, byte[]> swaps = new HashMap<>();

                // config.json (v6.0) — HAR build me jaata hai. Ab isme
                // sirf type/url, inject.js ka switch, panel ke seed
                // defaults (type ke hisaab se yahi par tay) aur
                // default-ON script ids hain. Features app ke andar
                // panel se LIVE badalte hain — config unhe bake nahi
                // karta. (Template purane flat keys bhi samajhta hai,
                // isliye v5.3 ke bane apps bhi sahi chalte hain.)
                JSONObject cfg = new JSONObject();
                cfg.put("type", isUrl ? "url" : "html");
                if (isUrl) cfg.put("url", urlText);
                cfg.put("customCode", fCustom);
                JSONObject pd = new JSONObject();
                pd.put("nightMode", false);
                pd.put("fullScreen", false);
                pd.put("bgPlay", defScripts.contains("bg-play"));
                pd.put("pip", defScripts.contains("pip-mode"));
                pd.put("adBlock", isUrl);
                pd.put("gestures", isUrl);
                pd.put("historyRec", isUrl);
                pd.put("desktopMode", false);
                cfg.put("panelDefaults", pd);
                JSONArray ds = new JSONArray();
                for (String id : defScripts) {
                    if (!"bg-play".equals(id) && !"pip-mode".equals(id)) ds.put(id);
                }
                cfg.put("defaultScripts", ds);
                swaps.put("assets/config.json",
                        cfg.toString().getBytes(StandardCharsets.UTF_8));

                // index.html: HTML type par user ka page; URL type par
                // chhota redirect page (asal load config ke URL se hota
                // hai — ye sirf placeholder/safety hai).
                swaps.put("assets/index.html", isUrl ? redirectHtml(urlText) : html);

                // Libraries (v6.0): chuni hui library files builder ke
                // apne assets se seedha generated app ke andar — sirf
                // chune par hi (yahi APK ka size badhati hain).
                if (libThree) {
                    byte[] three = readAssetBytes("three.min.js");
                    if (three == null || three.length < 100000) {
                        setStatus("❌ Three.js library (three.min.js) is build me nahi mili.\n"
                                + "SP WEB OS ka latest version install karke phir try karo.");
                        return;
                    }
                    swaps.put("assets/three.min.js", three);
                }
                if (libChart) {
                    byte[] chart = readAssetBytes("chart.min.js");
                    if (chart == null || chart.length < 50000) {
                        setStatus("❌ Chart.js library (chart.min.js) is build me nahi mili.\n"
                                + "SP WEB OS ka latest version install karke phir try karo.");
                        return;
                    }
                    swaps.put("assets/chart.min.js", chart);
                }
                if (libKatex) {
                    // KaTeX best-effort library hai: builder build me
                    // asset na bana ho to build nahi rokta — sirf
                    // chetaavni ke saath aage badhta hai.
                    String[] katexFiles = null;
                    try { katexFiles = getAssets().list("lib/katex"); }
                    catch (Exception ignored) { }
                    boolean katexOk = false;
                    if (katexFiles != null) {
                        for (String fn : katexFiles) {
                            if ("katex.min.js".equals(fn)) katexOk = true;
                        }
                    }
                    if (!katexOk) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "⚠️ KaTeX is build me uplabdh nahi — bina KaTeX ke APK ban raha hai",
                                Toast.LENGTH_LONG).show());
                    } else {
                        List<String> all = new ArrayList<>();
                        collectAssetPaths("lib/katex", all);
                        for (String path : all) {
                            byte[] b = readAssetBytes(path);
                            if (b != null) swaps.put("assets/" + path, b);
                        }
                    }
                }

                // Custom JS/CSS: assets/inject.js (CSS ek <style> wale
                // JS wrapper me badal kar user JS ke saath judta hai).
                if (fCustom) {
                    String inject = buildInjectJs(jsCode, cssCode);
                    if (inject != null) {
                        swaps.put("assets/inject.js",
                                inject.getBytes(StandardCharsets.UTF_8));
                    }
                }

                builtApk = ApkMaker.buildApk(this, "template.apk", swaps, label, pkg, iconBytes);
                builtLabel = label == null ? "Web App" : label;
                builtPackage = pkg == null ? ApkPatcher.OLD_PACKAGE : pkg;
                // URL apps ke liye Downloads me redirect wala .html
                // save hota hai, taaki per-app folder poora rahe.
                String saved = saveOutputsToDownloads(
                        isUrl ? redirectHtml(urlText) : html, builtApk, builtLabel);
                setStatus("✅ APK taiyaar!\nApp: " + builtLabel + " (naam: " + nameSource + ")"
                        + "\nType: " + (isUrl ? "🌐 URL — " + urlText : "📄 HTML")
                        + "\nBuild choices: " + featSummary
                        + "\n(Features app ke andar • panel se badlenge)"
                        + "\nPackage: " + builtPackage
                        + saved
                        + (iconBytes != null ? "\n🎨 Custom icon lagaya gaya." : "")
                        + "\nAb 'Install Karo' dabao.");
                runOnUiThread(() -> installBtn.setEnabled(true));
            } catch (Exception e) {
                setStatus("❌ APK nahi bana: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\n(Tip: agar naam diya tha, to naam khaali karke ek baar phir try karo — default mode hamesha chalta hai.)");
            }
        }).start();
    }

    /** Ek asset folder ke andar ki saari files (subfolders samet)
     *  recursively ikatthi karta hai — KaTeX ke fonts ke liye. */
    private void collectAssetPaths(String dir, List<String> out) {
        try {
            String[] kids = getAssets().list(dir);
            if (kids == null) return;
            for (String k : kids) {
                String p = dir + "/" + k;
                String[] sub = null;
                try { sub = getAssets().list(p); } catch (Exception ignored) { }
                if (sub != null && sub.length > 0) collectAssetPaths(p, out);
                else out.add(p);
            }
        } catch (Exception ignored) { }
    }

    /** Builder ka koi asset poora padhta hai (three.min.js ke liye);
     *  na mile to null. */
    private byte[] readAssetBytes(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** URL apps ke liye chhota redirect page — Downloads me
     *  <AppName>.html bankar save hota hai (aur APK ke andar
     *  placeholder bhi rehta hai; asal kaam config.json ke URL ka hai). */
    private static byte[] redirectHtml(String url) {
        String esc = url.replace("&", "&amp;").replace("\"", "&quot;");
        String page = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"refresh\" content=\"0; url=" + esc + "\">"
                + "<title>" + esc + "</title></head><body>"
                + "<p>Khul raha hai: <a href=\"" + esc + "\">" + esc + "</a></p>"
                + "</body></html>";
        return page.getBytes(StandardCharsets.UTF_8);
    }

    /** Custom JS/CSS feature ke liye inject.js ka text banata hai:
     *  CSS ko <style> lagane wale JS wrapper me badal kar user ke JS
     *  ke aage jodta hai. Dono khaali hon to null (inject.js nahi banta). */
    private static String buildInjectJs(String js, String css) {
        StringBuilder sb = new StringBuilder();
        String c = css == null ? "" : css.trim();
        String j = js == null ? "" : js.trim();
        if (!c.isEmpty()) {
            String esc = c.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\r", "").replace("\n", "\\n");
            sb.append("(function(){try{var st=document.createElement('style');"
                    + "st.textContent='").append(esc).append("';"
                    + "(document.head||document.documentElement).appendChild(st);"
                    + "}catch(e){}})();\n");
        }
        if (!j.isEmpty()) sb.append(j).append("\n");
        return sb.length() == 0 ? null : sb.toString();
    }

    private void install() {
        if (builtApk == null || !builtApk.exists()) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Pehle is app ko 'unknown apps install' ki permission do", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = pi.createSession(params);
            PackageInstaller.Session session = pi.openSession(sessionId);
            try (InputStream in = new FileInputStream(builtApk);
                 OutputStream out = session.openWrite("app", 0, builtApk.length())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent intent = new Intent(this, MainActivity.class).setAction(INSTALL_ACTION);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= android.app.PendingIntent.FLAG_MUTABLE;
            android.app.PendingIntent sender = android.app.PendingIntent.getActivity(this, 7, intent, flags);
            session.commit(sender.getIntentSender());
            session.close();
            setStatus("Installer khul raha hai... 📲");
        } catch (Exception e) {
            setStatus("❌ Install shuru nahi hua: " + e.getMessage()
                    + "\nAPK '📦 APKs' tab me bhi milega (Auto Save Apks ON ho to) — wahan se bhi install kar sakte ho.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && INSTALL_ACTION.equals(intent.getAction())) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99);
            if (st == PackageInstaller.STATUS_SUCCESS) {
                setStatus("🎉 '" + (builtLabel == null ? "Web App" : builtLabel)
                        + "' install ho gaya! Home screen par dekho.");
            } else if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm);
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                setStatus("Install status: " + st + (msg != null ? " — " + msg : ""));
            }
        }
    }
}
