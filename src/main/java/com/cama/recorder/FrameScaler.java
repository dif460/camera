package com.cama.recorder;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;

/**
 * 把摄像头原始帧缩放到固定输出分辨率。
 *
 * 摄像头驱动返回的帧通常是 TYPE_CUSTOM 的字节栅格，直接用 Graphics2D 绘制
 * 会触发逐像素颜色转换（720p 约 30ms/帧）。这里对常见的 8 位分量栅格走
 * 直接字节采样 + 4 点平均的快路径（约 2-4ms/帧），其他情况回退到 Graphics2D。
 */
public class FrameScaler {

    private final int width;
    private final int height;

    private BufferedImage[] buffers = new BufferedImage[2];
    private byte[][] bufferBytes = new byte[2][];
    private int bufferIndex;

    private int sourceWidth = -1;
    private int sourceHeight = -1;
    private ComponentSampleModel sampleModel;
    private int[] bandOffsets;
    private boolean fastPath;

    public FrameScaler(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("无效的输出分辨率: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
    }

    public Dimension getOutputSize() {
        return new Dimension(width, height);
    }

    public synchronized BufferedImage scale(BufferedImage source) {
        inspectSource(source);
        bufferIndex = 1 - bufferIndex;
        BufferedImage target = buffers[bufferIndex];
        if (fastPath) {
            scaleFast(source, target, bufferBytes[bufferIndex]);
        } else {
            scaleWithGraphics(source, target);
        }
        return target;
    }

    private void inspectSource(BufferedImage source) {
        if (source == null) {
            return;
        }
        if (source.getWidth() == sourceWidth && source.getHeight() == sourceHeight
                && sampleModel != null
                && sampleModel == source.getRaster().getSampleModel()) {
            return;
        }
        sourceWidth = source.getWidth();
        sourceHeight = source.getHeight();
        fastPath = false;
        sampleModel = null;
        if (!(source.getRaster().getSampleModel() instanceof ComponentSampleModel)) {
            return;
        }
        if (!(source.getRaster().getDataBuffer() instanceof DataBufferByte)) {
            return;
        }
        ComponentSampleModel csm = (ComponentSampleModel) source.getRaster().getSampleModel();
        int[] offsets = csm.getBandOffsets();
        if (offsets == null || offsets.length != 3) {
            return;
        }
        if (csm.getPixelStride() < 3) {
            return;
        }
        sampleModel = csm;
        bandOffsets = offsets.clone();
        fastPath = true;

        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] == null) {
                buffers[i] = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
                bufferBytes[i] = ((DataBufferByte) buffers[i].getRaster().getDataBuffer()).getData();
            }
        }
    }

    private void scaleFast(BufferedImage source, BufferedImage target, byte[] out) {
        byte[] in = ((DataBufferByte) source.getRaster().getDataBuffer()).getData();
        int srcW = sourceWidth;
        int srcH = sourceHeight;
        int pixelStride = sampleModel.getPixelStride();
        int scanlineStride = sampleModel.getScanlineStride();
        int base = sampleModel.getOffset(0, 0);
        int rOff = bandOffsets[0];
        int gOff = bandOffsets[1];
        int bOff = bandOffsets[2];

        if (srcW == width && srcH == height) {
            int idx = 0;
            for (int y = 0; y < height; y++) {
                int line = base + y * scanlineStride;
                for (int x = 0; x < width; x++) {
                    int p = line + x * pixelStride;
                    out[idx++] = in[p + bOff];
                    out[idx++] = in[p + gOff];
                    out[idx++] = in[p + rOff];
                }
            }
            return;
        }

        int idx = 0;
        for (int y = 0; y < height; y++) {
            int sy0 = y * srcH / height;
            int sy1 = Math.min(srcH - 1, (y + 1) * srcH / height - 1);
            if (sy1 < sy0) {
                sy1 = sy0;
            }
            int line0 = base + sy0 * scanlineStride;
            int line1 = base + sy1 * scanlineStride;
            for (int x = 0; x < width; x++) {
                int sx0 = x * srcW / width;
                int sx1 = Math.min(srcW - 1, (x + 1) * srcW / width - 1);
                if (sx1 < sx0) {
                    sx1 = sx0;
                }
                int p00 = line0 + sx0 * pixelStride;
                int p01 = line0 + sx1 * pixelStride;
                int p10 = line1 + sx0 * pixelStride;
                int p11 = line1 + sx1 * pixelStride;

                int r = ((in[p00 + rOff] & 0xFF) + (in[p01 + rOff] & 0xFF)
                        + (in[p10 + rOff] & 0xFF) + (in[p11 + rOff] & 0xFF)) >> 2;
                int g = ((in[p00 + gOff] & 0xFF) + (in[p01 + gOff] & 0xFF)
                        + (in[p10 + gOff] & 0xFF) + (in[p11 + gOff] & 0xFF)) >> 2;
                int b = ((in[p00 + bOff] & 0xFF) + (in[p01 + bOff] & 0xFF)
                        + (in[p10 + bOff] & 0xFF) + (in[p11 + bOff] & 0xFF)) >> 2;

                out[idx++] = (byte) b;
                out[idx++] = (byte) g;
                out[idx++] = (byte) r;
            }
        }
    }

    private void scaleWithGraphics(BufferedImage source, BufferedImage target) {
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
    }
}
