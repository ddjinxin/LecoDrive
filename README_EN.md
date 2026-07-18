---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'b047ce63-121b-4bc8-9f19-a1ed491ccc7c'
  PropagateID: 'b047ce63-121b-4bc8-9f19-a1ed491ccc7c'
  ReservedCode1: '16cc92d3-8f5f-4732-aec7-8206be3e903d'
  ReservedCode2: '16cc92d3-8f5f-4732-aec7-8206be3e903d'
---

<div align="center">

# LecoDrive

**A Panoramic Driving Dashboard for Android IVI Systems**

3D Car Model · Speedometer · Navi Broadcast · Compass · Weather · Wallpaper · Mileage & Fuel

[中文](README.md) | **English**

</div>

---

## 📸 Demo

https://pd.qq.com/s/c37t0jlqx

---

## 📖 Overview

**LecoDrive** is a panoramic driving dashboard built for Android in-vehicle infotainment (IVI) systems. It renders Draco-compressed GLB 3D car models via a **custom OpenGL ES 2.0 engine**, combined with a **270° arc LED speedometer**, **Amap Auto navigation broadcast**, **GPS compass**, **real-time weather system** and **dynamic wallpapers**, blending driving information with a sci-fi metallic visual style for an immersive driving experience.

> ⚠️ **Prerequisite**: This app requires **Leku Launcher** (`com.lecoauto`) to be installed as the car launcher; it prompts and exits if absent.

---

## ✨ Key Features

### 🚗 1. 3D Vehicle Rendering

Custom OpenGL ES 2.0 engine with zero third-party 3D library dependencies, supporting complex GLB model loading and rendering.

| Feature | Description |
|---|---|
| **Draco JNI decoding** | Native Draco decoding library, supports multi-mesh Draco-compressed GLB models |
| **Auto normalization** | Auto-calculates scaling factor from model bounding box; any-sized model renders ~3 units wide by default |
| **Semantic color cache** | Colors nodes by name (body silver, windows blue, tires black, 20+ semantic colors); cached in DrawUnit after first computation to avoid per-frame recalculation |
| **Dual render modes** | Batch mode for untextured models, single-buffer mode for textured models; accumulated node transforms ensure correct part positioning |
| **Texture management** | Auto-releases old textures on model switch to prevent GPU memory leaks |
| **Async loading** | Parses GLB on background thread, uploads textures on GL thread — never blocks the render loop |
| **Auto orientation** | Sets default view based on the model's longest axis; some car models rotate 180° specially |
| **Built-in default model** | Bundled Koenigsegg Regera model (`assets/default_car.glb`) — works out of the box |

### 🎛️ 2. Speedometer

270° arc dial with LED styling and sci-fi metallic texture.

- **270° arc dial** — metallic gradient outer ring + center screw cap
- **LED scale points** — full-circle LED glow scale, cyan glow elements
- **LED 7-segment display** — speed value in seven-segment digital font
- **Overspeed red warning** — dial turns red when threshold is exceeded
- **Gradient pointer** — sci-fi light-feel pointer

### 🧭 3. Compass Clock

GPS bearing-based direction indicator combined with clock display, in LED 7-segment style.

### 🗺️ 4. Navigation System

Receives **Amap Auto navigation broadcasts** in real time to parse navigation instructions — no SDK integration needed.

- **Amap broadcast parsing** — parses structured data: turn, distance, lane, road name, etc.
- **Turn icon mapping** — 53 built-in navi icons covering all iconIds:
  - Left / Right / Left-front / Right-front / U-turn
  - Left offset +30°, Right offset -30°, Left-front +15°, Right-front -15°
  - U-turn ±180°, icons 65 +10°, 66 -10°
- **Turn signal logic** — corresponding side light flashes amber (500ms cycle) on nav or cruise turns; hazard lights on U-turn
- **Brake light logic** — both rear lights glow steady red when GPS speed drops rapidly (>8km/h); delay 1s after brake release before turning off
- **Foreground service keep-alive** — `PanDriveService` reliably receives broadcasts in PiP/window mode

### 🌦️ 5. Weather System

Free API (no key required) + built-in weather videos for multi-dimensional meteorological visualization.

- **Open-Meteo API** — free, no key, GPS-triggered, 30-minute polling
- **WMO code mapping** — 6 weather states:
  - sunny(0,1) / cloud(2,3) / fog(45,48)
  - rain(51-57, 61-67, 80-82, 95-99)
  - snow(71-77, 85, 86)
  - wind(wind_speed>40km/h AND code 0-3)
- **Weather display** — temperature + state (bottom-left, large+small text), wind direction/speed (left lane, vertical rotation), humidity (right lane, vertical rotation)
- **Day/night adaptive colors** — black translucent by day, white translucent by night, with subtle sway animation
- **6 built-in weather videos** — sunny / cloud / rain / snow / fog / wind, auto-copied to device storage on first launch
- **Weather animation mutually exclusive with wallpaper** — enabling weather animation auto-switches to weather video
- **IP geolocation fallback** — when GPS coords are 0,0, gets approximate lat/lng via ip-api.com, rate-limited to 5 minutes

### 🖼️ 6. Wallpaper System

Supports both image and video wallpapers, independent day/night configuration, center-crop adaptive rendering.

- **Image wallpaper** — `BitmapFactory` decode + center-crop draw, supports jpg/png/webp
- **Video wallpaper** — `SurfaceView` + `MediaPlayer` + center-crop scaling, supports mp4/3gp/webm
- **Day & night** — independent day/night wallpaper config, one-tap switch
- **Wallpaper mask** — semi-transparent black overlay, 0x44 by day, 0x88 by night; falls back to gradient background when no wallpaper
- **Large image sampling** — iteratively computes power-of-two sample rate to avoid OOM on large images
- **4-button settings** — Day / Night / Weather / Default, one-tap management
- **Built-in default wallpaper** — `day.webp` + `night.webp`, works out of the box
- **In-app file browser** — replaces system file picker, compatible with IVI window mode

### 📊 7. Mileage & Fuel

LED 7-segment style, two-row layout (label + number), 4-second cyclic switching with slide animation.

- **Realtime mileage** — clears on each app start
- **Today's mileage** — auto-clears on day rollover
- **Total mileage** — GPS accumulation + user-configurable base mileage (setting base mileage auto-clears totalDistance to avoid double counting)
- **Fuel estimation** — speed-range-to-fuel mapping table, 20s sampling, 3-point moving average, 60s UI refresh
- **Mileage algorithm** — GPS Haversine formula accumulation, filters low-accuracy points (accuracy>20m) and stationary points (speed<2km/h)

### 🎢 8. Cruise & Demo Animation

- **Cruise steering** — GPS bearing change rate drives car head deflection (threshold 5°/s, amplification 2.5x, max ±30°), no response when speed <5km/h
- **Demo animation system** — every 10-minute cycle randomly plays 4 animations (zoom-in restore / zoom-out restore / rotation showcase / side rotation), with at least 1-minute intervals; auto-plays while driving

### 🔄 9. Model Management

- **Auto memory** — loads last-used GLB model on startup; only picks randomly if file is missing
- **Tap to switch** — tap navigation area to randomly switch models, excluding current and failed models, 500ms debounce
- **Auto search download directory** — System API → common IVI paths → external storage root; prioritizes `1.glb`→`10.glb`, then largest `.glb` by size
- **Error handling** — records path of models that fail to parse and never retries; after up to 5 consecutive failures, auto-switches to next model

### 🪟 10. Window Mode & Compatibility

- **Multi-window / split-screen support**
- **configChanges declaration** — all Activities declare `orientation|keyboardHidden|screenSize|smallestScreenSize|screenLayout` + `resizeableActivity="true"`
- **CLEAR_TOP stack-clearing** — after file selection, directly `startActivity(MainActivity, CLEAR_TOP) + finish()` to work around IVI Activity rebuild issues
- **Status bar avoidance** — dynamically adjusts top bar and button margins via `WindowInsets`
- **Broadcast setPackage** — resolves Android 16 same-app broadcast drop issue
- **ACTION_GET_CONTENT** — replaces `ACTION_OPEN_DOCUMENT` for IVI compatibility

---

## 🏗️ Technical Architecture

### Layout Proportions

```
┌─────────────────────────────────────────────┐
│             Date & Time (10%)               │
├─────────────────────────────────────────────┤
│                                             │
│     Speedometer + Compass Clock (45%)       │
│                                             │
├──────────────────────────┬──────────────────┤
│   Navigation (15%)       │                  │
│                          │  3D Car / Lane   │
│                          │     (30%)        │
└──────────────────────────┴──────────────────┘
```

### Tech Stack

| Aspect | Choice |
|---|---|
| **Language** | Java 1.8 |
| **Build** | Gradle (Android Gradle Plugin) |
| **minSdk** | 21 (Android 5.0 Lollipop) |
| **targetSdk** | 35 (Android 15) |
| **compileSdk** | 35 |
| **3D rendering** | OpenGL ES 2.0 (custom engine) |
| **Model decoding** | Draco JNI (native libs) |
| **Model format** | GLB (binary glTF) |
| **Third-party deps** | **Zero dependencies** |
| **Obfuscation** | R8 / ProGuard (minify + shrinkResources) |

### Visual Style

- Unified **sci-fi metallic style**
- LED glow elements (cyan / green)
- Overspeed red warning
- 270° arc dial + LED scale points + LED 7-segment display
- Metallic gradient outer ring + center screw cap

---

## 📁 Project Structure

```
app/src/main/
├── java/com/jingxin/pandrive/
│   ├── MainActivity.java          # Main UI
│   ├── SettingsActivity.java      # Settings (4-button layout)
│   ├── FilePickerActivity.java    # In-app file picker
│   ├── PanDriveService.java       # Foreground service (nav broadcast)
│   ├── PanDriveApp.java           # Application
│   ├── gl/
│   │   ├── Car3DRenderer.java     # GL renderer (GLB+Draco)
│   │   └── GlbParser.java         # GLB parser
│   ├── view/
│   │   ├── SpeedometerView.java   # Speedometer (1288 lines)
│   │   ├── CompassView.java       # Compass clock
│   │   ├── NavigationBarView.java # Navigation bar (847 lines, 53 icons)
│   │   ├── LaneView.java          # Lane gradient background
│   │   └── GridBackgroundView.java# Grid bg + wallpaper + weather text
│   └── data/
│       └── WeatherHelper.java     # Weather data (Open-Meteo + ip-api)
├── assets/
│   ├── default_car.glb            # Bundled Koenigsegg model (2.3 MB)
│   ├── default_wallpaper/         # Default wallpapers (day/night.webp)
│   └── pandrive_weather/          # 6 built-in weather videos (8.3 MB)
├── res/
│   ├── drawable-nodpi/            # 53 navi turn icons + other resources
│   ├── layout/                    # Layouts
│   └── xml/network_security_config.xml  # HTTP cleartext config (ip-api)
└── AndroidManifest.xml
```

---

## 🔧 Build & Run

### Prerequisites

- Android Studio (recommended)
- JDK 17 (bundled with Android Studio)
- Android SDK 35

### Build Steps

```bash
# 1. Clone
git clone https://github.com/ddjinxin/LecoDrive.git
cd LecoDrive

# 2. Configure signing
#    Create local.properties in project root with keystore info:
cat > local.properties << 'EOF'
sdk.dir=/path/to/Android/Sdk
STORE_PASSWORD=your_keystore_password
KEY_PASSWORD=your_key_password
EOF

#    Place your keystore at app/gaoden_release.jks

# 3. Build release APK
export JAVA_HOME="/path/to/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/path/to/Android/Sdk"
./gradlew assembleRelease

# 4. Output
#    app/build/outputs/apk/release/app-release.apk
```

### Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **Prerequisite**: Requires Leku Launcher (`com.lecoauto`) installed as the car launcher.

---

## 📂 Resource Placement

After installation, place files in the device's **Download directory** (auto-searched across common paths including `/sdcard/Download`, `/storage/emulated/0/Download`, etc.) to take effect:

| Resource | Path | Usage |
|---|---|---|
| **GLB car models** | `/sdcard/Download/*.glb` | 3D vehicle models (tap nav area to switch randomly) |
| **Wallpapers** | `/sdcard/Download/pandrive_wallpaper/day.*`<br/>`/sdcard/Download/pandrive_wallpaper/night.*` | Day/night wallpapers (jpg/png/webp/mp4) |
| **Weather videos** | `/sdcard/Download/pandrive_weather/` | Custom weather videos (sunny/cloud/rain/snow/fog/wind.mp4) |

> 💡 **Tip**: Weather videos and default wallpapers are bundled in assets and auto-copied to device storage on first launch — no manual placement needed.

---

## 📱 Compatibility

- **minSdk Android 5.0 (API 21)** — broad support for legacy IVI systems
- **targetSdk Android 15 (API 35)** — compliant with latest platform specs
- **Multi-window / split-screen** — compatible with always-on IVI window mode
- **Tested on**:
  - Huawei phone
  - Redmi phone
  - Android 13 emulator
  - Android 5.1 emulator
  - Freescale MEK-MX8Q IVI HMI (Android 8.1, API 27)

---

## 🌐 External Services

| Service | Purpose | Auth | Link |
|---|---|---|---|
| **Open-Meteo** | Weather data | Free, no key | https://open-meteo.com |
| **ip-api.com** | IP geolocation fallback | Free, 1500/day | https://ip-api.com |
| **Amap Auto** | Navigation broadcast source | Separate install | - |
| **Leku Launcher** | Car launcher | Separate install | - |

---

## 📋 Permissions

| Permission | Usage |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Weather API & IP geolocation |
| `SYSTEM_ALERT_WINDOW` | Floating features |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Foreground service keep-alive |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS compass & mileage |
| `POST_NOTIFICATIONS` | Notifications on Android 13+ |
| `READ/WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | Read GLB models & wallpapers |

---

## ⚠️ Known Issues

- **App freezes on fullscreen ↔ window mode switch** — On some IVI systems (Android 8.1 multi-window), the activity rebuilds but rendering stalls after switching modes. Root cause may be in GL context/Surface/TextureView rebuild. **Under investigation.**

---

## 📜 License

This project is for learning and communication purposes only. Contact the author for commercial use.

---

## 🙏 Acknowledgements

- [Open-Meteo](https://open-meteo.com) — free weather API
- [ip-api.com](https://ip-api.com) — free IP geolocation
- [Khronos glTF](https://www.khronos.org/gltf/) — GLB model format standard
- [Google Draco](https://github.com/google/draco) — 3D geometry compression library
- Amap Auto — navigation broadcast data source

---

<div align="center">

**LecoDrive**
A Panoramic Driving Dashboard Built for IVI

</div>

> AI生成
