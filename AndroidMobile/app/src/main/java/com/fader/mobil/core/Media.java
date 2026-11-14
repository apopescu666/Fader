package com.fader.mobil.core;

import java.util.Locale;

/** Shared audio helpers and callback types used by the read-only clients. */
final class Media {
    private Media() { }

    interface Cancel { boolean cancelled(); }

    interface Progress { void update(int folders, int songs); }

    interface Partial { void update(Library library); }

    static boolean audio(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".aif")
                || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".opus");
    }
}
