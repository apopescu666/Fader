package com.fader.tv;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Discovery never follows a server-supplied URL or accepts public Internet addresses. */
final class DiscoveryPolicy {
    static boolean local(InetAddress address) {
        return address instanceof Inet4Address && address.isSiteLocalAddress()
            && !address.isLoopbackAddress() && !address.isMulticastAddress();
    }
    static boolean mediaServer(byte[] bytes, int length) {
        if(length < 1 || length > 8192) return false;
        String response = new String(bytes, 0, length, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        if(!response.startsWith("http/1.1 200 ")) return false;
        for(String line : response.split("\r?\n")) {
            if(line.startsWith("st:"))
                return line.substring(3).trim().matches("urn:schemas-upnp-org:device:mediaserver:[1-9][0-9]*");
        }
        return false;
    }
}
