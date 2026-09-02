---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '1c74a13e-e6f9-4805-8f91-7166040a6a25'
  PropagateID: '1c74a13e-e6f9-4805-8f91-7166040a6a25'
  ReservedCode1: 'dd7c36bf-a250-480b-ad16-9f1559c807ca'
  ReservedCode2: 'dd7c36bf-a250-480b-ad16-9f1559c807ca'
---

<div align="center">

# 乐酷驾驶

**面向安卓车机的全景驾驶仪表盘应用**

3D 车模 · 速度仪表 · 导航广播 · 指南车 · 天气 · 壁纸 · 里程油耗

**简体中文** | [English](README_EN.md)

</div>

---

## 📸 演示

https://pd.qq.com/s/8wejgtf1k?b=2

---

<!-- LATEST_RELEASE_START -->
## 📢 最新版本 (v1.0.6.0)

### v1.0.6.0 — 新增滚轮透明度设置项
- **滚轮透明度可调** — 设置页新增「滚轮透明度」滑条（0~255，255=不透明），自由调节车辆信息滚轮（金属背景、凹槽、边框）的透明度，壁纸可从滚轮透出
- **实时生效** — 滑动时数值实时更新并立即刷新主界面滚轮，无需保存
- **默认按钮** — 滑条右侧「默认」按钮一键恢复 255（不透明）
- **普通模式与悬浮模式均支持** — SettingsActivity（全屏）与 SettingsView（乐酷悬浮）同步实现
- **持久化** — 通过 DataHub 写入 JSON 备份 + SharedPreferences，重启后自动恢复

### v1.0.5.9 — 仪表区三页滑动 + 车辆信息独立区域
- **仪表区三页滑动容器** — 新增自研 `DashboardView`（零第三方依赖），继承 FrameLayout，内部三页等宽排列，通过 GestureDetector + Scroller 实现左右滑动切页，DOWN 事件放行给子视图保留点击切换样式能力
- **三页内容：速度仪表盘 / 指南针 / 圆形时钟** — 原来挤在同一区域的仪表盘和指南针时钟改为三页滑动切换，底部 LED 风格指示点显示当前页面（青色实心/暗灰色），一次只显示一个仪表
- **每页宽度 = min(容器宽, 容器高)** — DashboardView 自身宽度收窄为单页宽度并水平居中，滑动距离短，操作精准
- **车辆信息独立为第③区域** — MileageView 从原仪表盘区域分离，成为竖向第③区域唯一内容，滚轮宽度对齐 DashboardView 实际宽度
- **滚轮显示优化** — 滚轮高度为 View 高度的 70%，滚轮宽度为 MileageView 宽度的 90%，标签字号为 MileageView 高度的 20%，内部数字/半径等比放大，滚轮中心下移给标签留空间
- **指南针/时钟绘制范围统一** — outerRadius 从 `0.36` 改为 `0.42`，arcStrokeWidth 从 `0.0632` 改为 `0.0737`，与速度仪表盘内容占比一致（直径约 84%）
- **设置页标签更新** — 区域名"仪表盘"→"仪表区"、"指南针"→"车辆信息"
- **帮助页内容更新** — 仪表区三页滑动说明、车辆信息七项数据说明、操作手势增加左右滑动、布局比例从五项改为六项

### v1.0.5.8 — 修复悬浮窗残留问题
- **悬浮窗无法关闭修复** — 应用在乐酷桌面悬浮模式下运行后，即使应用从内存关闭，悬浮窗仍残留在屏幕上无法消除。根因是 `PanDriveService.onDestroy()` 未清理悬浮窗，且 `removeFloatWindow()` 的 token null guard 导致僵尸窗口
- **新增 `forceRemoveFloatWindow()` 兜底方法** — 无视所有状态标记强制清理窗口+引用+状态位，供 Service 销毁时调用
- **`PanDriveService.onDestroy()` 增加悬浮窗清理** — 前台服务销毁时强制移除悬浮窗，解决"Activity 死了但 Service 还活着，悬浮窗无人管"的核心场景
- **`PanDriveService.onTaskRemoved()` 增加清理** — 用户从最近任务列表划掉应用时触发清理
- **`removeFloatWindow()` 去除 token null guard** — 原来窗口 token 为 null 时跳过 `removeViewImmediate`，导致僵尸窗口，改为无条件尝试移除
- **`onActivityDestroyed` 兜底** — 即使销毁的不是当前悬浮 Activity，只要仍在悬浮态也清理

### v1.0.5.7 — 新增「调整」区域（6区域布局）
- **新增第 6 个普通区域「调整」** — 位于导航栏与 3D 车道线之间，默认占比 0%（不占空间），用于配合手动调整其他区域在页面上的竖向位置
- **布局比例升级为 6 项** — 日期/仪表盘/指南针/导航/调整/车道线，横屏默认 `{10, 30, 15, 15, 0, 30}`，竖屏默认 `{10, 27, 15, 13, 0, 35}`，6 项合计仍须=100
- **设置页新增输入框** — 横屏/竖屏区域比例各新增「调整」标签及第 6 个输入框
- **修复设置页闪退** — `SettingsView`/`SettingsActivity` 布局比例数组由 5 项扩为 6 项时残留 `new EditText[5]`/`new float[5]`，点击设置页即崩溃（ArrayIndexOutOfBoundsException），已全部修正
- **技术实现** — `DataHub` 默认权重/备份读写/校验适配 6 项，`GridBackgroundView` 车道线几何改用 `weights[5]`，悬浮态自动适配

### v1.0.5.6 — 导航栏壁纸亮度自适应
- **导航栏文字颜色自动适配壁纸亮度** — 图片壁纸加载时采样导航区域像素亮度，视频壁纸每2秒周期采样，文字自动在黑/白之间切换，无需手动调日夜模式
- **亮度阈值 0.6** — 背景亮度低于0.6用白字，高于0.6用黑字
- **无壁纸时维持原逻辑** — 无壁纸渐变背景下仍按日夜模式决定文字颜色
- **技术实现** — `GridBackgroundView` 新增 `OnBackgroundBrightnessListener` 监听器，`NavigationBarView` 实现接口并改用 `shouldUseWhiteText()` 驱动配色

### v1.0.5.5 — 设置入口重构 + 日夜开关 + 悬浮态日夜监听修复
- **右上角按钮改为设置入口** — 原日夜切换按钮改为设置按钮（齿轮图标），单击直接进入设置页，取消长按进设置的逻辑
- **日夜切换移入设置页** — 新增「夜间模式」Switch 开关作为设置页第一项，即时切换日间/夜间模式
- **设置页新增返回按钮** — 标题左侧增加返回箭头按钮，点击退出设置页返回首页
- **悬浮态高德日夜监听修复** — `onResume()` 中 `themeController.registerAmapReceiver()` 原在悬浮态 return 之后，导致悬浮态下高德 KEY_TYPE=10019 广播无法驱动日夜切换。改为 return 之前注册，确保悬浮态也生效

### v1.0.5.4 — 悬浮态导航显示 + 梯形文字锚点同步
- **悬浮态导航信息不显示修复** — `NavigationBarView` 进入悬浮态时 View 被剥离触发 `onDetachedFromWindow` 移除了导航/模式监听，挂载到覆盖窗口后未恢复，导致高德导航信息在悬浮模式下完全不显示。新增 `onAttachedToWindow` 重新注册监听并同步当前模式
- **车道虚线滚动恢复** — `LaneView` 同类问题，`onDetachedFromWindow` 移除了速度监听但无恢复，悬浮态下车道虚线滚动动画失去速度驱动。新增 `onAttachedToWindow` 恢复 `addSpeedListener`
- **悬浮态梯形/文字锚点同步** — `GridBackgroundView.computeEdgeGeometry()` 原按权重比例重算文字锚点，与 `LaneView` 按实际尺寸绘制的梯形基准不一致，悬浮态下比例判断方式差异导致文字与梯形错位。改为悬浮态直接读取 LaneView 实际位置/高度作为锚点，非悬浮态保持原算法不变

### v1.0.5.3 — 悬浮态天气显示 + 里程轮播 + 极简模式配色修复
- **悬浮态天气不显示修复** — `hasFullscreenOverlay()` 原来遍历全部子 View，主布局 LinearLayout 本身 MATCH_PARENT 导致永远返回 true、天气文字从不绘制。改为用 `overlayViews` 集合只追踪动态叠加的覆盖层（设置页/帮助页/文件选择器），新增 `addOverlay()`/`removeOverlay()` 方法
- **里程油耗滚轮轮播恢复** — `MileageView` 进入悬浮态时 View 被剥离触发 `onDetachedFromWindow` 移除轮播回调，挂载到覆盖窗口后未恢复。新增 `onAttachedToWindow` 重启 4 秒轮播
- **极简模式夜间未点亮色块改为浅白** — `COLOR_NIGHT_INACTIVE_BAR` 从深灰蓝 `#4A5A6A` 改为浅白 `#E0E0E0`，与青色高亮色块对比更清晰

### v1.0.5.2 — 通知推送更新修复
- **通知点击改为直接启动 Activity** — 原来用 PendingIntent.getBroadcast 发广播，MainActivity 在后台或已销毁时弹窗会崩溃或丢失。改为 PendingIntent.getActivity 直接启动/唤醒 MainActivity
- **MainActivity 加 launchMode=singleTop** — 避免通知点击重复创建 Activity 实例
- **删除广播接收器** — 通知点击不再依赖广播，改用 onNewIntent/onCreate 识别 action 弹安装窗

### v1.0.5.1 — 悬浮态修复 + 帮助页 View 化
- **天气文字穿透修复** — 设置页/帮助页全屏覆盖时跳过天气文字绘制，不再透过
- **Toast 遮挡修复** — 新建 FloatToast，悬浮态用浮层 View 替代系统 Toast，不再被悬浮窗遮挡
- **帮助页 View 化** — 悬浮态下帮助页以 View 叠加方式显示，不再全屏覆盖 Activity
- **帮助内容更新** — 新增车道背景色/悬浮模式/主动推送更新说明
- **activity_help/settings.xml 加 fillViewport** — 防止天气视频从 padding 区域透出

### v1.0.5 — 主动推送更新 + 车道背景色 + 夜间白色（原始版本）
- **启动自动检查更新** — 修复原有的 bug（之前用 SP 持久化标记导致检查过一次就永远不检查），改为进程内存标记，进程重启自动重置
- **前台服务定时静默检查** — `PanDriveService` 每 2 小时后台静默检查更新，发现新版本自动下载并推送通知
- **通知点击弹窗安装** — 点击通知弹安装窗（立即安装 / 稍后 / 取消三选项），启动时也会检查上次推送未处理的更新
- **Gitee 优先 + GitHub 回退** — 更新源优先从 Gitee 拉取版本信息和下载 APK（国内快），Gitee 无 Release 时自动回退 GitHub
- **悬浮态检查更新改为可用** — 悬浮模式下设置页检查更新从「请退出悬浮模式」改为后台静默检查 + 通知推送
- **HSV 色相渐变条选色** — 夜间顶/底色、白天顶/底色四根色条，滑动即选即存（移植自静心音乐歌词高亮色条）
- **透明度滑块** — 车道背景透明度 0~100% 可调
- **一键恢复默认** — 每行独立「默认」按钮 + 底部「全部默认」按钮
- **夜间时间区域改白色** — 夜间日期 / LED 颜色从荧光绿 `#00E5A0` 改为白色 `#FFFFFF`

---

## 📢 v1.0.4

### 新功能
- **30公里距离窗口近油耗** — 新增按30公里距离窗口计算近期油耗的方式（总油耗÷总距离×100），与原120秒时间窗口可切换
- **近期油耗窗口样本持久化** — 时间窗口和距离窗口样本均持久化到独立JSON文件，应用重启后自动恢复，跨重启不丢失

### 设置页
- 新增近期油耗统计方式选择（时间周期 / 30公里距离周期）

---

## 📢 v1.0.3.1

### Bug 修复
- **今日行程/实时行程误清零** — 设置页保存时不再清零今日行程和实时行程，`setTotalMileage()` 移除了清零逻辑
- **累计里程设置页停留期间回退** — 进入设置页时记录快照，保存时只与快照比较，避免后台 GPS 累加导致误判修改而覆盖

### 优化
- 进入设置页不再自动聚焦到任何输入框

---

## 📢 v1.0.3

### 新功能
- **五区域布局比例可调** — 日期时间、仪表盘、指南针时钟、导航、3D车道线五个区域比例可在设置页自定义（横屏/竖屏独立配置，合计100%）
- **天气文字动态跟随** — 风向风速/湿度文字位置随车道线区域比例自动调整，不再硬编码

### Bug 修复
- **今日行程误清零** — 设置页保存时不再无条件清零今日行程和实时行程
- **巡航道路名显示不全** — 修复道路名宽度为0导致不显示的问题
- **跨天误判清零** — 修复 today_date 默认空值导致首次启动即误判跨天清零

### 其他
- 帮助文档新增布局比例说明
- 设置页标签加粗优化
- 清理8处死代码
<!-- LATEST_RELEASE_END -->

---

## 📖 项目简介

**乐酷驾驶**是一款为安卓车机（IVI）打造的全景驾驶仪表盘应用。它通过 **OpenGL ES 2.0 自研引擎**渲染 Draco 压缩的 GLB 格式 3D 车辆模型，结合 **270° 弧形 LED 速度仪表**、**高德车机版导航广播**、**GPS 指南车**、**实时天气系统**与**动态壁纸**，把行车信息与科幻金属风格的视觉表现融为一体，打造沉浸式驾驶体验。

> ⚠️ **运行前置**：本应用需配合「乐酷桌面」（包名 `com.lecoauto`）作为车机启动器，未安装时会提示并退出。

---

## ✨ 核心功能

### 🚗 一、3D 车辆模型渲染

自研 OpenGL ES 2.0 渲染引擎，零第三方 3D 库依赖，支持复杂 GLB 模型加载与渲染。

| 功能点 | 描述 |
|---|---|
| **Draco JNI 解码** | 集成 Draco 原生解码库，支持多 mesh Draco 压缩的 GLB 模型 |
| **自动归一化缩放** | 根据模型包围盒自动计算缩放系数，任意大小模型在默认状态下均约 3 单位宽 |
| **语义颜色缓存** | 按节点名称着色（车体银白、车窗蓝色、轮胎黑色等 20+ 语义色），首次计算后缓存到 DrawUnit，避免每帧重复计算 |
| **双渲染模式** | Batch 模式面向无纹理模型，Single-buffer 模式面向有纹理模型，节点变换累积确保部件位置正确 |
| **纹理资源管理** | 切换模型时自动释放旧纹理，避免 GPU 内存泄漏 |
| **异步加载** | 后台线程解析 GLB，GL 线程上传纹理，不阻塞渲染主循环 |
| **朝向自动检测** | 根据模型最长轴设置默认视角，部分车型特殊旋转 180° |
| **内置默认车模** | 内置柯尼塞格 Regera 模型（`assets/default_car.glb`），开箱即用 |

### 🎛️ 二、速度仪表盘

270° 弧形表盘 + LED 风格，科幻金属质感。

- **270° 弧形表盘** — 金属渐变外圈 + 中心螺丝帽装饰
- **LED 刻度点** — 全周 LED 发光刻度，青色发光元素
- **LED 七段数码管数字** — 速度数值以数码管字体呈现
- **超速红色预警** — 超过阈值时表盘变红预警
- **渐变指针** — 科幻光感指针

### 🧭 三、指南针时钟

基于 GPS bearing 的方向指示 + 时钟复合显示，LED 七段数码管风格。

### 🗺️ 四、导航系统

通过接收**高德车机版导航广播**实时解析导航指令，无需自身集成 SDK。

- **高德广播数据解析** — 解析转向、距离、车道、道路名等结构化数据
- **转向图标映射** — 53 个内置 navi 图标，覆盖全部 iconId：
  - 左转 / 右转 / 左前方 / 右前方 / 调头
  - 左侧偏转 +30°、右侧偏转 -30°、左前方 +15°、右前方 -15°
  - 调头 ±180°、新增 65 号 +10°、66 号 -10°
- **转向灯逻辑** — 导航或巡航偏转时对应侧车灯琥珀色闪烁（500ms 周期），调头时双闪
- **刹车灯逻辑** — GPS 速度短时快速下降（>8km/h）时两侧后灯红色常亮，松刹车后延时 1 秒熄灭
- **前台服务保活** — `PanDriveService` 在画中画/窗口模式下仍能稳定接收广播

### 🌦️ 五、天气系统

免费 API（无需 key）+ 内置天气视频，多维度气象信息可视化。

- **Open-Meteo API** — 免费无 key，GPS 坐标触发，30 分钟轮询
- **WMO 代码映射** — 6 种天气状态：
  - sunny(0,1) / cloud(2,3) / fog(45,48)
  - rain(51-57, 61-67, 80-82, 95-99)
  - snow(71-77, 85, 86)
  - wind(wind_speed>40km/h AND code 0-3)
- **天气显示** — 温度 + 状态（左下大字+小字）、风向风速（左车道竖排旋转）、湿度（右车道竖排旋转）
- **昼夜自适应配色** — 白天黑色半透明、夜间白色半透明，轻微晃动动画
- **6 个内置天气视频** — sunny / cloud / rain / snow / fog / wind，首次启动自动复制到设备存储
- **天气动画与壁纸互斥** — 开启天气动画后自动切换天气视频
- **IP 定位兜底** — GPS 坐标为 0,0 时通过 ip-api.com 获取大致经纬度，5 分钟限频

### 🖼️ 六、壁纸系统

支持图片与视频壁纸，日夜独立配置，center-crop 自适应渲染。

- **图片壁纸** — `BitmapFactory` 解码 + center-crop 绘制，支持 jpg/png/webp
- **视频壁纸** — `SurfaceView` + `MediaPlayer` + center-crop 缩放，支持 mp4/3gp/webm
- **日夜双壁纸** — 白天/夜间独立壁纸配置，一键切换
- **壁纸遮罩** — 半透明黑色叠加，日间 0x44、夜间 0x88，无壁纸时回退渐变背景
- **大图采样降缩** — 循环计算 2 的幂次采样率，避免大图 OOM
- **四按钮设置页** — 白天 / 夜间 / 天气 / 默认，一键管理
- **内置默认壁纸** — `day.webp` + `night.webp`，开箱即用
- **应用内文件浏览器** — 替代系统文件选择器，兼容车机窗口模式

### 📊 七、里程与油耗

LED 七段数码管风格，两行布局（标签 + 数字），4 秒循环切换，滑动动画。

- **实时里程** — 单次启动清零
- **今日里程** — 跨天自动清零
- **累计里程** — GPS 累加 + 用户可配置存量里程（设置存量里程时自动清零 totalDistance 避免重复计算）
- **油耗估算** — 速度区间映射油耗表，20 秒采样，3 点移动平均，60 秒刷新 UI
- **里程计算算法** — GPS Haversine 公式累加，过滤低精度点（accuracy>20m）和静止点（speed<2km/h）

### 🎢 八、巡航与演示动画

- **巡航模式转向** — GPS bearing 变化率驱动车头偏转（阈值 5°/s，放大 2.5 倍，最大 ±30°），车速 <5km/h 时不响应
- **演示动画系统** — 每 10 分钟周期随机播放 4 种动画（放大还原 / 缩小还原 / 旋转展示 / 侧面旋转），动画间隔至少 1 分钟，行驶过程中自动演示

### 🔄 九、模型管理

- **自动记忆** — 启动时加载上次使用的 GLB 模型，文件不存在时才随机选择
- **单击切换** — 单击导航区域随机切换模型，排除当前和加载失败的模型，500ms 防抖
- **自动搜索下载目录** — 系统 API → 常见车机路径 → 外部存储根目录，优先 `1.glb`→`10.glb`，其次按体积最大的 `.glb`
- **错误处理** — 解析失败的模型记录路径不再重试，连续失败最多 5 次后自动切换下一个

### 🪟 十、窗口模式 & 兼容性

- **多窗口/分屏支持**
- **configChanges 声明** — 所有 Activity 声明 `orientation|keyboardHidden|screenSize|smallestScreenSize|screenLayout` + `resizeableActivity="true"`
- **CLEAR_TOP 清栈方案** — 文件选择完成后直接 `startActivity(MainActivity, CLEAR_TOP) + finish()`，规避车机 Activity 重建问题
- **STATUS_BAR 避让** — 通过 `WindowInsets` 动态调整顶栏与按钮 margin
- **广播 setPackage** — 解决 Android 16 同应用广播丢弃问题
- **ACTION_GET_CONTENT** — 替代 `ACTION_OPEN_DOCUMENT`，兼容车机系统

---

## 🏗️ 技术架构

### 布局比例

```
┌─────────────────────────────────────────────┐
│              日期时间 (10%)                  │
├─────────────────────────────────────────────┤
│                                             │
│         仪表盘 + 指南针时钟 (45%)            │
│                                             │
├──────────────────────────┬──────────────────┤
│      导航 (15%)           │                  │
│                          │  3D 车道线 (30%)  │
│                          │                  │
└──────────────────────────┴──────────────────┘
```

### 技术栈

| 方面 | 选型 |
|---|---|
| **语言** | Java 1.8 |
| **构建** | Gradle (Android Gradle Plugin) |
| **最低 SDK** | 21 (Android 5.0 Lollipop) |
| **目标 SDK** | 35 (Android 15) |
| **编译 SDK** | 35 |
| **3D 渲染** | OpenGL ES 2.0 (自研引擎) |
| **模型解码** | Draco JNI (原生库) |
| **模型格式** | GLB (二进制 glTF) |
| **第三方依赖** | **零依赖** |
| **代码混淆** | R8 / ProGuard (minify + shrinkResources) |

### 视觉风格

- 统一**科幻金属风格**
- LED 发光元素（青色 / 绿色）
- 超速红色预警
- 270° 弧形表盘 + LED 刻度点 + LED 七段数码管数字
- 金属渐变外圈 + 中心螺丝帽装饰

---

## 📁 项目结构

```
app/src/main/
├── java/com/jingxin/pandrive/
│   ├── MainActivity.java          # 主界面
│   ├── SettingsActivity.java      # 设置页（四按钮布局）
│   ├── FilePickerActivity.java    # 应用内文件选择
│   ├── PanDriveService.java       # 前台服务（导航广播）
│   ├── PanDriveApp.java           # Application
│   ├── gl/
│   │   ├── Car3DRenderer.java     # 3D 渲染器（GLB+Draco）
│   │   └── GlbParser.java         # GLB 解析器
│   ├── view/
│   │   ├── SpeedometerView.java   # 速度仪表盘 (1288 行)
│   │   ├── CompassView.java       # 指南针时钟
│   │   ├── NavigationBarView.java # 导航栏 (847 行, 53 图标)
│   │   ├── LaneView.java          # 车道线渐变背景
│   │   └── GridBackgroundView.java# 网格背景 + 壁纸 + 天气文字
│   └── data/
│       └── WeatherHelper.java     # 天气数据（Open-Meteo + ip-api）
├── assets/
│   ├── default_car.glb            # 内置柯尼塞格车模 (2.3 MB)
│   ├── default_wallpaper/         # 内置默认壁纸 (day/night.webp)
│   └── pandrive_weather/          # 6 个内置天气视频 (8.3 MB)
├── res/
│   ├── drawable-nodpi/            # 53 个 navi 转向图标 + 其他资源
│   ├── layout/                    # 布局
│   └── xml/network_security_config.xml  # HTTP 明文配置（ip-api）
└── AndroidManifest.xml
```

---

## 🔧 构建与运行

### 环境要求

- Android Studio（推荐）
- JDK 17（Android Studio 内置）
- Android SDK 35

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/ddjinxin/LecoDrive.git
cd LecoDrive

# 2. 配置签名
#    在项目根目录创建 local.properties 并填入密钥信息：
cat > local.properties << 'EOF'
sdk.dir=/path/to/Android/Sdk
STORE_PASSWORD=your_keystore_password
KEY_PASSWORD=your_key_password
EOF

#    将签名密钥放到 app/gaoden_release.jks

# 3. 编译 Release APK
export JAVA_HOME="/path/to/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/path/to/Android/Sdk"
./gradlew assembleRelease

# 4. 产物路径
#    app/build/outputs/apk/release/app-release.apk
```

### 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **运行前置**：需先安装「乐酷桌面」（`com.lecoauto`）作为车机启动器。

---

## 📂 资源放置说明

应用安装后，将以下文件放到设备的 **Download 目录**（应用会自动搜索常见路径，包括 `/sdcard/Download`、`/storage/emulated/0/Download` 等）即可生效：

| 资源 | 路径 | 用途 |
|---|---|---|
| **GLB 车模** | `/sdcard/Download/*.glb` | 3D 车辆模型（点击导航区随机切换）|
| **壁纸** | `/sdcard/Download/pandrive_wallpaper/day.*`<br/>`/sdcard/Download/pandrive_wallpaper/night.*` | 日间/夜间壁纸（jpg/png/webp/mp4）|
| **天气视频** | `/sdcard/Download/pandrive_weather/` | 自定义天气视频（sunny/cloud/rain/snow/fog/wind.mp4）|

> 💡 **提示**：天气视频与默认壁纸已内置到 assets，首次启动自动复制到设备存储，无需手动放置。

---

## 📱 兼容性

- **最低 Android 5.0 (API 21)** — 广泛兼容老旧车机
- **目标 Android 15 (API 35)** — 符合最新平台规范
- **多窗口 / 分屏** — 兼容车机常驻窗口模式
- **测试设备**：
  - 华为手机
  - 红米手机
  - Android 13 模拟器
  - Android 5.1 模拟器
  - Freescale MEK-MX8Q 车机 HMI（Android 8.1, API 27）

---

## 🌐 依赖的外部服务

| 服务 | 用途 | 鉴权 | 项目地址 |
|---|---|---|---|
| **Open-Meteo** | 天气数据 | 免费，无 key | https://open-meteo.com |
| **ip-api.com** | IP 地理定位兜底 | 免费，1500次/天 | https://ip-api.com |
| **高德车机版** | 导航广播源 | 需独立安装 | - |
| **乐酷桌面** | 车机启动器 | 需独立安装 | - |

---

## 📋 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 天气 API 与 IP 定位 |
| `SYSTEM_ALERT_WINDOW` | 悬浮显示 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 前台服务保活 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS 指南车与里程 |
| `POST_NOTIFICATIONS` | Android 13+ 通知 |
| `READ/WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` | 读取 GLB 模型与壁纸 |

---

## ⚠️ 已知问题

- **全屏 ↔ 窗口模式切换时应用卡死** — 在某些车机系统（Android 8.1 多窗口）上，切换模式时 Activity 重建后画面不刷新。根因可能在 GL context/Surface/TextureView 重建层，**修复中**。

---

## 📜 开源协议

本项目仅供学习交流使用。如需商用请联系作者。

---

## 🙏 致谢

- [Open-Meteo](https://open-meteo.com) — 免费天气 API
- [ip-api.com](https://ip-api.com) — 免费 IP 定位
- [Khronos glTF](https://www.khronos.org/gltf/) — GLB 模型格式标准
- [Google Draco](https://github.com/google/draco) — 3D 几何压缩库
- 高德地图车机版 — 导航广播数据源

---

<div align="center">

**乐酷驾驶**
为车机而生的全景驾驶仪表盘

</div>

> AI生成