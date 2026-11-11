package com.fader.tv;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
public class DiscoveryPolicyTest {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static boolean reply(String text){byte[] b=text.getBytes(StandardCharsets.US_ASCII);return DiscoveryPolicy.mediaServer(b,b.length);}
 public static void main(String[] args)throws Exception{
  check(DiscoveryPolicy.local(InetAddress.getByName("192.168.158.10")));
  check(DiscoveryPolicy.local(InetAddress.getByName("10.0.0.2")));
  for(String ip:new String[]{"127.0.0.1","8.8.8.8","239.255.255.250","0.0.0.0","::1"})check(!DiscoveryPolicy.local(InetAddress.getByName(ip)));
  check(reply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://example.com/ignored\r\n\r\n"));
  check(reply("HTTP/1.1 200 OK\r\nst: urn:schemas-upnp-org:device:MediaServer:2\r\n"));
  check(!reply("HTTP/1.1 404 Not Found\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n"));
  check(!reply("HTTP/1.1 200 OK\r\nSERVER: MediaServer\r\n"));
  check(!reply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"));
  check(!reply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1evil\r\n"));
  check(!DiscoveryPolicy.mediaServer(new byte[8193],8193));
  System.out.println("Discovery policy tests passed");
 }
}
