# CAMA 摄像头录像

基于 Java 8 + Swing 的本地摄像头录像工具：实时预览、一键录像，输出 MP4 文件。
摄像头调用使用 Sarxos webcam-capture，视频编码使用 JCodec（纯 Java H.264），
**不需要安装 FFmpeg 或其他运行库**。

## 环境要求

- JDK 8（需要 `javac`），默认读取 `JAVA_HOME`；未设置时脚本使用 `D:\Compilation_environment\jdk8`
- Windows（摄像头通过 DirectShow 访问）
- 摄像头驱动正常，Windows「设置 → 隐私 → 相机」允许桌面应用访问摄像头

## 编译与运行

```bash
# 编译（输出到 out\）
build.bat

# 启动界面
run.bat

# 仅检测摄像头与支持的分辨率
probe.bat
```

项目没有使用 Maven，依赖 jar 已放在 `lib\`，`build.bat` 直接调用 `javac` 编译。

## 功能

- 摄像头设备选择与刷新
- 实时预览（画面保持宽高比缩放）
- 输出分辨率：1280×720 / 960×540 / 640×480 / 320×240
- 输出帧率：8 / 10 / 12 / 15 / 20 / 24 / 30
- 录像状态与时长显示，停止后自动保存
- 录像保存在项目根目录的 `recordings\` 下，文件名形如 `CAMA_20261102_231500.mp4`
- 关闭窗口时若正在录像会提示保存

## 性能说明

JCodec 是纯 Java 编码器，编码速度受 CPU 影响。在本机（Ryzen 5 5500U）实测：

| 输出分辨率 | 可持续帧率 |
| --- | --- |
| 1280×720 | 约 8 fps |
| 960×540 | 约 12 fps |
| 640×480 | 约 20 fps |
| 320×240 | 约 30 fps |

切换分辨率时会自动把帧率设为该分辨率的推荐值，也可以手动调整。
若手动设置的帧率超出编码能力，视频实际时长会短于录像时长（画面会略微加速）。

摄像头驱动返回的原始帧在本机为 1280×720、`TYPE_CUSTOM` 的字节栅格，
直接绘制会触发逐像素颜色转换（约 30ms/帧），因此 `FrameScaler` 直接操作
`DataBufferByte` 做快速缩放（约 3-6ms/帧）。采集、预览、编码分别在独立线程中运行，
编码线程按精确时钟节拍取最新帧，保证视频时长与真实时长一致。

## 目录结构

```
cama/
├── build.bat / run.bat / probe.bat
├── lib/                         依赖 jar
│   ├── webcam-capture-0.3.12.jar
│   ├── bridj-0.7.0.jar
│   ├── slf4j-api-1.7.36.jar
│   ├── slf4j-simple-1.7.36.jar
│   ├── jcodec-0.2.5.jar
│   └── jcodec-javase-0.2.5.jar
├── recordings/                  录像输出目录
└── src/main/java/com/cama/
    ├── Main.java                程序入口
    ├── CameraProbe.java         摄像头检测工具
    ├── recorder/
    │   ├── VideoRecorder.java   MP4 录像器（JCodec）
    │   └── FrameScaler.java     快速缩放
    └── ui/
        ├── CameraFrame.java     主窗口与采集/编码线程
        └── PreviewPanel.java    预览面板
```
