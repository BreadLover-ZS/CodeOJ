package com.codeoj.codeojbackendcodesandbox.service.impl.nativejava;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;

/** Separate contestant JVM. Exit status is observed by the root-only container supervisor. */
public final class IsolatedLauncher {
    private IsolatedLauncher() { }
    public static void main(String[] args) {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Paths.get(args[0]).toUri().toURL()}, null)) {
            Class<?> entry = Class.forName(args[1], false, loader);
            Method main = entry.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) { System.exit(43); }
            main.setAccessible(true);
            Thread.currentThread().setContextClassLoader(loader);
            try { main.invoke(null, (Object) new String[0]); }
            catch (InvocationTargetException | ExceptionInInitializerError failure) { System.exit(42); }
        } catch (Throwable unavailable) { System.exit(43); }
    }
}
