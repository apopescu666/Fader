package com.fader.tv;

import java.util.*;

/** Crossfade lengths, natural-order library queueing, tag-less metadata and DIDL-Lite parsing. */
public class P04Test {
    static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    static void eq(Object a, Object b, String what) {
        if (!Objects.equals(a, b)) throw new AssertionError(what + " " + a + " != " + b);
    }

    public static void main(String[] args) {
        // Crossfade cycles through all supported lengths up to 10s, then back to 0 (no crossfade).
        eq(CrossfadePolicy.next(0), 3, "cf 0->3");
        eq(CrossfadePolicy.next(3), 5, "cf 3->5");
        eq(CrossfadePolicy.next(5), 8, "cf 5->8");
        eq(CrossfadePolicy.next(8), 10, "cf 8->10");
        eq(CrossfadePolicy.next(10), 0, "cf 10->0");
        eq(CrossfadePolicy.clamp(7), 8, "cf clamp");
        eq(CrossfadePolicy.clamp(9), 10, "cf clamp high");
        CrossfadePolicy.valid(0); CrossfadePolicy.valid(3); CrossfadePolicy.valid(5); CrossfadePolicy.valid(8); CrossfadePolicy.valid(10);
        check(CrossfadePolicy.valid(8), "cf 8 valid");
        check(CrossfadePolicy.valid(10), "cf 10 valid");
        check(!CrossfadePolicy.valid(4), "cf 4 invalid");

        // Natural order: numbers compare by value, not lexicographically.
        List<String> in = new ArrayList<>(Arrays.asList("10", "2", "1", "A1", "a2"));
        in.sort(LibraryPolicy.NATURAL);
        eq(in.get(0), "1", "natural 0");
        eq(in.get(1), "2", "natural 1");
        eq(in.get(2), "10", "natural 2");
        eq(LibraryPolicy.clampIndex(-5, 3), 0, "clamp low");
        eq(LibraryPolicy.clampIndex(5, 3), 2, "clamp high");
        eq(LibraryPolicy.clampIndex(5, 0), 0, "clamp empty");
        eq(LibraryPolicy.cap(LibraryPolicy.MAX_QUEUE + 5), LibraryPolicy.MAX_QUEUE, "cap");
        check(LibraryPolicy.MAX_QUEUE >= 20000, "cap supports large folders");
        List<String> q = LibraryPolicy.folderQueue(
            Arrays.asList("s/d2/b.mp3", "s/d1/a.mp3"),
            Arrays.asList("s/d2", "s/z"));
        eq(q.get(0), "s/d1/a.mp3", "queue audio first");
        eq(q.get(1), "s/d2/b.mp3", "queue audio second");
        eq(q.get(2), "s/d2", "queue folder after audio");
        eq(q.get(3), "s/z", "queue folder last");

        // Tag-less filename metadata, like a native player falls back to on-disk names.
        MetaParser.Parsed p1 = MetaParser.fromName("01 - Artist - Title.mp3", "music/Album/01 - Artist - Title.mp3");
        eq(p1.title, "Title", "fname title");
        eq(p1.artist, "Artist", "fname artist");
        MetaParser.Parsed p2 = MetaParser.fromName("Song.mp3", "");
        check(p2.nonEmptyTitle() && p2.title.contains("Song"), "fname only title");
        check(MetaParser.isCoverName("folder.jpg") && MetaParser.isCoverName("cover.png")
            && !MetaParser.isCoverName("song.mp3"), "cover detection");
        // Album hints must ignore the SMB scheme/host and the generic share root.
        eq(MetaParser.pathAlbum("smb://192.168.1.10/music/Jazz/Jazz Tune.wav"), "Jazz", "album skips smb prefix");
        eq(MetaParser.pathAlbum("music/Album/01.mp3"), "Album", "album from plain path");
        eq(MetaParser.fromName("Jazz Tune.wav", "smb://127.0.0.1/music/Jazz/Jazz Tune.wav").album, "Jazz", "track album skips smb prefix");

        // DIDL-Lite: containers take precedence over items (children of one Browse).
        String folders =
            "<DIDL-Lite xmlns:dc=\"d\" xmlns:upnp=\"u\">"
            + "<container id=\"1\"><dc:title>Album A</dc:title><upnp:childCount>3</upnp:childCount></container>"
            + "<container id=\"2\"><dc:title>Album B</dc:title><upnp:childCount>1</upnp:childCount></container>"
            + "</DIDL-Lite>";
        List<DidlParser.Media> mf = DidlParser.parse(folders);
        eq(mf.size(), 2, "didl folders count");
        eq(mf.get(0).title, "Album A", "didl folder title");
        eq(mf.get(0).container, true, "didl folder flag");

        String tracks =
            "<DIDL-Lite xmlns:dc=\"d\" xmlns:upnp=\"u\">"
            + "<item id=\"10\"><dc:title>Track One</dc:title>"
            + "<upnp:artist>Artist</upnp:artist><upnp:album>Album A</upnp:album>"
            + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
            + "<dc:duration>2100000</dc:duration>"
            + "<res protocolInfo=\"http-get:*:audio/mp3\">http://nas/music/AlbumA/01.mp3</res>"
            + "</item>"
            + "<item id=\"11\"><dc:title>Track Two</dc:title>"
            + "<upnp:artist>Artist</upnp:artist>"
            + "<res protocolInfo=\"http-get:*:audio/flac\">http://nas/music/AlbumA/02.flac</res>"
            + "</item>"
            + "</DIDL-Lite>";
        List<DidlParser.Media> mt = DidlParser.parse(tracks);
        eq(mt.size(), 2, "didl tracks count");
        eq(mt.get(0).title, "Track One", "didl track title");
        eq(mt.get(0).artist, "Artist", "didl track artist");
        eq(mt.get(0).album, "Album A", "didl track album");
        eq(mt.get(0).durationMs, 2100000L, "didl duration ms");
        eq(mt.get(0).resource, "http://nas/music/AlbumA/01.mp3", "didl resource url");
        eq(mt.get(1).durationMs, 0L, "didl missing duration");

        // Malformed / empty DIDL must not throw.
        eq(DidlParser.parse(null).size(), 0, "didl null");
        eq(DidlParser.parse("not xml at all").size(), 0, "didl garbage");
        System.out.println("PASS: crossfade to 10s, natural-order queue, filename metadata, album path, DIDL-Lite parse");
    }
}
