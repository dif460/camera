package com.cama.recorder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 录像器统一接口，便于在 FFmpeg 编码和 JCodec 编码之间切换。
 */
public interface VideoWriter {

    void start(File file, int fps) throws IOException;

    void encode(BufferedImage image) throws IOException;

    void stop() throws IOException;

    boolean isRecording();

    long getElapsedSeconds();

    /** 视频开始编码的时间戳，用于和音频对齐。 */
    long getStartTimeMillis();

    long getFrameCount();

    File getOutputFile();
}
