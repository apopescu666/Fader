package com.fader.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, defensive UPnP/DLNA DIDL-Lite reader for ContentDirectory Browse responses.
 *
 * This is intentionally not a full XML namespace parser: DIDL-Lite from real devices is irregular,
 * so fields are extracted per-item with bounded regexes. Only audio items and containers the caller
 * can act on are emitted. Never throws on malformed input.
 */
final class DidlParser {
    private DidlParser() { }

    static final class Media {
        final String id, cls, title, artist, album;
        final long size; final long durationMs;
        final String resource;       // first res URL, or null
        final boolean container;
        Media(String id, String cls, String title, String artist, String album,
              long size, long durationMs, String resource, boolean container) {
            this.id = id; this.cls = cls; this.title = title; this.artist = artist; this.album = album;
            this.size = size; this.durationMs = durationMs; this.resource = resource; this.container = container;
        }
        static Media ofItem(String id, String title, String artist, String album, long durationMs, String resource) {
            return new Media(id, "object.item.audioItem.musicTrack", title, artist, album, 0, durationMs, resource, false);
        }
        static Media ofFolder(String id, String title, int childCount) {
            return new Media(id, "object.container", title, "", "", 0, 0, null, true);
        }
    }

    private static final Pattern ITEM = Pattern.compile(
        "<item[ >].*?</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTAINER = Pattern.compile(
        "<container[ >].*?</container>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RES_URL = Pattern.compile("<res[^>]*>([^<]+)</res>", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("[ >]id=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static String idOf(String block) {
        Matcher m = ID.matcher(block.length() < 512 ? block : block.substring(0, 512));
        return m.find() ? m.group(1).trim() : null;
    }

    public static List<Media> parse(String didl) {
        List<Media> out = new ArrayList<>();
        if (didl == null || didl.isEmpty()) return out;
        // A Browse returns the direct children of one container. Children are either folders
        // (containers) or audio items. Prefer the container level and only fall back to items
        // when no container is present, so a single subtree is not listed twice.
        Matcher container = CONTAINER.matcher(didl);
        while (container.find()) out.add(parseContainer(container.group(0)));
        if (out.isEmpty()) {
            Matcher m = ITEM.matcher(didl);
            while (m.find()) {
                Media media = parseItem(m.group(0));
                if (media != null) out.add(media);
            }
        }
        return out;
    }

    private static long durationMs(String block) {
        // dcm:duration and upnp:albumInfo both carry "<dc:duration>ms</dc:duration>" (milliseconds).
        Integer ms = firstTagNumber(block, "(?:dc|dcm|metadata):duration");
        if (ms != null) return ms;
        return 0;
    }

    private static String firstTag(String block, String tag) {
        Pattern p = Pattern.compile("<" + tag + "([^>]*)>([^<]*)</" + tag + ">",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(block);
        return m.find() ? m.group(2).trim() : null;
    }

    private static Integer firstTagNumber(String block, String tag) {
        String v = firstTag(block, tag);
        if (v == null) return null;
        try {
            return Integer.parseInt(v.trim().replaceAll("[^0-9]", ""));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstResUrl(String block) {
        Matcher m = RES_URL.matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Media parseItem(String block) {
        String id = idOf(block);
        String title = firstTag(block, "dc:title");
        String resource = firstResUrl(block);
        if (resource == null) return null;
        if (title == null || title.trim().isEmpty()) title = "Unknown";
        String artist = firstTag(block, "upnp:artist");
        String album = firstTag(block, "upnp:album");
        if (album == null) album = firstTag(block, "dc:album");
        long dur = durationMs(block);
        return Media.ofItem(id, title, artist == null ? "" : artist, album == null ? "" : album, dur, resource);
    }

    private static Media parseContainer(String block) {
        String id = idOf(block);
        String title = firstTag(block, "dc:title");
        Integer count = firstTagNumber(block, "upnp:childCount");
        return Media.ofFolder(id, title == null ? "Unknown" : title, count == null ? 0 : count);
    }
}
