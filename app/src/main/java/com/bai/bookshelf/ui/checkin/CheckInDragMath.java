package com.bai.bookshelf.ui.checkin;

/**
 * 折叠与翻月的纯计算：拖动进度、松手落点、提交判定、收尾时长。
 * 手势层只调这几个函数，不把夹取与阈值散进回调。
 */
final class CheckInDragMath {

    private CheckInDragMath() {
    }

    /**
     * dy 向上为负：上滑把进度推向 1（单周），下滑推向 0（整月）。
     * travelPx 至少 1，越界一律夹到 [0,1]，别让回弹反向拉伸高度。
     */
    static float progress(float startP, float dyPx, int travelPx) {
        float span = Math.max(1, travelPx);
        return clamp01(startP + (-dyPx) / span);
    }

    /** 快甩看速度方向（px/s，向上为负），慢拖过半看进度 */
    static boolean settleToWeek(float p, float vyPxPerSec, float flingPxPerSec) {
        if (Math.abs(vyPxPerSec) > flingPxPerSec) {
            return vyPxPerSec < 0f;
        }
        return p >= 0.5f;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * 横向翻月的提交判定：拖过 {@code commitFraction} 页宽，或抬手速度超过
     * {@code commitVelocityDp}。位移够看位移方向，纯靠速度的快甩看速度方向
     * （回拨过界时两者可能相反）。
     *
     * @return +1 = 下一个月，-1 = 上一个月，0 = 不够，回弹
     */
    static int commitDelta(float xPx, float vxPxPerSec, float widthPx, float density,
                           float commitFraction, float commitVelocityDp) {
        if (widthPx <= 0f) {
            return 0;
        }
        boolean distHit = Math.abs(xPx) > widthPx * commitFraction;
        boolean velHit = Math.abs(vxPxPerSec) > commitVelocityDp * density;
        if (!distHit && !velHit) {
            return 0;
        }
        return (distHit ? xPx : vxPxPerSec) < 0f ? 1 : -1;
    }

    /**
     * 提交动画的时长：整段 {@code fullMs} 按剩余距离占页宽的比例缩放，下限 {@code minMs}
     * ——已经快到位的拖动不该再等一整段。宽度非法（未测量）时给整段。
     */
    static long settleMs(float xPx, float targetPx, float widthPx, long fullMs, long minMs) {
        if (widthPx <= 0f) {
            return fullMs;
        }
        return Math.max(minMs, Math.round(fullMs * Math.abs(targetPx - xPx) / widthPx));
    }

    /**
     * 纵向折叠收尾时长：进度 0..1 本身就是一段距离，按还剩多少给时长，下限 {@code minMs}。
     * 手指停在中间时往哪边落就补剩下的那一截，别每次都跑满 220ms。
     */
    static long collapseMs(float p, boolean toWeek, long fullMs, long minMs) {
        return Math.max(minMs, Math.round(fullMs * Math.abs((toWeek ? 1f : 0f) - p)));
    }
}
