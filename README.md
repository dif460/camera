# CAMA 摄像头录像

基于 Java 8 + Swing 的本地摄像头录像工具：实时预览、一键录像，输出带 AAC 音频的 MP4 文件。
摄像头调用使用 Sarxos webcam-capture，视频编码使用 JCodec（纯 Java H.264），
音频采集使用 Java Sound，停止录像后由 FFmpeg 把 WAV 与视频轨混流成标准 MP4
（视频流直接 copy，只编码音频，不重新编码画面）。

## 环境要求

- JDK 8（需要 `javac`），默认读取 `JAVA_HOME`；未设置时脚本使用 `D:\Compilation_environment\jdk8`
- Windows（摄像头通过 DirectShow 访问，麦克风通过 Java Sound 访问）
- 摄像头驱动正常，Windows「设置 → 隐私 → 相机」允许桌面应用访问摄像头
- 麦克风在「设置 → 隐私 → 麦克风」中允许桌面应用访问
- 音频合成需要 `tools\ffmpeg.exe`；首次使用运行 `tools\fetch-ffmpeg.bat` 下载
  （也可以直接用 PATH 中的 ffmpeg）

## 编译与运行

```bash
# 编译（输出到 out\）
build.bat

# 首次使用时准备 FFmpeg（约 38 MB 下载）
tools\fetch-ffmpeg.bat

# 启动界面
run.bat

# 仅检测摄像头与支持的分辨率
probe.bat
```

项目没有使用 Maven，依赖 jar 已放在 `lib\`，`build.bat` 直接调用 `javac` 编译。

## 功能

- 深色界面：顶部设备栏、圆角预览卡片、底部控制条与状态栏
- 摄像头设备选择与刷新
- 实时预览（画面保持宽高比缩放），画面上叠加设备名称与输出参数
- 输出分辨率：1280×720 / 960×540 / 640×480 / 320×240
- 输出帧率：8 / 10 / 12 / 15 / 20 / 24 / 30
- 麦克风选择：默认麦克风、系统枚举到的录音设备，或「不录制音频」
- 录像时同步采集音频，停止后自动合成为 AAC 128 kbps 音轨（44.1 kHz / 48 kHz，单声道或立体声随设备）
- 录像时预览显示 REC 计时与脉冲红框，控制按钮切换为停止样式
- 快捷键 `Ctrl+R` 开始 / 停止录像
- 录像状态与时长显示，停止后自动保存
- 录像保存在项目根目录的 `recordings\` 下，文件名形如 `CAMA_20261102_231500.mp4`
- 关闭窗口时若正在录像会提示保存

## 音频说明

- 录音在视频开始前启动，混流时按时间差裁掉多出来的开头，保证音画同步
- 停止录像后状态栏会短暂显示「正在保存录像…」，此时 FFmpeg 在后台合成音频；
  视频流不重新编码，1 分钟片段通常 1 秒内完成
- 麦克风被占用或权限被拒绝时自动降级为纯视频录制，状态栏会给出提示
- 找不到 FFmpeg 时同样只录视频；不会留下无法播放的文件
- 极端情况下（FFmpeg 合成失败）会保留 `xxx.mp4` 视频和 `xxx.wav` 音频两个文件
- 录制过程中的 WAV 和中间视频放在 `recordings\.tmp\`，成功后自动删除，
  超过 6 小时的残留文件会在下次开始录像时清理

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
├── tools/
│   ├── fetch-ffmpeg.bat         下载 FFmpeg 到 tools\ffmpeg.exe
│   └── ffmpeg.exe               音频混流（不提交到仓库）
├── recordings/                  录像输出目录
│   └── .tmp/                    录制中的临时视频与 WAV
└── src/main/java/com/cama/
    ├── Main.java                程序入口
    ├── CameraProbe.java         摄像头检测工具
    ├── recorder/
    │   ├── VideoRecorder.java   MP4 录像器（JCodec）
    │   ├── AudioRecorder.java   麦克风录音器（Java Sound → WAV）
    │   ├── FfmpegMuxer.java     WAV + MP4 → AAC 混流
    │   └── FrameScaler.java     快速缩放
    └── ui/
        ├── CameraFrame.java     主窗口与采集/编码线程
        ├── PreviewPanel.java    预览面板与录像叠加层
        ├── Theme.java           深色主题（配色、字体、下拉框样式）
        └── PillButton.java      圆角按钮
```
