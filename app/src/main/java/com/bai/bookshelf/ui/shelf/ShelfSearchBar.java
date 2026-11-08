package com.bai.bookshelf.ui.shelf;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.Motion;
import com.bai.bookshelf.util.SearchText;

/**
 * 书架搜索框（v5.7，从 ShelfFragment 抽出）：
 * 150ms 输入防抖、清空按钮、归一化查询读取。
 */
public final class ShelfSearchBar {

    private static final int FOCUS_FADE_MS = 160;
    private static final int CLEAR_FADE_MS = 120;

    private final EditText input;
    private final View clearButton;
    private final Runnable apply;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean suppress;
    private boolean focusShown;
    private boolean focusApplied;

    public ShelfSearchBar(EditText input, View clearButton, Runnable apply) {
        this.input = input;
        this.clearButton = clearButton;
        this.apply = apply;
    }

    public void setup() {
        // 最近搜索也挂了同一个焦点监听，这里链式调用，不能直接覆盖
        View.OnFocusChangeListener previous = input.getOnFocusChangeListener();
        input.setOnFocusChangeListener((v, hasFocus) -> {
            animateFocus(hasFocus);
            if (previous != null) {
                previous.onFocusChange(v, hasFocus);
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppress) {
                    return;
                }
                handler.removeCallbacks(apply);
                handler.postDelayed(apply, 150);
            }

            @Override
            public void afterTextChanged(Editable s) {
                setClearVisible(s != null && s.length() > 0);
            }
        });
        clearButton.setOnClickListener(v -> clearNow());
    }

    public String text() {
        return input.getText().toString().trim();
    }

    public String normalized() {
        return SearchText.normalize(input.getText().toString());
    }

    public void setText(String query) {
        cancelPending();
        input.setText(query == null ? "" : query);
    }

    /** 清空并立即重筛（空结果态的「清空搜索」） */
    public void clearNow() {
        setTextSilently("");
        apply.run();
        input.requestFocus();
    }

    /** 清空但不立即重筛（调用方随后会自己 apply） */
    public void clearSilently() {
        setTextSilently("");
    }

    public void cancelPending() {
        handler.removeCallbacks(apply);
    }

    /** 聚焦描边 160ms 交叉到主色；首次监听不重播 */
    private void animateFocus(boolean focused) {
        if (focusApplied && focusShown == focused) {
            return;
        }
        focusApplied = true;
        focusShown = focused;
        int target = focused ? R.drawable.bg_search_focus : R.drawable.bg_search;
        if (!Motion.enabled(input.getContext())) {
            input.setBackgroundResource(target);
            return;
        }
        Drawable from = input.getBackground();
        Drawable to = ContextCompat.getDrawable(input.getContext(), target);
        if (from == null || to == null) {
            input.setBackgroundResource(target);
            return;
        }
        final TransitionDrawable fade = new TransitionDrawable(new Drawable[]{from, to});
        input.setBackground(fade);
        fade.startTransition(FOCUS_FADE_MS);
        input.postDelayed(() -> {
            if (input.getBackground() == fade) {
                input.setBackgroundResource(target);
            }
        }, FOCUS_FADE_MS);
    }

    /** 清空按钮：alpha + scale 120ms 出现/收起 */
    private void setClearVisible(boolean visible) {
        if (visible) {
            if (clearButton.getVisibility() == View.VISIBLE) {
                return;
            }
            clearButton.animate().cancel();
            clearButton.setVisibility(View.VISIBLE);
            if (!Motion.enabled(clearButton.getContext())) {
                clearButton.setAlpha(1f);
                clearButton.setScaleX(1f);
                clearButton.setScaleY(1f);
                return;
            }
            clearButton.setAlpha(0f);
            clearButton.setScaleX(0.8f);
            clearButton.setScaleY(0.8f);
            clearButton.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(CLEAR_FADE_MS)
                    .setInterpolator(Motion.STANDARD)
                    .start();
        } else {
            if (clearButton.getVisibility() != View.VISIBLE) {
                return;
            }
            clearButton.animate().cancel();
            if (!Motion.enabled(clearButton.getContext())) {
                clearButton.setAlpha(1f);
                clearButton.setScaleX(1f);
                clearButton.setScaleY(1f);
                clearButton.setVisibility(View.GONE);
                return;
            }
            clearButton.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f)
                    .setDuration(CLEAR_FADE_MS)
                    .setInterpolator(Motion.STANDARD)
                    .withEndAction(() -> {
                        clearButton.setVisibility(View.GONE);
                        clearButton.setAlpha(1f);
                        clearButton.setScaleX(1f);
                        clearButton.setScaleY(1f);
                    }).start();
        }
    }

    private void setTextSilently(String text) {
        suppress = true;
        input.setText(text);
        suppress = false;
    }
}
