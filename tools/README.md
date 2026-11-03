# tools

## ffmpeg.exe

音频合成依赖 FFmpeg，程序只使用它的混流和 AAC 编码能力：

```bash
ffmpeg -i audio.wav -i video.mp4 -c:v copy -c:a aac -b:a 128k out.mp4
```

本目录不提交二进制文件，首次使用前运行：

```bat
fetch-ffmpeg.bat
```

脚本从 PyPI 下载 `imageio-ffmpeg 0.8.0`（Windows x64 wheel）并提取其中的
`ffmpeg.exe`。该二进制来自 [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 的
FFmpeg 9.0.2 essentials build（GPLv3）。

如果本机 PATH 中已有 ffmpeg，也可以不下载：程序会依次查找
`tools/ffmpeg.exe`、`tools/ffmpeg/ffmpeg.exe`、`PATH` 中的 `ffmpeg`。
