package com.family.tvremote;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 같은 Wi-Fi 안의 LG webOS TV를 SSDP로 찾는다. 결과: IP → 이름 */
public class Discovery {

    public static Map<String, String> find(Context ctx) {
        Map<String, String> found = new LinkedHashMap<>();
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        DatagramSocket socket = null;
        try {
            if (wifi != null) {
                lock = wifi.createMulticastLock("lgtv-discovery");
                lock.acquire();
            }
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n";
            byte[] data = msg.getBytes("UTF-8");
            socket = new DatagramSocket();
            socket.setSoTimeout(800);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            long end = System.currentTimeMillis() + 4000;
            int sent = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (sent < 3) {
                    socket.send(new DatagramPacket(data, data.length, group, 1900));
                    sent++;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String resp = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    String ip = p.getAddress().getHostAddress();
                    if (resp.toLowerCase().contains("webos") || resp.toLowerCase().contains("lge")) {
                        String name = "LG TV";
                        for (String line : resp.split("\r\n")) {
                            String l = line.toLowerCase();
                            if (l.startsWith("dlnadevicename.lge.com:")) {
                                name = java.net.URLDecoder.decode(line.substring(line.indexOf(':') + 1).trim(), "UTF-8");
                            }
                        }
                        if (!found.containsKey(ip)) found.put(ip, name);
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null) socket.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
        return found;
    }
}
