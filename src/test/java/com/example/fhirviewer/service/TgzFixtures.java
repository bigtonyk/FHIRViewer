package com.example.fhirviewer.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Builds minimal npm-style .tgz archives (ustar tar + gzip) for tests.
 */
final class TgzFixtures {

    private TgzFixtures() {
    }

    /** Writes a .tgz containing the given entries (entry path -> UTF-8 text). */
    static void writeTgz(Path target, Map<String, String> entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            writeTarEntry(tar, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        tar.write(new byte[1024]); // end-of-archive: two zero blocks
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(target))) {
            gz.write(tar.toByteArray());
        }
    }

    /** Appends one ustar tar entry (512-byte header, data, zero padding). */
    private static void writeTarEntry(ByteArrayOutputStream out, String name, byte[] data) {
        byte[] header = new byte[512];
        putFixed(header, 0, name, 100);
        putOctal(header, 100, 0644, 8);          // mode
        putOctal(header, 108, 0, 8);             // uid
        putOctal(header, 116, 0, 8);             // gid
        putOctal(header, 124, data.length, 12);  // size
        putOctal(header, 136, 0, 12);            // mtime
        Arrays.fill(header, 148, 156, (byte) ' '); // checksum placeholder
        header[156] = '0';                        // typeflag: regular file
        putFixed(header, 257, "ustar", 6);
        putFixed(header, 263, "00", 2);

        long sum = 0;
        for (byte b : header) {
            sum += b & 0xffL;
        }
        putFixed(header, 148, String.format("%06o", sum), 6);
        header[154] = 0;
        header[155] = ' ';

        out.write(header, 0, 512);
        out.write(data, 0, data.length);
        int padding = (512 - (data.length % 512)) % 512;
        out.write(new byte[padding], 0, padding);
    }

    private static void putFixed(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] header, int offset, long value, int length) {
        String digits = Long.toOctalString(value);
        StringBuilder field = new StringBuilder();
        while (field.length() + digits.length() < length - 1) {
            field.append('0');
        }
        field.append(digits).append('\0');
        putFixed(header, offset, field.toString(), length);
    }
}