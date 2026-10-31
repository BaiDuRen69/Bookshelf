package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 封面主色的纯 Java 计算（v5.8.14）：不依赖 Bitmap，可在 JVM 单测里跑 */
public class CoverColorTest {

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Test
    public void 主色取面积最大的彩色而不是白底() {
        int[] pixels = new int[120];
        for (int i = 0; i < 100; i++) {
            pixels[i] = argb(200, 40, 40); // 红书名
        }
        for (int i = 100; i < 120; i++) {
            pixels[i] = argb(255, 255, 255); // 白底
        }
        int color = CoverColor.dominant(pixels);
        assertEquals(200, (color >> 16) & 0xFF);
        assertEquals(40, (color >> 8) & 0xFF);
        assertEquals(40, color & 0xFF);
    }

    @Test
    public void 纯白或纯透明没有可用主色() {
        int[] white = new int[50];
        java.util.Arrays.fill(white, 0xFFFFFFFF);
        assertEquals(0, CoverColor.dominant(white));

        int[] transparent = new int[50];
        java.util.Arrays.fill(transparent, 0x00FF0000);
        assertEquals(0, CoverColor.dominant(transparent));
    }

    @Test
    public void 灰阶封面保留中性色() {
        int[] gray = new int[30];
        java.util.Arrays.fill(gray, argb(128, 128, 128));
        int color = CoverColor.dominant(gray);
        assertEquals(128, (color >> 16) & 0xFF);
    }

    @Test
    public void 浅色纸面会压暗_夜读会提亮() {
        int light = CoverColor.adjust(0xFFFFFFFF, false);
        int lightLuma = ((light >> 16) & 0xFF) * 21
                + ((light >> 8) & 0xFF) * 72 + (light & 0xFF) * 7;
        assertTrue("浅色主色应压暗：" + Integer.toHexString(light), lightLuma < 120 * 100);

        int night = CoverColor.adjust(0xFF101010, true);
        int nightLuma = ((night >> 16) & 0xFF) * 21
                + ((night >> 8) & 0xFF) * 72 + (night & 0xFF) * 7;
        assertTrue("夜读主色应提亮：" + Integer.toHexString(night), nightLuma > 120 * 100);
    }
}
