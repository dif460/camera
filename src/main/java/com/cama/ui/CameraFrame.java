package com.cama.ui;

import com.cama.recorder.FrameScaler;
import com.cama.recorder.VideoRecorder;
import com.github.sarxos.webcam.Webcam;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CameraFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String[] FPS_OPTIONS = {"8", "10", "12", "15", "20", "24", "30"};
    private static final String[] RESOLUTION_OPTIONS = {
            "1280 x 720", "960 x 540", "640 x 480", "320 x 240"
    };

    private final JComboBox<String> deviceCombo = new JComboBox<String>();
    private final JComboBox<String> resolutionCombo = new JComboBox<String>(RESOLUTION_OPTIONS);
    private final JComboBox<String> fpsCombo = new JComboBox<String>(FPS_OPTIONS);
    private final JButton refreshButton = new JButton("刷新");
    private final JButton recordButton = new JButton("● 开始录像");
    private final JButton folderButton = new JButton("录像目录");
    private final JLabel recLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel("就绪");
    private final PreviewPanel preview = new PreviewPanel();
    private final VideoRecorder recorder = new VideoRecorder();
    private final File recordingsDir;

    private List<Webcam> webcams = new ArrayList<Webcam>();
    private Webcam webcam;
    private Thread captureThread;
    private Thread encoderThread;
    private volatile boolean capturing;
    private volatile boolean encoding;
    private final java.util.concurrent.atomic.AtomicReference<BufferedImage> latestFrame =
            new java.util.concurrent.atomic.AtomicReference<BufferedImage>();
    private FrameScaler scaler;
    private FrameScaler previewScaler;
    private Timer recTimer;
    private boolean updatingUi;

    public CameraFrame() {
        super("CAMA 摄像头录像");
        this.recordingsDir = new File(System.getProperty("user.dir"), "recordings");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(980, 720);
        setMinimumSize(new Dimension(720, 520));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        add(createToolBar(), BorderLayout.NORTH);
        add(preview, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        deviceCombo.setPreferredSize(new Dimension(240, deviceCombo.getPreferredSize().height));
        resolutionCombo.setPreferredSize(new Dimension(120, resolutionCombo.getPreferredSize().height));
        fpsCombo.setPreferredSize(new Dimension(70, fpsCombo.getPreferredSize().height));
        resolutionCombo.setSelectedItem("640 x 480");
        fpsCombo.setSelectedItem("20");
        recordButton.setEnabled(false);

        deviceCombo.addActionListener(e -> {
            if (!updatingUi) {
                openCamera(deviceCombo.getSelectedIndex());
            }
        });
        resolutionCombo.addActionListener(e -> {
            if (!updatingUi && !recorder.isRecording()) {
                fpsCombo.setSelectedItem(String.valueOf(recommendedFps()));
            }
        });
        refreshButton.addActionListener(e -> refreshDevices());
        recordButton.addActionListener(e -> toggleRecording());
        folderButton.addActionListener(e -> openRecordingsFolder());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        refreshDevices();
    }

    private JToolBar createToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        bar.add(new JLabel("摄像头: "));
        bar.add(deviceCombo);
        bar.add(refreshButton);
        bar.addSeparator();
        bar.add(new JLabel("输出分辨率: "));
        bar.add(resolutionCombo);
        bar.addSeparator();
        bar.add(new JLabel("帧率: "));
        bar.add(fpsCombo);
        bar.addSeparator();
        bar.add(recordButton);
        bar.add(folderButton);
        bar.add(Box.createHorizontalGlue());

        recLabel.setFont(recLabel.getFont().deriveFont(Font.BOLD, 14f));
        recLabel.setForeground(Color.RED);
        bar.add(recLabel);
        return bar;
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        panel.add(statusLabel, BorderLayout.WEST);
        return panel;
    }

    private void refreshDevices() {
        if (recorder.isRecording()) {
            return;
        }
        updatingUi = true;
        try {
            stopCapture();
            if (webcam != null) {
                webcam.close();
                webcam = null;
            }
            webcams = Webcam.getWebcams();
            deviceCombo.removeAllItems();
            for (Webcam cam : webcams) {
                deviceCombo.addItem(cam.getName());
            }
            if (webcams.isEmpty()) {
                recordButton.setEnabled(false);
                statusLabel.setText("未检测到摄像头，请检查设备连接与 Windows 隐私设置");
                return;
            }
            deviceCombo.setSelectedIndex(0);
        } catch (Exception ex) {
            statusLabel.setText("枚举摄像头失败: " + ex.getMessage());
        } finally {
            updatingUi = false;
        }
        openCamera(0);
    }

    private void openCamera(int index) {
        stopRecording();
        stopCapture();
        if (webcam != null) {
            webcam.close();
            webcam = null;
        }
        if (index < 0 || index >= webcams.size()) {
            recordButton.setEnabled(false);
            return;
        }
        Webcam cam = webcams.get(index);
        // 尽量以设备支持的最大分辨率采集，录像时再缩放到输出分辨率
        try {
            Dimension[] sizes = cam.getViewSizes();
            if (sizes != null && sizes.length > 0) {
                Dimension best = sizes[0];
                for (Dimension d : sizes) {
                    if (d.width * d.height > best.width * best.height) {
                        best = d;
                    }
                }
                cam.setViewSize(best);
            }
        } catch (Exception ignored) {
            // 部分驱动不支持设置，使用默认分辨率
        }
        statusLabel.setText("正在打开摄像头...");
        boolean opened;
        try {
            opened = cam.open();
        } catch (Exception ex) {
            opened = false;
        }
        if (!opened) {
            recordButton.setEnabled(false);
            statusLabel.setText("无法打开摄像头: " + cam.getName());
            return;
        }
        webcam = cam;
        Dimension actual = cam.getViewSize();
        if (actual == null) {
            actual = new Dimension(640, 480);
        }
        // 预览使用标准字节栅格，避免 Swing 绘制 TYPE_CUSTOM 图像时的逐像素转换
        if (actual.width > 960) {
            int previewH = Math.max(2, actual.height * 960 / actual.width);
            previewScaler = new FrameScaler(960, previewH & ~1);
        } else {
            previewScaler = new FrameScaler(actual.width, actual.height);
        }
        statusLabel.setText("已就绪  |  " + cam.getName() + "  采集画面 "
                + actual.width + "x" + actual.height);
        recordButton.setEnabled(true);
        startCapture();
    }

    private void startCapture() {
        final Webcam cam = webcam;
        if (cam == null) {
            return;
        }
        capturing = true;
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int consecutiveErrors = 0;
                while (capturing && cam.isOpen()) {
                    BufferedImage image = null;
                    try {
                        image = cam.getImage();
                        consecutiveErrors = 0;
                    } catch (Exception ex) {
                        consecutiveErrors++;
                        if (consecutiveErrors > 20) {
                            break;
                        }
                    }
                    if (image != null) {
                        latestFrame.set(image);
                        preview.setImage(previewScaler.scale(image));
                    }
                    // 采集线程本身由驱动限速，这里仅避免空转
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "cama-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void stopCapture() {
        capturing = false;
        Thread t = captureThread;
        captureThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 编码线程独立运行，按目标帧率节拍取最新帧编码，
     * 避免 JCodec 编码耗时拖慢采集和预览。
     */
    private void startEncoderThread() {
        encoding = true;
        encoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long nextTick = System.currentTimeMillis();
                while (encoding && recorder.isRecording()) {
                    BufferedImage source = latestFrame.get();
                    if (source == null) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    try {
                        recorder.encode(scaler.scale(source));
                    } catch (IOException ex) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                handleRecordError();
                            }
                        });
                        break;
                    }
                    nextTick += frameIntervalMillis();
                    long now = System.currentTimeMillis();
                    if (nextTick < now) {
                        // 编码跟不上目标帧率时不追帧，直接取下一帧
                        nextTick = now + 1;
                    }
                    if (!waitUntil(nextTick)) {
                        break;
                    }
                }
            }
        }, "cama-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    /**
     * 精确等待到指定时间点。Windows 下 Thread.sleep 的粒度约 15ms，
     * 长等待用 sleep，最后一段用让出 CPU 的忙等，保证帧节拍稳定。
     */
    private boolean waitUntil(long deadline) {
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (remain > 20) {
                try {
                    Thread.sleep(remain - 15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } else {
                Thread.yield();
            }
        }
    }

    private void stopEncoderThread() {
        encoding = false;
        Thread t = encoderThread;
        encoderThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int selectedFps() {
        try {
            return Integer.parseInt((String) fpsCombo.getSelectedItem());
        } catch (Exception e) {
            return 30;
        }
    }

    private long frameIntervalMillis() {
        return Math.max(1L, 1000L / selectedFps());
    }

    /**
     * 本机 JCodec（纯 Java）编码性能参考：720p 约 8fps、540p 约 12fps、480p 约 20fps。
     * 切换分辨率时给出可达的默认帧率，用户仍可手动调整。
     */
    private int recommendedFps() {
        String res = (String) resolutionCombo.getSelectedItem();
        if ("1280 x 720".equals(res)) {
            return 8;
        }
        if ("960 x 540".equals(res)) {
            return 12;
        }
        if ("320 x 240".equals(res)) {
            return 30;
        }
        return 20;
    }

    private Dimension selectedResolution() {
        String res = (String) resolutionCombo.getSelectedItem();
        String[] parts = res == null ? new String[0] : res.split("x");
        if (parts.length == 2) {
            try {
                return new Dimension(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // 落到默认值
            }
        }
        return new Dimension(640, 480);
    }

    private void toggleRecording() {
        if (recorder.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (webcam == null || !webcam.isOpen()) {
            JOptionPane.showMessageDialog(this, "摄像头未就绪，无法录像。",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Dimension res = selectedResolution();
        scaler = new FrameScaler(res.width, res.height);
        String name = "CAMA_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".mp4";
        File output = new File(recordingsDir, name);
        try {
            recorder.start(output, selectedFps());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "创建录像文件失败: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        recordButton.setText("■ 停止录像");
        deviceCombo.setEnabled(false);
        resolutionCombo.setEnabled(false);
        fpsCombo.setEnabled(false);
        refreshButton.setEnabled(false);
        statusLabel.setText("录像中: " + output.getName() + "  |  " + res.width + "x" + res.height
                + " @ " + selectedFps() + "fps");
        startEncoderThread();
        startRecTimer();
    }

    private void stopRecording() {
        if (!recorder.isRecording()) {
            return;
        }
        stopEncoderThread();
        File output = recorder.getOutputFile();
        try {
            recorder.stop();
            statusLabel.setText("已保存: " + (output == null ? "" : output.getAbsolutePath()));
        } catch (IOException ex) {
            statusLabel.setText("保存录像失败: " + ex.getMessage());
        }
        finishRecordingUi();
    }

    private void handleRecordError() {
        if (!recorder.isRecording()) {
            return;
        }
        stopEncoderThread();
        try {
            recorder.stop();
        } catch (IOException ignored) {
            // 已尽力收尾
        }
        finishRecordingUi();
        statusLabel.setText("录像发生错误，已停止并保存可用部分");
    }

    private void finishRecordingUi() {
        stopRecTimer();
        recLabel.setText(" ");
        recordButton.setText("● 开始录像");
        deviceCombo.setEnabled(true);
        resolutionCombo.setEnabled(true);
        fpsCombo.setEnabled(true);
        refreshButton.setEnabled(true);
    }

    private void startRecTimer() {
        stopRecTimer();
        recTimer = new Timer(500, e -> {
            long seconds = recorder.getElapsedSeconds();
            recLabel.setText(String.format("● REC %02d:%02d", seconds / 60, seconds % 60));
        });
        recTimer.start();
    }

    private void stopRecTimer() {
        if (recTimer != null) {
            recTimer.stop();
            recTimer = null;
        }
    }

    private void openRecordingsFolder() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(recordingsDir);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "无法打开目录: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void shutdown() {
        if (recorder.isRecording()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "正在录像，退出前将停止并保存，是否继续？",
                    "确认退出", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        stopRecording();
        stopCapture();
        if (webcam != null) {
            webcam.close();
        }
        dispose();
    }
}
