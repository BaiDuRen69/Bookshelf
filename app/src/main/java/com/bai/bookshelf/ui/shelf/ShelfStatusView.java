package com.bai.bookshelf.ui.shelf;

import android.view.View;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.FragmentShelfBinding;

/**
 * 书架头部的状态区（v5.6 从 ShelfFragment 抽出）：
 * 全库册数/地点数 meta、结果行（共 N 本 · 已筛选 X 项 · 搜索）、
 * 空态文案与主按钮（清空搜索 / 清除筛选 / 扫码录入）。
 */
public class ShelfStatusView {

    private final FragmentShelfBinding b;
    private final java.util.function.Supplier<String> sortLabel;
    private final Runnable clearAllFilters;
    private final Runnable clearSearchNow;
    private final Runnable clearSearchSilently;
    private final Runnable startScan;

    public ShelfStatusView(FragmentShelfBinding b,
                           java.util.function.Supplier<String> sortLabel,
                           Runnable clearAllFilters,
                           Runnable clearSearchNow,
                           Runnable clearSearchSilently,
                           Runnable startScan) {
        this.b = b;
        this.sortLabel = sortLabel;
        this.clearAllFilters = clearAllFilters;
        this.clearSearchNow = clearSearchNow;
        this.clearSearchSilently = clearSearchSilently;
        this.startScan = startScan;
    }

    /**
     * 头部左侧：全库种数 / 册数与地点数（地点字典到达后才有地点数）。
     *
     * <p>v5.8 口径：以前这里按「行」计数却写着「册」，一本三册的书被算成 1。
     * 现在种数是主、册数只在两者不同时补一句，避免「19 册」这种既错又无信息量的标签。
     */
    public void updateMeta(int titleCount, int copyCount, int locationCount) {
        if (b == null) {
            return;
        }
        StringBuilder meta = new StringBuilder().append(titleCount).append(" 种");
        if (copyCount != titleCount) {
            meta.append(" · ").append(copyCount).append(" 册");
        }
        if (locationCount > 0) {
            meta.append(" · ").append(locationCount).append(" 个地点");
        }
        b.tvShelfMeta.setText(meta);
    }

    /**
     * 头部结果行（v4.41）：有筛选时「共 N 本 · 已筛选 X 项」+ 右侧固定「清除」按钮，
     * 只搜索时「共 N 本 · 搜索结果」+「清空」。按钮固定占位，不会被文字挤掉。
     */
    public void bindResultLine(int count, int activeFilters, String keyword, String smartName) {
        if (activeFilters == 0 && keyword.isEmpty() && smartName.isEmpty()) {
            b.tvShelfResultCount.setText("按" + sortLabel.get());
            b.btnClearFilters.setVisibility(View.GONE);
            return;
        }
        b.btnClearFilters.setVisibility(View.VISIBLE);
        StringBuilder text = new StringBuilder("共 ").append(count).append(" 种");
        if (!smartName.isEmpty()) {
            text.append(" · ").append(smartName);
        }
        if (activeFilters > 0) {
            text.append(" · 已筛选 ").append(activeFilters).append(" 项");
        }
        if (!keyword.isEmpty()) {
            text.append(" · 搜索");
        }
        b.tvShelfResultCount.setText(text);
        b.btnClearFilters.setText("清除");
        // 结果行的清除是「一键回到全部」：智能书架、筛选项、搜索词一起清
        b.btnClearFilters.setOnClickListener(v -> {
            clearSearchSilently.run();
            clearAllFilters.run();
        });
    }

    /**
     * 空状态文案（v3.0 修复：此前无论什么原因列表为空，都显示「书架还是空的」，
     * 导致搜索无结果 / 筛选无结果时给出误导性提示）。
     *
     * @param empty     列表是否为空
     * @param keyword   当前搜索词
     * @param hasFilter 是否处于筛选状态（含关键词或任一筛选项非「全部」）
     */
    public void updateEmptyState(boolean empty, String keyword, boolean hasFilter) {
        if (b == null) {
            return;
        }
        // v4.24：空态拆成「标题 + 说明 + 主按钮」，只有真的没有藏书时才引导扫码
        b.llEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            return;
        }
        b.tvEmptyDesc.setVisibility(View.VISIBLE);
        if (!keyword.isEmpty()) {
            b.tvEmptyTitle.setText("没有找到「" + keyword.trim() + "」");
            b.tvEmptyDesc.setText("换个关键词，或清空筛选条件试试");
            // v4.41：空结果给出口，而不是只留一句提示
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("清空搜索");
            b.btnEmptyAdd.setOnClickListener(v -> clearSearchNow.run());
        } else if (hasFilter) {
            b.tvEmptyTitle.setText("当前筛选下没有藏书");
            b.tvEmptyDesc.setText("试试清除筛选条件，看全部藏书");
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("清除筛选");
            b.btnEmptyAdd.setOnClickListener(v -> clearAllFilters.run());
        } else {
            b.tvEmptyTitle.setText("书架还是空的");
            b.tvEmptyDesc.setText("扫码录入第一种书，开始整理你的书房");
            b.btnEmptyAdd.setVisibility(View.VISIBLE);
            b.btnEmptyAdd.setText("扫码录入第一种书");
            b.btnEmptyAdd.setOnClickListener(v -> startScan.run());
        }
    }
}
