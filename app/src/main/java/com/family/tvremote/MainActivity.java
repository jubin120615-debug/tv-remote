package com.family.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A안: 그라파이트 + 오렌지. 방향키 모드와 터치패드 모드를 오간다. */
public class MainActivity extends Activity implements LgTv.Listener {

    // 색: 버튼은 {윗면 밝은쪽, 윗면 어두운쪽, 옆면(두께), 글자}
    private static final int BG = Color.parseColor("#0E0F12");
    private static final int BAR = Color.parseColor("#1B1D22");
    private static final int INK = Color.parseColor("#E9EBEF");
    private static final int SUB = Color.parseColor("#9298A4");
    private static final int ORANGE = Color.parseColor("#FF7A1A");
    private static final int OFF_DOT = Color.parseColor("#4A4E58");
    private static final int[] GRAPHITE = {
            Color.parseColor("#4A4E58"), Color.parseColor("#2B2E35"),
            Color.parseColor("#17181C"), Color.parseColor("#E9EBEF")};
    private static final int[] ACCENT = {
            Color.parseColor("#FFB067"), Color.parseColor("#FF7A1A"),
            Color.parseColor("#B24D00"), Color.parseColor("#2B1400")};

    private LgTv tv;
    private TextView statusText;
    private View statusDot;
    private TextView segKeys, segPad;
    private View keysMode, padMode;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new LgTv(this, this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        showMode(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("pad", false));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (tv.getIp().isEmpty()) {
            findTv();
        } else {
            tv.connect();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        tv.disconnect();
    }

    @Override
    public void onStatus(String text, boolean connected) {
        statusText.setText(text);
        ((GradientDrawable) statusDot.getBackground()).setColor(connected ? ORANGE : OFF_DOT);
    }

    // ---------- 화면 ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(18), dp(16), dp(20));

        // 머리: 제목 + 설정 + 전원
        LinearLayout head = row();
        TextView title = new TextView(this);
        title.setText("우리집 TV");
        title.setTextColor(INK);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = key("설정", GRAPHITE, 13, v -> showSettings());
        head.addView(settings, fixed(dp(56), dp(42)));
        Button power = key("전원", ACCENT, 14, v -> {
            if (tv.isConnected()) tv.powerOff();
            else tv.powerOn();
        });
        head.addView(power, fixed(dp(54), dp(42)));
        root.addView(head, lp(-1, -2, 0, dp(10)));

        // 연결 상태
        LinearLayout pill = row();
        pill.setBackground(rounded(BAR, dp(999)));
        pill.setPadding(dp(14), dp(7), dp(14), dp(7));
        statusDot = new View(this);
        statusDot.setBackground(oval(OFF_DOT));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(8);
        pill.addView(statusDot, dotLp);
        statusText = new TextView(this);
        statusText.setTextColor(SUB);
        statusText.setTextSize(12);
        statusText.setText("준비 중…");
        pill.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(pill, lp(-1, -2, 0, dp(10)));

        // 모드 스위치
        LinearLayout seg = row();
        seg.setBackground(rounded(BAR, dp(999)));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        segKeys = segItem("방향키");
        segPad = segItem("터치패드");
        segKeys.setOnClickListener(v -> showMode(false));
        segPad.setOnClickListener(v -> showMode(true));
        seg.addView(segKeys, new LinearLayout.LayoutParams(0, dp(34), 1));
        seg.addView(segPad, new LinearLayout.LayoutParams(0, dp(34), 1));
        root.addView(seg, lp(-1, -2, 0, dp(12)));

        // 가운데: 모드에 따라 바뀌는 영역
        FrameLayout center = new FrameLayout(this);
        keysMode = buildKeysMode();
        padMode = new TouchPadView(this, new TouchPadView.Listener() {
            @Override public void onMove(int dx, int dy) { tv.move(dx, dy); }
            @Override public void onClick() { tv.click(); }
            @Override public void onScroll(int dy) { tv.scroll(dy); }
        });
        center.addView(keysMode, new FrameLayout.LayoutParams(-1, -1));
        center.addView(padMode, new FrameLayout.LayoutParams(-1, -1));
        root.addView(center, lp(-1, 0, 1, dp(12)));

        // 아래: 항상 보이는 버튼
        LinearLayout nav = row();
        nav.addView(key("뒤로", GRAPHITE, 15, v -> tv.button("BACK")), cell());
        nav.addView(key("홈", GRAPHITE, 15, v -> tv.button("HOME")), cell());
        nav.addView(key("음소거", GRAPHITE, 15, v -> tv.button("MUTE")), cell());
        root.addView(nav, lp(-1, dp(52), 0, dp(6)));

        LinearLayout vc = row();
        vc.addView(rocker("볼륨", "－", "＋", () -> tv.volumeDown(), () -> tv.volumeUp()), cell());
        vc.addView(rocker("채널", "▼", "▲", () -> tv.channelDown(), () -> tv.channelUp()), cell());
        root.addView(vc, lp(-1, dp(58), 0, 0));

        return root;
    }

    private View buildKeysMode() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 앱 바로가기: 버튼은 무채색, 글자에만 서비스 색
        LinearLayout apps1 = row();
        apps1.addView(appKey("유튜브", "#FF6464", new String[]{"youtube", "유튜브"}, "youtube.leanback.v4"), cell());
        apps1.addView(appKey("넷플릭스", "#FF5A60", new String[]{"netflix", "넷플릭스"}, "netflix"), cell());
        apps1.addView(appKey("디즈니+", "#7FB0FF", new String[]{"disney", "디즈니"}, "com.disney.disneyplus-prod"), cell());
        col.addView(apps1, lp(-1, dp(54), 0, 0));
        LinearLayout apps2 = row();
        apps2.addView(appKey("쿠팡플레이", "#C3A6FF", new String[]{"coupang", "쿠팡"}, null), cell());
        apps2.addView(appKey("티빙", "#FF6FA0", new String[]{"tving", "티빙"}, null), cell());
        apps2.addView(key("외부입력", GRAPHITE, 14, v -> tv.button("INPUT_HUB")), cell());
        col.addView(apps2, lp(-1, dp(54), 0, dp(10)));

        // 방향키
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.addView(padRow(null, arrow("▲", "UP"), null), new LinearLayout.LayoutParams(-1, 0, 1));
        pad.addView(padRow(arrow("◀", "LEFT"), key("확인", ACCENT, 18, v -> tv.button("ENTER")), arrow("▶", "RIGHT")),
                new LinearLayout.LayoutParams(-1, 0, 1));
        pad.addView(padRow(null, arrow("▼", "DOWN"), null), new LinearLayout.LayoutParams(-1, 0, 1));
        col.addView(pad, lp(-1, 0, 1, 0));
        return col;
    }

    private void showMode(boolean touchpad) {
        keysMode.setVisibility(touchpad ? View.GONE : View.VISIBLE);
        padMode.setVisibility(touchpad ? View.VISIBLE : View.GONE);
        styleSeg(segKeys, !touchpad);
        styleSeg(segPad, touchpad);
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("pad", touchpad).apply();
    }

    private TextView segItem(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(13);
        return t;
    }

    private void styleSeg(TextView t, boolean on) {
        t.setBackground(on ? rounded(ORANGE, dp(999)) : null);
        t.setTextColor(on ? ACCENT[3] : SUB);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private Button appKey(String label, String tint, String[] keywords, String fallbackId) {
        Button b = key(label, GRAPHITE, 14, v -> tv.launchApp(label, keywords, fallbackId));
        b.setTextColor(Color.parseColor(tint));
        return b;
    }

    private LinearLayout padRow(View left, View center, View right) {
        LinearLayout r = row();
        View[] cells = {left, center, right};
        for (View c : cells) {
            if (c == null) c = new View(this);
            r.addView(c, cell());
        }
        return r;
    }

    private Button arrow(String label, String name) {
        Button b = key(label, GRAPHITE, 20, null);
        repeatWhileHeld(b, () -> tv.button(name));
        return b;
    }

    /** 가로로 누운 볼륨/채널 버튼: 왼쪽 내림, 가운데 이름, 오른쪽 올림 */
    private LinearLayout rocker(String title, String downLabel, String upLabel, Runnable down, Runnable up) {
        LinearLayout r = row();
        r.setBackground(jellyLayer(GRAPHITE, dp(24), 6));
        r.setPadding(0, 0, 0, dp(6));
        Button minus = flat(downLabel);
        repeatWhileHeld(minus, down);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(SUB);
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        Button plus = flat(upLabel);
        repeatWhileHeld(plus, up);
        r.addView(minus, new LinearLayout.LayoutParams(0, -1, 1));
        r.addView(t, new LinearLayout.LayoutParams(-2, -1));
        r.addView(plus, new LinearLayout.LayoutParams(0, -1, 1));
        return r;
    }

    private Button flat(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(INK);
        b.setTextSize(20);
        b.setAllCaps(false);
        b.setBackground(null);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    /** 누르고 있으면 계속 반복 (볼륨·채널·방향키) */
    private void repeatWhileHeld(final View v, final Runnable action) {
        final Runnable[] repeater = new Runnable[1];
        repeater[0] = () -> {
            action.run();
            main.postDelayed(repeater[0], 150);
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    press(view, true);
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    action.run();
                    main.postDelayed(repeater[0], 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    press(view, false);
                    main.removeCallbacks(repeater[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void press(View v, boolean down) {
        v.setPressed(down);
        if (down) {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).setInterpolator(null).start();
        } else {
            v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
        }
    }

    /** 3D 버튼: 위에서 아래로 밝아지는 윗면 + 아래쪽 두께. 누르면 쏙 들어감. */
    private Button key(String label, int[] c, int textSp, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(c[3]);
        b.setTextSize(textSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(0, 0, 0, dp(5));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, jellyLayer(c, dp(16), 2));
        states.addState(new int[]{}, jellyLayer(c, dp(16), 5));
        b.setBackground(states);
        b.setStateListAnimator(null);
        if (click != null) {
            b.setOnTouchListener((view, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) press(view, true);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) press(view, false);
                return false;
            });
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                click.onClick(v);
            });
        }
        return b;
    }

    private LayerDrawable jellyLayer(int[] c, int radius, int depthDp) {
        GradientDrawable side = new GradientDrawable();
        side.setCornerRadius(radius);
        side.setColor(c[2]);
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c[0], c[1]});
        face.setCornerRadius(radius);
        face.setStroke(Math.max(1, dp(1) / 2), Color.argb(28, 255, 255, 255));
        LayerDrawable layer = new LayerDrawable(new Drawable[]{side, face});
        layer.setLayerInset(0, 0, dp(6 - depthDp), 0, 0);
        layer.setLayerInset(1, 0, dp(6 - depthDp), 0, dp(depthDp));
        return layer;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private LinearLayout.LayoutParams fixed(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(8);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(0, 0, 0, bottom);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 설정 ----------

    private void showSettings() {
        String ip = tv.getIp();
        String msg = "TV 주소: " + (ip.isEmpty() ? "없음" : ip)
                + "\n켜기 기능: " + (tv.getMacs().isEmpty() ? "아직 준비 안 됨 (한 번 연결되면 자동 설정)" : "사용 가능");
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setMessage(msg)
                .setPositiveButton("TV 자동 찾기", (d, w) -> findTv())
                .setNeutralButton("주소 직접 입력", (d, w) -> enterIp())
                .setNegativeButton("다시 연결", (d, w) -> tv.connect())
                .show();
    }

    private void enterIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("예: 192.168.0.23");
        input.setText(tv.getIp());
        ScrollView wrap = new ScrollView(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("TV IP 주소")
                .setMessage("TV 설정 → 네트워크 → 연결 정보에서 확인할 수 있어요")
                .setView(wrap)
                .setPositiveButton("저장", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.isEmpty()) {
                        tv.setIp(v);
                        tv.connect();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void findTv() {
        onStatus("같은 Wi-Fi에서 TV를 찾는 중…", false);
        new Thread(() -> {
            final Map<String, String> found = Discovery.find(this);
            main.post(() -> {
                if (isFinishing()) return;
                if (found.isEmpty()) {
                    onStatus("TV를 못 찾았어요. TV를 켜고 다시 시도하거나 주소를 직접 입력하세요", false);
                    Toast.makeText(this, "설정 → 주소 직접 입력도 가능해요", Toast.LENGTH_LONG).show();
                } else if (found.size() == 1) {
                    pick(found.keySet().iterator().next());
                } else {
                    final List<String> ips = new ArrayList<>(found.keySet());
                    String[] labels = new String[ips.size()];
                    for (int i = 0; i < ips.size(); i++) {
                        labels[i] = found.get(ips.get(i)) + " (" + ips.get(i) + ")";
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("어느 TV인가요?")
                            .setItems(labels, (d, which) -> pick(ips.get(which)))
                            .show();
                }
            });
        }).start();
    }

    private void pick(String ip) {
        tv.setIp(ip);
        tv.connect();
    }
}
