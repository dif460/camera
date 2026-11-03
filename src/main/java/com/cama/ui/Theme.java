package com.cama.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 深色主题：集中管理配色、字体和控件样式，让整个界面保持一致的视觉语言。
 */
public final class Theme {

    private Theme() {
    }

    public static final Color BG = new Color(0x0D1015);
    public static final Color SURFACE = new Color(0x151920);
    public static final Color SURFACE_2 = new Color(0x1D232D);
    public static final Color SURFACE_3 = new Color(0x262E3B);
    public static final Color BORDER = new Color(0x2A3240);
    public static final Color TEXT = new Color(0xEBEFF5);
    public static final Color MUTED = new Color(0x8C97A8);
    public static final Color FAINT = new Color(0x5C6676);
    public static final Color ACCENT = new Color(0xFF4D5E);
    public static final Color ACCENT_HOVER = new Color(0xFF6775);
    public static final Color ACCENT_PRESSED = new Color(0xE03B4C);
    public static final Color GREEN = new Color(0x3DDC84);
    public static final Color VIDEO_BG = new Color(0x07090C);

    public static final Font BASE;
    public static final Font BOLD;
    public static final Font TITLE;
    public static final Font MONO;

    static {
        String family = pickFont("Microsoft YaHei UI", "Microsoft YaHei",
                "PingFang SC", "Noto Sans CJK SC", "Dialog");
        BASE = new Font(family, Font.PLAIN, 13);
        BOLD = new Font(family, Font.BOLD, 13);
        TITLE = new Font(family, Font.BOLD, 20);
        MONO = new Font(pickFont("Consolas", "Cascadia Mono", "Monospaced"), Font.BOLD, 13);
    }

    private static String pickFont(String... families) {
        String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (String family : families) {
            for (String name : available) {
                if (name.equalsIgnoreCase(family)) {
                    return name;
                }
            }
        }
        return families[families.length - 1];
    }

    /** 安装深色外观默认值，主要影响弹窗、下拉列表等系统绘制的部分。 */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 保持当前外观
        }
        UIManager.put("Panel.background", BG);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", BASE);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", BASE);
        UIManager.put("OptionPane.buttonFont", BOLD);
        UIManager.put("Button.font", BOLD);
        UIManager.put("ComboBox.background", SURFACE_2);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", SURFACE_3);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.disabledBackground", SURFACE);
        UIManager.put("ComboBox.disabledForeground", FAINT);
        UIManager.put("PopupMenu.background", SURFACE_2);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER));
        UIManager.put("List.background", SURFACE_2);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", SURFACE_3);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("ScrollPane.background", SURFACE_2);
        UIManager.put("ToolTip.background", SURFACE_3);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", BASE);
        UIManager.put("TextArea.font", BASE);
        UIManager.put("TextField.font", BASE);
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    /** 创建统一样式的深色下拉框。 */
    public static JComboBox<String> combo(String[] items) {
        final JComboBox<String> combo = new JComboBox<String>(items);
        combo.setUI(new DarkComboBoxUI());
        combo.setFont(BASE);
        combo.setForeground(TEXT);
        combo.setBackground(SURFACE_2);
        combo.setBorder(new EmptyBorder(0, 12, 0, 4));
        combo.setOpaque(false);
        combo.setFocusable(false);
        combo.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        combo.setRenderer(new DarkRenderer());
        return combo;
    }

    private static final class DarkRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            label.setOpaque(true);
            label.setFont(BASE);
            label.setBorder(new EmptyBorder(5, 10, 5, 10));
            if (isSelected && index >= 0) {
                label.setBackground(SURFACE_3);
                label.setForeground(TEXT);
            } else {
                label.setBackground(SURFACE_2);
                label.setForeground(TEXT);
            }
            return label;
        }
    }

    private static final class DarkComboBoxUI extends BasicComboBoxUI {

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = c.getWidth();
            int h = c.getHeight();
            g2.setColor(c.isEnabled() ? SURFACE_2 : SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g2.setColor(BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds,
                                                boolean hasFocus) {
            g.setColor(comboBox.isEnabled() ? SURFACE_2 : SURFACE);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected JButton createArrowButton() {
            Icon chevron = new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c.isEnabled() ? MUTED : FAINT);
                    g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.drawLine(x + 2, y + 3, x + 5, y + 6);
                    g2.drawLine(x + 5, y + 6, x + 8, y + 3);
                    g2.dispose();
                }

                @Override
                public int getIconWidth() {
                    return 10;
                }

                @Override
                public int getIconHeight() {
                    return 10;
                }
            };
            JButton button = new JButton(chevron);
            button.setBorder(new EmptyBorder(0, 2, 0, 8));
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            button.setFocusable(false);
            button.setOpaque(false);
            button.setRolloverEnabled(false);
            button.setPreferredSize(new Dimension(26, 30));
            return button;
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup popup = new BasicComboPopup(comboBox) {
                private static final long serialVersionUID = 1L;

                @Override
                protected Rectangle computePopupBounds(int px, int py, int pw, int ph) {
                    Rectangle bounds = super.computePopupBounds(px, py, pw, ph);
                    bounds.width = Math.max(bounds.width, comboBox.getWidth());
                    return bounds;
                }
            };
            popup.setBorder(BorderFactory.createLineBorder(BORDER));
            return popup;
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            Dimension size = super.getPreferredSize(c);
            if (size != null && size.height < 34) {
                size.height = 34;
            }
            return size;
        }
    }

    /** 供预览面板绘制文字标签使用。 */
    public static void paintChip(Graphics2D g2, String text, int x, int y, Font font,
                                 Color background, Color foreground) {
        FontMetrics fm = g2.getFontMetrics(font);
        int padX = 11;
        int padY = 6;
        int width = fm.stringWidth(text) + padX * 2;
        int height = fm.getHeight() + padY * 2 - 3;
        g2.setColor(background);
        g2.fillRoundRect(x, y, width, height, height, height);
        g2.setColor(foreground);
        g2.setFont(font);
        g2.drawString(text, x + padX, y + padY + fm.getAscent());
    }

    /** 分隔线（用于控制条）。 */
    public static JComponent divider() {
        JComponent divider = new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(BORDER);
                g.fillRect(getWidth() / 2, 0, 1, getHeight());
            }
        };
        divider.setOpaque(false);
        divider.setPreferredSize(new Dimension(1, 22));
        return divider;
    }

    /** 下拉框字体尺寸调整后调用，保证箭头区域完整显示。 */
    public static void sizeCombo(JComboBox<String> combo, int width, int height) {
        combo.setPreferredSize(new Dimension(width, height));
        combo.setMinimumSize(new Dimension(width, height));
        combo.setMaximumSize(new Dimension(width, height));
    }

    /** 统一弹窗背景，避免深色界面里出现刺眼的白色对话框。 */
    public static JPopupMenu darkMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(SURFACE_2);
        return menu;
    }
}
