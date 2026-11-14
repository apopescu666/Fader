package com.fader.mobil.core;

import android.media.MediaDataSource;
import android.net.Uri;
import android.util.Xml;
import android.util.Base64;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;

/** Read-only WebDAV (HTTPS) client, so a NAS can be browsed and streamed from anywhere. */
final class WebDavClient implements LibraryClient {
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY = "<?xml version=\"1.0\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:displayname/><d:resourcetype/><d:getcontentlength/>"
            + "</d:prop></d:propfind>";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final String root;
    private final String authHeader;

    WebDavClient(String url, String username, String password) {
        this.root = url.endsWith("/") ? url : url + "/";
        this.authHeader = buildAuth(username, password);
    }

    @Override public String rootUrl() { return root; }
    @Override public String modeLabel() { return "WebDAV"; }

    @Override public Library quickLibrary(String url, String name) throws IOException {
        List<Entry> all = list(url);
        List<Entry> folders = new ArrayList<>();
        List<Entry> audio = new ArrayList<>();
        Entry cover = null;
        for (Entry e : all) {
            if (e.directory) folders.add(e);
            else if (Media.audio(e.name)) audio.add(e);
            else if (cover == null && MetaParser.isCoverName(e.name)) cover = e;
        }
        folders.sort((a, b) -> LibraryPolicy.naturalCompare(a.name, b.name));
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.name, b.name));
        return new Library(url, name, folders, audio, cover, false);
    }

    @Override public int countAudio(String url, Media.Cancel cancel, Media.Progress progress) throws IOException {
        int count = 0, visited = 0;
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        while (!queue.isEmpty() && visited < 100000 && count < LibraryPolicy.MAX_QUEUE) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
            String dir = queue.poll();
            if (++visited > 100000) break;
            List<Entry> all;
            try { all = list(dir); } catch (Exception e) { continue; }
            for (Entry e : all) {
                if (e.directory) queue.add(e.url);
                else if (Media.audio(e.name)) count++;
            }
        }
        return count;
    }

    @Override public Library indexLibrary(String url, String name, Media.Cancel cancel, Media.Progress progress, Media.Partial partial) throws IOException {
        List<Entry> folders = new ArrayList<>();
        List<Entry> audio = new ArrayList<>();
        final Entry[] cover = {null};
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        int visited = 0;
        boolean truncated = false;
        while (!queue.isEmpty() && visited < 100000 && audio.size() < LibraryPolicy.MAX_QUEUE) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
            final String dir = queue.poll();
            if (audio.size() >= LibraryPolicy.MAX_QUEUE) { truncated = true; break; }
            if (++visited > 100000) { truncated = true; break; }
            List<Entry> all;
            try { all = list(dir); } catch (Exception e) { continue; }
            for (Entry e : all) {
                if (dir.equals(url) && cover[0] == null && MetaParser.isCoverName(e.name) && !e.directory) cover[0] = e;
                if (e.directory) {
                    if (dir.equals(url)) folders.add(e);
                    queue.add(e.url);
                } else if (Media.audio(e.name)) {
                    audio.add(e);
                    if (audio.size() >= LibraryPolicy.MAX_QUEUE) { truncated = true; break; }
                }
            }
            if (progress != null) progress.update(visited, audio.size());
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
        }
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.name, b.name));
        return new Library(url, name, folders, audio, cover[0], truncated);
    }

    @Override public MediaDataSource source(Entry entry) throws IOException {
        return new HttpSource(entry.url, authHeader);
    }

    @Override public Uri directUri(Entry entry) { return Uri.parse(entry.url); }

    @Override public java.util.Map<String, String> httpHeaders(Entry entry) {
        if (authHeader == null) return null;
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Authorization", authHeader);
        return h;
    }

    @Override public void close() throws IOException { }

    /** Basic auth header, reused by the artwork loader. */
    String authHeader() { return authHeader; }

    // ---- PROPFIND ----

    private List<Entry> list(String url) throws IOException {
        List<Entry> out = new ArrayList<>();
        String xml = propfind(url);
        parse(xml, url, out);
        return out;
    }

    /** HttpURLConnection refuses PROPFIND, so the listing goes through OkHttp. */
    private String propfind(String url) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML))
                .header("Depth", "1");
        if (authHeader != null) builder.header("Authorization", authHeader);
        try (Response response = HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) return "";
            return response.body().string();
        }
    }

    // ---- parsing -------------------------------------------------------

    /** Parses a WebDAV multistatus body into entries. */
    private void parse(String xml, String base, List<Entry> out) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));
            String href = null, displayName = null, length = null;
            boolean collection = false, inResponse = false;
            while (true) {
                int event = parser.next();
                if (event == XmlPullParser.END_DOCUMENT) break;
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (tag.endsWith("response")) {
                        inResponse = true;
                        href = null;
                        displayName = null;
                        length = null;
                        collection = false;
                    } else if (inResponse) {
                        if (tag.endsWith("href")) href = safeText(parser);
                        else if (tag.endsWith("displayname")) displayName = safeText(parser);
                        else if (tag.endsWith("getcontentlength")) length = safeText(parser);
                        else if (tag.endsWith("collection")) collection = true;
                    }
                } else if (event == XmlPullParser.END_TAG && parser.getName().endsWith("response")) {
                    inResponse = false;
                    addEntry(base, href, displayName, length, collection, out);
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Unreadable WebDAV response", e);
        }
    }

    private static String safeText(XmlPullParser parser) {
        try {
            return parser.nextText();
        } catch (Exception e) {
            return null;
        }
    }

    private static void addEntry(String base, String href, String displayName, String length,
                                 boolean collection, List<Entry> out) {
        if (href == null || href.isEmpty()) return;
        String full = resolve(base, href);
        if (full == null) return;
        if (stripSlash(full).equalsIgnoreCase(stripSlash(base))) return;
        boolean dir = collection || full.endsWith("/");
        String name = displayName == null ? "" : decodePercent(displayName.trim());
        if (name.isEmpty()) name = lastSegment(href);
        long size = 0;
        if (!dir && length != null) {
            try {
                size = Long.parseLong(length.trim());
            } catch (NumberFormatException ignored) {
                size = 0;
            }
        }
        out.add(new Entry(name, dir ? ensureSlash(full) : full, dir, size, 0));
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ensureSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static String decodePercent(String value) {
        if (value == null || !value.contains("%")) return value;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String lastSegment(String href) {
        String value = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int slash = value.lastIndexOf('/');
        String segment = slash >= 0 ? value.substring(slash + 1) : value;
        try {
            return java.net.URLDecoder.decode(segment, "UTF-8");
        } catch (Exception e) {
            return segment;
        }
    }

    private static String resolve(String base, String href) {
        try {
            return URI.create(base).resolve(href).toString();
        } catch (Exception first) {
            try {
                return URI.create(base).resolve(href.replace(" ", "%20")).toString();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String buildAuth(String user, String pass) {
        if (user == null || user.isEmpty()) return null;
        String cred = user + ":" + (pass == null ? "" : pass);
        return "Basic " + Base64.encodeToString(cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }


}
