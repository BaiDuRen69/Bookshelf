package com.bai.bookshelf.ui.detail;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.util.CoverColor;
import com.bai.bookshelf.util.ThemeManager;

import java.util.List;

/**
 * 详情页 hero 区（v5.9.0 从 BookDetailActivity 抽出）：封面主色的取色缓存与
 * 三处着色（遮罩 / 分隔线 / 进度条），外加几个纯格式化小工具。
 *
 * <p>取色是异步的，「当前算到的颜色 + 算给哪本书 + 有没有在算」这三个字段
 * 只服务于 hero 本身，所以收在这里，不再摊在活动页上。
 */
final class DetailHero {

    private final Activity host;
    private final ActivityBookDetailBinding b;

    private int accent;
    private String accentKey = "";
    private boolean loading;

    DetailHero(Activity host, ActivityBookDetailBinding b) {
        this.host = host;
        this.b = b;
    }

    /** 当前封面主色；0 = 还没取到或这本书没封面 */
    int accentColor() {
        return accent;
    }

    /** 换书 / 换主题时重新着色，没算过的主色在后台取一次 */
    void bindCoverAccent(Book book) {
        final String key = book.coverUrl + "|" + book.title + "|" + ThemeManager.current(host);
        int cached = CoverColor.cached(host, book);
        if (cached != 0) {
            accent = cached;
            accentKey = key;
        } else if (!key.equals(accentKey)) {
            accent = 0;
            accentKey = key;
        }
        applyCoverAccent();
        if (accent != 0 || loading) {
            return;
        }
        loading = true;
        final Book target = book;
        final String wanted = key;
        BookshelfApp.get().io(() -> {
            int color = CoverColor.of(host, target);
            host.runOnUiThread(() -> {
                loading = false;
                if (book != null && wanted.equals(book.coverUrl + "|" + book.title + "|"
                        + ThemeManager.current(host))) {
                    accent = color;
                    applyCoverAccent();
                }
            });
        });
    }

    private void applyCoverAccent() {
        boolean night = ThemeManager.current(host) == ThemeManager.THEME_NIGHT;
        int base = ThemeManager.resolveColor(host, R.attr.primary_container);
        int scrim = accent == 0 ? base
                : androidx.core.graphics.ColorUtils.blendARGB(
                        base, accent, night ? 0.30f : 0.22f);
        b.vHeroScrim.setBackgroundColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(scrim, 217));
        b.vHeroLine.setBackgroundColor(accent == 0
                ? ThemeManager.resolveColor(host, ThemeManager.ATTR_COLOR_SECONDARY)
                : androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 180));
        if (b.pbReadingProgress.getVisibility() == View.VISIBLE) {
            b.pbReadingProgress.setProgressTintList(ColorStateList.valueOf(
                    accent != 0 ? accent
                            : ThemeManager.resolveColor(host, ThemeManager.ATTR_COLOR_PRIMARY)));
        }
    }

    // ==================== 纯格式化小工具 ====================

    /** yyyy-MM-dd → 「M月d日」；缺日期返回空串让上层决定怎么措辞 */
    static String mdLabel(String date) {
        int key = com.bai.bookshelf.util.DateKeys.parseKey(date);
        return key <= 0 ? "" : (key / 100 % 100) + "月" + (key % 100) + "日";
    }

    /** 今天（yyyy-MM-dd），写借阅/归还日期时用 */
    static String today() {
        return com.bai.bookshelf.util.DateKeys.formatKey(
                com.bai.bookshelf.util.DateKeys.todayKey());
    }

    /** 非空（去掉空白后）才收进列表，收的是去掉首尾空格的值 */
    static void addIfNotEmpty(List<String> list, String value) {
        if (value != null && !value.trim().isEmpty()) {
            list.add(value.trim());
        }
    }

    /** 用「 · 」串起来，空列表返回空串 */
    static String joinWithDot(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** hero 状态 chip：借出用黄铜底白字，其余用白底主色字 */
    void bindChip(TextView tv, String text, boolean emphasize) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        if (emphasize) {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(host, R.color.badge_borrowed)));
            tv.setTextColor(android.graphics.Color.WHITE);
        } else {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(null);
            tv.setTextColor(ThemeManager.resolveColor(host, ThemeManager.ATTR_COLOR_PRIMARY));
        }
    }
}
