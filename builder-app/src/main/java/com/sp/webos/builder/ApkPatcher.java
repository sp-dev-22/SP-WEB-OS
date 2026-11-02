package com.sp.webos.builder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every generated app its own identity:
 *
 *  - AndroidManifest.xml (binary AXML): the string pool is rebuilt with
 *        package  "com.sp.webos.webapp"  ->  the user's new package id
 *        label    "Web App"              ->  the user's app name
 *    String indexes never change (strings are replaced in place),
 *    so all other chunks keep working untouched.
 *
 *  - resources.arsc: the package chunk's fixed 256-byte name field is
 *    overwritten with the new package id (same length layout, in place).
 *
 * The activity class string is deliberately NOT touched: it already
 * points at the real class inside the template's dex.
 */
public class ApkPatcher {

    static final String OLD_PACKAGE = "com.sp.webos.webapp";
    static final String OLD_LABEL = "Web App";
    static final String TEMPLATE_ACTIVITY = "com.sp.webos.template.MainActivity";

    // ------------------------------------------------------------- AXML

    public static byte[] patchManifest(byte[] axml, String newPackage, String newLabel)
            throws IOException {
        if (axml.length < 16 || u16(axml, 0) != 0x0003) {
            throw new IOException("Template manifest pehchana nahi gaya (AXML header)");
        }
        int poolOff = 8; // first chunk after the file header
        if (u16(axml, poolOff) != 0x0001) {
            throw new IOException("Template manifest me string pool nahi mila");
        }
        int poolSize = (int) u32(axml, poolOff + 4);
        byte[] newPool = rebuildStringPool(axml, poolOff, poolSize, newPackage, newLabel);

        int total = 8 + newPool.length + (axml.length - poolOff - poolSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        out.write(axml, 0, 4);            // type + headerSize
        writeU32(out, total);             // new file size
        out.write(newPool);
        out.write(axml, poolOff + poolSize, axml.length - poolOff - poolSize);
        return out.toByteArray();
    }

    private static byte[] rebuildStringPool(byte[] data, int off, int size,
                                            String newPackage, String newLabel)
            throws IOException {
        int stringCount = (int) u32(data, off + 8);
        int styleCount = (int) u32(data, off + 12);
        int flags = (int) u32(data, off + 16);
        boolean utf8 = (flags & 0x100) != 0;
        int stringsStart = (int) u32(data, off + 20);
        int stylesStart = (int) u32(data, off + 24);
        int headerSize = u16(data, off + 2);

        // ---- decode all strings ----
        List<String> strings = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int rel = (int) u32(data, off + headerSize + 4 * i);
            int pos = off + stringsStart + rel;
            strings.add(utf8 ? readUtf8String(data, pos) : readUtf16String(data, pos));
        }

        // ---- apply the identity replacements (exact matches only) ----
        for (int i = 0; i < strings.size(); i++) {
            String s = strings.get(i);
            if (s.equals(OLD_PACKAGE) && newPackage != null) {
                strings.set(i, newPackage);
            } else if (s.equals(OLD_LABEL) && newLabel != null) {
                strings.set(i, newLabel);
            } else if (s.equals(OLD_PACKAGE + ".MainActivity") || s.equals(".MainActivity")) {
                // Safety net: the activity must keep pointing at the real
                // class in the dex, whatever package the app gets.
                strings.set(i, TEMPLATE_ACTIVITY);
            }
        }

        // ---- re-encode the string data in the pool's original encoding ----
        ByteArrayOutputStream strData = new ByteArrayOutputStream();
        int[] newOffsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            newOffsets[i] = strData.size();
            if (utf8) writeUtf8String(strData, strings.get(i));
            else writeUtf16String(strData, strings.get(i));
        }
        byte[] strBytes = strData.toByteArray();

        // styles (our template has none) are copied verbatim; their
        // offsets are relative to the start of the style data.
        byte[] styleOffsets = new byte[4 * styleCount];
        byte[] styleData = new byte[0];
        if (styleCount > 0) {
            System.arraycopy(data, off + headerSize + 4 * stringCount, styleOffsets, 0, styleOffsets.length);
            int sLen = size - stylesStart;
            styleData = new byte[sLen];
            System.arraycopy(data, off + stylesStart, styleData, 0, sLen);
        }

        int newStringsStart = headerSize + 4 * stringCount + 4 * styleCount;
        int newStylesStart = styleCount > 0 ? newStringsStart + strBytes.length : 0;
        int rawSize = newStringsStart + strBytes.length + styleData.length;
        int newSize = (rawSize + 3) & ~3; // chunks are 4-byte aligned

        ByteArrayOutputStream pool = new ByteArrayOutputStream(newSize);
        writeU16(pool, 0x0001);
        writeU16(pool, headerSize);
        writeU32(pool, newSize);
        writeU32(pool, stringCount);
        writeU32(pool, styleCount);
        writeU32(pool, flags);
        writeU32(pool, newStringsStart);
        writeU32(pool, newStylesStart);
        for (int o : newOffsets) writeU32(pool, o);
        pool.write(styleOffsets);
        pool.write(strBytes);
        pool.write(styleData);
        while (pool.size() < newSize) pool.write(0);
        return pool.toByteArray();
    }

    private static String readUtf8String(byte[] d, int pos) throws IOException {
        int[] p = {pos};
        readUtf8Len(d, p); // char length (not needed)
        int byteLen = readUtf8Len(d, p);
        if (p[0] + byteLen > d.length) throw new IOException("String pool kharab hai (UTF-8)");
        String s = new String(d, p[0], byteLen, StandardCharsets.UTF_8);
        return s;
    }

    private static int readUtf8Len(byte[] d, int[] p) {
        int b = d[p[0]++] & 0xFF;
        if ((b & 0x80) != 0) {
            int b2 = d[p[0]++] & 0xFF;
            return ((b & 0x7F) << 8) | b2;
        }
        return b;
    }

    private static void writeUtf8String(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeUtf8Len(out, s.length());
        writeUtf8Len(out, bytes.length);
        out.write(bytes);
        out.write(0);
    }

    private static void writeUtf8Len(ByteArrayOutputStream out, int len) {
        if (len > 0x7F) {
            out.write(0x80 | (len >> 8));
            out.write(len & 0xFF);
        } else {
            out.write(len);
        }
    }

    private static String readUtf16String(byte[] d, int pos) throws IOException {
        int len = u16(d, pos);
        pos += 2;
        if ((len & 0x8000) != 0) {
            int hi = len & 0x7FFF;
            len = (hi << 16) | u16(d, pos);
            pos += 2;
        }
        if (pos + len * 2 > d.length) throw new IOException("String pool kharab hai (UTF-16)");
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) u16(d, pos + 2 * i));
        return sb.toString();
    }

    private static void writeUtf16String(ByteArrayOutputStream out, String s) {
        int len = s.length();
        if (len >= 0x8000) {
            writeU16(out, 0x8000 | (len >> 16));
            writeU16(out, len & 0xFFFF);
        } else {
            writeU16(out, len);
        }
        for (int i = 0; i < len; i++) writeU16(out, s.charAt(i));
        writeU16(out, 0);
    }

    // ------------------------------------------------------------- ARSC

    public static byte[] patchArscPackage(byte[] arsc, String newPackage) throws IOException {
        if (arsc.length < 16 || u16(arsc, 0) != 0x0002) {
            throw new IOException("resources.arsc pehchana nahi gaya");
        }
        // Walk every chunk after the ResTable header.
        int off = u16(arsc, 2);
        while (off + 8 <= arsc.length) {
            int type = u16(arsc, off);
            long chunkSize = u32(arsc, off + 4);
            if (chunkSize < 8 || off + chunkSize > arsc.length) break;
            if (type == 0x0200) { // ResTable_package
                // name = fixed char[128] UTF-16 field at chunk offset 12
                int nameOff = off + 12;
                if (nameOff + 256 > arsc.length) {
                    throw new IOException("resources.arsc package name field chhota hai");
                }
                if (newPackage.length() > 127) {
                    throw new IOException("Package id bahut lamba hai");
                }
                byte[] out = arsc.clone();
                for (int i = 0; i < 128; i++) {
                    int c = i < newPackage.length() ? newPackage.charAt(i) : 0;
                    out[nameOff + 2 * i] = (byte) (c & 0xFF);
                    out[nameOff + 2 * i + 1] = (byte) ((c >> 8) & 0xFF);
                }
                return out;
            }
            off += (int) chunkSize;
        }
        // No package chunk anywhere: this arsc carries no package name
        // (a resource-less table is just a header + string pool). The
        // binary manifest alone then defines the app's identity,
        // so there is nothing to sync — return the file untouched.
        return arsc;
    }

    // ------------------------------------------------------------- helpers

    private static int u16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int off) {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
                | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }
}
