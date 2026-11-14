package com.fader.mobil.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort tag-less metadata from a filename and folder naming.
 * Native TV players fall back to on-disk naming when embedded tags are empty;
 * this mirrors common "Artist - Title", "Track - Artist - Title" and number prefixes.
 * Never throws; returns empty fields when nothing is recognized.
 */
final class MetaParser {
    private MetaParser() { }

    private static final Pattern DISC_TRACK =
        Pattern.compile("^\\s*(\\d{1,2})\\s*[-.]\\s*(\\d{1,3})\\s*-\\s*");
    private static final Pattern TRACK_PREFIX =
        Pattern.compile("^\\s*(\\d{1,3})\\s*[-.]\\s*");
    private static final Pattern ARTIST_TITLE =
        Pattern.compile("^(.+?)\\s*[-]\\s*(.+)$");

    static final class Parsed {
        final String artist, title, album;
        Parsed(String artist, String title, String album) { this.artist = artist; this.title = title; this.album = album; }
        boolean nonEmptyArtist() { return artist != null && !artist.isEmpty(); }
        boolean nonEmptyTitle() { return title != null && !title.isEmpty(); }
        boolean nonEmptyAlbum() { return album != null && !album.isEmpty(); }
    }

    /** Parse the base name (no extension). {@code path} is the full share path for album/context. */
    static Parsed fromName(String baseName, String path) {
        String b = baseName == null ? "" : baseName.trim();
        int dot = b.lastIndexOf('.');
        if (dot > 0 && dot > b.lastIndexOf('/')) b = b.substring(0, dot); // strip the audio extension
        b = clean(b);
        if (b.isEmpty()) return new Parsed("", "", pathAlbum(path));
        String stem = b;
        String track = "";
        String rest = b;
        Matcher disc = DISC_TRACK.matcher(b);
        if (disc.find()) {
            rest = b.substring(disc.end());
        } else {
            Matcher tr = TRACK_PREFIX.matcher(b);
            if (tr.find()) {
                track = tr.group(1);
                rest = b.substring(tr.end());
            }
        }
        rest = clean(rest);
        String artist, title;
        Matcher at = ARTIST_TITLE.matcher(rest);
        if (at.find()) {
            artist = clean(at.group(1));
            title = clean(at.group(2));
        } else {
            artist = "";
            title = clean(stem);
        }
        // A "Track - Artist - Title" already split into track and "Artist - Title".
        if (track.isEmpty() && !rest.isEmpty() && rest.equals(stem)) {
            // No leading number detected; the artist/title split stands.
        }
        return new Parsed(artist, title.isEmpty() ? clean(stem) : title, pathAlbum(path));
    }

    /** Album hint from a "Disc/Album/..." path segment, best effort. */
    static String pathAlbum(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path;
        int scheme = p.indexOf("://");
        if (scheme >= 0) {
            int slash = p.indexOf('/', scheme + 3);
            p = slash < 0 ? "" : p.substring(slash);
        }
        String[] parts = p.split("/");
        // Heuristic: the first path segment that is not a known root-ish name is treated as album.
        for (String part : parts) {
            String c = clean(part);
            if (c.isEmpty()) continue;
            if (isRootName(c)) continue;
            return c;
        }
        return "";
    }

    private static boolean isRootName(String c) {
        String s = c.toLowerCase(Locale.ROOT);
        return s.equals("music") || s.equals("music/") || s.equals("nas") || s.equals("share")
            || s.equals("shares") || s.equals("public") || s.equals("media") || s.equals("home");
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('/', ' ').replace('\\', ' ').trim();
    }

    /** True when a sibling file in a folder acts as folder artwork. */
    static boolean isCoverName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals(".metadata")) return false;
        return n.equals("folder.jpg") || n.equals("cover.jpg") || n.equals("folder.png")
            || n.equals("cover.png") || n.equals("folder.jpeg") || n.equals("cover.jpeg")
            || n.equals("front.jpg") || n.equals("front.png")
            || n.equals("album.jpg") || n.equals("album.png") || n.equals("albumart.jpg")
            || n.equals("albumart.png") || n.equals("artwork.jpg") || n.equals("artwork.png")
            || n.equals("thumb.jpg") || n.equals("cover.bmp");
    }
}
