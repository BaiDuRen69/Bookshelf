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
    private final Map<List<String>, String> selectionCache = new HashMap<>();

    /** 一级分类条目（下拉第 N 项 → entries[N-1]） */
    public List<Category> categoryEntries() {
        return categoryEntries;
    }

    /** 视图重建前调用：保存各 Spinner 当前的选中文案 */
    public void saveSelections(FragmentShelfBinding b) {
        if (b == null) {
            return;
        }
        remember(b.spCategory, categoryCache);
        remember(b.spTag, tagCache);
        remember(b.spLocation, locCache);
        remember(b.spChannel, channelCache);
        remember(b.spCountry, countryCache);
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

    /** Spinner 选中变化时记录选中文案（以对应 cache 为 key） */
    public void recordSelection(FragmentShelfBinding b, AdapterView<?> parent, int position) {
        if (b == null) {
            return;
        }
        List<String> cache = cacheOf(b, parent);
        if (cache == null) {
            return;
        }
        if (position <= 0) {
            // 用户主动选回首项「不限」——清掉记忆，否则下次视图重建又会被恢复成旧筛选
            selectionCache.remove(cache);
            return;
        }
        Object item = parent.getSelectedItem();
        String text = item == null ? "" : item.toString();
        if (text.isEmpty() || text.equals(cache.get(0))) {
            return;
        }
        selectionCache.put(cache, text);
    }

    /** 标签筛选下拉：首项「标签」= 不限，其余为标签库中的每个标签 */
    public void fillTag(FragmentShelfBinding b, List<Tag> tags) {
        List<String> names = new ArrayList<>();
        names.add("标签");
        for (Tag tag : tags) {
            names.add(tag.name);
        }
        fillIfChanged(b.spTag, names, tagCache);
    }

    /** 地点筛选下拉：首项「地点」= 不限，其余为各地点名 */
    public void fillLocation(FragmentShelfBinding b, List<Location> locations) {
        List<String> names = new ArrayList<>();
        names.add("地点");
        for (Location loc : locations) {
            names.add(loc.name);
        }
        fillIfChanged(b.spLocation, names, locCache);
    }

    /** 来源筛选下拉（v4.10 两级化）：首项「来源」= 不限，其余为三个来源大类 */
    public void fillChannel(FragmentShelfBinding b) {
        Context ctx = b.getRoot().getContext();
        List<String> names = new ArrayList<>();
        names.add("来源");
        names.addAll(Arrays.asList(ctx.getResources().getStringArray(R.array.own_type_entries)));
        fillIfChanged(b.spChannel, names, channelCache);
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
        fillIfChanged(b.spCategory, names, categoryCache);
    }

    /** 国家/地区筛选下拉：首项「国家」= 不限，其余为藏书中出现过的国家/地区 */
    public void fillCountry(FragmentShelfBinding b, List<String> names) {
        List<String> items = new ArrayList<>();
        items.add("国家");
        items.addAll(names);
        fillIfChanged(b.spCountry, items, countryCache);
    }

    private void remember(Spinner spinner, List<String> cache) {
        if (spinner == null) {
            return;
        }
        Object sel = spinner.getSelectedItem();
        String text = sel == null ? "" : sel.toString();
        if (text.isEmpty() || text.equals(cache.isEmpty() ? "" : cache.get(0))) {
            // 选的是首项「不限」→ 不是筛选，清掉记忆
            selectionCache.remove(cache);
            return;
        }
        selectionCache.put(cache, text);
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
    private void fillIfChanged(Spinner spinner, List<String> names, List<String> cache) {
        Context ctx = spinner.getContext();
        // Spinner 还没有适配器时必须填，否则永远是空白
        if (spinner.getAdapter() == null) {
            cache.clear();
            cache.addAll(names);
            spinner.setAdapter(new HighlightAdapter<>(ctx, new ArrayList<>(names)));
            // v3.3：视图重建后恢复用户此前的筛选选择（选中文案存在 selectionCache 里）
            restoreSelection(spinner, names, cache);
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

    /** 视图重建后恢复筛选选中项：用重建前记住的已选项文案 */
    private void restoreSelection(Spinner spinner, List<String> names, List<String> cache) {
        String remembered = selectionCache.get(cache);
        int idx = remembered == null ? -1 : names.indexOf(remembered);
        if (idx > 0) {
            spinner.setSelection(idx, false);
        }
    }
}
