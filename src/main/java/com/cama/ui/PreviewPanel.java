package com.cama.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * 预览面板：保持宽高比缩放显示采集画面，并在画面之上绘制录像状态、
 * 设备名称和输出参数等叠加信息。
 */
public class PreviewPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int CARD_RADIUS = 16;

    private volatile BufferedImage image;
    private volatile boolean recording;
    private volatile long elapsedSeconds;
    private volatile String sourceLabel = "";
    private volatile String outputLabel = "";

    private Timer pulseTimer;
    private long pulseStart;

    public PreviewPanel() {
        setBackground(Theme.BG);
        setOpaque(false);
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
        if (recording) {
            pulseStart = System.currentTimeMillis();
            if (pulseTimer == null) {
                pulseTimer = new Timer(50, e -> repaint());
            }
            pulseTimer.start();
        } else {
            elapsedSeconds = 0;
            if (pulseTimer != null) {
                pulseTimer.stop();
            }
        }
        repaint();
    }

    public void setElapsedSeconds(long seconds) {
        this.elapsedSeconds = seconds;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel == null ? "" : sourceLabel;
        repaint();
    }

    public void setOutputLabel(String outputLabel) {
        this.outputLabel = outputLabel == null ? "" : outputLabel;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int panelW = getWidth();
            int panelH = getHeight();
            if (panelW <= 0 || panelH <= 0) {
                return;
            }

            // 外观是圆角深色卡片，画面不足的区域自然留黑
            g2.setColor(Theme.VIDEO_BG);
            g2.fillRoundRect(0, 0, panelW - 1, panelH - 1, CARD_RADIUS, CARD_RADIUS);
            g2.setColor(new Color(0x232B38));
            g2.drawRoundRect(0, 0, panelW - 1, panelH - 1, CARD_RADIUS, CARD_RADIUS);

            BufferedImage img = image;
            if (img == null) {
                drawEmptyState(g2, panelW, panelH);
                return;
            }

            double scale = Math.min(panelW / (double) img.getWidth(),
                    panelH / (double) img.getHeight());
            int w = (int) Math.round(img.getWidth() * scale);
            int h = (int) Math.round(img.getHeight() * scale);
            int x = (panelW - w) / 2;
            int y = (panelH - h) / 2;

            RoundRectangle2D clip = new RoundRectangle2D.Float(x, y, w, h, 12, 12);
            g2.clip(clip);
            g2.drawImage(img, x, y, w, h, null);
            g2.setClip(null);

            float pulse = recording ? (float) ((Math.sin((System.currentTimeMillis() - pulseStart)
                    / 260.0) + 1) / 2) : 0f;

            if (recording) {
                g2.setColor(new Color(255, 77, 94, 110 + (int) (pulse * 90)));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(clip);
            }

            drawOverlays(g2, x, y, w, h, panelW, pulse);
        } finally {
            g2.dispose();
        }
    }

    private void drawOverlays(Graphics2D g2, int x, int y, int w, int h, int panelW,
                              float pulse) {
        int margin = 14;

        if (!sourceLabel.isEmpty()) {
            Theme.paintChip(g2, sourceLabel, x + margin, y + margin, Theme.BASE,
                    new Color(8, 10, 14, 165), new Color(0xD3DAE6));
        }

        if (recording) {
            drawRecordingChip(g2, x + w - margin, y + margin, pulse);
        }

        if (!outputLabel.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics(Theme.BASE);
            int chipW = fm.stringWidth(outputLabel) + 22;
            Theme.paintChip(g2, outputLabel, x + w - margin - chipW, y + h - margin
                    - (fm.getHeight() + 9), Theme.BASE,
                    new Color(8, 10, 14, 165), new Color(0xB8C1D0));
        }
    }

    private void drawRecordingChip(Graphics2D g2, int right, int top, float pulse) {
        String time = String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60);
        String text = "REC  " + time;
        Font font = Theme.MONO.deriveFont(Font.BOLD, 13f);
        FontMetrics fm = g2.getFontMetrics(font);

        int padLeft = 13;
        int padRight = 13;
        int dot = 8;
        int gap = 7;
        int chipW = padLeft + dot + gap + fm.stringWidth(text) + padRight;
        int chipH = fm.getHeight() + 12;
        int left = right - chipW;

        g2.setColor(new Color(255, 77, 94, 218));
        g2.fillRoundRect(left, top, chipW, chipH, chipH, chipH);

        int dotAlpha = 150 + (int) (pulse * 105);
        g2.setColor(new Color(255, 255, 255, Math.min(255, dotAlpha)));
        g2.fillOval(left + padLeft, top + (chipH - dot) / 2, dot, dot);

        g2.setFont(font);
        g2.setColor(Color.WHITE);
        g2.drawString(text, left + padLeft + dot + gap,
                top + (chipH - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void drawEmptyState(Graphics2D g2, int panelW, int panelH) {
        int cx = panelW / 2;
        int cy = panelH / 2 - 16;

        Color iconColor = new Color(0x39424F);
        g2.setColor(iconColor);
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int bodyW = 86;
        int bodyH = 58;
        int bodyX = cx - bodyW / 2;
        int bodyY = cy - bodyH / 2;
        g2.drawRoundRect(bodyX, bodyY, bodyW, bodyH, 12, 12);
        g2.drawRoundRect(cx - 12, bodyY - 10, 24, 12, 6, 6);
        g2.drawOval(cx - 16, cy - 16, 32, 32);
        g2.setColor(new Color(0x2B3340));
        g2.fillOval(cx - 9, cy - 9, 18, 18);

        String title = "未检测到画面";
        String hint = "请检查摄像头连接，或 Windows「设置 → 隐私 → 相机」是否允许访问";
        Font titleFont = Theme.BASE.deriveFont(Font.BOLD, 16f);
        FontMetrics tfm = g2.getFontMetrics(titleFont);
        g2.setFont(titleFont);
        g2.setColor(Theme.MUTED);
        g2.drawString(title, cx - tfm.stringWidth(title) / 2, cy + 62);

        FontMetrics hfm = g2.getFontMetrics(Theme.BASE);
        g2.setFont(Theme.BASE);
        g2.setColor(Theme.FAINT);
        g2.drawString(hint, cx - hfm.stringWidth(hint) / 2, cy + 88);
    }
}
