package com.commandopark.atak;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 폰앱. 화면(지도, 정보창)은 assets/index.html 이 그리고,
 * 이 파일은 GPS·나침반 값을 화면에 넣어 주고 워치로 현재 상태를 보내는 역할만 한다.
 */
public class MainActivity extends Activity implements LocationListener, SensorEventListener {

    private static final String WATCH_PATH = "/atak/pos";

    private WebView web;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private boolean pageReady = false;
    private String pendingMsg = null;

    private final float[] rot = new float[9];
    private long lastHeadingAt = 0;
    private float declination = 0f;      // 자북과 진북의 차이(도)

    private ConnectivityManager connectivity;
    private boolean dataOn = false;
    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            runOnUiThread(() -> setData(true));
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(() -> setData(connectivity.getActiveNetwork() != null));
        }
    };

    private volatile List<String> watchNodes = new ArrayList<>();
    private long nodesCheckedAt = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 지난번에 고른 화면 방향(기본: 가로). 이 앱 화면에만 적용되고 폰의 자동 회전 설정은 건드리지 않는다.
        applyOrientation(getPreferences(MODE_PRIVATE).getString("orient", "land"));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // 위치 보정값 저장용
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                js("onNativeData(" + dataOn + ")");
                if (pendingMsg != null) {
                    showMsg(pendingMsg);
                    pendingMsg = null;
                }
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (hasLocationPermission()) js("onNativePermission(true)");   // 설정에서 허용하고 돌아온 경우
        startLocation();
        try {
            setData(connectivity.getActiveNetwork() != null);
            connectivity.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
        sensorManager.unregisterListener(this);
        try {
            connectivity.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
    }

    /** 데이터(모바일 데이터·와이파이) 연결 여부를 화면에 알린다 */
    private void setData(boolean on) {
        dataOn = on;
        js("onNativeData(" + on + ")");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /* ---------------- 화면 방향: 가로 / 세로 ---------------- */

    private void applyOrientation(String mode) {
        setRequestedOrientation("port".equals(mode)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /* ---------------- 볼륨 버튼: 위 = 확대, 아래 = 축소 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                js("onNativeZoom(" + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1) + ")");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /* ---------------- GPS ---------------- */

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // 권한이 없으면 화면의 안내 창이 먼저 뜨고, 거기서 "확인"을 눌러야 권한 창을 띄운다
            return;
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                showMsg("폰의 위치(GPS)가 꺼져 있어요. 켜 주세요.");
            }
            // 1초마다, 이동 거리와 상관없이 받는다
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
        } catch (SecurityException e) {
            showMsg("위치 권한이 필요해요.");
        } catch (Exception e) {
            showMsg("이 폰에서 GPS를 쓸 수 없어요.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            js("onNativePermission(true)");
            startLocation();
        } else {
            js("onNativeDenied()");
            showMsg("위치 권한이 필요해요. 설정 > 앱 > ATAK > 권한에서 위치를 '정확한 위치'로 허용해 주세요.");
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        String alt = "null";
        if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) {
            alt = num(l.getMslAltitudeMeters());
        } else if (l.hasAltitude()) {
            alt = num(l.getAltitude());
        }
        String spd = l.hasSpeed() ? num(l.getSpeed()) : "null";
        String brg = l.hasBearing() ? num(l.getBearing()) : "null";
        String acc = l.hasAccuracy() ? num(l.getAccuracy()) : "0";

        declination = new GeomagneticField(
                (float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis()).getDeclination();

        js(String.format(Locale.US, "onNativeFix(%.7f,%.7f,%s,%s,%s,%s)",
                l.getLatitude(), l.getLongitude(), acc, alt, spd, brg));
    }

    @Override
    public void onProviderDisabled(String provider) {
        showMsg("폰의 위치(GPS)가 꺼져 있어요. 켜 주세요.");
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /* ---------------- 나침반: 내가 바라보는 방향 ---------------- */

    @SuppressWarnings("deprecation")
    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (now - lastHeadingAt < 150) return;
        lastHeadingAt = now;

        SensorManager.getRotationMatrixFromVector(rot, e.values);
        // rot 의 각 열 = 폰의 X(오른쪽)·Y(위쪽)·Z(화면 바깥) 축이 실제로 향하는 방향 (동, 북, 위)
        float east, north;
        if (Math.abs(rot[8]) >= 0.5f) {
            // 폰이 눕혀져 있을 때: 화면 위쪽이 가리키는 방향
            int r = getWindowManager().getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) {
                east = rot[0];
                north = rot[3];
            } else if (r == Surface.ROTATION_270) {
                east = -rot[0];
                north = -rot[3];
            } else if (r == Surface.ROTATION_180) {
                east = -rot[1];
                north = -rot[4];
            } else {
                east = rot[1];
                north = rot[4];
            }
        } else {
            // 폰이 세워져 있을 때: 화면을 보는 사람이 향한 방향(폰 뒷면 쪽)
            east = -rot[2];
            north = -rot[5];
        }
        double deg = Math.toDegrees(Math.atan2(east, north)) + declination;
        deg = ((deg % 360) + 360) % 360;
        js("onNativeHeading(" + num(deg) + ")");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /* ---------------- 화면(JS)과 주고받기 ---------------- */

    private static String num(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private void js(String code) {
        if (pageReady) web.evaluateJavascript(code, null);
    }

    private void showMsg(String msg) {
        if (!pageReady) {
            pendingMsg = msg;
            return;
        }
        js("onNativeMsg('" + msg.replace("'", "\\'") + "')");
    }

    /** 화면이 1초마다 불러서 워치로 현재 상태를 보낸다. 워치가 없으면 아무 일도 안 한다. */
    private class Bridge {
        /** 위치 권한이 이미 있는지 (없으면 화면이 안내 창을 먼저 보여 준다) */
        @JavascriptInterface
        public boolean hasLocation() {
            return hasLocationPermission();
        }

        /** "가로 모드"/"세로 모드" 버튼: 방향을 바꾸고 다음 실행 때도 쓰도록 저장한다 */
        @JavascriptInterface
        public void setOrientation(String mode) {
            final String m = "port".equals(mode) ? "port" : "land";
            runOnUiThread(() -> {
                SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
                e.putString("orient", m);
                e.apply();
                applyOrientation(m);
            });
        }

        /** 안내 창에서 "확인"을 눌렀을 때: 안드로이드 권한 창을 띄운다 */
        @JavascriptInterface
        public void askLocation() {
            runOnUiThread(() -> requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1));
        }

        @JavascriptInterface
        public void toWatch(String csv) {
            try {
                long now = System.currentTimeMillis();
                if (now - nodesCheckedAt > 10000) {
                    nodesCheckedAt = now;
                    Wearable.getNodeClient(MainActivity.this).getConnectedNodes()
                            .addOnSuccessListener(nodes -> {
                                List<String> ids = new ArrayList<>();
                                for (Node n : nodes) ids.add(n.getId());
                                watchNodes = ids;
                            });
                }
                byte[] data = csv.getBytes(StandardCharsets.UTF_8);
                for (String id : watchNodes) {
                    Wearable.getMessageClient(MainActivity.this).sendMessage(id, WATCH_PATH, data);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
