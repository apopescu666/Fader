package com.fader.mobil.core;

import android.media.MediaDataSource;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * Minimal HTTP range source for UPnP/DLNA stream URLs. Streams sequentially and re-opens a Range
 * request when the media player seeks to a new position. This is a best-effort, read-only client:
 * it never opens a listening socket on the device.
 */
final class HttpSource extends MediaDataSource {
    private final String url;
    private final String authorization;
    private HttpURLConnection con;
    private InputStream in;
    private long offset = 0;
    private long total = -1;
    private boolean closed;

    HttpSource(String url) { this(url, null); }
    HttpSource(String url, String authorization) { this.url = url; this.authorization = authorization; }

    private void openAt(long from) throws IOException {
        close();
        URL u = new URL(url);
        con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(15000);
        con.setRequestMethod("GET");
        if (authorization != null) con.setRequestProperty("Authorization", authorization);
        con.setRequestProperty("Range", "bytes=" + from + "-");
        con.connect();
        int code = con.getResponseCode();
        if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
            in = con.getInputStream();
            if (code == HttpURLConnection.HTTP_OK) from = 0; // server ignored the range
            offset = from;
            long cl = con.getContentLength();
            total = cl < 0 ? -1 : cl + offset;
        } else {
            throw new IOException("HTTP " + code + " for UPnP resource");
        }
    }

    @Override public long getSize() {
        if (total < 0) {
            try {
                URLConnection c = new URL(url).openConnection();
                c.setConnectTimeout(5000);
                if (authorization != null) c.setRequestProperty("Authorization", authorization);
                c.connect();
                long len = c.getContentLength();
                total = len < 0 ? -1 : len;
            } catch (IOException ignored) { total = -1; }
        }
        return total;
    }

    @Override public int readAt(long position, byte[] buffer, int off, int size) throws IOException {
        if (closed) throw new IOException("Source closed");
        if (size == 0) return 0;
        if (position != offset || in == null) openAt(position);
        int read = 0;
        while (read < size) {
            int n = in.read(buffer, off + read, size - read);
            if (n < 0) break;
            read += n;
        }
        offset += read;
        return read == 0 ? -1 : read;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        if (con != null) con.disconnect();
        in = null; con = null;
    }
}
