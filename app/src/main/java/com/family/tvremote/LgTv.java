package com.family.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * LG webOS TV 를 같은 Wi-Fi 안에서 직접 제어한다 (서버 불필요).
 * 메인 연결(ssap)로 볼륨·채널·앱 실행·끄기를, 보조 연결(pointer input)로 방향키·홈·뒤로를 보낸다.
 */
public class LgTv {

    public interface Listener {
        void onStatus(String text, boolean connected);
    }

    private interface Callback {
        void onResponse(JSONObject msg);
    }

    private static final String SIGNATURE =
            "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbm"
            + "ctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR"
            + "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy"
            + "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4"
            + "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n"
            + "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM"
            + "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj"
            + "oa7NQnAtw==";

    private static final String[] PERMISSIONS = {
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
            "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
            "CONTROL_POWER", "CONTROL_TV_SCREEN", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
            "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
            "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
            "READ_INSTALLED_APPS", "READ_SETTINGS"
    };

    private static final String[] SIGNED_PERMISSIONS = {
            "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
            "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH",
            "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER",
            "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
            "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"
    };

    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;
    private final Map<String, Callback> pending = new HashMap<>();

    private WebSocket ws;
    private WebSocket inputWs;
    private String host;
    private boolean triedWss;
    private boolean opened;
    private boolean signed = true;
    private boolean registered;
    private int nextId = 1;
    private int generation = 0;
    private JSONArray launchPoints;

    public LgTv(Context ctx, Listener listener) {
        this.prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE);
        this.listener = listener;
        this.client = buildClient();
    }

    // ---------- 설정 ----------

    public String getIp() { return prefs.getString("ip", ""); }

    public void setIp(String ip) {
        if (!ip.equals(getIp())) {
            prefs.edit().putString("ip", ip).remove("key").remove("macs").apply();
        }
    }

    public String getMacs() { return prefs.getString("macs", ""); }

    public boolean isConnected() { return registered; }

    // ---------- 연결 ----------

    public synchronized void connect() {
        String ip = getIp();
        if (ip.isEmpty()) {
            status("TV를 먼저 찾아주세요 (설정)", false);
            return;
        }
        disconnect();
        host = ip;
        signed = true;
        triedWss = true;
        status("TV에 연결 중…", false);
        open("wss://" + ip + ":3001");
    }

    public synchronized void disconnect() {
        generation++;
        registered = false;
        opened = false;
        pending.clear();
        if (ws != null) { ws.cancel(); ws = null; }
        if (inputWs != null) { inputWs.cancel(); inputWs = null; }
    }

    private void open(String url) {
        final int gen = generation;
        opened = false;
        Request req = new Request.Builder().url(url).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                synchronized (LgTv.this) {
                    if (gen != generation) return;
                    opened = true;
                    // 최신 펌웨어는 등록 전에 시스템 정보 요청이 필요함
                    sendRaw(socket, "get_sys_info", "request", "ssap://system/getSystemInfo", new JSONObject());
                    socket.send(registerMessage().toString());
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                synchronized (LgTv.this) {
                    if (gen != generation) return;
                    handle(text);
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                synchronized (LgTv.this) {
                    if (gen != generation) return;
                    if (!opened && triedWss) {
                        // 예전 TV는 3000번(ws)만 지원
                        triedWss = false;
                        open("ws://" + host + ":3000");
                        return;
                    }
                    registered = false;
                    status("TV에 연결할 수 없어요. TV가 켜져 있고 같은 Wi-Fi인지 확인하세요", false);
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                synchronized (LgTv.this) {
                    if (gen != generation) return;
                    registered = false;
                    status("연결이 끊겼어요", false);
                }
            }
        });
    }

    private void handle(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String id = msg.optString("id");
            String type = msg.optString("type");

            if ("register_0".equals(id)) {
                if ("response".equals(type)) {
                    status("TV 화면에 뜬 요청에서 '허용'을 눌러주세요", false);
                } else if ("registered".equals(type)) {
                    String key = msg.optJSONObject("payload") != null
                            ? msg.getJSONObject("payload").optString("client-key", "") : "";
                    if (!key.isEmpty()) prefs.edit().putString("key", key).apply();
                    registered = true;
                    status("연결됨", true);
                    afterRegistered();
                } else if ("error".equals(type)) {
                    if (signed) {
                        // 최신 펌웨어는 서명된 매니페스트를 거부 → 서명 없이 재시도
                        signed = false;
                        ws.send(registerMessage().toString());
                    } else {
                        prefs.edit().remove("key").apply();
                        status("TV가 연결을 거절했어요. 다시 연결해 주세요", false);
                    }
                }
                return;
            }

            Callback cb = pending.remove(id);
            if (cb != null) cb.onResponse(msg);
        } catch (Exception ignored) {
        }
    }

    private void afterRegistered() {
        // 방향키·홈·뒤로 버튼용 보조 연결
        request("ssap://com.webos.service.networkinput/getPointerInputSocket", new JSONObject(), msg -> {
            JSONObject p = msg.optJSONObject("payload");
            if (p == null) return;
            String path = p.optString("socketPath", "");
            if (path.isEmpty()) return;
            final int gen = generation;
            inputWs = client.newWebSocket(new Request.Builder().url(path).build(), new WebSocketListener() {
                @Override
                public void onFailure(WebSocket socket, Throwable t, Response response) {
                    synchronized (LgTv.this) {
                        if (gen == generation) inputWs = null;
                    }
                }
            });
        });
        // TV에 설치된 앱 목록 (앱 이름으로 실행하기 위해)
        request("ssap://com.webos.applicationManager/listLaunchPoints", new JSONObject(), msg -> {
            JSONObject p = msg.optJSONObject("payload");
            if (p != null && p.optJSONArray("launchPoints") != null) {
                launchPoints = p.optJSONArray("launchPoints");
            }
        });
        // TV 켜기(Wake-on-LAN)에 쓸 MAC 주소 저장
        request("ssap://com.webos.service.connectionmanager/getinfo", new JSONObject(), msg -> {
            JSONObject p = msg.optJSONObject("payload");
            if (p == null) return;
            StringBuilder macs = new StringBuilder();
            for (String k : new String[]{"wiredInfo", "wifiInfo"}) {
                JSONObject info = p.optJSONObject(k);
                if (info == null) continue;
                String mac = info.optString("macAddress", "");
                if (mac.length() >= 12) {
                    if (macs.length() > 0) macs.append(',');
                    macs.append(mac);
                }
            }
            if (macs.length() > 0) prefs.edit().putString("macs", macs.toString()).apply();
        });
    }

    // ---------- 명령 ----------

    public synchronized void request(String uri, JSONObject payload) {
        request(uri, payload, null);
    }

    private synchronized void request(String uri, JSONObject payload, Callback cb) {
        if (!registered || ws == null) {
            status("TV와 연결되지 않았어요", false);
            return;
        }
        String id = "req_" + (nextId++);
        if (cb != null) pending.put(id, cb);
        sendRaw(ws, id, "request", uri, payload);
    }

    public void volumeUp() { request("ssap://audio/volumeUp", new JSONObject()); }
    public void volumeDown() { request("ssap://audio/volumeDown", new JSONObject()); }
    public void channelUp() { request("ssap://tv/channelUp", new JSONObject()); }
    public void channelDown() { request("ssap://tv/channelDown", new JSONObject()); }
    public void powerOff() { request("ssap://system/turnOff", new JSONObject()); }

    public void launch(String appId) {
        try {
            request("ssap://system.launcher/launch", new JSONObject().put("id", appId));
        } catch (Exception ignored) {
        }
    }

    /**
     * TV에 설치된 앱 중 이름에 keywords 가 들어간 앱을 실행한다.
     * 목록을 아직 못 받았거나 못 찾으면 fallbackId(알려진 앱 ID)로 시도한다.
     */
    public synchronized void launchApp(String label, String[] keywords, String fallbackId) {
        if (!registered) {
            status("TV와 연결되지 않았어요", false);
            return;
        }
        if (launchPoints != null) {
            for (int i = 0; i < launchPoints.length(); i++) {
                JSONObject app = launchPoints.optJSONObject(i);
                if (app == null) continue;
                String title = app.optString("title", "").toLowerCase().replace(" ", "");
                String id = app.optString("id", "").toLowerCase();
                for (String k : keywords) {
                    String key = k.toLowerCase().replace(" ", "");
                    if (title.contains(key) || id.contains(key)) {
                        launch(app.optString("id"));
                        status(label + " 켜는 중", true);
                        return;
                    }
                }
            }
        }
        if (fallbackId != null) {
            launch(fallbackId);
            status(label + " 켜는 중", true);
        } else {
            status("TV에 " + label + " 앱이 없어요. TV 앱스토어에서 설치해 주세요", true);
        }
    }

    /** HOME, BACK, UP, DOWN, LEFT, RIGHT, ENTER, MUTE, INPUT_HUB 등 */
    public synchronized void button(String name) {
        if (!registered) {
            status("TV와 연결되지 않았어요", false);
            return;
        }
        if (inputWs == null) {
            afterRegistered();
            status("버튼 연결 준비 중… 한 번 더 눌러주세요", true);
            return;
        }
        inputWs.send("type:button\nname:" + name + "\n\n");
    }

    // ---------- 터치패드 (TV 포인터) ----------

    private synchronized boolean pointerReady() {
        if (!registered) {
            status("TV와 연결되지 않았어요", false);
            return false;
        }
        if (inputWs == null) {
            afterRegistered();
            return false;
        }
        return true;
    }

    public synchronized void move(int dx, int dy) {
        if (pointerReady()) inputWs.send("type:move\ndx:" + dx + "\ndy:" + dy + "\ndown:0\n\n");
    }

    public synchronized void click() {
        if (pointerReady()) inputWs.send("type:click\n\n");
    }

    public synchronized void scroll(int dy) {
        if (pointerReady()) inputWs.send("type:scroll\ndx:0\ndy:" + dy + "\n\n");
    }

    /** TV 켜기: 저장된 MAC 주소로 Wake-on-LAN 신호를 보내고 다시 연결 시도 */
    public void powerOn() {
        final String macs = getMacs();
        if (macs.isEmpty()) {
            status("TV를 한 번 연결해야 켜기를 쓸 수 있어요", false);
            return;
        }
        status("TV 켜는 중…", false);
        new Thread(() -> {
            for (String mac : macs.split(",")) sendWol(mac.trim());
        }).start();
        // 켜지는 데 시간이 걸리므로 몇 번 다시 연결 시도
        for (int i = 1; i <= 4; i++) {
            main.postDelayed(() -> { if (!registered) connect(); }, i * 4000L);
        }
    }

    private static void sendWol(String mac) {
        try {
            String hex = mac.replace(":", "").replace("-", "");
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            byte[] packet = new byte[102];
            for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
            for (int i = 6; i < packet.length; i += 6) System.arraycopy(macBytes, 0, packet, i, 6);
            DatagramSocket s = new DatagramSocket();
            s.setBroadcast(true);
            InetAddress addr = InetAddress.getByName("255.255.255.255");
            for (int r = 0; r < 3; r++) {
                s.send(new DatagramPacket(packet, packet.length, addr, 9));
                s.send(new DatagramPacket(packet, packet.length, addr, 7));
            }
            s.close();
        } catch (Exception ignored) {
        }
    }

    // ---------- 내부 ----------

    private void sendRaw(WebSocket socket, String id, String type, String uri, JSONObject payload) {
        try {
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("type", type);
            m.put("uri", uri);
            m.put("payload", payload);
            socket.send(m.toString());
        } catch (Exception ignored) {
        }
    }

    private JSONObject registerMessage() {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("appVersion", "1.1");
            manifest.put("manifestVersion", 1);
            if (signed) {
                manifest.put("permissions", new JSONArray(PERMISSIONS));
                JSONObject sig = new JSONObject();
                sig.put("signature", SIGNATURE);
                sig.put("signatureVersion", 1);
                manifest.put("signatures", new JSONArray().put(sig));
                JSONObject s = new JSONObject();
                s.put("appId", "com.lge.test");
                s.put("created", "20140509");
                s.put("localizedAppNames", new JSONObject()
                        .put("", "LG Remote App")
                        .put("ko-KR", "리모컨 앱")
                        .put("zxx-XX", "ЛГ Rэмotэ AПП"));
                s.put("localizedVendorNames", new JSONObject().put("", "LG Electronics"));
                s.put("permissions", new JSONArray(SIGNED_PERMISSIONS));
                s.put("serial", "2f930e2d2cfe083771f68e4fe7bb07");
                s.put("vendorId", "com.lge");
                manifest.put("signed", s);
            } else {
                TreeSet<String> all = new TreeSet<>();
                for (String p : PERMISSIONS) all.add(p);
                for (String p : SIGNED_PERMISSIONS) all.add(p);
                manifest.put("permissions", new JSONArray(all));
            }
            JSONObject payload = new JSONObject();
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            payload.put("manifest", manifest);
            String key = prefs.getString("key", "");
            if (!key.isEmpty()) payload.put("client-key", key);

            JSONObject msg = new JSONObject();
            msg.put("type", "register");
            msg.put("id", "register_0");
            msg.put("payload", payload);
            return msg;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void status(final String text, final boolean connected) {
        main.post(() -> listener.onStatus(text, connected));
    }

    /** TV는 자체 서명 인증서를 쓰므로 인증서 검사를 생략한다 (집 안 네트워크 전용). */
    private static OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS);
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            b.sslSocketFactory(ssl.getSocketFactory(), trustAll);
            b.hostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {
        }
        return b.build();
    }
}
