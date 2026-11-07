package com.bai.bookshelf.ui.checkin;

import com.bai.bookshelf.util.DateKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 精确日期选择器的纯逻辑（v5.9.1 修订第三步）：已选 / 参考底纹 / 框选锚点 / 区间预览。
 *
 * <p>不碰 View 不碰库，填充与取消的规则都能离屏单测：
 * 框选模式下第一点落锚、第二点把两端之间整段选入；若那段已经全选过，
 * 再点同样两端就整段取消。普通模式下单点勾/取消。超过今天的日一律不参与。
 */
public final class DayPickerState {

    public static final int ACTION_NONE = 0;
    public static final int ACTION_TOGGLE = 1;
    public static final int ACTION_FILL = 2;
    public static final int ACTION_CANCEL = 3;

    private final TreeSet<Integer> picked = new TreeSet<>();
    private final TreeSet<Integer> reference = new TreeSet<>();
    private boolean rangeMode;
    private int anchor;
    private int previewFrom;
    private int previewTo;
    private int lastFrom;
    private int lastTo;

    public void setPicked(Collection<Integer> keys) {
        picked.clear();
        if (keys != null) {
            for (Integer k : keys) {
                if (k != null && k > 0) {
                    picked.add(k);
                }
            }
        }
    }

    public Set<Integer> picked() {
        return Collections.unmodifiableSet(picked);
    }

    /** 升序副本，落库与计数都用它 */
    public List<Integer> pickedKeys() {
        return new ArrayList<>(picked);
    }

    public boolean isPicked(int dayKey) {
        return picked.contains(dayKey);
    }

    /** 原模糊区间的底纹：只是参考，不算已选；点它一下才变已选 */
    public void setReference(Collection<Integer> keys) {
        reference.clear();
        if (keys != null) {
            for (Integer k : keys) {
                if (k != null && k > 0) {
                    reference.add(k);
                }
            }
        }
    }

    public boolean isReference(int dayKey) {
        return reference.contains(dayKey) && !picked.contains(dayKey);
    }

    /** 「连续区间」开关：关掉时锚点与预览一起清，不留半截框选 */
    public void setRangeMode(boolean on) {
        rangeMode = on;
        if (!on) {
            anchor = 0;
            previewFrom = 0;
            previewTo = 0;
        }
    }

    public boolean rangeMode() {
        return rangeMode;
    }

    public void setAnchor(int dayKey) {
        anchor = dayKey;
    }

    public int anchor() {
        return anchor;
    }

    /** 悬停预览：锚点已落、终点未定时的 outline 底 */
    public void setPreview(int fromKey, int toKey) {
        previewFrom = Math.min(fromKey, toKey);
        previewTo = Math.max(fromKey, toKey);
    }

    public void clearPreview() {
        previewFrom = 0;
        previewTo = 0;
    }

    public boolean inPreview(int dayKey) {
        return previewFrom > 0 && dayKey >= previewFrom && dayKey <= previewTo
                && !picked.contains(dayKey);
    }

    /** 「清空」：只清已选，参考底纹留着给用户对照 */
    public void clear() {
        picked.clear();
        anchor = 0;
        previewFrom = 0;
        previewTo = 0;
    }

    /**
     * 点一天，返回发生的动作（{@link #ACTION_NONE} 表示越界或只是落锚，集合没变）。
     * 区间端点用 {@link #lastFrom()} / {@link #lastTo()} 取。
     */
    public int tap(int dayKey, int todayKey) {
        lastFrom = 0;
        lastTo = 0;
        if (dayKey <= 0 || dayKey > todayKey) {
            return ACTION_NONE; // 未来 / 越界日不参与任何填充
        }
        if (rangeMode) {
            if (anchor == 0) {
                anchor = dayKey;
                clearPreview();
                return ACTION_NONE;
            }
            int from = Math.min(anchor, dayKey);
            int to = Math.max(anchor, dayKey);
            anchor = 0;
            clearPreview();
            lastFrom = from;
            lastTo = to;
            List<Integer> range = DateKeys.keyRange(from, to);
            if (picked.containsAll(range)) {
                picked.removeAll(range);
                return ACTION_CANCEL;
            }
            picked.addAll(range);
            return ACTION_FILL;
        }
        if (!picked.remove(dayKey)) {
            picked.add(dayKey);
        }
        lastFrom = dayKey;
        lastTo = dayKey;
        return ACTION_TOGGLE;
    }

    public int lastFrom() {
        return lastFrom;
    }

    public int lastTo() {
        return lastTo;
    }
}
