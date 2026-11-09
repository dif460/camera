package com.cama.ui;

import com.cama.recorder.AudioRecorder;
import com.cama.recorder.FfmpegMuxer;
import com.cama.recorder.FfmpegVideoRecorder;
import com.cama.recorder.FrameScaler;
import com.cama.recorder.VideoRecorder;
import com.cama.recorder.VideoWriter;
import com.github.sarxos.webcam.Webcam;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

    private final JComboBox<String> deviceCombo = Theme.combo(new String[0]);
    private final JComboBox<String> resolutionCombo = Theme.combo(RESOLUTION_OPTIONS);
    private final JComboBox<String> fpsCombo = Theme.combo(FPS_OPTIONS);
    private final JComboBox<String> micCombo = Theme.combo(new String[0]);
    private final PillButton refreshButton = PillButton.ghost("刷新");
    private final PillButton recordButton = PillButton.accent("● 开始录像");
    private final PillButton folderButton = PillButton.ghost("打开录像目录");
    private final PillButton hideButton = PillButton.ghost("—");
    private final JLabel statusLabel = Theme.label("就绪", Theme.BASE.deriveFont(12f),
            Theme.MUTED);
    private final JLabel infoLabel = Theme.label("", Theme.BASE.deriveFont(Font.PLAIN, 12f),
            Theme.FAINT);
    private final PreviewPanel preview = new PreviewPanel();
    /**
     * 有 ffmpeg 时用它编码 H.264（兼容性最好，且能和音频合成单文件），
     * 没有时退回纯 Java 的 JCodec（只能录无声视频）。
     */
    private final VideoWriter recorder = FfmpegMuxer.isAvailable()
            ? new FfmpegVideoRecorder() : new VideoRecorder();
    private final AudioRecorder audioRecorder = new AudioRecorder();
    private final List<AudioRecorder.Device> micDevices = new ArrayList<AudioRecorder.Device>();
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
    private boolean recordingActive;
    private boolean recordError;
    private boolean finalizing;
    private File pendingOutputFile;
    private File pendingVideoFile;
    private File pendingAudioFile;
    private long pendingAudioLeadMillis;
    private TrayIcon trayIcon;
    private boolean trayAvailable;
    private boolean trayHintShown;
    private boolean utilityWindow;

    public CameraFrame() {
        super("CAMA · 摄像头录像");
        // UTILITY 窗口不会出现在任务栏，仍然保留标题栏、最小化与缩放。
        // 只有确认系统托盘可用时才这么做，否则窗口没有任何入口可以找回。
        if (SystemTray.isSupported()) {
            setType(Window.Type.UTILITY);
            utilityWindow = true;
        }
        setIconImage(createAppIcon(64));
        this.recordingsDir = new File(System.getProperty("user.dir"), "recordings");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1080, 780);
        setMinimumSize(new Dimension(780, 580));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        add(createCenter(), BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);

        Theme.sizeCombo(deviceCombo, 280, 34);
        Theme.sizeCombo(resolutionCombo, 160, 34);
        Theme.sizeCombo(fpsCombo, 96, 34);
        Theme.sizeCombo(micCombo, 190, 34);
        micCombo.setToolTipText("录像时使用的麦克风，停止后由 FFmpeg 合成为 AAC 音轨");
        hideButton.setToolTipText("最小化到托盘");
        hideButton.setBorder(new EmptyBorder(0, 11, 0, 11));
        hideButton.addActionListener(e -> minimizeToTray());
        recordButton.setButtonHeight(38);
        recordButton.setToolTipText("开始 / 停止录像（Ctrl+R）");
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
                updateInfo();
            }
        });
        fpsCombo.addActionListener(e -> {
            if (!updatingUi) {
                updateInfo();
            }
        });
        refreshButton.addActionListener(e -> refreshDevices());
        recordButton.addActionListener(e -> toggleRecording());
        folderButton.addActionListener(e -> openRecordingsFolder());

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
                "toggleRecord");
        getRootPane().getActionMap().put("toggleRecord", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                if (recordButton.isEnabled()) {
                    toggleRecording();
                }
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                // 点标题栏的最小化按钮时直接收进托盘
                if (trayAvailable && isVisible()) {
                    hideToTray();
                } else if (utilityWindow) {
                    // 托盘图标注册失败时，UTILITY 窗口没有任务栏入口，
                    // 真的最小化就找不回来了，直接还原并给出提示
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            setExtendedState(JFrame.NORMAL);
                            statusLabel.setText("系统托盘不可用，窗口无法最小化");
                        }
                    });
                }
            }
        });

        installTrayIcon();
        refreshDevices();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setBackground(Theme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.X_AXIS));

        JComponent mark = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 0, 28, 28, 9, 9);
                g2.setColor(Color.WHITE);
                g2.fillOval(10, 10, 8, 8);
                g2.dispose();
            }
        };
        mark.setOpaque(false);
        mark.setPreferredSize(new Dimension(28, 28));
        mark.setMinimumSize(new Dimension(28, 28));
        mark.setMaximumSize(new Dimension(28, 28));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = Theme.label("CAMA", Theme.TITLE, Theme.TEXT);
        JLabel subtitle = Theme.label("摄像头录像", Theme.BASE.deriveFont(11f), Theme.MUTED);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titles.add(title);
        titles.add(Box.createVerticalStrut(1));
        titles.add(subtitle);
        titles.setAlignmentY(Component.CENTER_ALIGNMENT);

        brand.add(mark);
        brand.add(Box.createHorizontalStrut(11));
        brand.add(titles);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        controls.add(Theme.label("摄像头", Theme.BASE, Theme.MUTED));
        controls.add(deviceCombo);
        controls.add(refreshButton);
        controls.add(Box.createHorizontalStrut(2));
        controls.add(hideButton);

        header.add(brand, BorderLayout.WEST);
        header.add(controls, BorderLayout.EAST);
        return header;
    }

    private JPanel createCenter() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Theme.BG);
        center.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        center.add(preview, BorderLayout.CENTER);
        return center;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 9));
        footer.setBackground(Theme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(13, 20, 11, 20)));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        row.setOpaque(false);
        row.add(recordButton);
        row.add(Box.createHorizontalStrut(8));
        row.add(Theme.divider());
        row.add(Box.createHorizontalStrut(3));
        row.add(Theme.label("输出分辨率", Theme.BASE, Theme.MUTED));
        row.add(resolutionCombo);
        row.add(Theme.label("帧率", Theme.BASE, Theme.MUTED));
        row.add(fpsCombo);
        row.add(Theme.divider());
        row.add(Theme.label("麦克风", Theme.BASE, Theme.MUTED));
        row.add(micCombo);
        row.add(Theme.divider());
        row.add(folderButton);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.add(statusLabel, BorderLayout.WEST);
        statusRow.add(infoLabel, BorderLayout.EAST);

        footer.add(row, BorderLayout.CENTER);
        footer.add(statusRow, BorderLayout.SOUTH);
        return footer;
    }

    private void refreshDevices() {
        if (recordingActive || finalizing) {
            return;
        }
        updatingUi = true;
        try {
            refreshAudioDevices();
            stopCapture();
            if (webcam != null) {
                webcam.close();
                webcam = null;
            }
            preview.setImage(null);
            preview.setSourceLabel("");
            webcams = Webcam.getWebcams();
            deviceCombo.removeAllItems();
            for (Webcam cam : webcams) {
                deviceCombo.addItem(cam.getName());
            }
            if (webcams.isEmpty()) {
                recordButton.setEnabled(false);
                statusLabel.setText("未检测到摄像头，请检查设备连接与 Windows 隐私设置");
                updateInfo();
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

    /** 重新枚举麦克风。没有 ffmpeg 时音频不可用，只能录制视频。 */
    private void refreshAudioDevices() {
        String previous = (String) micCombo.getSelectedItem();
        micDevices.clear();
        micCombo.removeAllItems();
        micCombo.addItem("不录制音频");
        micCombo.addItem("默认麦克风");
        try {
            for (AudioRecorder.Device device : AudioRecorder.listDevices()) {
                micDevices.add(device);
                micCombo.addItem(device.getName());
            }
        } catch (Exception ignored) {
            // 没有可用的录音设备时保底只提供「默认麦克风」
        }
        boolean ffmpegReady = FfmpegMuxer.isAvailable();
        micCombo.setEnabled(ffmpegReady);
        if (!ffmpegReady) {
            micCombo.setSelectedIndex(0);
            return;
        }
        if (previous == null || !selectComboItem(previous)) {
            micCombo.setSelectedIndex(1);
        }
    }

    private boolean selectComboItem(String value) {
        for (int i = 0; i < micCombo.getItemCount(); i++) {
            if (value.equals(micCombo.getItemAt(i))) {
                micCombo.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    /** 当前选中的麦克风；返回 null 表示不录音。 */
    private AudioRecorder.Device selectedMicDevice() {
        int index = micCombo.getSelectedIndex();
        if (index == 1) {
            return AudioRecorder.defaultDevice();
        }
        if (index >= 2) {
            int deviceIndex = index - 2;
            if (deviceIndex < micDevices.size()) {
                return micDevices.get(deviceIndex);
            }
        }
        return null;
    }

    /** 创建托盘图标和应用图标：深色圆角方块 + 白色圆点，与界面品牌一致。 */
    private static Image createAppIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.ACCENT);
        int arc = Math.round(size * 0.32f);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        int dot = Math.round(size * 0.30f);
        int offset = (size - dot) / 2;
        g.fillOval(offset, offset, dot, dot);
        g.dispose();
        return image;
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) {
            trayAvailable = false;
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem showItem = new MenuItem("显示主界面");
        showItem.addActionListener(e -> restoreFromTray());
        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }));
        menu.add(showItem);
        menu.addSeparator();
        menu.add(quitItem);

        TrayIcon icon = new TrayIcon(createAppIcon(32), "CAMA 摄像头录像", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> restoreFromTray());
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
            trayAvailable = true;
        } catch (AWTException ex) {
            trayAvailable = false;
            trayIcon = null;
        }
    }

    /** 最小化按钮：优先收进托盘，没有托盘时退回系统最小化。 */
    private void minimizeToTray() {
        if (!trayAvailable) {
            if (utilityWindow) {
                statusLabel.setText("系统托盘不可用，无法隐藏窗口");
                return;
            }
            setExtendedState(JFrame.ICONIFIED);
            return;
        }
        hideToTray();
    }

    private void hideToTray() {
        if (!isVisible()) {
            return;
        }
        Webcam cameraToClose = null;
        if (!recordingActive) {
            // 没在录像时释放摄像头，避免隐藏后摄像头指示灯常亮
            stopCapture();
            cameraToClose = webcam;
            webcam = null;
            recordButton.setEnabled(false);
        }
        setVisible(false);
        if (!trayHintShown) {
            trayHintShown = true;
            trayIcon.displayMessage("CAMA", "已最小化到托盘，双击图标可恢复。",
                    TrayIcon.MessageType.NONE);
        }
        if (cameraToClose != null) {
            closeWebcamAsync(cameraToClose);
        }
    }

    /**
     * 关闭摄像头可能要在驱动层等待，放在后台线程执行，避免冻结 EDT。
     * 即便如此它也不会永久阻塞，除非库内部已经被 interrupt 破坏。
     */
    private static void closeWebcamAsync(final Webcam cam) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cam.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响主流程
                }
            }
        }, "cama-camera-close");
        thread.setDaemon(true);
        thread.start();
    }

    private void restoreFromTray() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isVisible()) {
                    setVisible(true);
                    // 从标题栏最小化进来的窗口仍带 ICONIFIED 状态，
                    // 显示之后再清掉，否则恢复出来仍然是缩小的
                    setExtendedState(JFrame.NORMAL);
                }
                toFront();
                requestFocus();
                if (webcam == null || !webcam.isOpen()) {
                    openCamera(deviceCombo.getSelectedIndex());
                } else if (!capturing) {
                    startCapture();
                }
            }
        });
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
            preview.setSourceLabel("");
            preview.setImage(null);
            updateInfo();
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
            preview.setSourceLabel("");
            preview.setImage(null);
            updateInfo();
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
        preview.setSourceLabel(cam.getName());
        updateInfo();
        statusLabel.setText("已就绪  |  " + cam.getName()
                + (FfmpegMuxer.isAvailable() ? "" : "  ·  未找到 ffmpeg，本次只能录制视频"));
        recordButton.setEnabled(!finalizing);
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
            // 这里绝对不能 interrupt：采集线程可能正阻塞在 Webcam.getImage() 里，
            // 而 sarxos webcam-capture 的 WebcamProcessor 在等任务结果时被打断后，
            // 那个任务的结果就再也没人取走，处理线程会永久卡在 outbound 队列上，
            // 之后所有摄像头操作（close/open/getImage）都会永久阻塞。
            // getImage() 会自己超时返回，等待它自然退出即可。
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

    private void updateInfo() {
        Dimension out = selectedResolution();
        StringBuilder text = new StringBuilder();
        if (webcam != null) {
            Dimension src = webcam.getViewSize();
            if (src != null) {
                text.append("采集 ").append(src.width).append("×").append(src.height)
                        .append("    ·    ");
            }
        }
        text.append("输出 ").append(out.width).append("×").append(out.height)
                .append(" @ ").append(selectedFps()).append(" fps");
        infoLabel.setText(text.toString());
        preview.setOutputLabel("输出 " + out.width + "×" + out.height
                + " · " + selectedFps() + " fps");
    }

    private void toggleRecording() {
        if (recordingActive) {
            stopRecording();
        } else if (!finalizing) {
            startRecording();
        }
    }

    private void startRecording() {
        if (recordingActive || finalizing) {
            return;
        }
        if (webcam == null || !webcam.isOpen()) {
            JOptionPane.showMessageDialog(this, "摄像头未就绪，无法录像。",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Dimension res = selectedResolution();
        scaler = new FrameScaler(res.width, res.height);

        String baseName = "CAMA_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File output = new File(recordingsDir, baseName + ".mp4");
        File tmpDir = new File(recordingsDir, ".tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            JOptionPane.showMessageDialog(this, "无法创建临时目录: " + tmpDir.getAbsolutePath(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        cleanTempDir(tmpDir);
        File videoTmp = new File(tmpDir, baseName + ".video.mp4");
        File audioTmp = new File(tmpDir, baseName + ".audio.wav");

        // 音频先于视频开始采集，停止时再把多出来的开头裁掉，避免音画不同步
        AudioRecorder.Device mic = selectedMicDevice();
        boolean audioStarted = false;
        String notice = null;
        if (mic != null) {
            if (FfmpegMuxer.isAvailable()) {
                try {
                    audioRecorder.start(audioTmp, mic);
                    audioStarted = true;
                } catch (Exception ex) {
                    notice = "麦克风不可用，本次仅录制视频";
                }
            } else {
                notice = "未找到 ffmpeg，本次仅录制视频";
            }
        }

        try {
            recorder.start(videoTmp, selectedFps());
        } catch (IOException ex) {
            if (audioStarted) {
                audioRecorder.stop();
                audioTmp.delete();
            }
            JOptionPane.showMessageDialog(this, "创建录像文件失败: " + ex.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        pendingOutputFile = output;
        pendingVideoFile = videoTmp;
        pendingAudioFile = audioStarted ? audioTmp : null;
        pendingAudioLeadMillis = audioStarted
                ? Math.max(0L, recorder.getStartTimeMillis() - audioRecorder.getStartedAtMillis())
                : 0L;
        recordingActive = true;
        recordError = false;

        recordButton.setText("■ 停止录像");
        recordButton.setStyle(new Color(0x2A1A1F), new Color(0x3B2229), new Color(0x3B2229),
                Theme.ACCENT, new Color(0x8A3442));
        recordButton.revalidate();
        deviceCombo.setEnabled(false);
        resolutionCombo.setEnabled(false);
        fpsCombo.setEnabled(false);
        micCombo.setEnabled(false);
        refreshButton.setEnabled(false);
        statusLabel.setText("录像中  →  " + output.getName()
                + (notice == null ? "" : "  ·  " + notice));
        infoLabel.setForeground(Theme.ACCENT);
        preview.setRecording(true);
        preview.setElapsedSeconds(0);
        startEncoderThread();
        startRecTimer();
    }

    private void stopRecording() {
        if (!recordingActive) {
            return;
        }
        recordingActive = false;
        finalizing = true;
        stopRecTimer();
        stopEncoderThread();

        final File output = pendingOutputFile;
        final File video = pendingVideoFile;
        final File audio = pendingAudioFile;
        final long audioLead = pendingAudioLeadMillis;
        final boolean failed = recordError;

        if (audioRecorder.isRecording()) {
            audioRecorder.stop();
        }
        String audioError = audioRecorder.getError();

        try {
            recorder.stop();
        } catch (IOException ex) {
            String prefix = audioError == null || audioError.isEmpty() ? "" : audioError + "；";
            audioError = prefix + "视频收尾失败: " + ex.getMessage();
        }
        final String audioErrorFinal = audioError;

        pendingOutputFile = null;
        pendingVideoFile = null;
        pendingAudioFile = null;
        pendingAudioLeadMillis = 0L;

        finishRecordingUi();
        recordButton.setEnabled(false);
        statusLabel.setText("正在保存录像…");

        // 混流会调用外部 ffmpeg，放到后台线程，避免界面卡住
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final String message = finalizeRecording(output, video, audio,
                        audioLead, failed, audioErrorFinal);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        finalizing = false;
                        recordButton.setEnabled(webcam != null && webcam.isOpen());
                        statusLabel.setText(message);
                    }
                });
            }
        }, "cama-finalize");
        worker.setDaemon(false);
        worker.start();
    }

    private void handleRecordError() {
        if (!recordingActive) {
            return;
        }
        recordError = true;
        stopRecording();
    }

    private void finishRecordingUi() {
        stopRecTimer();
        recordButton.setText("● 开始录像");
        recordButton.setStyle(Theme.ACCENT, Theme.ACCENT_HOVER, Theme.ACCENT_PRESSED,
                Color.WHITE, null);
        recordButton.revalidate();
        deviceCombo.setEnabled(true);
        resolutionCombo.setEnabled(true);
        fpsCombo.setEnabled(true);
        micCombo.setEnabled(FfmpegMuxer.isAvailable());
        refreshButton.setEnabled(true);
        infoLabel.setForeground(Theme.FAINT);
        preview.setRecording(false);
        updateInfo();
    }

    /** 停止录制后在后台完成：有音频就混流成 AAC，没有就只清理临时文件。 */
    private String finalizeRecording(File output, File video, File audio, long audioLeadMillis,
                                     boolean failed, String audioError) {
        if (output == null || video == null || !video.isFile()) {
            return "录像数据缺失，未能保存";
        }
        boolean audioReady = audio != null && audio.isFile() && audio.length() > 44;
        StringBuilder message = new StringBuilder();
        if (audioReady && FfmpegMuxer.isAvailable()) {
            try {
                FfmpegMuxer.mux(video, audio, output, audioLeadMillis);
                video.delete();
                audio.delete();
                message.append("已保存  →  ").append(output.getAbsolutePath());
            } catch (Exception ex) {
                message.append(fallbackSave(output, video, audio))
                        .append("  ·  ").append(ex.getMessage());
            }
        } else if (audioReady) {
            File wavOut = new File(output.getParentFile(),
                    baseNameWithoutMp4(output.getName()) + ".wav");
            boolean wavSaved = moveFile(audio, wavOut);
            message.append(moveFile(video, output)
                    ? "已保存  →  " + output.getAbsolutePath()
                    : "视频保存失败");
            if (wavSaved) {
                message.append("，音频 → ").append(wavOut.getName());
            }
        } else {
            if (audio != null) {
                audio.delete();
            }
            message.append(moveFile(video, output)
                    ? "已保存  →  " + output.getAbsolutePath()
                    : "保存录像失败");
        }
        if (failed) {
            message.insert(0, "录像中断，已保存可用部分  ·  ");
        }
        if (audioError != null && !audioError.isEmpty()) {
            message.append("  ·  音频提示：").append(audioError);
        }
        return message.toString();
    }

    /** 混流失败时尽量保留视频，并把 WAV 一并保存，方便事后手动合成。 */
    private String fallbackSave(File output, File video, File audio) {
        File wavOut = new File(output.getParentFile(),
                baseNameWithoutMp4(output.getName()) + ".wav");
        boolean wavSaved = moveFile(audio, wavOut);
        boolean videoSaved = moveFile(video, output);
        StringBuilder message = new StringBuilder();
        message.append(videoSaved
                ? "音频合成失败，已保存视频 → " + output.getName()
                : "音频合成失败，视频保存失败");
        if (wavSaved) {
            message.append("，音频 → ").append(wavOut.getName());
        }
        return message.toString();
    }

    private static String baseNameWithoutMp4(String name) {
        return name.endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
    }

    private static void cleanTempDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static boolean moveFile(File from, File to) {
        if (from == null || to == null || !from.isFile()) {
            return false;
        }
        try {
            if (to.exists() && !to.delete()) {
                return false;
            }
            if (from.renameTo(to)) {
                return true;
            }
            copyFile(from, to);
            if (to.length() == from.length()) {
                from.delete();
                return true;
            }
        } catch (IOException ignored) {
            // 交给调用方报告失败
        }
        return false;
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private void startRecTimer() {
        stopRecTimer();
        recTimer = new Timer(250, e -> {
            if (recordingActive && !recorder.isRecording()) {
                // 编码器异常退出时自动收尾
                handleRecordError();
                return;
            }
            long seconds = recorder.getElapsedSeconds();
            preview.setElapsedSeconds(seconds);
            if (recorder.isRecording()) {
                infoLabel.setText(String.format("● REC %02d:%02d    ·    %s @ %d fps",
                        seconds / 60, seconds % 60,
                        selectedResolution().width + "×" + selectedResolution().height,
                        selectedFps()));
            }
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
        if (recordingActive) {
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
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
        dispose();
    }
}
