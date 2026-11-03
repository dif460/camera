package com.cama.ui;

import javax.swing.JButton;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * 圆角按钮：支持实心/描边两种风格，以及录像状态下的样式切换。
 */
public class PillButton extends JButton {

    private static final long serialVersionUID = 1L;

    private Color base;
    private Color hover;
    private Color pressed;
    private Color textColor;
    private Color borderColor;
    private int buttonHeight = 34;
    private int arc = 10;

    public PillButton(String text, Color base, Color hover, Color pressed,
                      Color textColor, Color borderColor) {
        super(text);
        this.base = base;
        this.hover = hover;
        this.pressed = pressed;
        this.textColor = textColor;
        this.borderColor = borderColor;
        setFont(Theme.BASE.deriveFont(Font.BOLD));
        setBorder(new EmptyBorder(0, 18, 0, 18));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** 主操作按钮：红色实心。 */
    public static PillButton accent(String text) {
        return new PillButton(text, Theme.ACCENT, Theme.ACCENT_HOVER, Theme.ACCENT_PRESSED,
                Color.WHITE, null);
    }

    /** 次要操作按钮：深色描边。 */
    public static PillButton ghost(String text) {
        return new PillButton(text, Theme.SURFACE_2, Theme.SURFACE_3, Theme.SURFACE_3,
                Theme.TEXT, Theme.BORDER);
    }

    public void setStyle(Color base, Color hover, Color pressed, Color textColor,
                         Color borderColor) {
        this.base = base;
        this.hover = hover;
        this.pressed = pressed;
        this.textColor = textColor;
        this.borderColor = borderColor;
        repaint();
    }

    public void setButtonHeight(int height) {
        this.buttonHeight = height;
        revalidate();
    }

    public void setArc(int arc) {
        this.arc = arc;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        Insets insets = getInsets();
        return new Dimension(fm.stringWidth(getText()) + insets.left + insets.right,
                Math.max(buttonHeight, fm.getHeight() + 12));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        boolean enabled = isEnabled();
        Color fill;
        Color border;
        Color fg;
        if (!enabled) {
            fill = Theme.SURFACE;
            border = Theme.BORDER;
            fg = Theme.FAINT;
        } else if (getModel().isPressed()) {
            fill = pressed;
            border = borderColor;
            fg = textColor;
        } else if (getModel().isRollover()) {
            fill = hover;
            border = borderColor == null ? null : hover;
            fg = textColor;
        } else {
            fill = base;
            border = borderColor;
            fg = textColor;
        }

        int w = getWidth();
        int h = getHeight();
        int offset = enabled && getModel().isPressed() ? 1 : 0;
        g2.setColor(fill);
        g2.fillRoundRect(0, offset, w - 1, h - 1 - offset, arc, arc);
        if (border != null) {
            g2.setColor(border);
            g2.drawRoundRect(0, offset, w - 1, h - 1 - offset, arc, arc);
        }

        FontMetrics fm = g2.getFontMetrics(getFont());
        int textX = (w - fm.stringWidth(getText())) / 2;
        int textY = (h - fm.getHeight()) / 2 + fm.getAscent() + offset;
        g2.setFont(getFont());
        g2.setColor(fg);
        g2.drawString(getText(), textX, textY);
        g2.dispose();
    }
}
