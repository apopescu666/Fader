package com.fader.tv;

final class TrackItem {
    final Stream nas;
    final NasClient.Entry entry;
    /** Folder the user opened, used by the local metadata index. */
    final String folderUrl;
    volatile String title, artist="Unknown artist";
    volatile String album="";
    volatile int duration;
    volatile boolean hasCover;
    TrackItem(Stream n, NasClient.Entry e) { this(n, e, null); }
    TrackItem(Stream n, NasClient.Entry e, String folderUrl) {
        nas = n; entry = e; this.folderUrl = folderUrl;
        String parent = e.url == null ? "" : e.url;
        int slash = parent.lastIndexOf('/');
        parent = slash > 0 ? parent.substring(0, slash + 1) : "";
        MetaParser.Parsed p = MetaParser.fromName(e.name, parent);
        title = p.nonEmptyTitle() ? p.title : e.name.replaceFirst("\\.[^.]+$", "");
        artist = p.nonEmptyArtist() ? p.artist : "Unknown artist";
        album = p.album;
    }
    String key() { return entry.url+"|"+entry.size+"|"+entry.modified; }
}
