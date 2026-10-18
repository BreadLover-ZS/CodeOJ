package com.codeoj.codeojbackendcodesandbox.service.impl.nativejava;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Private control file, separate from contestant stdout/stderr. Not a hostile-code boundary. */
public final class NativeReceipt {
    private static final String VERSION = "CODEOJ_NATIVE_V1";
    private static final int MAX_BYTES = 8192;

    private NativeReceipt() { }

    public static void write(Path path, String nonce, String status, String detail) throws IOException {
        String record = VERSION + "\n" + nonce + "\n" + status + "\n" + detail + "\n";
        byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES || detail.contains("\n") || detail.contains("\r")) {
            throw new IOException("INVALID_NATIVE_RECEIPT");
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) { channel.write(buffer); }
        }
    }

    public static String[] read(Path path, String nonce) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded read */ }
        }
        if (buffer.position() > MAX_BYTES) { throw new IOException("OVERSIZED_NATIVE_RECEIPT"); }
        buffer.flip();
        String[] fields = StandardCharsets.UTF_8.newDecoder().decode(buffer).toString().split("\n", -1);
        if (fields.length != 5 || !VERSION.equals(fields[0]) || !nonce.equals(fields[1])
                || !fields[4].isEmpty() || fields[3].isEmpty()) {
            throw new IOException("INVALID_NATIVE_RECEIPT");
        }
        return new String[]{fields[2], fields[3]};
    }
}
