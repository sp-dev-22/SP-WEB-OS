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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // Type + Features (v5.3): ek hi template; type aur feature choices
    // prefs me yaad rehte hain aur har build me assets/config.json
    // bankar generated app me jaate hain.
    private static final String PREF_APP_TYPE = "app_type";
    private static final String PREF_FEAT_FULLSCREEN = "feat_fullscreen";
    private static final String PREF_FEAT_DARKPAGE = "feat_darkpage";
    private static final String PREF_FEAT_CUSTOMCODE = "feat_customcode";
    private static final String PREF_FEAT_ADBLOCK = "feat_adblock";
    private static final String PREF_FEAT_HISTORY = "feat_history";
    private static final String PREF_FEAT_GESTURES = "feat_gestures";
    private static final String PREF_FEAT_3D = "feat_3d";

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
    private CheckBox cbFullscreen, cbDarkPage, cbCustomCode;
    private CheckBox cbAdBlock, cbHistory, cbGestures, cb3d;
    private LinearLayout customCodeBox;
    private EditText jsBox, cssBox;
    private Button installBtn;
    private ImageView iconPreview;
    private TextView iconStatus;
    private byte[] pickedHtml;
    private String pickedName;
    private File builtApk;
    private String builtLabel;
    private String builtPackage;

    // Tabs
    private static final int TAB_HOME = 0;
    private static final int TAB_HTML = 1;
    private static final int TAB_APK = 2;
    private static final int TAB_SETTINGS = 3;
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

        tabViews = new ScrollView[]{homeScroll, htmlScroll, apkScroll, settingsScroll};

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

        String[] labels = {"🏠 Home", "📄 HTMLs", "📦 APKs", "⚙️ Settings"};
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

    private void showTab(int idx) {
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

        root.addView(heading("✨ Features", 15), lp(12));
        cbFullscreen = featureCheck(PREF_FEAT_FULLSCREEN, "⛶ Full Screen — सभी apps");
        root.addView(cbFullscreen, lp(2));
        cbDarkPage = featureCheck(PREF_FEAT_DARKPAGE, "🌙 Page Dark Mode — सभी apps");
        root.addView(cbDarkPage, lp(2));
        cbCustomCode = featureCheck(PREF_FEAT_CUSTOMCODE,
                "🧩 Custom JS/CSS (Extension) — सभी apps");
        root.addView(cbCustomCode, lp(2));

        customCodeBox = new LinearLayout(this);
        customCodeBox.setOrientation(LinearLayout.VERTICAL);
        jsBox = new EditText(this);
        jsBox.setHint("JS code (optional) — har page load ke baad chalega");
        jsBox.setMinLines(3);
        jsBox.setGravity(Gravity.TOP);
        jsBox.setTextSize(12);
        styleField(jsBox);
        customCodeBox.addView(jsBox, lp(2));
        cssBox = new EditText(this);
        cssBox.setHint("CSS code (optional) — page ki styling badlega");
        cssBox.setMinLines(3);
        cssBox.setGravity(Gravity.TOP);
        cssBox.setTextSize(12);
        styleField(cssBox);
        customCodeBox.addView(cssBox, lp(6));
        customCodeBox.setVisibility(View.GONE);
        root.addView(customCodeBox, lp(4));

        cbAdBlock = featureCheck(PREF_FEAT_ADBLOCK, "🚫 Ad Block — सिर्फ़ URL apps");
        root.addView(cbAdBlock, lp(2));
        cbHistory = featureCheck(PREF_FEAT_HISTORY,
                "🤫 Browse History (secret •) — सिर्फ़ URL apps");
        root.addView(cbHistory, lp(2));
        cbGestures = featureCheck(PREF_FEAT_GESTURES, "👆 Swipe Gestures — सिर्फ़ URL apps");
        root.addView(cbGestures, lp(2));
        cb3d = featureCheck(PREF_FEAT_3D, "🎮 3D JS Library — सिर्फ़ HTML apps");
        root.addView(cb3d, lp(2));
        root.addView(bodyText("Features freely combine hote hain — jitne chaaho "
                + "ek saath. Jo feature kisi type par nahi chalta, woh us type "
                + "par apne aap band (OFF) ho jata hai."), lp(4));

        String savedType = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_APP_TYPE, "html");
        selectType("url".equals(savedType) ? "url" : "html");
        updateCustomCodeVisibility();

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
                + "Kya-kya save ho, ye teen toggles tay karte hain (teeno default ON)."), lp(6));
        addSaveToggle(root, "Auto Save HTMLs", PREF_AUTO_SAVE_HTML);
        addSaveToggle(root, "Auto Save Apks", PREF_AUTO_SAVE_APK);
        addSaveToggle(root, "Auto Save Icons", PREF_AUTO_SAVE_ICON);

        root.addView(heading("📖 Kaise istemaal karen", 16), lp(16));
        root.addView(bodyText(
                "1.  Home tab me HTML file chuno ya code paste karo.\n"
                + "2.  App ka naam do (optional). Naam se alag Package ID banta hai, "
                + "taaki kai apps ek saath phone par reh saken. Naam khaali ho to HTML ke "
                + "<title> ya file ke naam se apne aap ban jata hai. Phir App Type "
                + "(📄 HTML / 🌐 URL) chuno aur ✨ Features tick karo — features "
                + "freely combine hote hain.\n"
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
                "'🎨 App Icon Banao' dabao → background rang aur pattern chuno "
                + "(13 patterns hain) → '✅ यह icon इस्तेमाल करो' dabakar dialog "
                + "band karo → phir APK banao. Wahi design launcher par icon "
                + "banega. Purana bana hua icon lagana ho to dialog ke neeche "
                + "'🖼️ Saved Icons से चुनो' dabao.");
        helpQa(col, "Q3. App Type aur Features kya hain?",
                "Do Type hain: 📄 HTML App (single-file HTML se app) aur 🌐 URL "
                + "App (website ke URL se app). Type ke neeche ✨ Features ki "
                + "list hai — jitne chaaho tick karke ek saath laga sakte ho: "
                + "Full Screen, Page Dark Mode aur Custom JS/CSS teeno type par "
                + "chalte hain; Ad Block, Browse History aur Swipe Gestures "
                + "sirf URL apps par; 3D JS Library sirf HTML apps par. Jo "
                + "feature kisi type par nahi chalta, woh apne aap band ho "
                + "jata hai.");
        helpQa(col, "Q4. Files kahan save hoti hain? App ke andar kuch kyun nahi?",
                "v5.1 ki policy: builder app apne andar kuch save NAHI karta. Har "
                + "app ki files Downloads/SP WEB OS/<AppName>/ folder me milti "
                + "hain — <AppName>.apk, <AppName>.html aur icon.png. Builder "
                + "uninstall ho jaye to bhi files tumhare paas rehti hain.");
        helpQa(col, "Q5. Auto Save toggles kya karte hain?",
                "Settings ke teen toggles — Auto Save HTMLs / Apks / Icons — tay "
                + "karte hain har build ke baad Downloads me kya-kya save ho. "
                + "Teeno default ON hain. Koi OFF ho to woh file save nahi hogi "
                + "(APK phir bhi banta hai, Install bhi hota hai).");
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
                + "Uska built-in badla hai Custom JS/CSS feature: '🧩 Custom "
                + "JS/CSS' tick karo, JS box me apna JavaScript aur CSS box me "
                + "apni styling paste karo — woh code har page load ke baad "
                + "page ke andar chalega. Isse page ka rang-roop badalna, "
                + "buttons chhupana, chhoti automation — bahut kuch ho sakta "
                + "hai. Code naa aata ho to AI ko likhne ko kaho aur yahan "
                + "paste kar do.");
        helpQa(col, "Q12. 🚫 Ad Block kaise kaam karta hai?",
                "URL apps me Ad Block ON ho to mashhoor ad/tracker networks "
                + "(jaise DoubleClick, PopAds, Taboola, Adsterra…) ki requests "
                + "rok di jati hain — page saaf, halka aur tez banta hai. "
                + "Note: kabhi-kabhi koi site ad-network par hi nirbhar hoti "
                + "hai aur ajeeb behave kar sakti hai — aisa ho to us app ko "
                + "bina Ad Block ke dobara banao.");
        helpQa(col, "Q13. 🎮 3D JS Library ka istemaal kaise karoon?",
                "📄 HTML App chuno aur '🎮 3D JS Library' feature ON karo — "
                + "three.min.js (Three.js) app ke andar pehle se jud jayega. "
                + "Apni HTML file me bas itni line likho: "
                + "<script src=\"three.min.js\"></script> — phir THREE wala "
                + "poora code chalega, bina kuch alag se download kiye, "
                + "offline bhi.");

        col.addView(heading("💡 Tips", 17), lp(18));
        col.addView(bodyText(
                "💡 App ka naam hamesha do — naam se alag Package ID banta hai. "
                + "Bina naam wale apps ek-doosre ko replace kar dete hain.\n"
                + "💡 Ek hi naam = update. Purani app par naya version chadh jata "
                + "hai, data nahi udta.\n"
                + "💡 Icon me saaf pattern aur tez rang sabse achha dikhta hai — purana design 'Saved Icons' se dobara bhi lag sakta hai.\n"
                + "💡 Game ya 3D ke liye HTML App me '🎮 3D JS Library' feature "
                + "ON karo — three.min.js alag se download karne ki zaroorat nahi.\n"
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
        setFeatureEnabled(cbAdBlock, url);
        setFeatureEnabled(cbHistory, url);
        setFeatureEnabled(cbGestures, url);
        setFeatureEnabled(cb3d, !url);
    }

    private void setFeatureEnabled(CheckBox cb, boolean allowed) {
        if (cb == null) return;
        if (!allowed && cb.isChecked()) cb.setChecked(false); // listener pref bhi OFF kar dega
        cb.setEnabled(allowed);
        cb.setAlpha(allowed ? 1f : 0.45f);
    }

    /** Ek feature checkbox banata hai — saved state ke saath; toggle
     *  hote hi preference me save ho jata hai. */
    private CheckBox featureCheck(final String prefKey, String label) {
        final CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(14.5f);
        cb.setTextColor(DARK_TEXT);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(PINK));
        cb.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(prefKey, false));
        cb.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(prefKey, checked).apply();
            if (btn == cbCustomCode) updateCustomCodeVisibility();
        });
        return cb;
    }

    /** Custom JS/CSS tick ho to hi JS/CSS wale boxes dikhte hain. */
    private void updateCustomCodeVisibility() {
        if (customCodeBox != null) {
            customCodeBox.setVisibility(cbCustomCode != null && cbCustomCode.isChecked()
                    ? View.VISIBLE : View.GONE);
        }
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

    /** Icon Maker (assets/icon_maker.html) ko ek dialog ke andar kholta hai.
     *  Dialog ka content ek vertical layout hai — upar WebView (weight 1)
     *  aur neeche ek button row: Saved Icons picker + Band Karo.
     *
     *  v5.3 FIX (v5.2 ka designer-gaayab bug): v5.2 me wrap layout ko
     *  setLayoutParams se 580dp ki FIXED height di gayi thi aur designer
     *  WebView uske andar height 0 + weight 1 par tha. Lekin
     *  AlertDialog.setView() root view ke apne LayoutParams ki izzat
     *  NAHI karta — woh view ko apne internal panel me MATCH_PARENT
     *  wale generated params ke saath daalta hai aur dialog ko
     *  WRAP_CONTENT naapta hai. Wrap-content naap me bantware ke liye
     *  koi pakki height hoti hi nahi, isliye weight wala WebView 0px
     *  par sikud gaya: sirf neeche wali (wrap-content) button row
     *  dikhti thi, designer loaded hote hue bhi invisible tha.
     *  Pakka ilaaj: wrap par koi fixed height NA lagao; dialog show
     *  hone ke BAAD window ko definite size do (poori chaudi x screen
     *  ka ~85% unchaai) — tab root ki height pakki hoti hai aur
     *  weight-1 WebView ko sahi jagah milti hai. */
    private void openIconMaker() {
        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new IconBridge(), "IconBridge");
        // Keyboard fix (v5.1): WebView ko touch focus milna chahiye, warna
        // dialog ke andar text box par tap karne se keyboard nahi khulta.
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.loadUrl("file:///android_asset/icon_maker.html");

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(wv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(dp(10), dp(8), dp(10), dp(10));
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
        wrap.addView(btnRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🎨 App Icon Banao")
                .setView(wrap)
                .create();
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        savedBtn.setOnClickListener(v -> showSavedIconsPicker(dialog));
        dialog.show();
        if (dialog.getWindow() != null) {
            // ASAL FIX: window ko definite size do — poori chaudi aur
            // screen ka ~85% unchaai. Isi se WebView (weight 1) ko
            // pakki height milti hai aur designer poora dikhta hai.
            int wpx = getResources().getDisplayMetrics().widthPixels;
            int hpx = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
            dialog.getWindow().setLayout(wpx, hpx);
            // Dialog window ko IME (keyboard) allow karna bhi zaroori hai —
            // SOFT_INPUT_ADJUST_RESIZE ke bina WebView ke input focus to le
            // lete hain, lekin keyboard upar nahi aata.
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        wv.requestFocus();
        wv.requestFocusFromTouch();
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
    private void showSavedIconsPicker(final Dialog makerDialog) {
        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);
        final AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle("🖼️ Saved Icons")
                .setView(scroll)
                .setNegativeButton("✖ बंद करो", null)
                .create();
        fillSavedIconsList(listBox, picker, makerDialog);
        picker.show();
    }

    private void fillSavedIconsList(final LinearLayout listBox,
                                    final Dialog picker,
                                    final Dialog makerDialog) {
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
                listBox.addView(savedIconRow(it, picker, makerDialog, listBox), lp(6));
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
                                      final Dialog makerDialog,
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
                confirmDeleteSavedIcon(it, listBox, picker, makerDialog));
        row.addView(del);

        row.setOnClickListener(v -> useSavedIcon(it, picker, makerDialog));
        row.setOnLongClickListener(v -> {
            confirmDeleteSavedIcon(it, listBox, picker, makerDialog);
            return true;
        });
        return row;
    }

    /** Chuna hua saved icon cache wali custom-icon file me daal deta hai —
     *  uske baad purana preview/build flow apne aap chal padta hai. */
    private void useSavedIcon(SavedIconItem it, Dialog picker, Dialog makerDialog) {
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
            if (makerDialog != null) makerDialog.dismiss();
        } catch (Exception e) {
            Toast.makeText(this, "Icon load nahi ho paya: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeleteSavedIcon(final SavedIconItem it,
                                        final LinearLayout listBox,
                                        final Dialog picker,
                                        final Dialog makerDialog) {
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
                    fillSavedIconsList(listBox, picker, makerDialog);
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

        // ---- Features (v5.3): checkbox states UI thread par hi padh
        // lo. Type-gating yahan doosri parat ke roop me bhi hai
        // (checkboxes waise hi disabled rehte hain).
        final boolean fFullscreen = cbFullscreen.isChecked();
        final boolean fDarkPage = cbDarkPage.isChecked();
        final boolean fCustom = cbCustomCode.isChecked();
        final boolean fAdBlock = isUrl && cbAdBlock.isChecked();
        final boolean fHistory = isUrl && cbHistory.isChecked();
        final boolean fGestures = isUrl && cbGestures.isChecked();
        final boolean f3d = !isUrl && cb3d.isChecked();
        final String jsCode = jsBox.getText().toString();
        final String cssCode = cssBox.getText().toString();
        List<String> on = new ArrayList<>();
        if (fFullscreen) on.add("Full Screen");
        if (fDarkPage) on.add("Page Dark Mode");
        if (fCustom) on.add("Custom JS/CSS");
        if (fAdBlock) on.add("Ad Block");
        if (fHistory) on.add("Browse History");
        if (fGestures) on.add("Swipe Gestures");
        if (f3d) on.add("3D JS Library");
        final String featSummary = on.isEmpty() ? "koi nahi" : joinStrings(on);

        setStatus("APK ban raha hai... ⏳");
        installBtn.setEnabled(false);
        new Thread(() -> {
            try {
                Map<String, byte[]> swaps = new HashMap<>();

                // config.json — HAR build me jaata hai; template use
                // padhkar type + saare feature flags tay karta hai.
                JSONObject cfg = new JSONObject();
                cfg.put("type", isUrl ? "url" : "html");
                if (isUrl) cfg.put("url", urlText);
                cfg.put("fullscreen", fFullscreen);
                cfg.put("adBlock", fAdBlock);
                cfg.put("history", fHistory);
                cfg.put("gestures", fGestures);
                cfg.put("darkPage", fDarkPage);
                cfg.put("customCode", fCustom);
                swaps.put("assets/config.json",
                        cfg.toString().getBytes(StandardCharsets.UTF_8));

                // index.html: HTML type par user ka page; URL type par
                // chhota redirect page (asal load config ke URL se hota
                // hai — ye sirf placeholder/safety hai).
                swaps.put("assets/index.html", isUrl ? redirectHtml(urlText) : html);

                // 3D JS: builder ke apne assets wali three.min.js seedha
                // generated app ke andar assets/three.min.js bankar.
                if (f3d) {
                    byte[] three = readAssetBytes("three.min.js");
                    if (three == null || three.length < 100000) {
                        setStatus("❌ 3D JS library (three.min.js) is build me nahi mili.\n"
                                + "SP WEB OS ka latest version install karke phir try karo.");
                        return;
                    }
                    swaps.put("assets/three.min.js", three);
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
                        + "\nFeatures: " + featSummary
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
