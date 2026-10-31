package com.bai.bookshelf.ui.shelf;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import com.bai.bookshelf.util.SearchText;

/**
 * 书架搜索框（v5.7，从 ShelfFragment 抽出）：
 * 150ms 输入防抖、清空按钮、归一化查询读取。
 */
public final class ShelfSearchBar {

    private final EditText input;
    private final View clearButton;
    private final Runnable apply;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean suppress;

    public ShelfSearchBar(EditText input, View clearButton, Runnable apply) {
        this.input = input;
        this.clearButton = clearButton;
        this.apply = apply;
    }

    public void setup() {
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
                clearButton.setVisibility(
                        s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
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

    private void setTextSilently(String text) {
        suppress = true;
        input.setText(text);
        suppress = false;
    }
}
