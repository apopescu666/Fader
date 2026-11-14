package com.fader.mobil.core;

/** One file or folder inside a share. */
final class Entry {
    final String name, url;
    final boolean directory;
    final long size, modified;
    /** Duration in milliseconds when the source knows it (MediaStore does); 0 otherwise. */
    final long durationMs;
    /** Tags supplied by indexed sources such as MediaStore; null when unknown. */
    final String mediaTitle, mediaArtist, mediaAlbum;

    Entry(String name, String url, boolean directory, long size, long modified) {
        this(name, url, directory, size, modified, 0, null, null, null);
    }

    Entry(String name, String url, boolean directory, long size, long modified, long durationMs) {
        this(name, url, directory, size, modified, durationMs, null, null, null);
    }

    Entry(String name, String url, boolean directory, long size, long modified, long durationMs,
          String mediaTitle, String mediaArtist, String mediaAlbum) {
        this.name = name;
        this.url = url;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
        this.durationMs = durationMs;
        this.mediaTitle = mediaTitle;
        this.mediaArtist = mediaArtist;
        this.mediaAlbum = mediaAlbum;
    }
}
