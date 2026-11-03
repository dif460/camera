package com.cama;

import com.github.sarxos.webcam.Webcam;

import java.util.Arrays;
import java.util.List;

/**
 * 命令行工具：打印本机检测到的摄像头及其支持的分辨率。
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
    }
}
