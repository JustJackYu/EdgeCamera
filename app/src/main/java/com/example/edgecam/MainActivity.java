package com.example.edgecam;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

/**
 * Real-time "outline vision" camera: everything is black, only edges show as white lines.
 * Tap the picture to hide/show the controls.
 */
@SuppressWarnings("deprecation") // android.hardware.Camera is deprecated but simple and still supported.
public class MainActivity extends Activity implements EdgeRenderer.Listener {

    private static final int REQ_CAMERA = 1;

    private interface IntCallback {
        void on(int value);
    }

    private GLSurfaceView glView;
    private EdgeRenderer renderer;
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private boolean useFront = false;
    private boolean resumed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new EdgeRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x99000000);
        final int pad = dp(16);
        panel.setPadding(pad, pad, pad, pad);

        panel.addView(makeSlider("감도", 65, new IntCallback() {
            @Override
            public void on(int v) {
                renderer.setSensitivity(v / 100f);
            }
        }));
        panel.addView(makeSlider("선 굵기", 25, new IntCallback() {
            @Override
            public void on(int v) {
                renderer.setThickness(v / 100f);
            }
        }));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button switchBtn = makeButton("카메라 전환");
        switchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchCamera();
            }
        });
        Button shotBtn = makeButton("촬영");
        shotBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderer.requestCapture();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMargins(dp(4), dp(8), dp(4), 0);
        row.addView(switchBtn, blp);
        row.addView(shotBtn, new LinearLayout.LayoutParams(blp));
        panel.addView(row);

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Keep the controls above the navigation bar.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                panel.setPadding(pad, pad, pad, pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        // Tap the picture to hide/show the controls.
        glView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glView.onResume();
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraIfReady();
        } else {
            toast("카메라 권한이 있어야 쓸 수 있어요");
        }
    }

    // ---- EdgeRenderer.Listener (called on the GL thread) ----

    @Override
    public void onSurfaceTextureReady(final SurfaceTexture st) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                surfaceTexture = st;
                stopCamera();
                startCameraIfReady();
            }
        });
    }

    @Override
    public void onCaptured(final Bitmap bmp) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                save(bmp);
            }
        }).start();
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

    private View makeSlider(String label, int initial, final IntCallback cb) {
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

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
