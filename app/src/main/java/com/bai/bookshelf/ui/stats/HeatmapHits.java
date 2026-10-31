package com.bai.bookshelf.ui.stats;

import android.graphics.RectF;

/**
 * 购买热力图的可点击区域（v5.6 从 PurchaseHeatmapView 抽出）：
 * 每次绘制前重置，绘制时登记日格与月份标签的矩形，触摸时做命中测试。
 * 数组按需扩容，重复绘制不产生新对象。
 */
final class HeatmapHits {

    private RectF[] rects = new RectF[64];
    private int[] days = new int[64];
    private int count;

    private final RectF[] miniRects = new RectF[12];
    private final int[] miniIds = new int[12];
    private int miniCount;

    /** 新一帧开始绘制前清空登记 */
    void reset() {
        count = 0;
        miniCount = 0;
    }

    /** 登记一个日格；day 为业务上的天编号（或 月*100+日） */
    void add(float left, float top, float right, float bottom, int day) {
        if (count >= rects.length) {
            int newLen = rects.length * 2;
            RectF[] newRects = new RectF[newLen];
            int[] newDays = new int[newLen];
            System.arraycopy(rects, 0, newRects, 0, rects.length);
            System.arraycopy(days, 0, newDays, 0, days.length);
            rects = newRects;
            days = newDays;
        }
        if (rects[count] == null) {
            rects[count] = new RectF();
        }
        rects[count].set(left, top, right, bottom);
        days[count] = day;
        count++;
    }

    /** 命中测试：返回命中格子的下标，未命中返回 -1（外扩 slop 容差，照顾手指点击） */
    int hit(float x, float y, float slop) {
        for (int i = 0; i < count; i++) {
            RectF r = rects[i];
            if (r == null) {
                continue;
            }
            if (x >= r.left - slop && x <= r.right + slop
                    && y >= r.top - slop && y <= r.bottom + slop) {
                return i;
            }
        }
        return -1;
    }

    int dayAt(int index) {
        return days[index];
    }

    /** 登记一个月份标签热区 */
    void addMini(float left, float top, float right, float bottom, int month) {
        if (miniCount >= miniRects.length) {
            return;
        }
        if (miniRects[miniCount] == null) {
            miniRects[miniCount] = new RectF();
        }
        miniRects[miniCount].set(left, top, right, bottom);
        miniIds[miniCount] = month;
        miniCount++;
    }

    /** 命中月份标签：返回下标，未命中返回 -1 */
    int miniHit(float x, float y) {
        for (int i = 0; i < miniCount; i++) {
            RectF r = miniRects[i];
            if (r != null && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                return i;
            }
        }
        return -1;
    }

    int miniIdAt(int index) {
        return miniIds[index];
    }
}
