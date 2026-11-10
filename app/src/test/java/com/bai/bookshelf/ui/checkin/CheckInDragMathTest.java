package com.bai.bookshelf.ui.checkin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 打卡页手势的纯计算：纵向折叠的进度与落点、横向翻月的提交与时长（都不碰 View） */
public class CheckInDragMathTest {

    private static final int TRAVEL = 900; // 整月与单周的高度差，px

    @Test
    public void 整月起点上滑半程落在中间() {
        assertEquals(0.5f, CheckInDragMath.progress(0f, -TRAVEL / 2f, TRAVEL), 0.0001f);
    }

    @Test
    public void 单周起点下滑半程回到中间() {
        assertEquals(0.5f, CheckInDragMath.progress(1f, TRAVEL / 2f, TRAVEL), 0.0001f);
    }

    @Test
    public void 起点带残余进度也要接上() {
        // 动画没跑完就再次落指：从 0.3 继续上滑 1/3 程
        assertEquals(0.6333f, CheckInDragMath.progress(0.3f, -TRAVEL / 3f, TRAVEL), 0.001f);
    }

    @Test
    public void 越界一律夹到区间内() {
        assertEquals(1f, CheckInDragMath.progress(0f, -TRAVEL * 3f, TRAVEL), 0.0001f);
        assertEquals(0f, CheckInDragMath.progress(1f, TRAVEL * 3f, TRAVEL), 0.0001f);
        assertEquals(0f, CheckInDragMath.progress(0f, 120f, TRAVEL), 0.0001f);
    }

    @Test
    public void 零程旅行不当除数() {
        // 高度差算不出来（未测量）时 travelPx 兜底成 1，不能抛异常也不能给 NaN
        float p = CheckInDragMath.progress(0f, -8f, 0);
        assertEquals(1f, p, 0.0001f);
    }

    @Test
    public void 快速上甩按速度方向收周() {
        // 进度才 0.2，但甩动方向向上且过了阈值：以速度为准
        assertTrue(CheckInDragMath.settleToWeek(0.2f, -700f, 600f));
    }

    @Test
    public void 慢拖过半才收周() {
        assertTrue(CheckInDragMath.settleToWeek(0.5f, 0f, 600f));
        assertFalse(CheckInDragMath.settleToWeek(0.49f, 0f, 600f));
    }

    @Test
    public void 速度未过阈值时不看方向只看进度() {
        // 已经拖到 0.8 但只是慢速下滑：仍收周，不误判成甩动
        assertTrue(CheckInDragMath.settleToWeek(0.8f, 300f, 600f));
        assertFalse(CheckInDragMath.settleToWeek(0.2f, -300f, 600f));
    }

    @Test
    public void 快甩下滑展开整月() {
        assertFalse(CheckInDragMath.settleToWeek(0.9f, 2000f, 600f));
    }

    // ==================== 横向翻月：提交判定与时长（P3）====================

    private static final float W = 1080f;
    private static final float DENSITY = 3f;
    private static final float FRACTION = 0.25f;
    private static final float VEL_DP = 500f;
    private static final long FULL_MS = 180L;
    private static final long MIN_MS = 90L;

    @Test
    public void 恰好拖到四分之一页宽不算提交() {
        // 阈值是「拖过」，等号不成立：这点位移回弹
        assertEquals(0, CheckInDragMath.commitDelta(
                -W * FRACTION, 0f, W, DENSITY, FRACTION, VEL_DP));
        assertEquals(0, CheckInDragMath.commitDelta(
                W * FRACTION, 0f, W, DENSITY, FRACTION, VEL_DP));
    }

    @Test
    public void 刚过四分之一页宽按位移方向提交() {
        assertEquals(1, CheckInDragMath.commitDelta(
                -W * FRACTION - 1f, 0f, W, DENSITY, FRACTION, VEL_DP));
        assertEquals(-1, CheckInDragMath.commitDelta(
                W * FRACTION + 1f, 0f, W, DENSITY, FRACTION, VEL_DP));
    }

    @Test
    public void 快甩不过阈值不提交() {
        // 300px/s < 500dp/s×3 = 1500px/s，位移也没到
        assertEquals(0, CheckInDragMath.commitDelta(-100f, 300f, W, DENSITY, FRACTION, VEL_DP));
    }

    @Test
    public void 位移与速度反向时以位移为准() {
        // 回拨过界：往左拖够了但抬手瞬间往右回弹，仍提交到下一月
        assertEquals(1, CheckInDragMath.commitDelta(
                -W * FRACTION - 1f, 4000f, W, DENSITY, FRACTION, VEL_DP));
    }

    @Test
    public void 纯靠速度的快甩按速度方向() {
        // 位移没到 25%，速度 2000px/s 过了 1500px/s：看速度
        assertEquals(1, CheckInDragMath.commitDelta(-60f, -2000f, W, DENSITY, FRACTION, VEL_DP));
        assertEquals(-1, CheckInDragMath.commitDelta(60f, 2000f, W, DENSITY, FRACTION, VEL_DP));
    }

    @Test
    public void 宽度为零不提交也不给无限时长() {
        // 还没测量：既不换月，也不能拿 0 当除数
        assertEquals(0, CheckInDragMath.commitDelta(-900f, -9000f, 0f,
                DENSITY, FRACTION, VEL_DP));
        assertEquals(FULL_MS, CheckInDragMath.settleMs(-900f, -W, 0f, FULL_MS, MIN_MS));
    }

    @Test
    public void 提交动画按剩余距离缩放并有下限() {
        // 刚过阈值（还有近一整页）走满；越接近落点越快，最快不低于 MIN_MS
        assertEquals(FULL_MS, CheckInDragMath.settleMs(0f, -W, W, FULL_MS, MIN_MS));
        assertEquals(135L, CheckInDragMath.settleMs(-W * 0.25f, -W, W, FULL_MS, MIN_MS));
        assertEquals(MIN_MS, CheckInDragMath.settleMs(-W * 0.9f, -W, W, FULL_MS, MIN_MS));
        assertEquals(MIN_MS, CheckInDragMath.settleMs(-W, -W, W, FULL_MS, MIN_MS));
    }

    // ==================== 纵向折叠：收尾时长（P4）====================

    private static final long COLLAPSE_FULL_MS = 220L;
    private static final long COLLAPSE_MIN_MS = 120L;

    @Test
    public void 折叠收尾从端点起步走满整段() {
        assertEquals(COLLAPSE_FULL_MS, CheckInDragMath.collapseMs(
                0f, true, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
        assertEquals(COLLAPSE_FULL_MS, CheckInDragMath.collapseMs(
                1f, false, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
    }

    @Test
    public void 折叠收尾按还剩多少给时长() {
        // 进度本身就是距离：收起到 0.4 还差 0.6 程 → 132ms
        assertEquals(132L, CheckInDragMath.collapseMs(
                0.4f, true, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
        // 反向：展开回 0 看已经收起的那一截
        assertEquals(132L, CheckInDragMath.collapseMs(
                0.6f, false, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
    }

    @Test
    public void 折叠收尾剩下的不到下限就取下限() {
        // 0.9 收周只差 0.1 程（22ms），别闪一下
        assertEquals(COLLAPSE_MIN_MS, CheckInDragMath.collapseMs(
                0.9f, true, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
        assertEquals(COLLAPSE_MIN_MS, CheckInDragMath.collapseMs(
                0.1f, false, COLLAPSE_FULL_MS, COLLAPSE_MIN_MS));
    }
}
