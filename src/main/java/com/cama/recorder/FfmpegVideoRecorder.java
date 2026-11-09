package com.cama.recorder;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用 FFmpeg 直接把 BGR 原始帧编码成 H.264 MP4。
 *
 * 画面通过管道写入 ffmpeg 的标准输入，输出为兼容性最好的 H.264 High/Baseline
 * + yuv420p 文件，Windows 播放器、浏览器和手机都能直接播放。音频仍由
 * {@link AudioRecorder} 录成 WAV，停止后由 {@link FfmpegMuxer} 合成到同一个 MP4。
 */
public class FfmpegVideoRecorder implements VideoWriter {

    /** 等待 ffmpeg 把缓冲的帧编码完并写完文件头的超时时间。 */
    private static final long FINISH_TIMEOUT_SECONDS = 60L;

    private Process process;
    private OutputStream stdin;
    private Thread logReader;
    private final StringBuilder log = new StringBuilder();
    private BufferedImage convertBuffer;

    private File outputFile;
    private int fps;
    private int frameWidth;
    private int frameHeight;
    private long startTime;
    private long frameCount;
    private boolean recording;

    @Override
    public synchronized void start(File file, int fps) throws IOException {
        if (recording) {
            throw new IllegalStateException("录像已在进行中");
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps 必须大于 0");
        }
        String ffmpeg = FfmpegMuxer.command();
        if (ffmpeg == null) {
            throw new IOException("未找到 ffmpeg");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建录像目录: " + parent.getAbsolutePath());
        }
        this.outputFile = file;
        this.fps = fps;
        this.frameCount = 0;
        this.frameWidth = 0;
        this.frameHeight = 0;
        this.convertBuffer = null;
        this.startTime = System.currentTimeMillis();
        this.recording = true;
        // ffmpeg 需要知道分辨率，等第一帧到达时再启动进程
    }

    @Override
    public synchronized void encode(BufferedImage image) throws IOException {
        if (!recording || image == null) {
            return;
        }
        if (process == null) {
            startProcess(image.getWidth(), image.getHeight());
        }
        if (image.getWidth() != frameWidth || image.getHeight() != frameHeight) {
            throw new IOException("录像画面尺寸发生变化: "
                    + image.getWidth() + "x" + image.getHeight()
                    + "，期望 " + frameWidth + "x" + frameHeight);
        }
        writeFrame(image);
        frameCount++;
    }

    private void startProcess(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IOException("不支持的录像分辨率: " + width + "x" + height
                    + "（宽高必须为偶数）");
        }
        this.frameWidth = width;
        this.frameHeight = height;

        List<String> cmd = new ArrayList<String>();
        cmd.add(FfmpegMuxer.command());
        cmd.addAll(Arrays.asList(
                "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo",
                "-pixel_format", "bgr24",
                "-video_size", width + "x" + height,
                "-framerate", String.valueOf(fps),
                "-i", "pipe:0",
                "-an",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "22",
                "-pix_fmt", "yuv420p",
                "-g", String.valueOf(Math.max(fps * 2, 30)),
                "-movflags", "+faststart",
                outputFile.getAbsolutePath()));

        log.setLength(0);
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectErrorStream(true);
        process = builder.start();
        stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 20);

        final Process p = process;
        logReader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (log) {
                            if (log.length() > 0) {
                                log.append('\n');
                            }
                            log.append(line);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程结束时流会关闭
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                            // 尽力而为
                        }
                    }
                }
            }
        }, "cama-ffmpeg-encoder-log");
        logReader.setDaemon(true);
        logReader.start();
    }

    private void writeFrame(BufferedImage image) throws IOException {
        int rowBytes = frameWidth * 3;
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR
                && image.getRaster().getSampleModel() instanceof ComponentSampleModel
                && image.getRaster().getDataBuffer() instanceof DataBufferByte) {
            ComponentSampleModel sm = (ComponentSampleModel) image.getRaster().getSampleModel();
            int[] offsets = sm.getBandOffsets();
            if (sm.getPixelStride() == 3 && isBgrOrder(offsets)) {
                byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                int stride = sm.getScanlineStride();
                if (stride == rowBytes) {
                    stdin.write(data, 0, rowBytes * frameHeight);
                } else {
                    for (int y = 0; y < frameHeight; y++) {
                        stdin.write(data, y * stride, rowBytes);
                    }
                }
                return;
            }
        }

        // 非 BGR 紧凑格式：先转换到复用的 BGR 图像
        if (convertBuffer == null) {
            convertBuffer = new BufferedImage(frameWidth, frameHeight,
                    BufferedImage.TYPE_3BYTE_BGR);
        }
        Graphics2D g = convertBuffer.createGraphics();
        try {
            g.drawImage(image, 0, 0, frameWidth, frameHeight, null);
        } finally {
            g.dispose();
        }
        byte[] data = ((DataBufferByte) convertBuffer.getRaster().getDataBuffer()).getData();
        stdin.write(data, 0, rowBytes * frameHeight);
    }

    private static boolean isBgrOrder(int[] offsets) {
        return offsets != null && offsets.length == 3
                && offsets[0] == 2 && offsets[1] == 1 && offsets[2] == 0;
    }

    @Override
    public synchronized void stop() throws IOException {
        if (!recording) {
            return;
        }
        recording = false;
        Process p = process;
        process = null;
        if (p == null) {
            // 一帧都没录到，没有可保存的视频
            closeQuietly();
            return;
        }
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // 进程可能已经退出，下面根据退出码判断
        }
        stdin = null;

        boolean finished;
        try {
            finished = p.waitFor(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("录像收尾被中断", ex);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("录像收尾超时");
        }
        joinLogReader();

        int code = p.exitValue();
        if (code != 0) {
            String message;
            synchronized (log) {
                message = log.toString().trim();
            }
            throw new IOException("FFmpeg 编码失败"
                    + (message.isEmpty() ? "（退出码 " + code + "）" : ": " + message));
        }
        if (outputFile == null || !outputFile.isFile() || outputFile.length() == 0) {
            throw new IOException("FFmpeg 未生成录像文件");
        }
    }

    private void joinLogReader() {
        Thread reader = logReader;
        logReader = null;
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeQuietly() {
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
                // 尽力而为
            }
            stdin = null;
        }
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        joinLogReader();
    }

    @Override
    public synchronized boolean isRecording() {
        return recording;
    }

    @Override
    public synchronized long getElapsedSeconds() {
        if (startTime == 0) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - startTime) / 1000L);
    }

    @Override
    public synchronized long getStartTimeMillis() {
        return startTime;
    }

    @Override
    public synchronized long getFrameCount() {
        return frameCount;
    }

    @Override
    public synchronized File getOutputFile() {
        return outputFile;
    }
}
