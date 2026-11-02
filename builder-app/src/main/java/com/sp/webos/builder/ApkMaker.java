package com.sp.webos.builder;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds a signed APK on the phone itself:
 *   template.apk  ->  replace assets/index.html with the user's HTML
 *                 ->  re-sign with the built-in key (apksig)
 * No PC, no server, no internet needed.
 */
public class ApkMaker {

    private static final String TEMPLATE_ASSET = "template.apk";
    private static final String KEY_ASSET = "signing_key.b64";
    private static final char[] KEY_PASS = "spwebos123".toCharArray();
    private static final String HTML_ENTRY = "assets/index.html";

    /** Counts bytes written so STORED entries can be 4-byte aligned. */
    private static class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        long count = 0;
        CountingOutputStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); count += len;
        }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }

    public static File buildApk(Context ctx, byte[] htmlBytes) throws Exception {
        File workDir = new File(ctx.getCacheDir(), "apkbuild");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        File template = new File(workDir, "template.apk");
        copyAsset(ctx, TEMPLATE_ASSET, template);

        File unsigned = new File(workDir, "unsigned.apk");
        rewriteZip(template, unsigned, htmlBytes);

        File outDir = ctx.getExternalFilesDir(null);
        if (outDir == null) outDir = ctx.getFilesDir();
        File signed = new File(outDir, "WebApp.apk");
        signApk(ctx, unsigned, signed);
        return signed;
    }

    private static void copyAsset(Context ctx, String name, File dest) throws IOException {
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    /**
     * Copies the template ZIP, dropping old signatures (META-INF) and swapping
     * in the user's HTML. Entry compression methods are preserved; STORED
     * entries (e.g. resources.arsc) are kept STORED and 4-byte aligned,
     * which Android requires for targetSdk 30+.
     */
    private static void rewriteZip(File in, File out, byte[] htmlBytes) throws IOException {
        boolean htmlWritten = false;
        CountingOutputStream counting = new CountingOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)));
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(in));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("META-INF/")) continue; // old signature, dropped
                byte[] data = readAll(zin);
                if (name.equals(HTML_ENTRY)) {
                    data = htmlBytes;
                    htmlWritten = true;
                }
                writeEntry(zout, counting, name, data, e.getMethod());
                zin.closeEntry();
            }
            if (!htmlWritten) {
                writeEntry(zout, counting, HTML_ENTRY, htmlBytes, ZipEntry.DEFLATED);
            }
        }
    }

    private static void writeEntry(ZipOutputStream zout, CountingOutputStream counting,
                                   String name, byte[] data, int method) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        if (method == ZipEntry.STORED) {
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(data.length);
            ne.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            ne.setCrc(crc.getValue());
            // Align entry data to a 4-byte boundary using the extra field.
            long headerStart = counting.count;
            int nameLen = ne.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int dataStart = (int) (headerStart + 30 + nameLen);
            int pad = (4 - (dataStart % 4)) % 4;
            if (pad > 0) ne.setExtra(new byte[pad]);
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        zout.putNextEntry(ne);
        zout.write(data);
        zout.closeEntry();
    }

    private static void signApk(Context ctx, File unsigned, File signed) throws Exception {
        byte[] b64 = readAll(ctx.getAssets().open(KEY_ASSET));
        byte[] keyBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(keyBytes), KEY_PASS);
        String alias = null;
        java.util.Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (ks.isKeyEntry(a)) { alias = a; break; }
        }
        if (alias == null) throw new IllegalStateException("Signing key not found in keystore");
        PrivateKey key = (PrivateKey) ks.getKey(alias, KEY_PASS);
        Certificate cert = ks.getCertificate(alias);
        List<X509Certificate> certs = new ArrayList<>();
        certs.add((X509Certificate) cert);

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "SP Web OS", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setMinSdkVersion(24)
                .setCreatedBy("SP WEB OS")
                .build()
                .sign();
    }
}
