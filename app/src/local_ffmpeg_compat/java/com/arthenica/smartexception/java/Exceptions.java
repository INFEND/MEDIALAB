package com.arthenica.smartexception.java;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Small compatibility adapter for FFmpegKit when a local AAR is imported without its POM.
 * The FFmpegKit version used by this project calls only these two methods.
 * The original smart-exception-java dependency offers more formatting options.
 */
public final class Exceptions {
    private Exceptions() { }

    public static void registerRootPackage(String packageName) {
        // The root-package hint controls stack trace filtering in the upstream library.
        // Using an unfiltered trace here is intentional and preserves diagnostic detail.
    }

    public static String getStackTraceString(Throwable cause) {
        if (cause == null) return "";
        StringWriter text = new StringWriter();
        PrintWriter writer = new PrintWriter(text);
        cause.printStackTrace(writer);
        writer.flush();
        return "\n" + text.toString();
    }
}
