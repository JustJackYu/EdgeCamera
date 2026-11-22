package com.example.edgecam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Real-time "outline vision" camera: everything is black, only edges show as white lines.
 *
 * - Tap the picture to hide/show the controls.
 * - VR mode: landscape, one picture per eye side by side, controls hidden.
 *   Volume keys change sensitivity while the controls are hidden; Back leaves VR mode.
 * - On launch the app checks GitHub for a newer build and offers to download it.
 */
@SuppressWarnings("deprecation") // android.hardware.Camera is deprecated but simple and still supported.
public class MainActivity extends Activity implements EdgeRenderer.Listener {

    private static final int REQ_CAMERA = 1;
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/JustJackYu/EdgeCamera/releases/latest";
    private static final String DOWNLOAD_URL =
            "https://github.com/JustJackYu/EdgeCamera/releases/latest/download/EdgeCam.apk";

    private interface IntCallback {
        void on(int value);
    }

    private GLSurfaceView glView;
    private EdgeRenderer renderer;
    private LinearLayout panel;
    private SeekBar sensitivityBar;
    private Button vrBtn;

    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private boolean useFront = false;
    private boolean resumed = false;
    private boolean vrMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Use the whole screen, notch area included, so both VR halves stay centred on the lenses.
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(wlp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new EdgeRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x99000000);
        final int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout sensRow = makeSlider("감도", 65, v -> renderer.setSensitivity(v / 100f));
        sensitivityBar = (SeekBar) sensRow.getChildAt(1);
        panel.addView(sensRow);
        panel.addView(makeSlider("선 굵기", 25, v -> renderer.setThickness(v / 100f)));

        Button switchBtn = makeButton("카메라 전환", v -> switchCamera());
        Button shotBtn = makeButton("촬영", v -> renderer.requestCapture());
        panel.addView(buttonRow(switchBtn, shotBtn));

        Button hideBtn = makeButton("숨기기", v -> hideControls(true));
        vrBtn = makeButton("VR 모드", v -> setVrMode(!vrMode));
        Button updateBtn = makeButton("업데이트", v -> checkForUpdate(true));
        panel.addView(buttonRow(hideBtn, vrBtn, updateBtn));

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Keep the controls above the navigation bar when it is showing.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            panel.setPadding(pad, pad, pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        // Tap the picture to hide/show the controls.
        glView.setOnClickListener(v -> {
            if (panel.getVisibility() == View.VISIBLE) {
                hideControls(false);
            } else {
                showControls();
            }
        });

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        checkForUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glView.onResume();
        if (panel.getVisibility() != View.VISIBLE || vrMode) setFullscreenBars(true);
        startCameraIfReady();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopCamera();
        // The GL context (and its SurfaceTexture) is destroyed on pause; a new one arrives on resume.
        surfaceTexture = null;
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Screen rotated (VR mode on/off): the camera's display orientation must be recomputed.
        if (camera != null) {
            stopCamera();
            startCameraIfReady();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraIfReady();
        } else {
            toast("카메라 권한이 있어야 쓸 수 있어요");
        }
    }

    @Override
    public void onBackPressed() {
        if (vrMode) {
            setVrMode(false);
        } else if (panel.getVisibility() != View.VISIBLE) {
            showControls();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // While the controls are hidden (e.g. phone inside a VR viewer), volume keys tune sensitivity.
        if (panel.getVisibility() != View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                sensitivityBar.setProgress(Math.min(100, sensitivityBar.getProgress() + 5));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                sensitivityBar.setProgress(Math.max(0, sensitivityBar.getProgress() - 5));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Controls, full screen and VR ----

    private void hideControls(boolean showHint) {
        panel.setVisibility(View.GONE);
        setFullscreenBars(true);
        if (showHint) toast("화면을 탭하면 메뉴가 다시 나와요");
    }

    private void showControls() {
        panel.setVisibility(View.VISIBLE);
        if (!vrMode) setFullscreenBars(false);
    }

    private void setVrMode(boolean on) {
        vrMode = on;
        renderer.setStereo(on);
        vrBtn.setText(on ? "VR 종료" : "VR 모드");
        setRequestedOrientation(on
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (on) {
            hideControls(false);
            toast("VR 모드: 탭하면 메뉴, 볼륨키로 감도 조절");
        } else {
            showControls();
        }
    }

    /** Hides the status and navigation bars (swipe from an edge to peek at them). */
    private void setFullscreenBars(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.navigationBars());
            }
        } else {
            int flags = on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    : 0;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    // ---- Updates ----

    /** Asks GitHub for the newest release ("build-N") and offers it if N is above our versionCode. */
    private void checkForUpdate(final boolean manual) {
        if (manual) toast("업데이트 확인 중…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) throw new IllegalStateException("HTTP " + code);

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                } finally {
                    c.disconnect();
                }

                String tag = new JSONObject(sb.toString()).getString("tag_name");
                final int latest = Integer.parseInt(tag.replaceAll("\\D+", ""));
                long current = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();

                if (latest > current) {
                    runOnUiThread(() -> showUpdateDialog(latest));
                } else if (manual) {
                    toast("최신 버전이에요 (빌드 " + current + ")");
                }
            } catch (Exception e) {
                if (manual) toast("업데이트 확인 실패: " + e.getMessage());
            }
        }).start();
    }

    private void showUpdateDialog(int latest) {
        if (isFinishing() || isDestroyed() || vrMode) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("새 버전이 있어요")
                .setMessage("빌드 " + latest + "를 받을까요?\n다운로드된 EdgeCam.apk를 열면 지금 앱 위에 업데이트돼요.")
                .setPositiveButton("받기", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))))
                .setNegativeButton("나중에", null)
                .show();
    }

    // ---- EdgeRenderer.Listener (called on the GL thread) ----

    @Override
    public void onSurfaceTextureReady(final SurfaceTexture st) {
        runOnUiThread(() -> {
            surfaceTexture = st;
            stopCamera();
            startCameraIfReady();
        });
    }

    @Override
    public void onCaptured(final Bitmap bmp) {
        new Thread(() -> save(bmp)).start();
    }

    // ---- Camera ----

    private void startCameraIfReady() {
        if (!resumed || camera != null || surfaceTexture == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        int id = findCamera(useFront);
        if (id < 0) {
            id = findCamera(!useFront);
            if (id < 0) {
                toast("카메라를 찾을 수 없어요");
                return;
            }
            useFront = !useFront;
        }

        try {
            camera = Camera.open(id);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);

            Camera.Parameters p = camera.getParameters();
            Camera.Size size = choosePreviewSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(size.width, size.height);
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);

            int rotation = displayOrientation(info);
            camera.setDisplayOrientation(rotation);
            boolean swap = rotation == 90 || rotation == 270;
            renderer.setCameraSize(swap ? size.height : size.width, swap ? size.width : size.height);

            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
        } catch (Exception e) {
            toast("카메라를 열 수 없어요: " + e.getMessage());
            stopCamera();
        }
    }

    private void stopCamera() {
        if (camera == null) return;
        try {
            camera.stopPreview();
        } catch (Exception ignored) {
        }
        camera.release();
        camera = null;
    }

    private void switchCamera() {
        useFront = !useFront;
        stopCamera();
        startCameraIfReady();
    }

    private static int findCamera(boolean front) {
        int want = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == want) return i;
        }
        return -1;
    }

    /** Picks the preview size closest to 1280x720: sharp enough, light enough for real time. */
    private static Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (Camera.Size s : sizes) {
            int score = Math.abs(s.width - 1280) + Math.abs(s.height - 720);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private int displayOrientation(Camera.CameraInfo info) {
        int degrees = 0;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (info.orientation + degrees) % 360;
            return (360 - result) % 360; // compensate for the mirror
        }
        return (info.orientation - degrees + 360) % 360;
    }

    // ---- Saving ----

    private void save(Bitmap bmp) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "EdgeCam_" + System.currentTimeMillis() + ".png");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EdgeCam");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("저장 위치를 만들 수 없음");

            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IllegalStateException("파일을 열 수 없음");
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            } finally {
                os.close();
            }

            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            toast("갤러리 Pictures/EdgeCam에 저장했어요");
        } catch (Exception e) {
            toast("저장 실패: " + e.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    // ---- UI helpers ----

    /** A label + SeekBar row; the SeekBar is child index 1. */
    private LinearLayout makeSlider(String label, int initial, final IntCallback cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cb.on(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1f));

        cb.on(initial);
        return row;
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(8), dp(4), 0);
            row.addView(b, lp);
        }
        return row;
    }

    private Button makeButton(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }
}
