package com.fader.tv;

import java.util.Objects;

/** Pure UPnP/DLNA string logic: control-URL resolution, relative resource URLs, DIDL extraction. */
public class UpnpPolicyTest {
    static void eq(Object a, Object b, String what) {
        if (!Objects.equals(a, b)) throw new AssertionError(what + " " + a + " != " + b);
    }
    static void check(boolean v, String what) { if (!v) throw new AssertionError(what); }

    public static void main(String[] args) {
        String desc =
            "<root xmlns:p=\"DIDL\"><?p:device>"
            + "<serviceList>"
            + "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>"
            + "<controlURL>/cm/ctrl</controlURL></service>"
            + "<service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>"
            + "<controlURL>/cds/ctrl</controlURL></service>"
            + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
            + "<controlURL>/avt</controlURL></service>"
            + "</serviceList></p:device></root>";
        eq(UpnpPolicy.contentDirectoryControl(desc, "http://nas:8080/udhcp/desc"),
           "http://nas:8080/cds/ctrl", "cd control resolved");
        eq(UpnpPolicy.contentDirectoryControl(desc, "http://nas:8080/udhcp/desc"),
           "http://nas:8080/cds/ctrl", "cd control repeat");
        check(UpnpPolicy.contentDirectoryControl("<root></root>", "http://x/") == null, "no cds");

        eq(UpnpPolicy.resolve("http://nas:8080/", "/abs/path"), "http://nas:8080/abs/path", "resolve abs");
        eq(UpnpPolicy.resolve("http://nas:8080/", "rel/path"), "http://nas:8080/rel/path", "resolve rel");
        eq(UpnpPolicy.resolve("http://nas:8080/", "http://other:80/keep"), "http://other:80/keep", "resolve pass-through");

        String soap =
            "<s:Envelope><s:Body><u:BrowseResponse>" +
            "<Result><![CDATA["
            + "<DIDL-Lite><item id=\"42\"><dc:title>One</dc:title>"
            + "<upnp:artist>A</upnp:artist><upnp:album>AL</upnp:album>"
            + "<dc:duration>180000</dc:duration>"
            + "<res protocolInfo=\"http-get:*:audio/mp3\">http://nas:8080/music/one.mp3</res>"
            + "</item></DIDL-Lite>]]></Result></u:BrowseResponse></s:Body></s:Envelope>";
        String didl = UpnpPolicy.extractDidl(soap);
        check(didl.contains("<item id=\"42\">"), "didl unwrapped cdata");
        eq(UpnpPolicy.extractDidl(""), "", "empty didl");
        byte[] s2 = "HTTP/1.1 200 OK\r\nSERVER: x\r\nLOCATION: http://nas:8080/desc\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n".getBytes();
        eq(UpnpPolicy.locationHeader(s2, s2.length), "http://nas:8080/desc", "location header");
        byte[] s3 = "HTTP/1.1 200 OK\r\nST: x\r\n".getBytes();
        check(UpnpPolicy.locationHeader(s3, s3.length) == null, "no location");
        System.out.println("UPnP policy tests passed");
    }
}
