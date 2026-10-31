package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.ui.excerpt.ExcerptListActivity;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页「摘抄与书评」卡（v5.3，从 BookDetailActivity 抽出）：
 * 只做入口与预览：短评 + 最新 2 条摘抄，全部内容在摘抄页。
 */
public final class DetailExcerpts {

    private DetailExcerpts() {
    }

    public static void bind(Activity activity, ActivityBookDetailBinding b,
                            Book book, List<Excerpt> excerpts) {
        List<Excerpt> list = excerpts == null ? new ArrayList<>() : excerpts;
        b.tvExcerptCount.setText(list.isEmpty() ? "还没记录" : list.size() + " 条");

        String review = book.review == null ? "" : book.review.trim();
        b.tvDetailReview.setVisibility(review.isEmpty() ? View.GONE : View.VISIBLE);
        if (!review.isEmpty()) {
            b.tvDetailReview.setText("短评：" + review);
        }

        b.llExcerptPreview.removeAllViews();
        int density = Math.round(activity.getResources().getDisplayMetrics().density);
        for (int i = 0; i < Math.min(2, list.size()); i++) {
            Excerpt excerpt = list.get(i);
            TextView tv = new TextView(activity);
            String prefix = excerpt.page > 0 ? "P." + excerpt.page + "  " : "";
            String content = excerpt.content == null ? "" : excerpt.content.trim();
            tv.setText(prefix + content.replace('\n', ' '));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimensionPixelSize(R.dimen.text_body_small));
            tv.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface_variant));
            tv.setMaxLines(2);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6 * density;
            b.llExcerptPreview.addView(tv, lp);
        }

        b.rowAllExcerpts.setText(list.isEmpty()
                ? "写下第一条摘抄 ›" : "查看全部 " + list.size() + " 条 ›");
        b.cardExcerpts.setOnClickListener(v -> {
            Intent intent = new Intent(activity, ExcerptListActivity.class);
            intent.putExtra(ExcerptListActivity.EXTRA_BOOK_ID, book.id);
            intent.putExtra(ExcerptListActivity.EXTRA_BOOK_TITLE, book.title);
            activity.startActivity(intent);
        });
    }
}
