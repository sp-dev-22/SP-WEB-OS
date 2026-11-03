package com.sp.webos.builder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SP WEB OS — single-file HTML ko phone par hi APK banane wala app.
 * Flow: HTML file chuno ya code paste karo -> app ka naam do (optional)
 * -> "APK Banao" -> "Install Karo".
 * Har build ka HTML app ki apni library me save hota hai, aur bana hua
 * APK Downloads/SP WEB OS folder me bhi copy hota hai.
 * Alag naam = alag package id = kai apps ek saath phone par reh sakte hain.
 *
 * v5 UI: neeche 4 tabs — Home (build flow), HTMLs (library), APKs
 * (bane hue APK), Settings (jankari + help). Minimal pink-accent design.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_HTML = 42;
    private static final int REQ_WRITE_PERM = 77;
    private static final int REQ_APK_DELETE = 101;
    private static final String INSTALL_ACTION = "com.sp.webos.INSTALL_RESULT";

    // Minimal pink-accent palette
    private static final int PINK = 0xFFE91E63;
    private static final int PINK_DARK = 0xFFC2185B;
    private static final int DARK_TEXT = 0xFF212121;
    private static final int GREY_TEXT = 0xFF616161;
    private static final int FAINT_TEXT = 0xFF9E9E9E;
    private static final int PAGE_BG = 0xFFFAFAFA;
    private static final int CARD_BG = 0xFFFFFFFF;
    private static final int BORDER = 0xFFE0E0E0;
    private static final int RED = 0xFFD32F2F;

    private TextView status;
    private TextView packagePreview;
    private EditText codeBox;
    private EditText nameBox;
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
        b.setTextColor(PINK_DARK);
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
        t.setTextColor(PINK_DARK);
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
        showTab(TAB_HOME);
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

        Button libraryBtn = new Button(this);
        libraryBtn.setText("📚 Saved HTML (Library)");
        styleOutline(libraryBtn);
        libraryBtn.setOnClickListener(v -> showLibrary());
        root.addView(libraryBtn, lp(8));

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

        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        root.addView(bodyText("SP WEB OS  •  Version " + version
                + "\nKaam: single-file HTML ko phone par hi APK banana — no PC, no server."), lp(8));

        root.addView(heading("📖 Kaise istemaal karen", 16), lp(16));
        root.addView(bodyText(
                "1.  Home tab me HTML file chuno ya code paste karo.\n"
                + "2.  App ka naam do (optional). Naam se alag Package ID banta hai, "
                + "taaki kai apps ek saath phone par reh saken. Naam khaali ho to HTML ke "
                + "<title> ya file ke naam se apne aap ban jata hai.\n"
                + "3.  Chaho to '🎨 App Icon Banao' se apna icon design karo — background "
                + "colour, pattern aur text tum khud chunte ho.\n"
                + "4.  '🚀 APK Banao' dabao. APK taiyaar hoga, HTML library me save hoga.\n"
                + "5.  '📲 Install Karo' dabakar app install karo.\n\n"
                + "📄 HTMLs tab — save hue saare HTML: kholo (build ke liye load), "
                + "naam badlo, ya delete karo.\n"
                + "📦 APKs tab — bane hue saare APK: install, share ya delete karo."), lp(6));

        root.addView(heading("📥 Downloads folder", 16), lp(16));
        root.addView(bodyText(
                "Har bane hue APK ki ek copy 'Downloads/SP WEB OS' folder me bhi "
                + "rakhi jati hai. Wahan se Files app ke through bhi APK khol, share "
                + "ya delete kar sakte ho — APKs tab wahi folder dikhata hai."), lp(6));

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

    // ---------------------------------------------------------- HTMLs tab (library)

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

    private void refreshHtmlList() {
        if (htmlList == null) return;
        File[] files = libraryDir().listFiles((d, n) -> n.endsWith(".html"));
        List<File> all = new ArrayList<>();
        if (files != null) all.addAll(Arrays.asList(files));
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        long total = 0;
        for (File f : all) total += f.length();
        htmlHeader.setText("📄 Saved HTML — " + all.size() + " files • " + fmtSize(total));

        String q = htmlSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        htmlList.removeAllViews();

        List<File> shown = new ArrayList<>();
        for (File f : all) {
            if (q.isEmpty() || f.getName().toLowerCase(Locale.ROOT).contains(q)) shown.add(f);
        }
        if (shown.isEmpty()) {
            TextView empty = bodyText(all.isEmpty()
                    ? "Abhi koi HTML save nahi hai.\nPehle Home tab se ek APK banao — HTML apne aap yahan save ho jayega."
                    : "Is naam se koi HTML nahi mila. Kuch aur khoj kar dekho.");
            htmlList.addView(empty, lp(10));
            return;
        }
        for (File f : shown) htmlList.addView(htmlRow(f), lp(8));
    }

    private LinearLayout htmlRow(final File f) {
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
        use.setTextColor(PINK_DARK);
        use.setOnClickListener(v -> {
            loadFromLibrary(f);
            showTab(TAB_HOME);
        });
        btns.addView(use);

        Button rename = new Button(this);
        rename.setText("✏️ Rename");
        styleSmall(rename);
        rename.setOnClickListener(v -> renameHtmlDialog(f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.leftMargin = dp(8);
        btns.addView(rename, rp);

        Button del = new Button(this);
        del.setText("🗑️ Delete");
        styleSmall(del);
        del.setTextColor(RED);
        del.setOnClickListener(v -> confirmDeleteHtml(f));
        btns.addView(del, rp);

        row.addView(btns, lp(8));
        return row;
    }

    private void renameHtmlDialog(final File f) {
        // Library ke timestamp suffix (_yyyyMMdd-HHmmss) ko barkarar rakhte hain,
        // taaki file ki pehchan aur sorting na bigde.
        String oldName = f.getName();
        String foundSuffix = "";
        Matcher m = Pattern.compile("(_\\d{8}-\\d{6})\\.html$").matcher(oldName);
        if (m.find()) foundSuffix = m.group(1);
        final String suffix = foundSuffix;
        String base = oldName.replaceAll("(?i)\\.html$", "");
        if (!suffix.isEmpty() && base.endsWith(suffix)) {
            base = base.substring(0, base.length() - suffix.length());
        }

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
                            .replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                    if (typed.isEmpty()) {
                        Toast.makeText(this, "Naam khaali nahi ho sakta", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (typed.length() > 40) typed = typed.substring(0, 40);
                    File dest = new File(f.getParentFile(), typed + suffix + ".html");
                    if (dest.getName().equals(oldName)) return;
                    if (dest.exists()) {
                        Toast.makeText(this, "Is naam ki file pehle se hai", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (f.renameTo(dest)) {
                        Toast.makeText(this, "Naam badal gaya ✓", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Rename nahi ho paya", Toast.LENGTH_LONG).show();
                    }
                    refreshHtmlList();
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    /** Ek bane hue APK ka record — MediaStore Uri wala ya apni local file wala. */
    private static class ApkItem {
        String label;
        long size;
        long modifiedMs;
        Uri uri;      // MediaStore (Downloads) items ke liye
        File local;   // WebApp.apk (latest build) / purane Android par Downloads file
        boolean latest;
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

    /** Downloads/SP WEB OS (MediaStore / purane Android par folder) + apna
     *  latest build (WebApp.apk) — sab jagah try/catch, tab kabhi crash nahi. */
    private List<ApkItem> queryApkItems() {
        List<ApkItem> out = new ArrayList<>();
        // 1) Sabse naya build — app ke apne folder ki WebApp.apk
        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                File w = new File(ext, "WebApp.apk");
                if (w.exists() && w.length() > 0) {
                    ApkItem it = new ApkItem();
                    it.label = "WebApp.apk";
                    it.size = w.length();
                    it.modifiedMs = w.lastModified();
                    it.local = w;
                    it.latest = true;
                    out.add(it);
                }
            }
        } catch (Exception ignored) { }

        List<ApkItem> downloads = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            // 2a) Android 10+ — Downloads/SP WEB OS ko MediaStore se padho
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
            // 2b) Purane Android — public Downloads folder seedha padho
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "SP WEB OS");
                File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".apk"));
                if (fs != null) {
                    for (File f : fs) {
                        ApkItem it = new ApkItem();
                        it.label = f.getName();
                        it.size = f.length();
                        it.modifiedMs = f.lastModified();
                        it.local = f;
                        downloads.add(it);
                    }
                }
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
        name.setText((it.latest ? "🆕 " : "📦 ")
                + it.label.replaceAll("(?i)\\.apk$", ""));
        name.setTextSize(15);
        name.setTextColor(DARK_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        row.addView(name);

        TextView meta = new TextView(this);
        meta.setText(fmtSize(it.size) + " • " + fmtDate(it.modifiedMs)
                + (it.latest ? " • Latest build" : " • Downloads/SP WEB OS"));
        meta.setTextSize(12);
        meta.setTextColor(GREY_TEXT);
        row.addView(meta);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button install = new Button(this);
        install.setText("📲 Install");
        styleSmall(install);
        install.setTextColor(PINK_DARK);
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

    /** MediaStore APK: system installer intent. Local file (latest build):
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
                .setMessage(it.label + "\nYe APK "
                        + (it.latest ? "app ke folder se" : "Downloads/SP WEB OS folder se")
                        + " hat jayega. Install hua app safe rahega.")
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

    private void saveToLibrary(byte[] html, String baseName) {
        try {
            String safe = baseName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            if (safe.isEmpty()) safe = "html";
            if (safe.length() > 40) safe = safe.substring(0, 40);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File f = new File(libraryDir(), safe + "_" + stamp + ".html");
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(html);
            }
        } catch (Exception ignored) {
            // Library save fail ho jaye to bhi build nahi rukna chahiye.
        }
    }

    private void showLibrary() {
        File[] files = libraryDir().listFiles((d, n) -> n.endsWith(".html"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "Library khaali hai — pehle ek APK banao, HTML yahan save ho jayega",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            labels[i] = files[i].getName() + "  (" + (files[i].length() / 1024) + " KB)";
        }
        new AlertDialog.Builder(this)
                .setTitle("📚 Saved HTML — ek chuno")
                .setItems(labels, (dialog, which) -> loadFromLibrary(files[which]))
                .setNegativeButton("Band Karo", null)
                .show();
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

    // ---------------------------------------------------------- Downloads copy

    /** Bane hue APK ki copy Downloads/SP WEB OS me rakhta hai.
     *  @return jahan copy hua uska varnan, ya null agar na ho paya ho. */
    private String copyToDownloads(File apk, String displayLabel) {
        String fileName = displayLabel.replaceAll("[\\\\/:*?\"<>|]", "_") + ".apk";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return copyToDownloadsModern(apk, fileName);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_PERM);
                return null;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "SP WEB OS");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, fileName);
            copyFile(apk, dest);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private String copyToDownloadsModern(File apk, String fileName) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        cv.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/SP WEB OS");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) return null;
        try (InputStream in = new FileInputStream(apk);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) return null;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return "Downloads/SP WEB OS/" + fileName;
    }

    private static void copyFile(File src, File dest) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------- custom app icon

    private File customIconFile() {
        return new File(getFilesDir(), "custom_icon.png");
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

    /** Icon Maker (assets/icon_maker.html) ko ek dialog ke andar kholta hai. */
    private void openIconMaker() {
        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new IconBridge(), "IconBridge");
        int h = (int) (580 * getResources().getDisplayMetrics().density);
        wv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));
        wv.loadUrl("file:///android_asset/icon_maker.html");
        new AlertDialog.Builder(this)
                .setTitle("\uD83C\uDFA8 App Icon Banao")
                .setView(wv)
                .setNegativeButton("Band Karo", null)
                .show();
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
            // MediaStore delete ka system permission dialog wapas aaya —
            // user ne haan kaha ho to file system ne khud delete kar di hogi.
            refreshApkList();
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
        byte[] html = currentHtml();
        if (html == null || html.length == 0) {
            Toast.makeText(this, "Pehle HTML file chuno ya code paste karo", Toast.LENGTH_LONG).show();
            return;
        }
        String[] eff = effectiveName();
        final String label = eff == null ? null : eff[0];
        final String pkg = eff == null ? null : packageForName(eff[0]);
        final String nameSource = eff == null ? "default" : eff[1];
        String libBase = label != null ? label
                : (pickedName != null ? pickedName.replaceAll("\\.html?$", "") : "pasted-code");
        saveToLibrary(html, libBase);
        final byte[] iconBytes = readCustomIcon();
        setStatus("APK ban raha hai... ⏳");
        installBtn.setEnabled(false);
        new Thread(() -> {
            try {
                builtApk = ApkMaker.buildApk(this, html, label, pkg, iconBytes);
                builtLabel = label == null ? "Web App" : label;
                builtPackage = pkg == null ? ApkPatcher.OLD_PACKAGE : pkg;
                String dl = copyToDownloads(builtApk, builtLabel);
                setStatus("✅ APK taiyaar!\nApp: " + builtLabel + " (naam: " + nameSource + ")\nPackage: " + builtPackage
                        + "\nPath: " + builtApk.getAbsolutePath()
                        + (dl != null ? "\n📥 Downloads me copy: " + dl
                                      : "\n(Downloads copy nahi ho paya — APK upar wale path par hai)")
                        + "\n📚 HTML library me save ho gaya."
                        + (iconBytes != null ? "\n🎨 Custom icon lagaya gaya." : "")
                        + "\nAb 'Install Karo' dabao.");
                runOnUiThread(() -> installBtn.setEnabled(true));
            } catch (Exception e) {
                setStatus("❌ APK nahi bana: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\n(Tip: agar naam diya tha, to naam khaali karke ek baar phir try karo — default mode hamesha chalta hai.)");
            }
        }).start();
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
                    + "\nAPK Downloads/SP WEB OS folder me bhi hai, wahan se bhi install kar sakte ho.");
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
