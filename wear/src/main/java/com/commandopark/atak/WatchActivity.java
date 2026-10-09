package com.commandopark.atak;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.commandopark.atak.wear.R;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 워치앱. 폰이 1초마다 보내 주는 상태를 받아서 그리기만 한다. 터치 기능 없음.
 * 받는 값: x,y(지도 픽셀), 오차(m), 방위(없으면 -1), 구역, 가까운 스폰 이름, 거리(m), 방향(도), 끊김(0/1)
 */
public class WatchActivity extends Activity implements MessageClient.OnMessageReceivedListener {

    private static final String WATCH_PATH = "/atak/pos";
    private MapView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new MapView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Wearable.getMessageClient(this).addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Wearable.getMessageClient(this).removeListener(this);
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!WATCH_PATH.equals(event.getPath())) return;
        final String csv = new String(event.getData(), StandardCharsets.UTF_8);
        runOnUiThread(() -> view.update(csv));
    }

    static class MapView extends View {
        private static final float MPP = 0.3291f;        // 지도 1픽셀 = 0.3291m
        private static final float VIEW_METERS = 250f;   // 화면 가로에 보이는 실제 거리

        private final Bitmap map;
        private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint band = new Paint();
        private final Path chevron = new Path();
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

        private boolean has = false;
        private float x, y, acc;
        private int heading = -1, dist, bearing;
        private String zone = "", spawn = "";
        private boolean phoneStale = false;
        private long lastMsgAt = 0;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                invalidate();
                postDelayed(this, 500);
            }
        };

        MapView(Context c) {
            super(c);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            map = BitmapFactory.decodeResource(getResources(), R.drawable.field_map, o);
            stroke.setStyle(Paint.Style.STROKE);
            text.setTextAlign(Paint.Align.CENTER);
            chevron.moveTo(0, -19);
            chevron.lineTo(15, 17);
            chevron.lineTo(0, 9);
            chevron.lineTo(-15, 17);
            chevron.close();
        }

        void update(String csv) {
            try {
                String[] p = csv.split(",");
                if (p.length < 9) return;
                x = Float.parseFloat(p[0]);
                y = Float.parseFloat(p[1]);
                acc = Float.parseFloat(p[2]);
                heading = Integer.parseInt(p[3]);
                zone = p[4];
                spawn = p[5];
                dist = Integer.parseInt(p[6]);
                bearing = Integer.parseInt(p[7]);
                phoneStale = "1".equals(p[8]);
                has = true;
                lastMsgAt = System.currentTimeMillis();
                invalidate();
            } catch (Exception ignored) {
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            final int w = getWidth(), h = getHeight();
            final float u = w / 454f;                 // 화면 크기에 맞춘 배율
            final float cx = w / 2f, cy = h / 2f;
            c.drawColor(Color.BLACK);
            if (map == null) return;
            Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            Typeface sans = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);

            if (!has) {
                // 폰에서 아직 아무것도 안 왔을 때: 필드 전체를 보여 준다
                float k = w / 1920f * 1.35f;
                c.save();
                c.translate(cx - 1095 * k, cy - 480 * k);
                c.scale(k, k);
                c.drawBitmap(map, 0, 0, bmp);
                c.restore();
                c.drawColor(0x99000000);
                text.setTypeface(sans);
                text.setColor(Color.WHITE);
                text.setTextSize(26 * u);
                c.drawText("폰에서 ATAK을", cx, cy - 6 * u, text);
                c.drawText("켜 주세요", cx, cy + 28 * u, text);
                drawClock(c, cx, u, mono);
                return;
            }

            if ("FAR".equals(zone)) {
                // 코만도파크 근처가 아닐 때: 지도 없이 거리만
                text.setTypeface(sans);
                text.setColor(Color.WHITE);
                text.setTextSize(26 * u);
                c.drawText("코만도파크 밖", cx, cy - 12 * u, text);
                text.setTypeface(mono);
                text.setColor(0xFF19D3FF);
                text.setTextSize(30 * u);
                String km = dist >= 10000 ? String.valueOf(Math.round(dist / 1000f))
                        : String.format(Locale.US, "%.1f", dist / 1000f);
                c.drawText(km + "km", cx, cy + 30 * u, text);
                drawClock(c, cx, u, mono);
                return;
            }

            boolean lost = phoneStale || System.currentTimeMillis() - lastMsgAt > 5000;
            int self = lost ? 0xFFFFA41C : 0xFF19D3FF;

            // 지도: 내 위치가 한가운데, 북쪽이 위
            float k = w / (VIEW_METERS / MPP);
            c.save();
            c.translate(cx - x * k, cy - y * k);
            c.scale(k, k);
            c.drawBitmap(map, 0, 0, bmp);
            c.restore();

            // GPS 오차 범위
            float r = Math.max(acc / MPP * k, 6 * u);
            fill.setColor((self & 0x00FFFFFF) | 0x22000000);
            c.drawCircle(cx, cy, r, fill);
            stroke.setColor((self & 0x00FFFFFF) | 0x99000000);
            stroke.setStrokeWidth(2 * u);
            c.drawCircle(cx, cy, r, stroke);

            // 내 위치: 방향을 알면 화살표, 모르면 원
            c.save();
            c.translate(cx, cy);
            c.scale(u, u);
            fill.setColor(self);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(3f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            if (heading >= 0) {
                c.rotate(heading);
                stroke.setColor(Color.BLACK);
                stroke.setStrokeWidth(6f);
                c.drawPath(chevron, stroke);
                c.drawPath(chevron, fill);
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(3f);
                c.drawPath(chevron, stroke);
            } else {
                c.drawCircle(0, 0, 10, fill);
                c.drawCircle(0, 0, 10, stroke);
            }
            c.restore();

            // 위·아래 글자 띠
            band.setShader(new LinearGradient(0, 0, 0, 118 * u,
                    new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, 118 * u, band);
            band.setShader(new LinearGradient(0, h - 112 * u, 0, h,
                    new int[]{Color.TRANSPARENT, Color.BLACK, Color.BLACK},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, h - 112 * u, w, h, band);

            drawClock(c, cx, u, mono);

            // 현재 구역
            text.setTypeface(sans);
            if ("OUT".equals(zone)) {
                text.setColor(0xFFFFA41C);
                text.setTextSize(30 * u);
                c.drawText("필드 밖", cx, 92 * u, text);
            } else {
                text.setColor(0xFFFFE600);
                text.setTextSize(58 * u);
                c.drawText(zone, cx, 102 * u, text);
            }

            // 북쪽 표시
            float nx = 54 * u;
            fill.setColor(0x99000000);
            c.drawRect(nx - 16 * u, cy - 22 * u, nx + 16 * u, cy + 20 * u, fill);
            Path tri = new Path();
            tri.moveTo(nx, cy - 17 * u);
            tri.lineTo(nx + 7 * u, cy - 3 * u);
            tri.lineTo(nx - 7 * u, cy - 3 * u);
            tri.close();
            fill.setColor(0xFFFF4A3D);
            c.drawPath(tri, fill);
            text.setTypeface(mono);
            text.setColor(Color.WHITE);
            text.setTextSize(15 * u);
            c.drawText("N", nx, cy + 15 * u, text);

            // 가장 가까운 스폰: 이름 거리 방향
            String rest = " " + dist + "m " + String.format(Locale.US, "%03d", bearing) + "°";
            text.setTextSize(24 * u);
            text.setTextAlign(Paint.Align.LEFT);
            float total = text.measureText(spawn) + text.measureText(rest);
            float sx = cx - total / 2f, sy = h - 56 * u;
            text.setColor(spawn.contains("RED") ? 0xFFFF4A3D
                    : spawn.contains("BLUE") ? 0xFF4AA3FF : Color.WHITE);
            c.drawText(spawn, sx, sy, text);
            text.setColor(Color.WHITE);
            c.drawText(rest, sx + text.measureText(spawn), sy, text);
            text.setTextAlign(Paint.Align.CENTER);

            // 방위·오차, 또는 끊김 알림
            text.setTextSize(15 * u);
            if (lost) {
                text.setTypeface(sans);
                text.setColor(0xFFFFA41C);
                c.drawText("폰 신호 끊김", cx, h - 34 * u, text);
            } else {
                text.setColor(0xFFA9B4BD);
                String hd = heading >= 0 ? String.format(Locale.US, "HDG %03d°", heading) : "HDG ---";
                c.drawText(hd + " · ±" + Math.round(acc) + "m", cx, h - 34 * u, text);
            }
        }

        private void drawClock(Canvas c, float cx, float u, Typeface mono) {
            text.setTypeface(mono);
            text.setColor(0xFF5DFF7A);
            text.setTextSize(20 * u);
            c.drawText(clock.format(new Date()), cx, 46 * u, text);
        }
    }
}
