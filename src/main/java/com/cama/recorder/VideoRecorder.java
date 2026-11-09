package com.cama.recorder;

import org.jcodec.api.awt.AWTSequenceEncoder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 基于 JCodec 的 MP4 录像器，纯 Java 实现，不依赖 FFmpeg。
 * 作为 {@link FfmpegVideoRecorder} 的兜底方案，仅在找不到 ffmpeg 时使用；
 * 其产物兼容性一般，音频也无法混入。所有方法均加锁，编码在编码线程中完成。
 */
public class VideoRecorder implements VideoWriter {

    private AWTSequenceEncoder encoder;
    private File outputFile;
    private long startTime;
    private long frameCount;

    public synchronized void start(File file, int fps) throws IOException {
        if (encoder != null) {
            throw new IllegalStateException("录像已在进行中");
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps 必须大于 0");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建录像目录: " + parent.getAbsolutePath());
        }
        this.encoder = AWTSequenceEncoder.createSequenceEncoder(file, fps);
        this.outputFile = file;
        this.startTime = System.currentTimeMillis();
        this.frameCount = 0;
    }

    public synchronized void encode(BufferedImage image) throws IOException {
        if (encoder == null) {
            return;
        }
        try {
            encoder.encodeImage(image);
            frameCount++;
        } catch (IOException e) {
            // 编码失败时主动收尾，保证已写入的文件可播放
            closeEncoderQuietly();
            throw e;
        }
    }

    public synchronized void stop() throws IOException {
        if (encoder == null) {
            return;
        }
        AWTSequenceEncoder enc = encoder;
        encoder = null;
        try {
            enc.finish();
        } catch (IOException e) {
            closeEncoderQuietly();
            throw e;
        }
    }

    public synchronized boolean isRecording() {
        return encoder != null;
    }

    public synchronized long getElapsedSeconds() {
        if (startTime == 0) {
            return 0;
        }
        return (System.currentTimeMillis() - startTime) / 1000L;
    }

    /** 视频开始编码的时间戳，用于和音频对齐。 */
    public synchronized long getStartTimeMillis() {
        return startTime;
    }

    public synchronized long getFrameCount() {
        return frameCount;
    }

    public synchronized File getOutputFile() {
        return outputFile;
    }

    private void closeEncoderQuietly() {
        if (encoder != null) {
            try {
                encoder.finish();
            } catch (IOException ignored) {
                // 尽力而为
            }
            encoder = null;
        }
    }
}
