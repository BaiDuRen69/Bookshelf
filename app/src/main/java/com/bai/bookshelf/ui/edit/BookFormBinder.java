package com.bai.bookshelf.ui.edit;

import android.content.res.ColorStateList;
import android.view.View;

import androidx.activity.ComponentActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.util.BookFormText;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 编辑页的表单绑定（v5.6 从 BookEditActivity 抽出）：
 * 封面链接入口、出版信息折叠、book 对象回填（版次/印次三段、字数、标签、状态、评分）。
 */
public class BookFormBinder {

    private final ComponentActivity activity;
    private final ActivityBookEditBinding b;
    private final Book book;
    private final CoverEditor coverEditor;

    public BookFormBinder(ComponentActivity activity, ActivityBookEditBinding b,
                          Book book, CoverEditor coverEditor) {
        this.activity = activity;
        this.b = b;
        this.book = book;
        this.coverEditor = coverEditor;
    }

    /** v4.29：封面链接默认收起；出版信息默认折叠，减少首屏字段墙 */
    public void setupSections() {
        b.btnCoverLink.setOnClickListener(v -> {
            boolean show = b.tilCoverUrl.getVisibility() != View.VISIBLE;
            b.tilCoverUrl.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                b.etCoverUrl.requestFocus();
            }
        });
        b.tvPublishToggle.setOnClickListener(v ->
                applyPublishExpanded(b.llPublishGroup.getVisibility() != View.VISIBLE));
        applyPublishExpanded(false);
    }

    private void applyPublishExpanded(boolean expand) {
        b.llPublishGroup.setVisibility(expand ? View.VISIBLE : View.GONE);
        b.tvPublishToggle.setText(expand ? "收起出版信息" : "展开更多出版信息");
        b.tvPublishToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                expand ? R.drawable.ic_chevron_up_line : R.drawable.ic_chevron_down_line, 0);
        b.tvPublishToggle.setCompoundDrawableTintList(ColorStateList.valueOf(
                ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY)));
    }

    /** 把 book 对象的值填进表单（老数据的 TEXT 列可能是 NULL，一律经 nz 归一化防显示成 "null"） */
    public void bindFields() {
        b.etTitle.setText(nz(book.title));
        b.etIsbn.setText(nz(book.isbn));
        b.etAuthor.setText(nz(book.author));
        b.etTranslator.setText(nz(book.translator));
        b.etCountry.setText(nz(book.country));
        // 分类：tag 存真实分类名（保存/脏检查用），文本框显示「一级-二级」格式
        b.etCategory.setText(nz(book.category));
        b.etCategory.setTag(nz(book.category));
        b.etPublisher.setText(nz(book.publisher));
        // 版次/印次：存储格式 yyyy-mm|N，拆进三段输入框；旧格式原文放年框保留
        EditionFields.fill(b.etPubYear, b.etPubMonth, b.etPubEdition, book.publishDate);
        EditionFields.fill(b.etPrintYear, b.etPrintMonth, b.etPrintNo, book.printing);
        if (book.pages > 0) {
            b.etPages.setText(String.valueOf(book.pages));
        }
        if (book.listPrice > 0) {
            b.etListPrice.setText(String.valueOf(book.listPrice));
        }
        // 字数统一存「X万字」，编辑时剥掉单位
        b.etWordCount.setText(BookFormText.stripWanSuffix(book.wordCount));
        b.etDouban.setText(nz(book.doubanRating));
        // 出版信息属于低频字段，默认保持折叠；顶部「出版」分区可一键展开定位
        // 标签存储为逗号分隔，展示为「, 」分隔
        b.etTags.setText(nz(book.tags).replace(",", ", "));
        b.etSeriesName.setText(nz(book.seriesName));
        b.etSeriesVolume.setText(nz(book.seriesVolume));
        if (book.seriesOrder > 0) {
            b.etSeriesOrder.setText(String.valueOf(book.seriesOrder));
        }
        b.etCoverUrl.setText(nz(book.coverUrl));
        b.etPurchaseDate.setText(nz(book.purchaseDate));
        if (book.purchasePrice > 0) {
            b.etPurchasePrice.setText(String.valueOf(book.purchasePrice));
        }
        b.etQuantity.setText(String.valueOf(book.quantity <= 0 ? 1 : book.quantity));
        b.etNote.setText(nz(book.note));
        b.spStock.setSelection(book.stockStatus);
        b.spOwn.setSelection(book.ownType);
        coverEditor.load();
    }

    /** null 安全：Room 的 String 字段在库里是可空列，老数据可能为 NULL，统一按空串处理 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
