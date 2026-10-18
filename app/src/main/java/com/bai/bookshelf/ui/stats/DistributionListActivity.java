package com.bai.bookshelf.ui.stats;

import android.os.Bundle;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityCountryBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.ShelfNavigator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「名称分布」二级页的公共实现（v3.0 抽出）。
 *
 * <p>作者、出版社、国家/地区三个二级页除了「取哪个字段」之外完全一致：
 * 同一个布局、同一套条形渲染、同一个点击跳转行为，此前是三份复制粘贴的代码。
 * 现统一收敛到本类，子类只需实现 {@link #fieldLabel()} 与 {@link #accumulate} 两处。
 *
 * <p>子类同样复用了国家页的布局文件（三个页面结构本就一样），
 * 因此历史代码里变量名叫 containerCountries，这里保持不变以免无谓改动。
 */
public abstract class DistributionListActivity extends BaseActivity {

    /** 统计的字段中文名：用于标题与空状态文案，如「作者」 */
    protected abstract String fieldLabel();

    /** 从一本书里取出该字段的值（空字符串表示不计入） */
    protected abstract String extractField(Book book);

    protected ActivityCountryBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCountryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setTitle("全部" + fieldLabel() + "分布");
        b.tvSubtitle.setText("按藏书数量排序，点条目可跳到书架查看");
        b.toolbar.setNavigationOnClickListener(v -> finish());
        load();
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Map<String, Integer> counts = new HashMap<>();
            for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                String key = extractField(book);
                if (key != null && !key.trim().isEmpty()) {
                    counts.merge(key.trim(), 1, Integer::sum);
                }
            }
            // 按数量倒序；数量相同时按名称排序，保证每次进入顺序稳定
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((x, y) -> {
                int byCount = y.getValue() - x.getValue();
                return byCount != 0 ? byCount : x.getKey().compareTo(y.getKey());
            });
            app.ui(() -> fill(sorted));
        });
    }

    private void fill(List<Map.Entry<String, Integer>> entries) {
        new DistRowBuilder(this, name -> ShelfNavigator.search(this, name))
                .fill(b.containerCountries, entries, DistRowBuilder.emptyHint(fieldLabel(),
                        "点条目可跳到书架查看「" + fieldLabel() + "」为该值的所有书"));
    }
}
