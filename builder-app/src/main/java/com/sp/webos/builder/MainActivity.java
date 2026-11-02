package com.sp.webos.builder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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
import android.widget.Button;
import android.widget.EditText;
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
import java.util.Arrays;
import java.util.Date;
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
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_HTML = 42;
    private static final int REQ_WRITE_PERM = 77;
    private static final String INSTALL_ACTION = "com.sp.webos.INSTALL_RESULT";

    private TextView status;
    private TextView packagePreview;
    private EditText codeBox;
    private EditText nameBox;
    private Button installBtn;
    private byte[] pickedHtml;
    private String pickedName;
    private File builtApk;
    private String builtLabel;
    private String builtPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SP WEB OS");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Single-file HTML → APK, seedha phone par. No PC, no server.");
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub);

        Button pickBtn = new Button(this);
        pickBtn.setText("📄 HTML File Chuno");
        pickBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain"});
            startActivityForResult(i, REQ_PICK_HTML);
        });
        root.addView(pickBtn);

        Button libraryBtn = new Button(this);
        libraryBtn.setText("📚 Saved HTML (Library)");
        libraryBtn.setOnClickListener(v -> showLibrary());
        root.addView(libraryBtn);

        codeBox = new EditText(this);
        codeBox.setHint("...ya yahan HTML code paste karo");
        codeBox.setMinLines(8);
        codeBox.setGravity(Gravity.TOP);
        codeBox.setTextSize(13);
        root.addView(codeBox);

        nameBox = new EditText(this);
        nameBox.setHint("App ka naam (optional) — khaali chhodo to HTML title / file naam se apne aap banega");
        nameBox.setSingleLine(true);
        root.addView(nameBox);

        codeBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                updatePackagePreview();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        packagePreview = new TextView(this);
        packagePreview.setTextSize(12);
        root.addView(packagePreview);
        updatePackagePreview();
        nameBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                updatePackagePreview();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        Button buildBtn = new Button(this);
        buildBtn.setText("🚀 APK Banao");
        buildBtn.setOnClickListener(v -> build());
        root.addView(buildBtn);

        installBtn = new Button(this);
        installBtn.setText("📲 Bana Hua APK Install Karo");
        installBtn.setEnabled(false);
        installBtn.setOnClickListener(v -> install());
        root.addView(installBtn);

        status = new TextView(this);
        status.setText("Taiyaar. HTML file chuno ya code paste karke APK banao.");
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
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

    // ---------------------------------------------------------- pick / build / install

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        setStatus("APK ban raha hai... ⏳");
        installBtn.setEnabled(false);
        new Thread(() -> {
            try {
                builtApk = ApkMaker.buildApk(this, html, label, pkg);
                builtLabel = label == null ? "Web App" : label;
                builtPackage = pkg == null ? ApkPatcher.OLD_PACKAGE : pkg;
                String dl = copyToDownloads(builtApk, builtLabel);
                setStatus("✅ APK taiyaar!\nApp: " + builtLabel + " (naam: " + nameSource + ")\nPackage: " + builtPackage
                        + "\nPath: " + builtApk.getAbsolutePath()
                        + (dl != null ? "\n📥 Downloads me copy: " + dl
                                      : "\n(Downloads copy nahi ho paya — APK upar wale path par hai)")
                        + "\n📚 HTML library me save ho gaya."
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
