package com.fader.tv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure UPnP/DLNA string logic (no sockets): resolve a ContentDirectory control URL from a device
 * description, resolve relative resource URLs, and pull DIDL-Lite out of a SOAP result. Kept here so
 * the network client can be unit tested against captured samples.
 */
public final class UpnpPolicy {
    private UpnpPolicy() { }

    private static final Pattern SERVICE =
        Pattern.compile("<service\\b[^>]*>.*?</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CTRL =
        Pattern.compile("<controlURL\\b[^>]*>(.*?)</controlURL>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE =
        Pattern.compile("<serviceType\\b[^>]*>(.*?)</serviceType>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Returns the ContentDirectory control URL resolved against the description base, or null. */
    public static String contentDirectoryControl(String description, String base) {
        Matcher svc = SERVICE.matcher(description);
        while (svc.find()) {
            String block = svc.group(0);
            Matcher t = TYPE.matcher(block);
            if (t.find() && t.group(1).toLowerCase().contains("contentdirectory")) {
                Matcher c = CTRL.matcher(block);
                if (c.find()) return resolve(base, c.group(1).trim());
            }
        }
        return null;
    }

    /** Resolve a possibly relative reference against the base URL (up to its authority). */
    public static String resolve(String base, String ref) {
        if (ref == null) return null;
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) return ref;
        String prefix = base.substring(0, base.indexOf('/', schemeEnd + 3));
        return ref.startsWith("/") ? prefix + ref : prefix + "/" + ref;
    }

    /** Pull the DIDL-Lite document out of a SOAP BrowseResponse, decoding CDATA when present. */
    public static String extractDidl(String soap) {
        if (soap == null) return "";
        int a = soap.indexOf("<Result>");
        int b = soap.indexOf("</Result>");
        if (a < 0 || b < 0 || b <= a) return "";
        String raw = soap.substring(a + "<Result>".length(), b);
        raw = raw.trim();
        if (raw.startsWith("<![CDATA[")) {
            int end = raw.indexOf("]]>");
            raw = end >= 0 ? raw.substring(9, end) : raw.substring(9);
        }
        return raw;
    }

    private static final Pattern LOCATION =
        Pattern.compile("(?im)^LOCATION:\\s*(\\S+\\S*)$");

    /** Extract an SSDP "LOCATION:" header value, or null. */
    public static String locationHeader(byte[] reply, int length) {
        String s = new String(reply, 0, Math.max(0, Math.min(length, 8192)),
            java.nio.charset.StandardCharsets.US_ASCII);
        Matcher m = LOCATION.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }
}
