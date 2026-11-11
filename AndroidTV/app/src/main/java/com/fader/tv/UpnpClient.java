package com.fader.tv;

import android.media.MediaDataSource;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort UPnP/DLNA Content Directory browse + stream. The device is an outbound HTTP client only;
 * it opens no listening socket. Discovery already found MediaServer announcements; this lets their
 * content be browsed and played like a native TV source.
 */
final class UpnpClient implements Stream {
    private final String location;
    private String control;
    private boolean ready;

    UpnpClient(String location) { this.location = location; }

    void connect() throws IOException {
        String xml = get(location);
        control = UpnpPolicy.contentDirectoryControl(xml, location);
        if (control == null) throw new IOException("No ContentDirectory service on this media server");
        ready = true;
    }

    /** Browse children of a container (or the root with objectId "0"). */
    List<NasClient.Entry> browse(String objectId, String flag) throws IOException {
        if (!ready) connect();
        String soap =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
            "<s:Body><u:Browse xmlns:u=\"http://services.upnp.org/\">" +
            "<ObjectID>" + objectId + "</ObjectID>" +
            "<BrowseFlag>" + flag + "</BrowseFlag>" +
            "<Filter>*</Filter><ElementFirstIndex>0</ElementFirstIndex><NumberOfElements>0</NumberOfElements>" +
            "</u:Browse></s:Body></s:Envelope>";
        String didl = UpnpPolicy.extractDidl(post(control, soap));
        List<NasClient.Entry> out = new ArrayList<>();
        for (DidlParser.Media m : DidlParser.parse(didl)) {
            if (m.container) out.add(new NasClient.Entry(m.title, "upnp:" + m.id, true, 0, 0));
            else out.add(new NasClient.Entry(m.title, UpnpPolicy.resolve(location, m.resource), false, m.size, m.durationMs));
        }
        return out;
    }

    @Override public MediaDataSource source(NasClient.Entry entry) {
        return new HttpSource(entry.url);
    }

    @Override public void close() { ready = false; }

    // ---- bounded HTTP helpers ------------------------------------------------
    private String get(String url) throws IOException {
        HttpURLConnection c = connectGet(url);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        return new String(readAll(c.getInputStream()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String post(String control, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(control).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"");
        c.setFixedLengthStreamingMode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (code >= 400) {
            InputStream err = c.getErrorStream();
            return err == null ? "" : new String(readAll(err), java.nio.charset.StandardCharsets.UTF_8);
        }
        return new String(readAll(c.getInputStream()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private HttpURLConnection connectGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
