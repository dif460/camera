package com.cama.recorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用 FFmpeg 把 JCodec 录制的视频轨和 WAV 音频合成为标准 MP4（H.264 + AAC）。
 *
 * 只做混流：视频流直接 copy，不重新编码；音频编码为 128 kbps AAC。
 * FFmpeg 优先使用项目自带的 tools/ffmpeg.exe，其次使用 PATH 中的 ffmpeg。
 */
public final class FfmpegMuxer {

    private static final String[] LOCAL_CANDIDATES = {
            "tools/ffmpeg.exe",
            "tools/ffmpeg/ffmpeg.exe"
    };

    private static String cachedCommand;

    private FfmpegMuxer() {
    }

    /** 查找可用 ffmpeg，找不到返回 null。找到后缓存，失败则下次再找。 */
    public static synchronized String command() {
        if (cachedCommand != null) {
            return cachedCommand;
        }
        File base = new File(System.getProperty("user.dir", "."));
        for (String candidate : LOCAL_CANDIDATES) {
            File file = new File(base, candidate);
            if (file.isFile()) {
                cachedCommand = file.getAbsolutePath();
                return cachedCommand;
            }
        }
        // 尝试 PATH
        Process process = null;
        try {
            process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true).start();
            drain(process);
            if (process.waitFor() == 0) {
                cachedCommand = "ffmpeg";
            }
        } catch (Exception ignored) {
            cachedCommand = null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return cachedCommand;
    }

    public static boolean isAvailable() {
        return command() != null;
    }

    /**
     * 合成音视频。
     *
     * @param video           JCodec 生成的 MP4（只有视频轨）
     * @param audio           Java Sound 录制的 WAV
     * @param output          最终 MP4
     * @param audioLeadMillis 音频比视频提前开始的时间（毫秒），会从音频开头裁掉
     */
    public static void mux(File video, File audio, File output, long audioLeadMillis)
            throws IOException {
        String ffmpeg = command();
        if (ffmpeg == null) {
            throw new IOException("未找到 ffmpeg");
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent.getAbsolutePath());
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("无法覆盖已有文件: " + output.getAbsolutePath());
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(ffmpeg);
        cmd.addAll(Arrays.asList(
                "-hide_banner", "-loglevel", "error", "-y"));
        if (audioLeadMillis > 20) {
            // 音频先开始，裁掉视频开始前那一段，保证口型同步
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", audioLeadMillis / 1000.0));
        }
        cmd.add("-i");
        cmd.add(audio.getAbsolutePath());
        cmd.add("-i");
        cmd.add(video.getAbsolutePath());
        cmd.addAll(Arrays.asList(
                "-map", "1:v:0",
                "-map", "0:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "128k",
                // 音频不足时补静音，再以视频长度为准截断
                "-af", "apad",
                "-shortest",
                "-movflags", "+faststart",
                output.getAbsolutePath()));

        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final StringBuilder log = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readInto(process.getInputStream(), log);
            }
        }, "cama-ffmpeg-log");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(120, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("音频合成被中断", ex);
        }
        if (!finished) {
            process.destroyForcibly();
            output.delete();
            throw new IOException("音频合成超时");
        }
        try {
            reader.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (process.exitValue() != 0) {
            String message = log.toString().trim();
            output.delete();
            throw new IOException("音频合成失败: "
                    + (message.isEmpty() ? "ffmpeg 退出码 " + process.exitValue() : message));
        }
        if (!output.isFile() || output.length() == 0) {
            output.delete();
            throw new IOException("音频合成失败: 未生成输出文件");
        }
    }

    private static void drain(Process process) {
        readInto(process.getInputStream(), new StringBuilder());
    }

    private static void readInto(java.io.InputStream input, StringBuilder sink) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink != null) {
                    if (sink.length() > 0) {
                        sink.append('\n');
                    }
                    sink.append(line);
                }
            }
        } catch (IOException ignored) {
            // 进程结束时会关闭流
        }
    }
}
