package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ShelfIndex;
import com.bai.bookshelf.util.ThemeManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 右侧悬浮字母索引条（v5.8.16）：A–Z + #，点一下或按住拖动都跳。
 *
 * <p>没有内容的刻度画淡但仍可点（跳到下一个有内容的字母，由宿主决定），
 * 这样列表长度变化时刻度不会乱跳。
 */
public class IndexSidebarView extends View {

    public interface Listener {
        /** 选中某个字母（拖动过程中会连续回调） */
        void onLetter(String letter);

        /** 手指抬起：宿主可以收掉气泡 */
        void onDragEnd();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float textSizePx;
    private final float rowGapPx;
    private final Set<String> available = new HashSet<>();
    private int normalColor;
    private int activeColor;
    private String pressed;
    private Listener listener;

    public IndexSidebarView(Context context) {
        this(context, null);
    }

    public IndexSidebarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        textSizePx = 11 * Math.min(context.getResources().getDisplayMetrics().scaledDensity,
                1.3f * density);
        rowGapPx = 2 * density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(textSizePx);
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 刷新可用刻度与配色（宿主每次渲染结果时调一次） */
    public void setAvailable(Set<String> has) {
        available.clear();
        if (has != null) {
            available.addAll(has);
        }
        // 索引条贴在列表右侧（纸色底），所以用正文色而不是主色底上的 onPrimary
        normalColor = ThemeManager.resolveColor(getContext(), R.attr.on_surface_variant);
        activeColor = ThemeManager.resolveColor(getContext(), ThemeManager.ATTR_COLOR_PRIMARY);
        invalidate();
    }

    private float rowHeight() {
        int count = ShelfIndex.LETTERS.length;
        float usable = getHeight() - getPaddingTop() - getPaddingBottom()
                - rowGapPx * (count - 1);
        return count > 0 ? Math.max(textSizePx, usable / count) : textSizePx;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float row = rowHeight();
        float x = getWidth() / 2f;
        float y = getPaddingTop() + textSizePx * 0.85f;
        for (String letter : ShelfIndex.LETTERS) {
            boolean hit = letter.equals(pressed);
            paint.setColor(hit ? activeColor : normalColor);
            paint.setAlpha(hit ? 255 : (available.isEmpty() || available.contains(letter) ? 220 : 90));
            paint.setTypeface(hit ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(letter, x, y, paint);
            y += row + rowGapPx;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!performTouch(event)) {
                    return false;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                performTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
                performTouch(event);
                if (listener != null) {
                    listener.onDragEnd();
                }
                pressed = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) {
                    listener.onDragEnd();
                }
                pressed = null;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 把 y 换算成刻度；变了才回调，避免拖动时每帧都滚动列表 */
    private boolean performTouch(MotionEvent event) {
        int index = (int) ((event.getY() - getPaddingTop())
                / (rowHeight() + rowGapPx));
        if (index < 0) {
            index = 0;
        }
        if (index >= ShelfIndex.LETTERS.length) {
            index = ShelfIndex.LETTERS.length - 1;
        }
        String letter = ShelfIndex.LETTERS[index];
        if (!letter.equals(pressed)) {
            pressed = letter;
            invalidate();
            if (listener != null) {
                listener.onLetter(letter);
            }
        }
        return true;
    }
}
