package com.bai.bookshelf.ui.edit;

import android.widget.EditText;

import com.bai.bookshelf.util.BookSanitizer;

/**
 * 版次 / 印次三段输入（v5.5，从 BookEditActivity 抽出）：
 * 存储格式 yyyy-mm|N；旧自由文本原文塞进年框保留。
 */
public final class EditionFields {

    private EditionFields() {
    }

    public static void fill(EditText etYear, EditText etMonth, EditText etNo, String stored) {
        if (stored != null && !stored.isEmpty()) {
            String[] parts = BookSanitizer.splitEdition(stored);
            if (parts != null) {
                etYear.setText(parts[0]);
                etMonth.setText(parts[1]);
                etNo.setText(parts[2]);
                return;
            }
            etYear.setText(stored);
        }
    }

    public static String join(EditText etYear, EditText etMonth, EditText etNo) {
        return BookSanitizer.joinEdition(
                etYear.getText().toString().trim(),
                etMonth.getText().toString().trim(),
                etNo.getText().toString().trim());
    }
}
