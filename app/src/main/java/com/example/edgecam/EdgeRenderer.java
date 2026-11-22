package com.example.edgecam;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Renders the camera feed as white outlines on a pure black background.
 *
 * Pass 1: camera (external OES texture) -> offscreen texture A, upright, at camera resolution.
 * Pass 2: Sobel edge detection on texture A -> screen (center-cropped to fill), or -> texture B for saving.
 */
public class EdgeRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface Listener {
        /** Called on the GL thread whenever a new camera SurfaceTexture exists. */
        void onSurfaceTextureReady(SurfaceTexture st);

        /** Called on the GL thread with a captured, upright edge image. */
        void onCaptured(Bitmap bmp);
    }

    private static final String VS_OES =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FS_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n";

    private static final String VS_EDGE =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform vec2 uScale;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos * uScale, 0.0, 1.0);\n" +
            "  vTex = aTex;\n" +
            "}\n";

    private static final String FS_EDGE =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec2 uStep;\n" +
            "uniform float uThreshold;\n" +
            "float lum(vec2 o) {\n" +
            "  return dot(texture2D(uTex, vTex + o * uStep).rgb, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "void main() {\n" +
            "  float tl = lum(vec2(-1.0,  1.0));\n" +
            "  float t  = lum(vec2( 0.0,  1.0));\n" +
            "  float tr = lum(vec2( 1.0,  1.0));\n" +
            "  float l  = lum(vec2(-1.0,  0.0));\n" +
            "  float r  = lum(vec2( 1.0,  0.0));\n" +
            "  float bl = lum(vec2(-1.0, -1.0));\n" +
            "  float b  = lum(vec2( 0.0, -1.0));\n" +
            "  float br = lum(vec2( 1.0, -1.0));\n" +
            "  float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);\n" +
            "  float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);\n" +
            "  float m = sqrt(gx * gx + gy * gy);\n" +
            "  float e = smoothstep(uThreshold, uThreshold * 1.25 + 0.02, m);\n" +
            "  gl_FragColor = vec4(vec3(e), 1.0);\n" +
            "}\n";

    private final GLSurfaceView view;
    private final Listener listener;

    private FloatBuffer quad;
    private int progOes;
    private int progEdge;
    private int oesTex;
    private SurfaceTexture surfaceTexture;
    private final float[] texMatrix = new float[16];

    private int viewW = 1;
    private int viewH = 1;

    /** Upright camera frame size packed as (width << 32) | height. Written from the UI thread. */
    private volatile long camSize = 0;

    private int fboA, texA, fboAW, fboAH;
    private int fboB, texB, fboBW, fboBH;

    private volatile float threshold = 0.4f;
    private volatile float thickness = 1.75f;
    private volatile boolean captureRequested = false;
    private volatile boolean stereo = false;

    public EdgeRenderer(GLSurfaceView view, Listener listener) {
        this.view = view;
        this.listener = listener;
    }

    /** s in [0,1]: higher = more lines. */
    public void setSensitivity(float s) {
        threshold = 1.0f - s * 0.92f;
        view.requestRender();
    }

    /** t in [0,1]: higher = thicker, bolder lines. */
    public void setThickness(float t) {
        thickness = 1.0f + t * 3.0f;
        view.requestRender();
    }

    /** Size of the camera frame after rotation to the screen orientation. */
    public void setCameraSize(int w, int h) {
        camSize = ((long) w << 32) | (h & 0xffffffffL);
        view.requestRender();
    }

    /** Side-by-side view for phone VR viewers. */
    public void setStereo(boolean on) {
        stereo = on;
        view.requestRender();
    }

    public void requestCapture() {
        captureRequested = true;
        view.requestRender();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        view.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        float[] verts = {
                // x,   y,   u,   v
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
        };
        quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(verts).position(0);

        progOes = buildProgram(VS_OES, FS_OES);
        progEdge = buildProgram(VS_EDGE, FS_EDGE);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oesTex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // A new GL context means old framebuffer handles are gone.
        fboA = texA = fboAW = fboAH = 0;
        fboB = texB = fboBW = fboBH = 0;

        if (surfaceTexture != null) {
            surfaceTexture.release();
        }
        surfaceTexture = new SurfaceTexture(oesTex);
        surfaceTexture.setOnFrameAvailableListener(this);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        listener.onSurfaceTextureReady(surfaceTexture);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (surfaceTexture == null) return;
        try {
            surfaceTexture.updateTexImage();
        } catch (RuntimeException e) {
            return;
        }
        surfaceTexture.getTransformMatrix(texMatrix);

        long cs = camSize;
        int cw = (int) (cs >>> 32);
        int ch = (int) cs;
        if (cw <= 0 || ch <= 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, viewW, viewH);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        // Pass 1: camera -> texture A (upright).
        if (fboAW != cw || fboAH != ch) {
            int[] r = makeFbo(cw, ch, fboA, texA);
            fboA = r[0];
            texA = r[1];
            fboAW = cw;
            fboAH = ch;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA);
        GLES20.glViewport(0, 0, cw, ch);
        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uTexMatrix"), 1, false, texMatrix, 0);
        drawQuad(progOes);

        // Pass 2: edges -> screen.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float camAspect = cw / (float) ch;
        if (stereo) {
            // VR viewer: the same picture once per eye, side by side. Fit (not crop) so the whole
            // field of view stays visible; the leftover bars are black like everything else.
            int eyeW = viewW / 2;
            float eyeAspect = eyeW / (float) viewH;
            float sx = 1f, sy = 1f;
            if (eyeAspect > camAspect) {
                sx = camAspect / eyeAspect;
            } else {
                sy = eyeAspect / camAspect;
            }
            GLES20.glViewport(0, 0, eyeW, viewH);
            drawEdges(cw, ch, sx, sy);
            GLES20.glViewport(viewW - eyeW, 0, eyeW, viewH);
            drawEdges(cw, ch, sx, sy);
            GLES20.glViewport(0, 0, viewW, viewH);
        } else {
            // Normal: center-crop so the image fills the screen without stretching.
            float viewAspect = viewW / (float) viewH;
            float sx = 1f, sy = 1f;
            if (viewAspect > camAspect) {
                sy = viewAspect / camAspect;
            } else {
                sx = camAspect / viewAspect;
            }
            drawEdges(cw, ch, sx, sy);
        }

        if (captureRequested) {
            captureRequested = false;
            capture(cw, ch);
        }
    }

    private void capture(int cw, int ch) {
        if (fboBW != cw || fboBH != ch) {
            int[] r = makeFbo(cw, ch, fboB, texB);
            fboB = r[0];
            texB = r[1];
            fboBW = cw;
            fboBH = ch;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB);
        GLES20.glViewport(0, 0, cw, ch);
        drawEdges(cw, ch, 1f, 1f);

        ByteBuffer buf = ByteBuffer.allocateDirect(cw * ch * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, cw, ch, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);

        buf.rewind();
        Bitmap raw = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buf);
        // glReadPixels returns rows bottom-up; flip to upright.
        Matrix flip = new Matrix();
        flip.preScale(1f, -1f);
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, cw, ch, flip, false);
        raw.recycle();
        listener.onCaptured(upright);
    }

    private void drawEdges(int cw, int ch, float sx, float sy) {
        GLES20.glUseProgram(progEdge);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progEdge, "uTex"), 0);
        float th = thickness;
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progEdge, "uStep"), th / cw, th / ch);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progEdge, "uThreshold"), threshold);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progEdge, "uScale"), sx, sy);
        drawQuad(progEdge);
    }

    private void drawQuad(int prog) {
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aTex = GLES20.glGetAttribLocation(prog, "aTex");
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    private static int[] makeFbo(int w, int h, int oldFbo, int oldTex) {
        if (oldFbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{oldFbo}, 0);
        if (oldTex != 0) GLES20.glDeleteTextures(1, new int[]{oldTex}, 0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, t[0], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return new int[]{f[0], t[0]};
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Program link failed: " + log);
        }
        return p;
    }
}
