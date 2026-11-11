package com.fader.tv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stores the last uncaught Java exception in the app's private files so the
 * Settings screen can show it without adb. Native crashes and low-memory kills
 * cannot be captured here, but a Java OOM or IllegalStateException will be.
 */
final class CrashLog {
    private static final String FILE = "last-crash.txt";
    private static final String TAG = "FaderCrash";
    private static boolean installed;

    private CrashLog() { }

    static void install(final Context context) {
        if(installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter stack = new StringWriter();
                error.printStackTrace(new PrintWriter(stack));
                Runtime runtime = Runtime.getRuntime();
                long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
                long maxMb = runtime.maxMemory() / (1024L * 1024L);
                String text = "Fader TV crash\n"
                    + "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "\n"
                    + "Thread: " + thread.getName() + "\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n"
                    + "Memory: " + usedMb + " MB used of " + maxMb + " MB\n\n"
                    + stack;
                write(context, text);
                Log.e(TAG, text);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, FILE);
    }

    private static void write(Context context, String text) {
        File target = file(context);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    static boolean has(Context context) {
        return file(context).isFile();
    }

    static String read(Context context) {
        File target = file(context);
        if (!target.isFile()) return "";
        try (FileInputStream in = new FileInputStream(target)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void clear(Context context) {
        file(context).delete();
    }
}
