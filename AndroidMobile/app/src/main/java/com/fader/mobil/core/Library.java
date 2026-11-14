package com.fader.mobil.core;

import java.util.List;

/** A browsable slice of a share: immediate subfolders plus the folder's audio files. */
final class Library {
    final String url, name;
    final List<Entry> folders;
    final List<Entry> audio;
    final Entry cover;
    final boolean truncated;

    Library(String url, String name, List<Entry> folders, List<Entry> audio, Entry cover, boolean truncated) {
        this.url = url;
        this.name = name;
        this.folders = folders;
        this.audio = audio;
        this.cover = cover;
        this.truncated = truncated;
    }
}
