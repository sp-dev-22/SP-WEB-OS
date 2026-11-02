package com.sp.webos.builder;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SP WEB OS — single-file HTML ko phone par hi APK banane wala app.
 * Flow: HTML file chuno ya code paste karo -> "APK Banao" -> "Install Karo".
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_HTML = 42;
    private static final String INSTALL_ACTION = "com.sp.webos.INSTALL_RESULT";

    private TextView status;
    private EditText codeBox;
    private Button installBtn;
    private byte[] pickedHtml;
    private String pickedName;
    private File builtApk;

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

        codeBox = new EditText(this);
        codeBox.setHint("...ya yahan HTML code paste karo");
        codeBox.setMinLines(8);
        codeBox.setGravity(Gravity.TOP);
        codeBox.setTextSize(13);
        root.addView(codeBox);

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
        setStatus("APK ban raha hai... ⏳");
        installBtn.setEnabled(false);
        new Thread(() -> {
            try {
                builtApk = ApkMaker.buildApk(this, html);
                setStatus("✅ APK taiyaar!\nPath: " + builtApk.getAbsolutePath()
                        + "\nAb 'Install Karo' dabao. (Naya APK banane par purana generated app replace hoga — V1 limit.)");
                runOnUiThread(() -> installBtn.setEnabled(true));
            } catch (Exception e) {
                setStatus("❌ APK nahi bana: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
                    + "\nAPK is path par saved hai, file manager se bhi install kar sakte ho:\n"
                    + builtApk.getAbsolutePath());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && INSTALL_ACTION.equals(intent.getAction())) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99);
            if (st == PackageInstaller.STATUS_SUCCESS) {
                setStatus("🎉 Web App install ho gaya! Home screen par 'Web App' dekho.");
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
