package com.codeoj.codeojbackendcodesandbox.service.impl.nativejava;

import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;

/** Local trusted samples only: explicit main-thread attribution, never stderr matching. */
public final class NativeLauncher {
    private NativeLauncher() { }

    public static void main(String[] args) throws Exception {
        Path receipt = Paths.get(args[2]);
        String status = "PLATFORM", detail = "ENTRY_POINT_UNAVAILABLE";
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Paths.get(args[0]).toUri().toURL()}, null)) {
            Class<?> entry = Class.forName(args[1], false, loader);
            Method main = entry.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
                NativeReceipt.write(receipt, args[3], status, detail);return;
            }
            main.setAccessible(true); // Preserve package-private Main supported by the java launcher.
            Thread.currentThread().setContextClassLoader(loader);
            try {
                main.invoke(null, (Object) new String[0]);
                status = "OK";detail = "MAIN_RETURNED";
            } catch (InvocationTargetException userFailure) {
                Throwable cause = userFailure.getCause();
                if (cause != null && !platformFailure(cause)) { status = "RE";detail = "USER_MAIN_EXCEPTION"; }
                else { detail = "JVM_OR_LINKAGE_FAILURE"; }
            } catch (ExceptionInInitializerError initialization) {
                Throwable cause = initialization.getException();
                if (cause != null && !platformFailure(cause)) { status = "RE";detail = "USER_CLASS_INITIALIZATION_EXCEPTION"; }
                else { detail = "JVM_OR_LINKAGE_FAILURE"; }
            }
        } catch (Throwable unavailable) {
            // Includes VM/linkage/bootstrap errors. Do not publish exception messages or stack traces.
            status = "PLATFORM";detail = "JVM_OR_ENTRY_POINT_FAILURE";
        }
        NativeReceipt.write(receipt, args[3], status, detail);
    }

    private static boolean platformFailure(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath || failure instanceof LinkageError;
    }
}
