package com.jingxin.pandrive.gl;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

/**
 * TextureView + 自建 EGL 渲染线程
 * 解决 GLSurfaceView.setZOrderOnTop(true) 在窗口模式下触摸和渲染异常的问题
 *
 * TextureView 是普通 View，在窗口 View 层级内，触摸事件正常路由
 * 同时支持透明背景叠加，不需要 setZOrderOnTop
 *
 * 修复 Android 8.1 窗口模式下的问题：
 * 1. eglSwapBuffers 失败时重建 EGL Surface 而非退出线程
 * 2. TextureView 尺寸变化时重建 EGL Surface（解决缓冲区尺寸不匹配）
 * 3. GL 线程死亡检测与自动重启
 */
public class GlTextureRenderer implements TextureView.SurfaceTextureListener,
        Car3DRenderer.RenderRequester {

    private static final String TAG = "GL";

    private final GLSurfaceView.Renderer renderer;
    private GLThread glThread;
    // 保存 EGL config 供重建 surface 时复用
    private EGLConfig eglConfig;
    private TextureView textureView;

    public GlTextureRenderer(GLSurfaceView.Renderer renderer) {
        this.renderer = renderer;
    }

    public void setTextureView(TextureView textureView) {
        this.textureView = textureView;
        textureView.setSurfaceTextureListener(this);
    }

    // ==================== SurfaceTextureListener ====================

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        glThread = new GLThread(surface, width, height);
        glThread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (glThread != null && glThread.isAlive()) {
            // 窗口模式下尺寸变化，需要重建 EGL Surface 以匹配新的缓冲区尺寸
            glThread.onRecreateSurface(surface, width, height);
        } else if (glThread != null && !glThread.isAlive()) {
            // 线程已死（eglSwapBuffers 失败等），重启
            try { glThread.join(1000); } catch (InterruptedException ignored) {}
            glThread = new GLThread(surface, width, height);
            glThread.start();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (glThread != null) {
            glThread.requestExit();
            try {
                glThread.join(3000);
            } catch (InterruptedException ignored) {}
            glThread = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    // ==================== Render control ====================

    public void requestRender() {
        boolean alive = glThread != null && glThread.isAlive();
        if (alive) {
            glThread.requestRender();
        } else if (textureView != null && textureView.isAvailable()) {
            // 线程已死，尝试重启
            if (glThread != null) {
                try { glThread.join(1000); } catch (InterruptedException ignored) {}
            }
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                glThread = new GLThread(st, textureView.getWidth(), textureView.getHeight());
                glThread.start();
            }
        }
    }

    public void onPause() {
        if (glThread != null) {
            glThread.onPause();
        }
    }

    public void onResume() {
        if (glThread != null) {
            glThread.onResume();
        }
    }

    /**
     * 检查 GL 线程是否存活
     */
    public boolean isGLThreadAlive() {
        return glThread != null && glThread.isAlive();
    }

    // ==================== GL Thread ====================

    private class GLThread extends Thread {
        private volatile SurfaceTexture surfaceTexture;
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private Surface surface;

        private volatile boolean running = true;
        private volatile boolean paused = false;
        private boolean renderRequested = true;
        private int width, height;
        private volatile boolean resized = false;
        // 标记需要重建 EGL Surface（尺寸变化或 swapBuffers 失败时）
        private volatile boolean surfaceRecreateRequested = false;
        private volatile SurfaceTexture newSurfaceTexture;

        private static final int MAX_SWAP_FAIL_RETRY = 3;
        private int consecutiveSwapFailCount = 0;

        private final Object lock = new Object();

        GLThread(SurfaceTexture surface, int width, int height) {
            this.surfaceTexture = surface;
            this.width = width;
            this.height = height;
        }

        /**
         * 请求重建 EGL Surface（在 TextureView 尺寸变化时调用）
         * 重建 EGL Surface 以匹配新的缓冲区尺寸，解决 Android 8.1 窗口模式问题
         */
        void onRecreateSurface(SurfaceTexture surface, int w, int h) {
            synchronized (lock) {
                newSurfaceTexture = surface;
                width = w;
                height = h;
                surfaceRecreateRequested = true;
                resized = true;
                renderRequested = true;
                lock.notifyAll();
            }
        }

        void onResize(int w, int h) {
            synchronized (lock) {
                width = w;
                height = h;
                resized = true;
                renderRequested = true;
                lock.notifyAll();
            }
        }

        void requestRender() {
            synchronized (lock) {
                renderRequested = true;
                lock.notifyAll();
            }
        }

        void requestExit() {
            synchronized (lock) {
                running = false;
                lock.notifyAll();
            }
        }

        void onPause() {
            synchronized (lock) {
                paused = true;
                lock.notifyAll();
            }
        }

        void onResume() {
            synchronized (lock) {
                paused = false;
                renderRequested = true;
                lock.notifyAll();
            }
        }

        @Override
        public void run() {
            try {
                eglConfig = null; // 将在 initEGL 中设置
                if (!initEGL()) {
                    return;
                }

                // 首次创建
                renderer.onSurfaceCreated(null, null);
                renderer.onSurfaceChanged(null, width, height);

                // 渲染循环
                while (running) {
                    synchronized (lock) {
                        while (running && !renderRequested && !paused) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        if (!running) {
                            break;
                        }
                        if (paused) {
                            // 暂停时等待恢复
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                break;
                            }
                            continue;
                        }
                        renderRequested = false;
                    }

                    // 重建 EGL Surface（尺寸变化或 swapBuffers 失败后）
                    if (surfaceRecreateRequested) {
                        surfaceRecreateRequested = false;
                        if (!recreateEGLSurface()) {
                            break;
                        }
                        renderer.onSurfaceChanged(null, width, height);
                        consecutiveSwapFailCount = 0;
                    }

                    if (resized) {
                        resized = false;
                        renderer.onSurfaceChanged(null, width, height);
                    }

                    renderer.onDrawFrame(null);

                    // 检查是否需要下一帧（动画活跃时）
                    if (renderer instanceof Car3DRenderer) {
                        if (((Car3DRenderer) renderer).needsNextFrame()) {
                            renderRequested = true;
                        }
                    }

                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                        consecutiveSwapFailCount++;

                        if (consecutiveSwapFailCount <= MAX_SWAP_FAIL_RETRY) {
                            // 尝试重建 EGL Surface 而非退出线程
                            surfaceRecreateRequested = false; // 先清除，下面直接重建
                            if (recreateEGLSurface()) {
                                renderer.onSurfaceChanged(null, width, height);
                                renderRequested = true; // 请求重绘
                            } else {
                                break;
                            }
                        } else {
                            break;
                        }
                    } else {
                        consecutiveSwapFailCount = 0;
                    }
                }
            } finally {
                cleanupEGL();
            }
        }

        /**
         * 重建 EGL Window Surface（不重建 EGL Context 和 Display）
         * 用于 TextureView 尺寸变化或 eglSwapBuffers 失败时
         * 
         * 关键：必须从 TextureView 获取最新的 SurfaceTexture，
         * 因为 Android 8.1 窗口模式下系统可能替换底层 BufferQueue，
         * 存储的旧 SurfaceTexture 引用可能已失效
         */
        private boolean recreateEGLSurface() {
            // 1. 解绑当前 surface
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            // 2. 销毁旧 EGL Surface
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            // 3. 释放旧 Surface 对象
            if (surface != null) {
                surface.release();
                surface = null;
            }
            // 4. 获取最新 SurfaceTexture
            // 优先级：onRecreateSurface传入的 > TextureView当前持有的 > 存储的旧引用
            SurfaceTexture st = newSurfaceTexture;
            if (st == null && textureView != null) {
                st = textureView.getSurfaceTexture();
            }
            if (st == null) {
                st = surfaceTexture;
            }
            if (st == null) {
                return false;
            }
            // 更新存储的引用，确保后续用最新的
            surfaceTexture = st;
            // 设置 SurfaceTexture 缓冲区尺寸以匹配 TextureView
            st.setDefaultBufferSize(width, height);
            surface = new Surface(st);
            // 5. 创建新 EGL Surface
            if (eglConfig == null) {
                return false;
            }
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                return false;
            }
            // 6. 绑定新 Surface
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                return false;
            }
            return true;
        }

        private boolean initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                return false;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                return false;
            }

            // EGL config: RGBA8888 + depth16 + ES2
            int[] configAttrs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0)) {
                return false;
            }
            eglConfig = configs[0]; // 保存供重建 surface 时复用

            int[] contextAttrs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };

            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig,
                    EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                return false;
            }

            // 从 SurfaceTexture 创建 Surface，再创建 EGL Surface
            surfaceTexture.setDefaultBufferSize(width, height);
            surface = new Surface(surfaceTexture);
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                return false;
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                return false;
            }

            return true;
        }

        private void cleanupEGL() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay);
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}
