package com.fader.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Short, user-initiated LAN discovery. No port scanning, server or router configuration. */
final class NasDiscovery implements AutoCloseable {
    interface Listener { void found(Device device); void finished(); }
    static final class Device {
        final String name, host; final boolean smbAdvertised; final String location;
        Device(String name, String host, boolean smbAdvertised, String location) {
            this.name=name!=null?name:""; this.host=host; this.smbAdvertised=smbAdvertised; this.location=location;
        }
        String label(){
            if(smbAdvertised) return name+" · "+host+" · SMB";
            return name+" · "+host+" · Media server";
        }
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Map<String,Device> devices=new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> pending=new ArrayDeque<>();
    private boolean resolving, stopped;
    private volatile DatagramSocket socket;
    private NsdManager.DiscoveryListener discovery;
    private final Runnable finish=this::finishScan;
    private void finishScan(){close();listener.finished();}
    NasDiscovery(Context context,Listener listener){
        nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener=listener;
    }
    @SuppressWarnings("deprecation") // Android TV 12 uses the API 16 NSD callback contract.
    void start(){
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int error){}
            public void onStopDiscoveryFailed(String type,int error){}
            public void onServiceLost(NsdServiceInfo service){}
            public void onServiceFound(NsdServiceInfo service){main.post(()->{
                if(stopped||pending.size()>=32||!service.getServiceType().contains("_smb._tcp"))return;
                pending.add(service);resolveNext();
            });}
        };
        try{nsd.discoverServices("_smb._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);}catch(RuntimeException ignored){}
        main.postDelayed(finish,8000);
        Network network=connectivity.getActiveNetwork();
        new Thread(()->searchMediaServers(network),"Fader-NAS-discovery").start();
    }
    @SuppressWarnings("deprecation")
    private void resolveNext(){
        if(stopped||resolving||pending.isEmpty())return;
        resolving=true;NsdServiceInfo service=pending.remove();
        try{nsd.resolveService(service,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int error){main.post(()->{resolving=false;resolveNext();});}
            public void onServiceResolved(NsdServiceInfo info){main.post(()->{
                if(!stopped&&info.getPort()==445&&info.getHost()!=null&&DiscoveryPolicy.local(info.getHost()))
                    publish(new Device(info.getServiceName(),info.getHost().getHostAddress(),true,null));
                resolving=false;resolveNext();
            });}
        });}catch(RuntimeException ignored){resolving=false;resolveNext();}
    }
    private void publish(Device device){
        if(stopped||devices.size()>=32)return;
        Device old=devices.get(device.host);
        if(old!=null&&(old.smbAdvertised||!device.smbAdvertised))return;
        devices.put(device.host,device);listener.found(device);
    }
    private void searchMediaServers(Network network){
        // Replies are unicast to a temporary random UDP port; no multicast listener is installed.
        try(MulticastSocket udp=new MulticastSocket(null)){
            socket=udp;udp.setTimeToLive(1);if(network==null)return;
            network.bindSocket(udp);udp.bind(new InetSocketAddress(0));udp.setSoTimeout(700);
            synchronized(this){if(stopped)return;}
            byte[] request=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            DatagramPacket search=new DatagramPacket(request,request.length,InetAddress.getByName("239.255.255.250"),1900);
            udp.send(search);udp.send(search);
            long until=System.nanoTime()+7000000000L;
            byte[] buffer=new byte[8193];
            while(System.nanoTime()<until&&!udp.isClosed()){
                DatagramPacket reply=new DatagramPacket(buffer,buffer.length);
                try{udp.receive(reply);}catch(SocketTimeoutException ignored){continue;}
                if(DiscoveryPolicy.local(reply.getAddress())&&DiscoveryPolicy.mediaServer(buffer,reply.getLength())){
                    String host=reply.getAddress().getHostAddress();
                    String location=UpnpPolicy.locationHeader(buffer,reply.getLength());
                    main.post(()->publish(new Device("NAS / media server",host,false,location)));
                }
            }
        }catch(Exception ignored){/* Manual SMB entry remains available on restricted networks. */}
        finally{socket=null;}
    }
    @Override public synchronized void close(){
        if(stopped)return;stopped=true;main.removeCallbacks(finish);pending.clear();
        if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}
        DatagramSocket udp=socket;if(udp!=null)udp.close();
    }
}
