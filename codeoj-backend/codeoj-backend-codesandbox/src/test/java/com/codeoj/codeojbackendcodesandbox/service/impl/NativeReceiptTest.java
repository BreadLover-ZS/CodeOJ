package com.codeoj.codeojbackendcodesandbox.service.impl;

import com.codeoj.codeojbackendcodesandbox.service.impl.nativejava.NativeReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeReceiptTest {
    @TempDir Path directory;

    @Test void receiptIsBoundToThisInvocationAndRequiresCompleteRecord() throws Exception {
        Path receipt = Files.createFile(directory.resolve("result"));
        assertThrows(IOException.class, () -> NativeReceipt.read(receipt, "current"));
        NativeReceipt.write(receipt, "current", "RE", "USER_MAIN_EXCEPTION");
        assertArrayEquals(new String[]{"RE", "USER_MAIN_EXCEPTION"}, NativeReceipt.read(receipt, "current"));
        assertThrows(IOException.class, () -> NativeReceipt.read(receipt, "another"));
        Files.write(receipt, "CODEOJ_NATIVE_V1\ncurrent\nRE\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> NativeReceipt.read(receipt, "current"));
    }

    @Test void oversizedRecordCannotCauseUnboundedRead() throws Exception {
        Path receipt = Files.write(directory.resolve("large"), new byte[8193]);
        assertThrows(IOException.class, () -> NativeReceipt.read(receipt, "current"));
    }

    @Test void controlFileCannotFollowAContestantSymlink() throws Exception {
        Path target = Files.createFile(directory.resolve("real"));
        Path link = Files.createSymbolicLink(directory.resolve("link"), target);
        assertThrows(IOException.class, () -> NativeReceipt.write(link, "current", "OK", "MAIN_RETURNED"));
        assertThrows(IOException.class, () -> NativeReceipt.read(link, "current"));
    }
}
