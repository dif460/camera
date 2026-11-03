package com.cama.recorder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 Java Sound 的麦克风录音器，把采集到的 PCM 直接写成标准 WAV。
 *
 * 录音线程只做「读声卡 → 写文件」，不做任何编码；录像停止后再由 FFmpeg
 * 把 WAV 与 MP4 视频轨合成 AAC 音轨。
 */
public class AudioRecorder {

    /** 采集格式候选，按优先级选择设备支持的第一种。 */
    private static final AudioFormat[] FORMAT_CANDIDATES = {
            new AudioFormat(44100f, 16, 1, true, false),
            new AudioFormat(48000f, 16, 1, true, false),
            new AudioFormat(44100f, 16, 2, true, false),
            new AudioFormat(48000f, 16, 2, true, false)
    };

    /** 可选的录音设备。{@code info} 为 null 表示系统默认麦克风。 */
    public static final class Device {

        private final String name;
        private final Mixer.Info info;

        Device(String name, Mixer.Info info) {
            this.name = name;
            this.info = info;
        }

        public String getName() {
            return name;
        }

        Mixer.Info getInfo() {
            return info;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private TargetDataLine line;
    private Thread writer;
    private File file;
    private AudioFormat format;
    private volatile boolean recording;
    private volatile String error;
    private volatile long startedAtMillis;
    private volatile long dataBytes;

    /** 返回系统默认麦克风设备（总是可用；打开失败时由 start 抛异常）。 */
    public static Device defaultDevice() {
        return new Device("默认麦克风", null);
    }

    /**
     * 枚举能采集指定格式音频的混音器。Windows 下通常会列出
     * 「麦克风阵列 / Microphone (Realtek) / 立体声混音」等设备。
     */
    public static List<Device> listDevices() {
        List<Device> result = new ArrayList<Device>();
        Set<String> seen = new HashSet<String>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer;
            try {
                mixer = AudioSystem.getMixer(info);
            } catch (Exception ex) {
                continue;
            }
            if (!supportsCapture(mixer)) {
                continue;
            }
            String name = info.getName();
            // 同一个设备偶尔会注册多个 Mixer，按名字去重
            if (!seen.add(name)) {
                continue;
            }
            result.add(new Device(name, info));
        }
        return result;
    }

    private static boolean supportsCapture(Mixer mixer) {
        for (AudioFormat candidate : FORMAT_CANDIDATES) {
            try {
                if (mixer.isLineSupported(
                        new DataLine.Info(TargetDataLine.class, candidate))) {
                    return true;
                }
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        return false;
    }

    private static AudioFormat pickFormat(Mixer mixer) {
        for (AudioFormat candidate : FORMAT_CANDIDATES) {
            boolean supported;
            if (mixer == null) {
                supported = AudioSystem.isLineSupported(
                        new DataLine.Info(TargetDataLine.class, candidate));
            } else {
                supported = mixer.isLineSupported(
                        new DataLine.Info(TargetDataLine.class, candidate));
            }
            if (supported) {
                return candidate;
            }
        }
        return FORMAT_CANDIDATES[0];
    }

    /**
     * 开始录音。{@code device} 为 null 时使用系统默认麦克风。
     * 返回后音频已经开始采集，调用方随后再启动视频计时即可。
     */
    public synchronized void start(File output, Device device) throws IOException {
        if (line != null) {
            throw new IllegalStateException("录音已在进行中");
        }
        if (output == null) {
            throw new IllegalArgumentException("录音文件不能为空");
        }
        error = null;
        dataBytes = 0;
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建录音目录: " + parent.getAbsolutePath());
        }

        Mixer.Info mixInfo = device == null ? null : device.getInfo();
        Mixer mixer = null;
        if (mixInfo != null) {
            try {
                mixer = AudioSystem.getMixer(mixInfo);
            } catch (Exception ex) {
                throw new IOException("无法访问麦克风: " + mixInfo.getName(), ex);
            }
        }
        AudioFormat chosen = pickFormat(mixer);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, chosen);
        TargetDataLine dataLine;
        try {
            if (mixer == null) {
                dataLine = (TargetDataLine) AudioSystem.getLine(lineInfo);
            } else {
                dataLine = (TargetDataLine) mixer.getLine(lineInfo);
            }
            int bufferBytes = Math.max(4096,
                    (int) chosen.getSampleRate() * chosen.getFrameSize() / 2);
            dataLine.open(chosen, bufferBytes);
            dataLine.start();
        } catch (LineUnavailableException ex) {
            throw new IOException("麦克风不可用: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("麦克风不支持录音格式: " + ex.getMessage(), ex);
        }

        this.line = dataLine;
        this.file = output;
        this.format = chosen;
        this.recording = true;
        this.startedAtMillis = System.currentTimeMillis();
        this.writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writeAudio();
            }
        }, "cama-audio");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    private void writeAudio() {
        TargetDataLine dataLine = line;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            raf.write(new byte[44]); // 先占位，停止时回填真实长度
            byte[] buffer = new byte[8192];
            while (recording) {
                int read = dataLine.read(buffer, 0, buffer.length);
                if (read > 0) {
                    raf.write(buffer, 0, read);
                    dataBytes += read;
                }
            }
        } catch (Exception ex) {
            if (recording) {
                error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
        } finally {
            if (raf != null) {
                try {
                    writeHeader(raf, dataBytes);
                } catch (IOException ex) {
                    if (error == null) {
                        error = "写入音频文件失败: " + ex.getMessage();
                    }
                } finally {
                    try {
                        raf.close();
                    } catch (IOException ignored) {
                        // 尽力而为
                    }
                }
            }
            try {
                dataLine.stop();
                dataLine.close();
            } catch (Exception ignored) {
                // 设备可能已被拔出
            }
        }
    }

    /** 停止录音并等待 WAV 头写完。可重复调用。 */
    public synchronized void stop() {
        if (line == null) {
            return;
        }
        recording = false;
        TargetDataLine dataLine = line;
        line = null;
        try {
            // close 会中断阻塞中的 read，让写线程退出并回填文件头
            dataLine.stop();
            dataLine.close();
        } catch (Exception ignored) {
            // 设备已经关闭
        }
        Thread t = writer;
        writer = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeHeader(RandomAccessFile raf, long dataLength) throws IOException {
        AudioFormat f = format;
        int channels = f == null ? 1 : f.getChannels();
        int sampleRate = f == null ? 44100 : (int) f.getSampleRate();
        int bits = f == null ? 16 : f.getSampleSizeInBits();
        int blockAlign = channels * bits / 8;
        int byteRate = sampleRate * blockAlign;

        raf.seek(0);
        raf.write(ascii("RIFF"));
        writeInt(raf, (int) (36 + dataLength));
        raf.write(ascii("WAVE"));
        raf.write(ascii("fmt "));
        writeInt(raf, 16);
        writeShort(raf, 1); // PCM
        writeShort(raf, channels);
        writeInt(raf, sampleRate);
        writeInt(raf, byteRate);
        writeShort(raf, blockAlign);
        writeShort(raf, bits);
        raf.write(ascii("data"));
        writeInt(raf, (int) dataLength);
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) value.charAt(i);
        }
        return bytes;
    }

    private static void writeInt(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xff);
        raf.write((value >>> 8) & 0xff);
        raf.write((value >>> 16) & 0xff);
        raf.write((value >>> 24) & 0xff);
    }

    private static void writeShort(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xff);
        raf.write((value >>> 8) & 0xff);
    }

    public synchronized boolean isRecording() {
        return line != null;
    }

    public synchronized long getElapsedSeconds() {
        if (startedAtMillis == 0) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000L);
    }

    /** 第一帧音频开始采集的时间戳，用于和视频对齐。 */
    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public File getFile() {
        return file;
    }

    public String getError() {
        return error;
    }

    public AudioFormat getFormat() {
        return format;
    }
}
