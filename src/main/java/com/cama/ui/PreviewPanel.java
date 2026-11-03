package com.cama.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 预览面板：保持宽高比缩放显示采集到的最新画面。
 */
public class PreviewPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private volatile BufferedImage image;

    public PreviewPanel() {
        setBackground(Color.BLACK);
        setOpaque(true);
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            BufferedImage img = image;
            if (img == null) {
                drawHint(g2, "未检测到画面");
                return;
            }
            int panelW = getWidth();
            int panelH = getHeight();
            double scale = Math.min(panelW / (double) img.getWidth(),
                    panelH / (double) img.getHeight());
            int w = (int) Math.round(img.getWidth() * scale);
            int h = (int) Math.round(img.getHeight() * scale);
            int x = (panelW - w) / 2;
            int y = (panelH - h) / 2;
            g2.drawImage(img, x, y, w, h, null);
        } finally {
            g2.dispose();
        }
    }

    private void drawHint(Graphics2D g2, String text) {
        g2.setColor(new Color(160, 160, 160));
        g2.setFont(getFont().deriveFont(Font.PLAIN, 16f));
        int textW = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, (getWidth() - textW) / 2, getHeight() / 2);
    }
}
