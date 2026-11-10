package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.widget.AdapterView;
import android.widget.Spinner;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.databinding.FragmentShelfBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架筛选下拉的填充与选择记忆（v3.2/v3.3）：
 * 只在选项内容真正变化时重建适配器；视图重建前记住已选项，重建后恢复。
 * 分类一级条目同时在这里维护，供二级 chips 与筛选条件使用。
 */
public class ShelfFilterSpinners {

    private boolean filled = false; // 本视图是否已完整填充过
    private final List<Category> categoryEntries = new ArrayList<>(); // 一级分类（下拉项与位置对齐）

    private final List<String> tagCache = new ArrayList<>();
    private final List<String> locCache = new ArrayList<>();
    private final List<String> channelCache = new ArrayList<>();
    private final List<String> categoryCache = new ArrayList<>();
    private final List<String> countryCache = new ArrayList<>();
    /** 各维度记住的选中文案（dim → 文案）。来源不在卡 H 的六维里，只留内存不落 prefs。 */
    private final Map<String, String> remembered = new HashMap<>();
    /** 视图状态持久化（可空：单测或老调用方不接也能跑） */
    private ShelfViewPrefs viewPrefs;

    /** 接上持久化：把 prefs 里的文案维记忆当作「上一次视图」的初值 */
    public void bindViewPrefs(ShelfViewPrefs prefs) {
        viewPrefs = prefs;
        if (prefs == null) {
            return;
        }
        seedLabel(prefs, ShelfViewPrefs.DIM_LOCATION);
        seedLabel(prefs, ShelfViewPrefs.DIM_TAG);
        seedLabel(prefs, ShelfViewPrefs.DIM_CATEGORY);
        seedLabel(prefs, ShelfViewPrefs.DIM_COUNTRY);
    }

    private void seedLabel(ShelfViewPrefs prefs, String dim) {
        String label = prefs.label(dim);
        if (label.isEmpty()) {
            remembered.remove(dim);
        } else {
            remembered.put(dim, label);
        }
    }

    /** 一级分类条目（下拉第 N 项 → entries[N-1]） */
    public List<Category> categoryEntries() {
        return categoryEntries;
    }

    /** 视图重建前调用：保存各 Spinner 当前的选中文案 */
    public void saveSelections(FragmentShelfBinding b) {
        if (b == null) {
            return;
        }
        remember(b.spCategory, categoryCache, ShelfViewPrefs.DIM_CATEGORY);
        remember(b.spTag, tagCache, ShelfViewPrefs.DIM_TAG);
        remember(b.spLocation, locCache, ShelfViewPrefs.DIM_LOCATION);
        remember(b.spChannel, channelCache, "channel");
        remember(b.spCountry, countryCache, ShelfViewPrefs.DIM_COUNTRY);
    }

    /** 新视图创建时调用：清空内容缓存，强制下次重新填充 */
    public void onViewRecreated() {
        filled = false;
        tagCache.clear();
        locCache.clear();
        channelCache.clear();
        categoryCache.clear();
        countryCache.clear();
    }

    /** 本视图的五个筛选下拉都已填充完成 */
    public void markFilled() {
        filled = true;
    }

    /**
     * Spinner 选中变化时按维度记账，并双写进视图状态持久化（卡 H1）。
     *
     * <p>下标维（藏书状态/阅读状态）内容写死在 arrays.xml，只需把位置落 prefs；
     * 文案维（地点/标签/分类/国家/来源）另外在内存里记文案，供视图重建后回填。
     */
    public void recordSelection(FragmentShelfBinding b, AdapterView<?> parent, int position) {
        if (b == null) {
            return;
        }
        String dim = dimOf(b, parent);
        if (dim == null) {
            return;
        }
        if (position <= 0) {
            // 用户主动选回首项「不限」——清掉记忆，否则下次视图重建又会被恢复成旧筛选
            remembered.remove(dim);
            if (viewPrefs != null) {
                viewPrefs.select(dim, 0, "");
            }
            return;
        }
        Object item = parent.getSelectedItem();
        String text = item == null ? "" : item.toString();
        if (ShelfViewPrefs.isIndexDim(dim)) {
            if (viewPrefs != null) {
                viewPrefs.select(dim, position, text);
            }
            return;
        }
        List<String> cache = cacheOf(b, parent);
        if (cache == null || cache.isEmpty() || text.isEmpty() || text.equals(cache.get(0))) {
            return;
        }
        remembered.put(dim, text);
        if (viewPrefs != null) {
            viewPrefs.select(dim, position, text);
        }
    }

    /** 一键清除筛选后调用：内存记忆全清，否则下次视图重建还会把旧筛选恢复回来 */
    public void clearRemembered() {
        remembered.clear();
    }

    /** 标签筛选下拉：首项「标签」= 不限，其余为标签库中的每个标签 */
    public void fillTag(FragmentShelfBinding b, List<Tag> tags) {
        List<String> names = new ArrayList<>();
        names.add("标签");
        for (Tag tag : tags) {
            names.add(tag.name);
        }
        fillIfChanged(b.spTag, names, tagCache, ShelfViewPrefs.DIM_TAG);
    }

    /** 地点筛选下拉：首项「地点」= 不限，其余为各地点名 */
    public void fillLocation(FragmentShelfBinding b, List<Location> locations) {
        List<String> names = new ArrayList<>();
        names.add("地点");
        for (Location loc : locations) {
            names.add(loc.name);
        }
        fillIfChanged(b.spLocation, names, locCache, ShelfViewPrefs.DIM_LOCATION);
    }

    /** 来源筛选下拉（v4.10 两级化）：首项「来源」= 不限，其余为三个来源大类 */
    public void fillChannel(FragmentShelfBinding b) {
        Context ctx = b.getRoot().getContext();
        List<String> names = new ArrayList<>();
        names.add("来源");
        names.addAll(Arrays.asList(ctx.getResources().getStringArray(R.array.own_type_entries)));
        fillIfChanged(b.spChannel, names, channelCache, "channel");
    }

    /**
     * 分类筛选下拉（v4.10 两段式）：首项「分类」= 不限，其余只列一级栏目；
     * 选中一级后在下方行内展开该组二级 chips。
     */
    public void fillCategory(FragmentShelfBinding b, List<Category> categoryList) {
        categoryEntries.clear();
        List<String> names = new ArrayList<>();
        names.add("分类");
        for (Category top : categoryList) {
            if (top.parentId == null) {
                categoryEntries.add(top);
                names.add(top.name);
            }
        }
        fillIfChanged(b.spCategory, names, categoryCache, ShelfViewPrefs.DIM_CATEGORY);
    }

    /** 国家/地区筛选下拉：首项「国家」= 不限，其余为藏书中出现过的国家/地区 */
    public void fillCountry(FragmentShelfBinding b, List<String> names) {
        List<String> items = new ArrayList<>();
        items.add("国家");
        items.addAll(names);
        fillIfChanged(b.spCountry, items, countryCache, ShelfViewPrefs.DIM_COUNTRY);
    }

    /** 视图重建前抓取当前选中文案，只进内存（用户驱动的改动已经由 recordSelection 落盘） */
    private void remember(Spinner spinner, List<String> cache, String dim) {
        if (spinner == null) {
            return;
        }
        Object sel = spinner.getSelectedItem();
        String text = sel == null ? "" : sel.toString();
        if (text.isEmpty() || text.equals(cache.isEmpty() ? "" : cache.get(0))) {
            // 选的是首项「不限」→ 不是筛选，清掉记忆
            remembered.remove(dim);
            return;
        }
        remembered.put(dim, text);
    }

    /** 由 Spinner 实例反查它对应的维度名（六个持久化维 + 来源；排序不在这里） */
    private String dimOf(FragmentShelfBinding b, AdapterView<?> parent) {
        if (parent == b.spStock) {
            return ShelfViewPrefs.DIM_STOCK;
        } else if (parent == b.spReadFilter) {
            return ShelfViewPrefs.DIM_READ;
        } else if (parent == b.spCategory) {
            return ShelfViewPrefs.DIM_CATEGORY;
        } else if (parent == b.spTag) {
            return ShelfViewPrefs.DIM_TAG;
        } else if (parent == b.spLocation) {
            return ShelfViewPrefs.DIM_LOCATION;
        } else if (parent == b.spCountry) {
            return ShelfViewPrefs.DIM_COUNTRY;
        } else if (parent == b.spChannel) {
            return "channel";   // 只留内存，不落 prefs（不在卡 H 的六维里）
        }
        return null;
    }

    /** 由 Spinner 实例反查它对应的 cache 字段 */
    private List<String> cacheOf(FragmentShelfBinding b, AdapterView<?> parent) {
        if (parent == b.spCategory) {
            return categoryCache;
        } else if (parent == b.spTag) {
            return tagCache;
        } else if (parent == b.spLocation) {
            return locCache;
        } else if (parent == b.spChannel) {
            return channelCache;
        } else if (parent == b.spCountry) {
            return countryCache;
        }
        return null; // 藏书状态/阅读/排序：内容写死在 arrays.xml，无需记忆
    }

    /**
     * 给 Spinner 填充下拉项，仅在内容真正变化时才重建适配器（v3.2）。
     *
     * <p>原因：ArrayAdapter 每次 setAdapter 都会重建整个 Spinner 的视图树并丢掉选中状态，
     * 而旧版每次回页面都会全量重载 → 6 个 Spinner 无条件重建，
     * 这既是白花开销，也会让用户在筛选状态下切走再回来时看到下拉被"重置"的闪动。
     *
     * <p>v3.3 修正：必须同时满足「本视图已填充过」才允许跳过，否则视图重建后
     * 缓存内容与全新的空 Spinner 不匹配，会被误判为"没变化"而跳过填充，
     * 导致分类/标签/地点/来源/国家全部空白。
     */
    private void fillIfChanged(Spinner spinner, List<String> names, List<String> cache, String dim) {
        Context ctx = spinner.getContext();
        // Spinner 还没有适配器时必须填，否则永远是空白
        if (spinner.getAdapter() == null) {
            cache.clear();
            cache.addAll(names);
            spinner.setAdapter(new HighlightAdapter<>(ctx, new ArrayList<>(names)));
            // 卡 H2：回填只在 adapter 就绪之后做，且「adapter 为空」本身就是必须填充的兜底判据
            restoreSelection(spinner, names, dim);
            return;
        }
        if (filled && names.equals(cache)) {
            return; // 本视图已填充过且内容没变，跳过重建
        }
        // 记录用户当前选中的文案，重建后恢复，避免被重置回第一项
        Object selected = spinner.getSelectedItem();
        String selectedText = selected == null ? null : selected.toString();
        int selectedIndex = selectedText == null ? -1 : cache.indexOf(selectedText);

        cache.clear();
        cache.addAll(names);
        spinner.setAdapter(new HighlightAdapter<>(ctx, new ArrayList<>(names)));
        if (selectedIndex > 0) {
            spinner.setSelection(selectedIndex, false);
        }
    }

    /**
     * 视图重建后恢复筛选选中项：用记住的已选项文案（内存或 prefs 带来的）。
     *
     * <p>按文案而非按位置恢复：选项来自数据库，某条地点/标签被删或改名后位置会变，
     * 文案找不到就保持「不限」，不会把用户筛到另一本书上。
     */
    private void restoreSelection(Spinner spinner, List<String> names, String dim) {
        String label = remembered.get(dim);
        int idx = label == null ? -1 : names.indexOf(label);
        if (idx > 0) {
            spinner.setSelection(idx, false);
        }
    }
}
