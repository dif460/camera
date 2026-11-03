package com.cama;

import com.cama.recorder.AudioRecorder;
import com.cama.recorder.FfmpegMuxer;
import com.github.sarxos.webcam.Webcam;

import java.util.Arrays;
import java.util.List;

/**
 * 命令行工具：打印本机检测到的摄像头、麦克风，以及 FFmpeg 是否就绪。
 */
public class CameraProbe {

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        List<Webcam> cams = Webcam.getWebcams();
        System.out.println("检测到 " + cams.size() + " 个摄像头");
        for (Webcam cam : cams) {
            System.out.println("- " + cam.getName());
            try {
                System.out.println("  支持分辨率: " + Arrays.toString(cam.getViewSizes()));
            } catch (Exception ex) {
                System.out.println("  读取分辨率失败: " + ex.getMessage());
            }
        }

        List<AudioRecorder.Device> mics = AudioRecorder.listDevices();
        System.out.println("检测到 " + mics.size() + " 个录音设备");
        for (AudioRecorder.Device mic : mics) {
            System.out.println("- " + mic.getName());
        }

        System.out.println("音频合成: " + (FfmpegMuxer.isAvailable()
                ? "FFmpeg 就绪 (" + FfmpegMuxer.command() + ")"
                : "未找到 FFmpeg，请运行 tools\\fetch-ffmpeg.bat"));
    }
}
