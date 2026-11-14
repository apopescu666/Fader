package com.fader.mobil.core;

final class TrackItem {
    final Stream nas;
    final Entry entry;
    /** Folder the user opened, used by the local metadata index. */
    final String folderUrl;
    volatile String title, artist="Unknown artist";
    volatile String album="";
    volatile int duration;
    volatile boolean hasCover;
    TrackItem(Stream n, Entry e) { this(n, e, null); }
    TrackItem(Stream n, Entry e, String folderUrl) {
        nas = n; entry = e; this.folderUrl = folderUrl;
        String parent = e.url == null ? "" : e.url;
        int slash = parent.lastIndexOf('/');
        parent = slash > 0 ? parent.substring(0, slash + 1) : "";
        MetaParser.Parsed p = MetaParser.fromName(e.name, parent);
        title = decode(p.nonEmptyTitle() ? p.title : e.name.replaceFirst("\\.[^.]+$", ""));
        artist = decode(p.nonEmptyArtist() ? p.artist : "Unknown artist");
        album = decode(p.album);
    }
    private static String decode(String value) {
        if (value == null || !value.contains("%")) return value;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    String key() { return entry.url+"|"+entry.size+"|"+entry.modified; }
}
