package com.codeoj.codeojbackendcodesandbox.service.impl.nativejava;

import javax.tools.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Runs in a separate, bounded JDK process. Never initializes contestant classes. */
public final class NativeCompiler {
    private NativeCompiler() { }

    public static void main(String[] args) throws Exception {
        Path source = Paths.get(args[0]), classes = Paths.get(args[1]), receipt = Paths.get(args[2]);
        // Native callers pre-create this file; a fresh isolated artifact volume does not.
        try { Files.createFile(receipt); }
        catch (FileAlreadyExistsException existing) { /* NativeReceipt rejects symbolic links. */ }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) { NativeReceipt.write(receipt, args[3], "PLATFORM", "COMPILER_UNAVAILABLE");return; }
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            // A virtual source has no physical public-type filename restriction. javac handles
            // comments, literals, modifiers and Unicode; the actual class metadata picks entry.
            Boolean compiled = compiler.getTask(null, manager, null,
                    Arrays.asList("-encoding", "UTF-8", "-proc:none", "-classpath", classes.toString(),
                            "-sourcepath", classes.toString(), "-d", classes.toString()), null,
                    Collections.singletonList(new Source(source))).call();
            if (!Boolean.TRUE.equals(compiled)) { NativeReceipt.write(receipt, args[3], "CE", "COMPILATION_FAILED");return; }
        }
        List<String> publicTypes = new ArrayList<>(), mainTypes = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null);
             Stream<Path> files = Files.walk(classes)) {
            List<Path> outputs = files.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
            for (Path output : outputs) {
                String relative = classes.relativize(output).toString();
                String name = relative.substring(0, relative.length() - 6).replace(java.io.File.separatorChar, '.');
                Class<?> type = Class.forName(name, false, loader);
                if (type.getEnclosingClass() != null) { continue; }
                if (Modifier.isPublic(type.getModifiers())) { publicTypes.add(name); }
                if (type.getSimpleName().equals("Main")) { mainTypes.add(name); }
            }
        }
        if (publicTypes.size() > 1) { NativeReceipt.write(receipt, args[3], "CE", "MULTIPLE_PUBLIC_ENTRY_TYPES");return; }
        String entry = publicTypes.size() == 1 ? publicTypes.get(0) : mainTypes.size() == 1 ? mainTypes.get(0) : null;
        NativeReceipt.write(receipt, args[3], entry == null ? "PLATFORM" : "OK",
                entry == null ? "ENTRY_POINT_UNAVAILABLE" : entry);
    }

    public static final class Source extends SimpleJavaFileObject {
        private final Path path;
        Source(Path path) { super(path.toUri(), Kind.SOURCE);this.path = path; }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        }
        @Override public boolean isNameCompatible(String simpleName, Kind kind) {
            return kind == Kind.SOURCE && !"package-info".equals(simpleName) && !"module-info".equals(simpleName);
        }
    }
}
