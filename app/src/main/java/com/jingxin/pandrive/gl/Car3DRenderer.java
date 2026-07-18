package com.jingxin.pandrive.gl;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 3D车辆渲染器 - 多Mesh + Phong光照 + 透明排序 + 语义着色
 */
public class Car3DRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "Car3DRenderer";

    private final Context context;
    private static final String PREFS_NAME = "car3d_prefs";
    private static final String KEY_ROT_X = "rot_x";
    private static final String KEY_ROT_Y = "rot_y";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_AUTO_ROTATE = "auto_rotate";
    private static final String KEY_MODEL_PATH = "model_path";

    // 内置默认模型（assets），"assets://" 前缀路径表示从APK内置assets加载，不依赖外部存储
    private static final String ASSETS_PREFIX = "assets://";
    private static final String DEFAULT_ASSET_MODEL = "default_car.glb";

    // Shader locations
    private int program;
    private int aPositionLoc;
    private int aNormalLoc;
    private int aTexCoordLoc;
    private int uMVPMatrixLoc;
    private int uModelMatrixLoc;
    private int uTextureLoc;
    private int uHasTextureLoc;
    private int uBaseColorLoc;
    private int uTurnSignalLoc;
    private int uIsLightLoc;
    private int uBrakeSignalLoc;
    private int uLightDirLoc;

    // Model data
    private final List<GlbParser.DrawUnit> drawUnits = new ArrayList<>();
    private boolean modelLoaded = false;
    private int dummyTextureId;

    // Model transform (volatile: 主线程写 → GL线程读)
    private volatile float rotX;
    private volatile float rotY;
    private volatile float mScale;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 5.0f;
    private float modelCenterX, modelCenterY, modelCenterZ;
    private float modelNormalizeScale = 1f; // 归一化缩放，使模型约2单位宽

    // Auto rotation
    private boolean autoRotateEnabled;
    private boolean autoRotatePaused = false;
    private long lastFrameTime = 0;

    // 到达终点旋转2圈 (volatile: 主线程写 → GL线程读)
    private volatile boolean arrivalSpinActive = false;
    private volatile float arrivalSpinRemain = 0f; // 剩余旋转角度

    // 巡航模式：GPS bearing变化率驱动的转向
    private float lastAzimuth = -1f;
    private long lastAzimuthTimeMs = 0;
    private volatile float cruiseTargetRotY = 0f;   // 巡航偏转目标角度 (主线程写 → GL线程读)
    private float cruiseCurrentRotY = 0f;  // 巡航偏转当前角度（仅GL线程读写）
    private static final float CRUISE_RATE_THRESHOLD = 5f;    // 角速度阈值(°/s)，低于此视为直行
    private static final float CRUISE_ROT_MULTIPLIER = 2.5f;  // 角速度→偏转角放大系数
    private static final float CRUISE_MAX_ROT = 30f;          // 巡航最大偏转角
    private static final float CRUISE_MIN_SPEED = 5;          // 巡航转向最低车速(km/h)

    // Navigation rotation (volatile: 主线程写 → GL线程读)
    private volatile float naviTargetRotY = 0f;
    private float naviCurrentRotY = 0f;
    private float naviOscillationPhase = 0f;
    private int currentNaviIcon = 0;
    private float naviSegRemainMeters = -1f;
    private static final float NAVI_ROTATE_THRESHOLD_METERS = 100f;

    // 导航图标ID到偏转角度映射
    private static final float NAVI_LEFT_TURN_DEG = 30f;       // iconId=2 左转
    private static final float NAVI_RIGHT_TURN_DEG = -30f;     // iconId=3 右转
    private static final float NAVI_LEFT_FRONT_DEG = 15f;      // iconId=4 左前方
    private static final float NAVI_RIGHT_FRONT_DEG = -15f;    // iconId=5 右前方
    private static final float NAVI_LEFT_UTURN_DEG = 180f;     // iconId=8 左调头
    private static final float NAVI_RIGHT_UTURN_DEG = -180f;   // iconId=19 右调头
    private static final float NAVI_LEFT_SLIGHT_DEG = 10f;     // iconId=65 左微转
    private static final float NAVI_RIGHT_SLIGHT_DEG = -10f;   // iconId=66 右微转

    // ==================== 演示动画 ====================
    // 4个动画：0=放大还原, 1=缩小还原, 2=旋转展示, 3=侧面旋转
    // 每10分钟为一个周期，4个动画各随机播放一次，间隔>=1分钟
    private static final int DEMO_ANIM_COUNT = 4;
    private static final long DEMO_CYCLE_MS = 10 * 60 * 1000;      // 10分钟周期
    private static final long DEMO_MIN_GAP_MS = 60 * 1000;          // 动画间隔至少1分钟
    private static final float DEMO_ZOOM_MAX = 5.0f;                 // 动画1/2: 最大缩放倍数
    private static final float DEMO_SHRINK_MIN = 0.2f;               // 动画2: 最小缩放倍数(1/5)
    private static final long DEMO_ZOOM_DURATION_MS = 10000;         // 动画1/2: 10秒
    private static final long DEMO_SHOWCASE_DURATION_MS = 16000;     // 动画3: 旋转展示16秒(4×4秒)
    private static final float DEMO_SHOWCASE_ZOOM_MAX = 5.0f;        // 动画3: 放大5倍
    private static final long DEMO_SIDE_DURATION_MS = 10000;         // 动画4: 侧面旋转10秒
    private static final float DEMO_SIDE_ZOOM_MAX = 2.0f;            // 动画4: 最大缩放2倍

    private int demoCurrentAnim = -1;          // 当前正在播放的动画ID(-1=空闲)
    private int demoPhase = 0;                 // 当前动画阶段(多阶段动画用)
    private long demoStartTime = 0;            // 当前阶段开始时间
    private float demoRotY = 0f;               // 演示Y轴旋转叠加角度
    private float demoScaleFactor = 1f;        // 演示缩放叠加因子

    // 周期调度
    private long cycleStartTime = 0;           // 当前周期起始时间
    private int[] demoOrder = null;            // 本周期动画播放顺序
    private long[] demoScheduleTimes = null;   // 本周期各动画触发时间点(相对周期起始)
    private int demoPlayedIndex = 0;           // 本周期已播放到第几个

    // 转向灯
    private static final long BLINK_PERIOD_MS = 500;  // 闪烁周期500ms
    private float currentTurnSignal = 0f;  // -1=左转 0=无 1=右转

    // 刹车灯 (volatile: 主线程写 → GL线程读)
    private volatile boolean isBraking = false;
    private int lastSpeedKmh = 0;         // 上次车速(km/h)
    private long lastSpeedTimeMs = 0;     // 上次车速时间

    // 默认视角与触控限制
    private float defaultRotX = 0f;
    private float defaultRotY = 180f;
    private volatile int currentSpeedKmh = 0;       // 当前车速 (主线程写 → GL线程读)
    private static final int BRAKE_DELTA_THRESHOLD = 8;  // 减速超过8km/h视为刹车
    private static final int BRAKE_MIN_SPEED = 5;         // 低于5km/h不触发刹车灯
    private long brakeEndTimeMs = 0;      // 刹车结束时间
    private static final long BRAKE_LINGER_MS = 1000;     // 刹车灯延迟1秒熄灭

    private volatile String modelPath = null; // null表示自动搜索下载目录 (主线程写 → GL线程读)
    private volatile boolean pendingLoad = false; // 主线程写 → GL线程读
    private volatile boolean loadFailed = false; // 加载失败标记 (主线程写 → GL线程读)
    private final java.util.Set<String> failedPaths = new java.util.HashSet<>(); // 加载失败的模型路径
    private int switchRetryCount = 0; // 连续切换失败计数，防止死循环
    private static final int MAX_SWITCH_RETRIES = 5;

    // Pre-allocated matrices
    private final float[] projMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];
    private final float[] tempMatrix = new float[16];
    private final float[] rotXMat = new float[16];
    private final float[] rotYMat = new float[16];
    private final float[] scaleMat = new float[16];
    private final float[] transMat = new float[16];
    private final float[] bmTemp1 = new float[16];
    private final float[] bmTemp2 = new float[16];

    // Pending bitmaps for re-upload when surface recreated
    private List<Bitmap> pendingBitmaps = new ArrayList<>();

    public interface RenderRequester {
        void requestRender();
    }
    public interface OnSimSpeedListener {
        void onSimSpeed(int speedKmh);
    }
    public interface OnSimNaviListener {
        void onSimNavi(int iconId, float remainMeters, String roadName,
                       String segRemainDis, String nextRoadName, int speedLimit,
                       String routeRemainDis, int remainTimeSec, String etaText);
    }
    private RenderRequester renderRequester;
    private OnSimSpeedListener simSpeedListener;
    private OnSimNaviListener simNaviListener;

    private boolean needsNextFrame = false;

    // ==================== Shaders ====================

    private static final String VERTEX_SHADER =
            "attribute vec3 aPosition;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uModelMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vWorldPos;\n" +
            "varying vec3 vLocalPos;\n" +    // 模型空间坐标，用于判断灯的左右
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "    vNormal = mat3(uModelMatrix) * aNormal;\n" +
            "    vWorldPos = (uModelMatrix * vec4(aPosition, 1.0)).xyz;\n" +
            "    vLocalPos = aPosition;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vWorldPos;\n" +
            "varying vec3 vLocalPos;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uHasTexture;\n" +
            "uniform vec4 uBaseColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uTurnSignal;\n" +   // -1=左转 0=无 1=右转
            "uniform float uIsLight;\n" +       // 1=灯部件 0=非灯
            "uniform float uBrakeSignal;\n" +   // 1=刹车 0=无
            "void main() {\n" +
            "    vec3 N = normalize(vNormal);\n" +
            "    vec3 L = normalize(uLightDir);\n" +
            "    float diff = max(dot(N, L), 0.0);\n" +
            "    vec4 texCol = vec4(1.0);\n" +
            "    if (uHasTexture > 0.5) {\n" +
            "        texCol = texture2D(uTexture, vTexCoord);\n" +
            "        if (texCol.a < 0.02) discard;\n" +
            "    }\n" +
            "    vec4 baseCol = uBaseColor * texCol;\n" +
            "    float ambient = 0.35;\n" +
            "    vec3 color = baseCol.rgb * (ambient + diff * 0.65);\n" +
            "    // 刹车灯：灯部件在车尾(模型空间Z<0)时红色常亮\n" +
            "    if (uIsLight > 0.5 && uBrakeSignal > 0.5 && vLocalPos.z < 0.0) {\n" +
            "        color += vec3(1.0, 0.1, 0.05);\n" +
            "    }\n" +
            "    // 转向灯：用模型空间X坐标判断左右，不受旋转影响\n" +
            "    if (uIsLight > 0.5 && uTurnSignal != 0.0) {\n" +
            "        bool shouldGlow = (abs(uTurnSignal) > 1.5) || (uTurnSignal * sign(vLocalPos.x) > 0.0);\n" +
            "        if (shouldGlow) {\n" +
            "            color += vec3(1.0, 0.6, 0.1);\n" +
            "        }\n" +
            "    }\n" +
            "    vec3 V = normalize(vec3(0.0, 1.5, 3.5) - vWorldPos);\n" +
            "    vec3 H = normalize(L + V);\n" +
            "    float spec = pow(max(dot(N, H), 0.0), 32.0);\n" +
            "    color += vec3(1.0) * spec * 0.3;\n" +
            "    gl_FragColor = vec4(color, baseCol.a);\n" +
            "}\n";

    public Car3DRenderer(Context context) {
        this.context = context.getApplicationContext(); // 使用ApplicationContext避免Activity泄漏
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.rotX = prefs.getFloat(KEY_ROT_X, -15f);
        this.rotY = prefs.getFloat(KEY_ROT_Y, -30f);
        this.mScale = prefs.getFloat(KEY_SCALE, 1.8f);
        this.autoRotateEnabled = prefs.getBoolean(KEY_AUTO_ROTATE, false);
        // 读取上次使用的模型路径（内置 assets:// 模型不检查文件存在性）
        String lastPath = prefs.getString(KEY_MODEL_PATH, null);
        if (lastPath != null) {
            if (lastPath.startsWith(ASSETS_PREFIX)) {
                this.modelPath = lastPath;
            } else if (new File(lastPath).exists()) {
                this.modelPath = lastPath;
            }
        }
    }

    public void setRenderRequester(RenderRequester requester) { this.renderRequester = requester; }
    public void setSimSpeedListener(OnSimSpeedListener listener) { this.simSpeedListener = listener; }
    public void setSimNaviListener(OnSimNaviListener listener) { this.simNaviListener = listener; }
    public void requestRender() { if (renderRequester != null) renderRequester.requestRender(); }
    public boolean needsNextFrame() { return needsNextFrame; }

    public void setModelPath(String path) { this.modelPath = path; }
    public void setAutoRotate(boolean enabled) { this.autoRotateEnabled = enabled; }
    public void setRotation(float rx, float ry) { this.rotX = rx; this.rotY = ry; }
    public float getRotX() { return rotX; }
    public float getRotY() { return rotY; }
    public float getScale() { return mScale; }
    public void setScale(float scale) { mScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale)); }
    public boolean canDrag() { return currentSpeedKmh == 0; }

    // ==================== 导航转向 ====================

    public void setSpeed(int speedKmh) {
        long now = System.currentTimeMillis();
        if (lastSpeedTimeMs > 0 && lastSpeedKmh > BRAKE_MIN_SPEED) {
            int delta = speedKmh - lastSpeedKmh;
            long dt = now - lastSpeedTimeMs;
            // 归一化到1秒的减速量
            if (dt > 0) {
                float deltaPerSec = delta * 1000f / dt;
                if (deltaPerSec < -BRAKE_DELTA_THRESHOLD) {
                    isBraking = true;
                    brakeEndTimeMs = 0;
                } else if (deltaPerSec >= 0 || speedKmh <= BRAKE_MIN_SPEED) {
                    // 速度回升或极低速，进入刹车延迟熄灭
                    if (isBraking && brakeEndTimeMs == 0) {
                        brakeEndTimeMs = now;
                    }
                }
            }
        }
        // 刹车灯延迟熄灭
        if (isBraking && brakeEndTimeMs > 0 && now - brakeEndTimeMs > BRAKE_LINGER_MS) {
            isBraking = false;
            brakeEndTimeMs = 0;
        }
        // 车速从0变非0时恢复默认视角
        if (currentSpeedKmh == 0 && speedKmh > 0) {
            rotX = defaultRotX;
            rotY = defaultRotY;
            naviCurrentRotY = 0f;
            naviTargetRotY = 0f;
        }
        currentSpeedKmh = speedKmh;
        lastSpeedKmh = speedKmh;
        lastSpeedTimeMs = now;
        requestRender();
    }

    /** 巡航模式：接收方位角变化，计算转弯角速度映射为车头偏转 */
    public void setAzimuth(float azimuth) {
        long now = System.currentTimeMillis();
        if (lastAzimuthTimeMs > 0 && currentSpeedKmh >= CRUISE_MIN_SPEED) {
            float dt = (now - lastAzimuthTimeMs) / 1000f;
            if (dt > 0.1f && dt < 5f) {  // 忽略太频繁或太久的间隔
                // 360°环绕修正
                float delta = azimuth - lastAzimuth;
                if (delta > 180f) delta -= 360f;
                if (delta < -180f) delta += 360f;
                float rate = delta / dt;  // °/s，正值=右转，负值=左转
                if (Math.abs(rate) > CRUISE_RATE_THRESHOLD) {
                    cruiseTargetRotY = Math.max(-CRUISE_MAX_ROT, Math.min(CRUISE_MAX_ROT, -rate * CRUISE_ROT_MULTIPLIER));
                } else {
                    cruiseTargetRotY = 0f;  // 直行回正
                }
            }
        } else if (currentSpeedKmh < CRUISE_MIN_SPEED) {
            cruiseTargetRotY = 0f;
        }
        lastAzimuth = azimuth;
        lastAzimuthTimeMs = now;
        requestRender();
    }

    public void setNaviIcon(int iconId, float segRemainMeters) {
        naviSegRemainMeters = segRemainMeters;
        float iconTargetRotY;
        switch (iconId) {
            case 2:  iconTargetRotY = NAVI_LEFT_TURN_DEG; break;
            case 3:  iconTargetRotY = NAVI_RIGHT_TURN_DEG; break;
            case 4:  iconTargetRotY = NAVI_LEFT_FRONT_DEG; break;
            case 5:  iconTargetRotY = NAVI_RIGHT_FRONT_DEG; break;
            case 8:  iconTargetRotY = NAVI_LEFT_UTURN_DEG; break;
            case 19: iconTargetRotY = NAVI_RIGHT_UTURN_DEG; break;
            case 65: iconTargetRotY = NAVI_LEFT_SLIGHT_DEG; break;
            case 66: iconTargetRotY = NAVI_RIGHT_SLIGHT_DEG; break;
            default: iconTargetRotY = 0f; break;
        }
        if (iconTargetRotY != 0f && segRemainMeters >= 0f && segRemainMeters >= NAVI_ROTATE_THRESHOLD_METERS) {
            naviTargetRotY = 0f;
        } else {
            naviTargetRotY = iconTargetRotY;
        }
        requestRender();
    }

    // ==================== Surface lifecycle ====================

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        // 删除旧shader program（Surface重建时GL上下文已丢失，但显式清理更安全）
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aNormalLoc = GLES20.glGetAttribLocation(program, "aNormal");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uMVPMatrixLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        uModelMatrixLoc = GLES20.glGetUniformLocation(program, "uModelMatrix");
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");
        uHasTextureLoc = GLES20.glGetUniformLocation(program, "uHasTexture");
        uBaseColorLoc = GLES20.glGetUniformLocation(program, "uBaseColor");
        uTurnSignalLoc = GLES20.glGetUniformLocation(program, "uTurnSignal");
        uIsLightLoc = GLES20.glGetUniformLocation(program, "uIsLight");
        uBrakeSignalLoc = GLES20.glGetUniformLocation(program, "uBrakeSignal");
        uLightDirLoc = GLES20.glGetUniformLocation(program, "uLightDir");

        // 创建 1x1 白色贴图，给无纹理部件用
        dummyTextureId = createDummyTexture();

        if (modelLoaded) {
            reuploadAllTextures();
        } else {
            loadModel(modelPath);
        }
        requestRender();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = (float) width / height;
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.01f, 100f);
        requestRender();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // 检查是否有待加载的模型（切换模型后触发）
        if (pendingLoad && !modelLoaded) {
            loadModel(modelPath);
        }

        // 加载失败时自动切换到另一个模型
        if (loadFailed && switchRetryCount < MAX_SWITCH_RETRIES) {
            switchRetryCount++;
            switchToRandomModel();
        }

        if (!modelLoaded) return;

        needsNextFrame = false;

        // Arrival spin: 到达终点后自动旋转2圈(720°)
        if (arrivalSpinActive) {
            long now = System.nanoTime();
            if (lastFrameTime > 0) {
                float dt = (now - lastFrameTime) / 1e9f;
                float spinSpeed = 180f; // 每秒180°，4秒转完720°
                float increment = spinSpeed * dt;
                if (increment > arrivalSpinRemain) {
                    increment = arrivalSpinRemain;
                    arrivalSpinActive = false;
                }
                rotY += increment;
                arrivalSpinRemain -= increment;
            }
            lastFrameTime = now;
            needsNextFrame = true;
        } else if (autoRotateEnabled && !autoRotatePaused && Math.abs(naviTargetRotY) < 0.5f && Math.abs(naviCurrentRotY) < 0.5f) {
            long now = System.nanoTime();
            if (lastFrameTime > 0) rotY += 36f * ((now - lastFrameTime) / 1e9f);
            lastFrameTime = now;
            needsNextFrame = true;
        } else {
            lastFrameTime = System.nanoTime();
        }

        // Navigation rotation
        if (Math.abs(naviTargetRotY) > 0.5f) {
            float diff = naviTargetRotY - naviCurrentRotY;
            if (Math.abs(diff) > 0.5f) {
                naviCurrentRotY += diff * 0.08f;
            } else {
                naviCurrentRotY = naviTargetRotY;
            }
            needsNextFrame = true;
        } else {
            naviOscillationPhase = 0f;
            float diff = 0f - naviCurrentRotY;
            if (Math.abs(diff) > 0.5f) {
                naviCurrentRotY += diff * 0.08f;
                needsNextFrame = true;
            } else {
                naviCurrentRotY = 0f;
            }
        }

        // 巡航模式偏转：GPS bearing变化率驱动（导航模式时不生效）
        if (Math.abs(naviTargetRotY) < 0.5f && Math.abs(naviCurrentRotY) < 0.5f) {
            float diff = cruiseTargetRotY - cruiseCurrentRotY;
            if (Math.abs(diff) > 0.3f) {
                cruiseCurrentRotY += diff * 0.06f;
                needsNextFrame = true;
            } else {
                cruiseCurrentRotY = cruiseTargetRotY;
            }
        } else {
            // 导航模式时，巡航偏转渐归零
            if (Math.abs(cruiseCurrentRotY) > 0.3f) {
                cruiseCurrentRotY *= 0.9f;
                needsNextFrame = true;
            } else {
                cruiseCurrentRotY = 0f;
            }
        }

        // 演示动画：随机转向或缩放
        updateDemoAnimation();

        // 转向灯闪烁
        updateTurnSignalLights();

        // 刹车灯
        updateBrakeLight();

        // Camera
        Matrix.setLookAtM(viewMatrix, 0, 0f, 1.5f, 3.5f, 0f, 0f, 0f, 0f, 1f, 0f);

        // 行驶抖动：车速>0时轻微振动
        float shakeX = 0f, shakeY = 0f;
        if (currentSpeedKmh > 0) {
            double t = System.currentTimeMillis() / 1000.0;
            float intensity = Math.min(currentSpeedKmh / 120f, 1f) * 0.6f; // 最高0.6°
            shakeX = (float) (Math.sin(t * 7.3) * Math.cos(t * 3.1) * intensity);
            shakeY = (float) (Math.cos(t * 5.7) * Math.sin(t * 4.3) * intensity);
            needsNextFrame = true;
        }

        buildModelMatrix(rotY + naviCurrentRotY + cruiseCurrentRotY + demoRotY, shakeX, shakeY);
        // Global MVP = Proj * View * BaseModel
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, tempMatrix, 0);

        // 光照方向（世界空间）
        GLES20.glUseProgram(program);
        GLES20.glUniform3f(uLightDirLoc, 0.5f, 1.0f, 0.8f);

        // 转向灯信号：根据偏转方向设置，闪烁
        GLES20.glUniform1f(uTurnSignalLoc, currentTurnSignal);
        GLES20.glUniform1f(uBrakeSignalLoc, isBraking ? 1.0f : 0.0f);

        // 两遍绘制：不透明→透明
        // 先画不透明（开启背面剔除）
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);

        for (GlbParser.DrawUnit unit : drawUnits) {
            if (!unit.isTransparent) drawUnit(unit);
        }

        // 再画透明（关闭背面剔除，开启混合）
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);

        for (GlbParser.DrawUnit unit : drawUnits) {
            if (unit.isTransparent) drawUnit(unit);
        }

        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    // ==================== 绘制单个 DrawUnit ====================

    private void drawUnit(GlbParser.DrawUnit unit) {
        // 节点变换已在解析时烘焙到顶点数据中，直接用全局矩阵
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uModelMatrixLoc, 1, false, modelMatrix, 0);

        // Vertex attributes: pos(3) + normal(3) + uv(2) = 8 floats, stride = 32 bytes
        unit.vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 32, unit.vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionLoc);

        unit.vertexBuffer.position(3);
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, 32, unit.vertexBuffer);
        GLES20.glEnableVertexAttribArray(aNormalLoc);

        unit.vertexBuffer.position(6);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 32, unit.vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);

        // Texture / Color: uBaseColor always set, texCol * baseColor in shader
        float[] color = getEffectiveColor(unit);
        GLES20.glUniform4f(uBaseColorLoc, color[0], color[1], color[2], color[3]);
        GLES20.glUniform1f(uIsLightLoc, unit.isLight ? 1.0f : 0.0f);
        if (unit.hasTexture && unit.textureId >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, unit.textureId);
            GLES20.glUniform1i(uTextureLoc, 0);
            GLES20.glUniform1f(uHasTextureLoc, 1.0f);
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyTextureId);
            GLES20.glUniform1i(uTextureLoc, 0);
            GLES20.glUniform1f(uHasTextureLoc, 0.0f);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, unit.vertexCount);
    }

    // ==================== 语义着色 ====================

    // 语义颜色常量，避免每帧new float[]
    private static final float[] COLOR_CAR_PAINT = {0.1f, 0.3f, 0.8f, 1f};
    private static final float[] COLOR_GLASS = {0.3f, 0.5f, 0.8f, 0.5f};
    private static final float[] COLOR_TIRE = {0.15f, 0.15f, 0.15f, 1f};
    private static final float[] COLOR_BRAKE = {0.35f, 0.3f, 0.25f, 1f};
    private static final float[] COLOR_LIGHT = {1f, 1f, 0.85f, 1f};
    private static final float[] COLOR_CHROME = {0.85f, 0.85f, 0.88f, 1f};
    private static final float[] COLOR_CARBON = {0.25f, 0.25f, 0.25f, 1f};
    private static final float[] COLOR_CHASSIS = {0.3f, 0.3f, 0.3f, 1f};
    private static final float[] COLOR_INTERIOR = {0.35f, 0.3f, 0.25f, 1f};
    private static final float[] COLOR_DISK = {0.75f, 0.75f, 0.78f, 1f};
    private static final float[] COLOR_GRID = {0.2f, 0.2f, 0.2f, 1f};
    private static final float[] COLOR_RED_GLASS = {0.8f, 0.1f, 0.1f, 0.6f};
    private static final float[] COLOR_DETAILS = {0.5f, 0.5f, 0.5f, 1f};
    private static final float[] COLOR_DEFAULT = {0.6f, 0.6f, 0.6f, 1f};
    private static final float[] COLOR_WHITE = {1f, 1f, 1f, 1f};

    /**
     * 获取传给shader的uBaseColor值，结果缓存到DrawUnit.cachedColor避免每帧分配
     */
    private float[] getEffectiveColor(GlbParser.DrawUnit unit) {
        // 已缓存则直接返回
        if (unit.cachedColor != null) return unit.cachedColor;

        float[] semanticColor = computeSemanticColor(unit);
        float[] result;

        if (unit.hasTexture) {
            String name = (unit.materialName != null) ? unit.materialName.toLowerCase() : "";
            if (name.contains("light") && unit.baseColorFactor != null
                    && unit.baseColorFactor[0] + unit.baseColorFactor[1] + unit.baseColorFactor[2] < 0.6f) {
                result = COLOR_WHITE;
            } else if (name.contains("glass") && unit.baseColorFactor != null
                    && unit.baseColorFactor[0] + unit.baseColorFactor[1] + unit.baseColorFactor[2] < 0.6f) {
                // 玻璃：保留alpha但提亮RGB，需要新数组因为alpha来自baseColorFactor
                result = new float[]{1f, 1f, 1f, unit.baseColorFactor[3]};
            } else {
                result = unit.baseColorFactor != null ? unit.baseColorFactor : COLOR_WHITE;
            }
        } else {
            result = semanticColor;
        }

        unit.cachedColor = result;
        return result;
    }
    
    private float[] computeSemanticColor(GlbParser.DrawUnit unit) {
        String name = (unit.materialName != null) ? unit.materialName.toLowerCase() : "";
        if (name.contains("carpaint") || name.contains("paint"))  return COLOR_CAR_PAINT;
        if (name.contains("glass"))                                return COLOR_GLASS;
        if (name.contains("tire"))                                 return COLOR_TIRE;
        if (name.contains("brake"))                                return COLOR_BRAKE;
        if (name.contains("light"))                                return COLOR_LIGHT;
        if (name.contains("chrome") || name.contains("metal"))    return COLOR_CHROME;
        if (name.contains("carbon"))                               return COLOR_CARBON;
        if (name.contains("chassis"))                              return COLOR_CHASSIS;
        if (name.contains("interior") || name.contains("int") || name.contains("details_int"))  return COLOR_INTERIOR;
        if (name.contains("disk") || name.contains("hub"))        return COLOR_DISK;
        if (name.contains("grid") || name.contains("grille"))     return COLOR_GRID;
        if (name.contains("red") && name.contains("glass"))       return COLOR_RED_GLASS;
        if (name.contains("details") || name.contains("ext"))     return COLOR_DETAILS;
        return COLOR_DEFAULT;
    }



    // ==================== 模型加载 ====================

    /** 同步加载模型（在GL线程执行）：文件搜索+GLB解析+纹理上传 */
    private void loadModel(String path) {
        try {
            // 内置 assets 模型：直接从 APK 读取，跳过存储权限/下载目录检查
            boolean useAssets = path != null && path.startsWith(ASSETS_PREFIX);

            if (!useAssets) {
                // 外部存储模型：API 30+ 需要存储管理权限
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (!Environment.isExternalStorageManager()) {
                        Log.w(TAG, "存储权限未授予");
                        pendingLoad = true;
                        return;
                    }
                }
            }

            // 确定模型来源：外部文件 或 内置 assets
            File modelFile = null;
            String assetName = null;

            if (useAssets) {
                assetName = path.substring(ASSETS_PREFIX.length());
            } else {
                File downloadDir = findDownloadDir();
                if (downloadDir != null) {
                    modelFile = findModelFile(downloadDir, path);
                }
                // 下载目录无可用模型 → 兜底到内置默认模型
                if (modelFile == null) {
                    useAssets = true;
                    assetName = DEFAULT_ASSET_MODEL;
                    Log.w(TAG, "下载目录无可用模型，使用内置默认模型: " + assetName);
                }
            }

            // 统一解析流程：FileInputStream 或 assets InputStream
            GlbParser parser = new GlbParser();
            boolean success;
            String loadedModelPath;

            if (useAssets) {
                loadedModelPath = ASSETS_PREFIX + assetName;
                Log.i(TAG, "加载内置模型: " + loadedModelPath);
                try (InputStream is = context.getAssets().open(assetName)) {
                    success = parser.parse(is);
                }
            } else {
                loadedModelPath = modelFile.getAbsolutePath();
                Log.i(TAG, "加载模型: " + loadedModelPath + " (" + (modelFile.length() / 1024 / 1024) + "MB)");
                try (FileInputStream fis = new FileInputStream(modelFile)) {
                    success = parser.parse(fis);
                }
            }

            if (success && !parser.drawUnits.isEmpty()) {
                // 释放旧模型的GPU纹理
                releaseModelResources();

                drawUnits.clear();
                pendingBitmaps.clear();
                drawUnits.addAll(parser.drawUnits);

                // 包围盒已从烘焙后顶点重新计算，直接使用
                float dx = 0, dy = 0, dz = 0;
                if (parser.boundsMin != null && parser.boundsMax != null) {
                    dx = parser.boundsMax[0] - parser.boundsMin[0];
                    dy = parser.boundsMax[1] - parser.boundsMin[1];
                    dz = parser.boundsMax[2] - parser.boundsMin[2];
                    float maxDim = Math.max(dx, Math.max(dy, dz));
                    if (maxDim > 0.001f) {
                        modelNormalizeScale = 3.0f / maxDim;
                    }
                    modelCenterX = (parser.boundsMin[0] + parser.boundsMax[0]) / 2f;
                    modelCenterY = (parser.boundsMin[1] + parser.boundsMax[1]) / 2f;
                    modelCenterZ = (parser.boundsMin[2] + parser.boundsMax[2]) / 2f;
                }

                for (GlbParser.DrawUnit unit : drawUnits) {
                    if (unit.hasTexture && unit.pendingBitmap != null) {
                        pendingBitmaps.add(unit.pendingBitmap);
                        unit.textureId = uploadTexture(unit.pendingBitmap);
                    }
                }

                modelLoaded = true;
                classifyLights();
                pendingLoad = false;
                loadFailed = false;
                switchRetryCount = 0;
                failedPaths.clear();
                modelPath = loadedModelPath;
                // 记住当前模型路径，下次启动优先加载
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_MODEL_PATH, modelPath).apply();

                // 模型文件名（用于朝向特殊处理）
                String modelName = new File(loadedModelPath).getName().toLowerCase();

                // 重置视角到合理默认值（换了新模型后旧值不适用）
                if (dy > dx && dy > dz) {
                    defaultRotX = -90f;
                    defaultRotY = 180f;
                } else if (dx > dy && dx > dz) {
                    defaultRotX = 0f;
                    defaultRotY = 90f;
                } else {
                    defaultRotX = 0f;
                    defaultRotY = 180f;
                }
                // 林肯模型朝向相反，额外转180°
                if (modelName.contains("林肯") || modelName.contains("lincoln")) {
                    defaultRotY += 180f;
                }
                rotX = defaultRotX;
                rotY = defaultRotY;
                mScale = 1.0f;
                autoRotateEnabled = false;
                naviCurrentRotY = 0f;
                naviTargetRotY = 0f;

                // 林肯模型朝向相反，额外转180°
                if (modelName.contains("林肯") || modelName.contains("lincoln")) {
                    rotY += 180f;
                }

                Log.i(TAG, "模型加载成功! " + drawUnits.size()
                        + " drawUnits, 中心=(" + modelCenterX + "," + modelCenterY + "," + modelCenterZ + ")"
                        + ", normalizeScale=" + modelNormalizeScale);
            } else {
                Log.e(TAG, "GlbParser解析失败: " + loadedModelPath);
                // 内置模型失败不入 failedPaths（每次试结果都一样，加入会导致候选池永远排除它）
                if (!loadedModelPath.startsWith(ASSETS_PREFIX)) {
                    failedPaths.add(loadedModelPath);
                }
                loadFailed = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "模型加载异常: " + e.getMessage());
            if (path != null && !path.startsWith(ASSETS_PREFIX)) failedPaths.add(path);
            loadFailed = true;
        }
    }

    /** 释放当前模型的GPU纹理和CPU Bitmap */
    private void releaseModelResources() {
        for (GlbParser.DrawUnit unit : drawUnits) {
            if (unit.hasTexture && unit.textureId >= 0) {
                GLES20.glDeleteTextures(1, new int[]{unit.textureId}, 0);
                unit.textureId = -1;
            }
            if (unit.pendingBitmap != null && !unit.pendingBitmap.isRecycled()) {
                unit.pendingBitmap.recycle();
                unit.pendingBitmap = null;
            }
        }
        // 回收pendingBitmaps中可能残余的bitmap
        for (Bitmap bmp : pendingBitmaps) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    private void reuploadAllTextures() {
        if (dummyTextureId >= 0) GLES20.glDeleteTextures(1, new int[]{dummyTextureId}, 0);
        dummyTextureId = createDummyTexture();
        for (GlbParser.DrawUnit unit : drawUnits) {
            if (unit.hasTexture && unit.pendingBitmap != null && !unit.pendingBitmap.isRecycled()) {
                unit.textureId = uploadTexture(unit.pendingBitmap);
            }
        }
    }

    public void retryLoadModel() {
        if (modelLoaded) return;
        loadModel(modelPath);
    }

    public boolean hasPendingLoad() { return pendingLoad && !modelLoaded; }

    // ==================== 下载目录与模型搜索 ====================

    /** 自动定位下载目录，兼容不同车机厂商的定制路径 */
    private File findDownloadDir() {
        // 1. 优先系统API
        try {
            File sysDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (sysDir != null && sysDir.exists()) return sysDir;
        } catch (Exception ignored) {}

        // 2. 常见车机路径回退
        String[] fallbackPaths = {
            "/sdcard/Download",
            "/storage/emulated/0/Download",
            "/mnt/internal_sd/Download",
            "/mnt/sdcard/Download",
            "/sdcard/下载",
            "/storage/emulated/0/下载",
        };
        for (String p : fallbackPaths) {
            File f = new File(p);
            if (f.exists() && f.isDirectory()) return f;
        }

        // 3. 尝试从外部存储根目录下找Download/下载子目录
        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.exists()) {
            for (String name : new String[]{"Download", "下载"}) {
                File sub = new File(external, name);
                if (sub.exists() && sub.isDirectory()) return sub;
            }
        }

        return null;
    }

    /** 在下载目录中搜索GLB模型: 指定路径 → 随机选一个.glb（排除当前模型和失败模型） */
    private File findModelFile(File downloadDir, String specifiedPath) {
        // 如果指定了明确的路径且文件存在，直接使用
        if (specifiedPath != null) {
            File f = new File(specifiedPath);
            if (f.exists()) return f;
        }

        // 搜索下载目录下所有.glb文件
        File[] glbFiles = downloadDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".glb"));
        if (glbFiles == null || glbFiles.length == 0) return null;

        // specifiedPath == null 表示切换模型模式：排除当前模型和失败模型
        if (specifiedPath == null) {
            String currentPath = modelPath; // 读取当前模型路径
            java.util.List<File> candidates = new java.util.ArrayList<>();
            for (File f : glbFiles) {
                if (!failedPaths.contains(f.getAbsolutePath())) candidates.add(f);
            }
            if (candidates.isEmpty()) {
                // 所有模型都失败，清空记录重新尝试
                failedPaths.clear();
                candidates = java.util.Arrays.asList(glbFiles);
            }
            if (candidates.isEmpty()) return null;

            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            java.util.Random rng = new java.util.Random();
            File chosen;
            do {
                chosen = candidates.get(rng.nextInt(candidates.size()));
            } while (chosen.getAbsolutePath().equals(currentPath) && candidates.size() > 1);
            return chosen;
        }

        // 随机选一个
        return glbFiles[new java.util.Random().nextInt(glbFiles.length)];
    }

    /**
     * 随机切换到另一个模型，避免选到当前同一个或已失败的
     * 候选池 = 下载目录的 .glb 文件 + 内置默认模型（assets://default_car.glb）
     */
    public void switchToRandomModel() {
        java.util.List<String> candidates = new java.util.ArrayList<>();

        // 收集下载目录的 .glb 文件（排除失败模型）
        File downloadDir = findDownloadDir();
        if (downloadDir != null) {
            File[] glbFiles = downloadDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".glb"));
            if (glbFiles != null) {
                for (File f : glbFiles) {
                    if (!failedPaths.contains(f.getAbsolutePath())) {
                        candidates.add(f.getAbsolutePath());
                    }
                }
            }
        }

        // 内置默认模型作为候选（assets 路径不会被加入 failedPaths，始终可选）
        String assetPath = ASSETS_PREFIX + DEFAULT_ASSET_MODEL;
        candidates.add(assetPath);

        // 全部失败时清空记录重新尝试（保留内置模型作为底线）
        long externalCount = candidates.stream().filter(p -> !p.startsWith(ASSETS_PREFIX)).count();
        if (externalCount == 0 && !candidates.isEmpty()) {
            // 仅有内置候选（外部模型全失败或不存在），无需清空 failedPaths
        } else if (candidates.stream().filter(p -> !p.startsWith(ASSETS_PREFIX))
                .allMatch(p -> failedPaths.contains(p))) {
            failedPaths.clear();
        }

        // 排除当前模型
        candidates.remove(modelPath);

        // 无可切换的候选
        if (candidates.isEmpty()) {
            Log.w(TAG, "无其他模型可切换，保持当前: " + modelPath);
            return;
        }

        // 随机选一个（candidates.size()>=1）
        if (candidates.size() == 1) {
            modelPath = candidates.get(0);
        } else {
            modelPath = candidates.get(new java.util.Random().nextInt(candidates.size()));
        }

        Log.i(TAG, "切换到模型: " + modelPath + " (候选数:" + candidates.size() + ")");

        // 重置模型状态，触发重新加载
        modelLoaded = false;
        loadFailed = false;
        pendingLoad = true;
        requestRender();
    }

    // ==================== 纹理 ====================

    private int uploadTexture(Bitmap bitmap) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        return textures[0];
    }

    private int createDummyTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        ByteBuffer bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        bb.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        bb.flip();
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
        return textures[0];
    }

    // ==================== 演示动画 ====================

    private void updateDemoAnimation() {
        long now = System.currentTimeMillis();

        if (demoCurrentAnim >= 0) {
            // 动画播放中，更新当前动画
            updateCurrentDemoAnim(now);
            needsNextFrame = true;
            scheduleDemoWakeUp(now);
        } else {
            // 空闲：检查是否需要触发下一个动画
            if (demoOrder == null || demoScheduleTimes == null) {
                initDemoCycle(now);
            }
            if (demoPlayedIndex < DEMO_ANIM_COUNT) {
                long triggerTime = cycleStartTime + demoScheduleTimes[demoPlayedIndex];
                if (now >= triggerTime) {
                    startDemoAnim(demoOrder[demoPlayedIndex], now);
                    demoPlayedIndex++;
                    needsNextFrame = true;
                }
            } else if (now - cycleStartTime >= DEMO_CYCLE_MS) {
                // 10分钟到期，开始新周期
                initDemoCycle(now);
            }
            scheduleDemoWakeUp(now);
        }
    }

    /**
     * 初始化新的10分钟演示周期：随机排列顺序 + 随机分配触发时间
     */
    private void initDemoCycle(long now) {
        cycleStartTime = now;
        demoPlayedIndex = 0;
        demoOrder = new int[DEMO_ANIM_COUNT];
        demoScheduleTimes = new long[DEMO_ANIM_COUNT];

        // Fisher-Yates 洗牌：随机排列0,1,2,3
        for (int i = 0; i < DEMO_ANIM_COUNT; i++) demoOrder[i] = i;
        java.util.Random rng = new java.util.Random();
        for (int i = DEMO_ANIM_COUNT - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = demoOrder[i]; demoOrder[i] = demoOrder[j]; demoOrder[j] = tmp;
        }

        // 随机分配触发时间：4个动画在10分钟内各1次，间隔>=1分钟
        // 最晚动画需预留最长动画时间(16秒)+缓冲(4秒)=20秒
        long availableTime = DEMO_CYCLE_MS - 20_000;  // 可用时间窗口
        long minSpan = DEMO_MIN_GAP_MS;                // 最小间隔1分钟
        // 4个点的最小总跨度 = 3 * 1min = 3min, 剩余可随机分配
        long minTotal = (DEMO_ANIM_COUNT - 1) * minSpan;
        long extraBudget = availableTime - minTotal;

        // 在最小间距基础上加随机偏移
        long[] offsets = new long[DEMO_ANIM_COUNT];
        offsets[0] = 0;
        for (int i = 1; i < DEMO_ANIM_COUNT; i++) {
            offsets[i] = minSpan + (long)(rng.nextDouble() * (extraBudget / (DEMO_ANIM_COUNT - 1)));
        }
        // 最后一项取剩余全部，确保用满10分钟
        // 先计算前3个offset的累计
        long usedSoFar = 0;
        for (int i = 0; i < DEMO_ANIM_COUNT - 1; i++) {
            demoScheduleTimes[i] = usedSoFar + offsets[i];
            usedSoFar = demoScheduleTimes[i];
        }
        demoScheduleTimes[DEMO_ANIM_COUNT - 1] = availableTime;  // 最后一个贴末尾

        // 首个动画加点延迟（5~30秒），避免周期一开就播放
        long firstDelay = 5000 + (long)(rng.nextDouble() * 25000);
        for (int i = 0; i < DEMO_ANIM_COUNT; i++) {
            demoScheduleTimes[i] += firstDelay;
        }

        Log.d(TAG, "演示周期: 顺序=" + java.util.Arrays.toString(demoOrder)
                + " 时间=" + java.util.Arrays.toString(demoScheduleTimes));
    }

    /**
     * 开始播放指定ID的演示动画
     */
    private void startDemoAnim(int animId, long now) {
        demoCurrentAnim = animId;
        demoPhase = 0;
        demoStartTime = now;
        demoRotY = 0f;
        demoScaleFactor = 1f;
        Log.d(TAG, "开始演示动画" + animId);
    }

    /**
     * 更新当前正在播放的动画
     */
    private void updateCurrentDemoAnim(long now) {
        switch (demoCurrentAnim) {
            case 0: updateDemoZoomIn(now); break;
            case 1: updateDemoZoomOut(now); break;
            case 2: updateDemoShowcase(now); break;
            case 3: updateDemoSideRotation(now); break;
        }
    }

    /**
     * 动画0：放大还原（5倍→1倍，10秒）
     */
    private void updateDemoZoomIn(long now) {
        float progress = (float)(now - demoStartTime) / DEMO_ZOOM_DURATION_MS;
        if (progress >= 1f) {
            finishDemoAnim();
            return;
        }
        float t;
        if (progress < 0.5f) {
            t = progress / 0.5f;
            t = smoothstep(t);
            demoScaleFactor = 1f + (DEMO_ZOOM_MAX - 1f) * t;
        } else {
            t = (progress - 0.5f) / 0.5f;
            t = smoothstep(t);
            demoScaleFactor = DEMO_ZOOM_MAX - (DEMO_ZOOM_MAX - 1f) * t;
        }
    }

    /**
     * 动画1：缩小还原（1/5倍→1倍，10秒）
     */
    private void updateDemoZoomOut(long now) {
        float progress = (float)(now - demoStartTime) / DEMO_ZOOM_DURATION_MS;
        if (progress >= 1f) {
            finishDemoAnim();
            return;
        }
        float t;
        if (progress < 0.5f) {
            t = progress / 0.5f;
            t = smoothstep(t);
            demoScaleFactor = 1f - (1f - DEMO_SHRINK_MIN) * t;  // 1→0.2
        } else {
            t = (progress - 0.5f) / 0.5f;
            t = smoothstep(t);
            demoScaleFactor = DEMO_SHRINK_MIN + (1f - DEMO_SHRINK_MIN) * t;  // 0.2→1
        }
    }

    /**
     * 动画2：旋转展示（16秒，4阶段各4秒）
     * 阶段0: Y轴旋转+180°（车尾→车头）
     * 阶段1: 放大5倍
     * 阶段2: 缩放还原
     * 阶段3: Y轴旋转-180°（车头→车尾）
     */
    private void updateDemoShowcase(long now) {
        long phaseDuration = DEMO_SHOWCASE_DURATION_MS / 4;  // 4秒/阶段
        long elapsed = now - demoStartTime;
        float totalProgress = (float) elapsed / DEMO_SHOWCASE_DURATION_MS;

        if (totalProgress >= 1f) {
            finishDemoAnim();
            return;
        }

        float phaseProgress = (float)(elapsed % phaseDuration) / phaseDuration;
        int phase = (int)(elapsed / phaseDuration);
        float t = smoothstep(phaseProgress);

        switch (phase) {
            case 0: // 旋转+180°
                demoRotY = 180f * t;
                demoScaleFactor = 1f;
                break;
            case 1: // 放大5倍
                demoRotY = 180f;
                demoScaleFactor = 1f + (DEMO_SHOWCASE_ZOOM_MAX - 1f) * t;
                break;
            case 2: // 缩放还原
                demoRotY = 180f;
                demoScaleFactor = DEMO_SHOWCASE_ZOOM_MAX - (DEMO_SHOWCASE_ZOOM_MAX - 1f) * t;
                break;
            case 3: // 旋回-180°
                demoRotY = 180f * (1f - t);
                demoScaleFactor = 1f;
                break;
        }
    }

    /**
     * 动画3：侧面旋转（10秒）
     * Y轴旋转540°：车尾→左侧90°→右侧180°→左侧180°→车尾90°
     * 全程同步缩放：1倍→中点2倍→1倍（椭圆曲线）
     */
    private void updateDemoSideRotation(long now) {
        float progress = (float)(now - demoStartTime) / DEMO_SIDE_DURATION_MS;
        if (progress >= 1f) {
            finishDemoAnim();
            return;
        }

        // 540°连续旋转
        demoRotY = 540f * progress;

        // 缩放：用正弦曲线，progress=0时1倍，0.5时2倍，1时1倍
        float scaleFactor = 1f + (DEMO_SIDE_ZOOM_MAX - 1f) * (float)Math.sin(progress * Math.PI);
        demoScaleFactor = scaleFactor;
    }

    /**
     * 动画播放完毕，重置状态
     */
    private void finishDemoAnim() {
        Log.d(TAG, "演示动画" + demoCurrentAnim + "播放完毕");
        demoCurrentAnim = -1;
        demoPhase = 0;
        demoRotY = 0f;
        demoScaleFactor = 1f;
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private android.os.Handler demoWakeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable demoWakeRunnable = null;

    private void scheduleDemoWakeUp(long now) {
        if (demoCurrentAnim >= 0) {
            // 动画进行中，频繁唤醒保持流畅
            postDemoWake(50);
        } else if (demoScheduleTimes != null && demoPlayedIndex < DEMO_ANIM_COUNT) {
            // 空闲中，在下次触发时间唤醒
            long triggerTime = cycleStartTime + demoScheduleTimes[demoPlayedIndex];
            long delay = Math.max(1, triggerTime - now);
            postDemoWake(delay);
        } else if (demoScheduleTimes != null) {
            // 本周期已播完，等周期结束
            long cycleEnd = cycleStartTime + DEMO_CYCLE_MS;
            long delay = Math.max(1, cycleEnd - now);
            postDemoWake(delay);
        }
    }

    private void postDemoWake(long delayMs) {
        if (demoWakeRunnable != null) {
            demoWakeHandler.removeCallbacks(demoWakeRunnable);
        }
        demoWakeRunnable = new Runnable() {
            @Override
            public void run() {
                requestRender();
            }
        };
        demoWakeHandler.postDelayed(demoWakeRunnable, delayMs);
    }

    // ==================== 转向灯分类 ====================

    private void classifyLights() {
        if (drawUnits == null) return;

        // 打印所有材质名，便于调试
        for (GlbParser.DrawUnit unit : drawUnits) {
            Log.d(TAG, "DrawUnit材质: " + unit.materialName + " hasTexture=" + unit.hasTexture
                    + " bcf=" + java.util.Arrays.toString(unit.baseColorFactor));
        }

        // 通过材质名和颜色特征识别灯部件，只标记isLight，左右由shader判断
        int lightCount = 0;
        for (GlbParser.DrawUnit unit : drawUnits) {
            unit.isLight = isLightPart(unit);
            if (unit.isLight) lightCount++;
        }
        Log.d(TAG, "灯部件识别: " + lightCount + "/" + drawUnits.size() + " 个DrawUnit是灯");
    }

    private boolean isLightPart(GlbParser.DrawUnit unit) {
        String name = (unit.materialName != null) ? unit.materialName.toLowerCase() : "";

        // 材质名包含light/lihgt等关键词
        if (name.contains("light") || name.contains("lihgt") || name.contains("lamp")) {
            return true;
        }

        return false;
    }

    private void updateTurnSignalLights() {
        // 计算当前有效偏转角度（导航+演示）
        float effectiveRotY = naviCurrentRotY + cruiseCurrentRotY + demoRotY;

        if (Math.abs(effectiveRotY) < 2f) {
            currentTurnSignal = 0f;
            return;
        }

        // 闪烁：500ms周期，前250ms亮，后250ms灭
        boolean blinkOn = (System.currentTimeMillis() % BLINK_PERIOD_MS) < (BLINK_PERIOD_MS / 2);

        if (!blinkOn) {
            currentTurnSignal = 0f;
        } else {
            // 调头(|effectiveRotY|>=90°)时双闪(uTurnSignal=2)，其余单侧闪
            if (Math.abs(effectiveRotY) >= 90f) {
                currentTurnSignal = 2f;
            } else {
                currentTurnSignal = effectiveRotY > 0 ? 1f : -1f;
            }
        }

        needsNextFrame = true;  // 确保持续刷新以维持闪烁
    }

    // 刹车灯刷新：刹车状态下确保GL线程持续渲染
    private void updateBrakeLight() {
        if (isBraking) {
            needsNextFrame = true;
        }
    }

    // ==================== Model matrix ====================

    private void buildModelMatrix(float effectiveRotY, float shakeX, float shakeY) {
        float totalScale = mScale * modelNormalizeScale * demoScaleFactor;
        Matrix.setRotateM(rotXMat, 0, rotX + shakeX, 1f, 0f, 0f);
        Matrix.setRotateM(rotYMat, 0, effectiveRotY + shakeY, 0f, 1f, 0f);
        Matrix.setIdentityM(scaleMat, 0);
        scaleMat[0] = scaleMat[5] = scaleMat[10] = totalScale;
        Matrix.setIdentityM(transMat, 0);
        transMat[12] = -modelCenterX;
        transMat[13] = -modelCenterY;
        transMat[14] = -modelCenterZ;
        Matrix.multiplyMM(bmTemp1, 0, rotYMat, 0, scaleMat, 0);
        Matrix.multiplyMM(bmTemp2, 0, bmTemp1, 0, transMat, 0);
        Matrix.multiplyMM(modelMatrix, 0, rotXMat, 0, bmTemp2, 0);
    }

    // ==================== Touch handling ====================

    public void onTouchDrag(float dx, float dy) {
        autoRotatePaused = true;
        rotY += dx * 0.3f;
        rotX += dy * 0.3f;
        rotX = Math.max(-60f, Math.min(30f, rotX));
        requestRender();
    }

    public void onTouchScale(float scaleFactor) {
        mScale *= scaleFactor;
        mScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, mScale));
        requestRender();
    }

    public void onTouchUp() {
        autoRotatePaused = false;
        saveState();
        requestRender();
    }

    public void restoreDefaultView() {
        rotX = defaultRotX;
        rotY = defaultRotY;
        naviCurrentRotY = 0f;
        naviTargetRotY = 0f;
        requestRender();
    }

    /** 到达终点后触发旋转2圈 */
    public void startArrivalSpin() {
        arrivalSpinRemain = 720f;
        arrivalSpinActive = true;
        lastFrameTime = 0;
        requestRender();
    }

    private void saveState() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ROT_X, rotX)
                .putFloat(KEY_ROT_Y, rotY)
                .putFloat(KEY_SCALE, mScale)
                .putBoolean(KEY_AUTO_ROTATE, autoRotateEnabled)
                .apply();
    }

    // ==================== Shader compile ====================

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private int createProgram(String vertSource, String fragSource) {
        int vertShader = compileShader(GLES20.GL_VERTEX_SHADER, vertSource);
        int fragShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSource);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vertShader);
        GLES20.glAttachShader(prog, fragShader);
        GLES20.glLinkProgram(prog);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(prog));
        }
        return prog;
    }
}
