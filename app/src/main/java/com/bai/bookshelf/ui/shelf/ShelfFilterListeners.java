package com.bai.bookshelf.ui.shelf;

import android.view.View;
import android.widget.AdapterView;

import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.databinding.FragmentShelfBinding;

import java.util.List;

/**
 * 书架筛选下拉的选中监听（v5.6 从 ShelfFragment 抽出）：
 * 藏书/阅读/地点/标签/国家共用一套；来源与分类是两段式，
 * 选中一级后在下方展开二级 chips。
 */
public class ShelfFilterListeners {

    private final FragmentShelfBinding b;
    private final java.util.function.BooleanSupplier spinnerReady;
    private final Runnable applyFilters;
    private final java.util.function.BiConsumer<AdapterView<?>, Integer> recordSelection;
    private final java.util.function.IntConsumer setChannelType;
    private final java.util.function.Supplier<ShelfSecondaryChips> chips;
    private final java.util.function.Supplier<List<Category>> categoryEntries;

    /** 上一次下拉事件的位置（首个事件来自程序填充，不展开二级） */
    private int lastChannelPos = -1;
    private int lastCategoryPos = -1;

    public ShelfFilterListeners(FragmentShelfBinding b,
                                java.util.function.BooleanSupplier spinnerReady,
                                Runnable applyFilters,
                                java.util.function.BiConsumer<AdapterView<?>, Integer> recordSelection,
                                java.util.function.IntConsumer setChannelType,
                                java.util.function.Supplier<ShelfSecondaryChips> chips,
                                java.util.function.Supplier<List<Category>> categoryEntries) {
        this.b = b;
        this.spinnerReady = spinnerReady;
        this.applyFilters = applyFilters;
        this.recordSelection = recordSelection;
        this.setChannelType = setChannelType;
        this.chips = chips;
        this.categoryEntries = categoryEntries;
    }

    public void attach() {
        AdapterView.OnItemSelectedListener generic = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                highlight(parent, position, spinnerReady.getAsBoolean());
                // v3.3：记住用户的选择，视图重建后据此恢复
                recordSelection.accept(parent, position);
                if (spinnerReady.getAsBoolean()) {
                    applyFilters.run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        b.spStock.setOnItemSelectedListener(generic);
        b.spReadFilter.setOnItemSelectedListener(generic);
        b.spLocation.setOnItemSelectedListener(generic);
        b.spTag.setOnItemSelectedListener(generic);
        b.spCountry.setOnItemSelectedListener(generic);

        b.spChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 首个事件来自程序填充（适配器刚装好），不当作用户选择
                boolean userDriven = lastChannelPos >= 0;
                lastChannelPos = position;
                highlight(parent, position, userDriven);
                recordSelection.accept(parent, position);
                if (!spinnerReady.getAsBoolean()) {
                    return;
                }
                if (position > 0 && userDriven) {
                    setChannelType.accept(position - 1); // ownType 0/1/2
                    chips.get().clearChannelSub();    // 换了大类，先回到整组
                    applyFilters.run();
                    chips.get().showChannel(position - 1);
                } else {
                    if (position == 0) {
                        setChannelType.accept(-1);
                        chips.get().clearChannelSub();
                        chips.get().showChannel(-1);
                    }
                    applyFilters.run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 分类下拉（v4.10）：在通用筛选行为之上，选中一级后在下方行内展开二级 chips
        b.spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 首个事件来自程序填充（适配器刚装好），不当作用户选择
                boolean userDriven = lastCategoryPos >= 0;
                lastCategoryPos = position;
                highlight(parent, position, userDriven);
                recordSelection.accept(parent, position);
                if (!spinnerReady.getAsBoolean()) {
                    return;
                }
                int index = position - 1;
                if (position > 0 && userDriven && index < categoryEntries.get().size()) {
                    chips.get().clearCategorySub(); // 换了一级，先回到整组
                    applyFilters.run();
                    chips.get().showCategory(categoryEntries.get().get(index));
                } else {
                    if (position == 0) {
                        chips.get().clearCategorySub();
                        chips.get().showCategory(null);
                    }
                    applyFilters.run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static void highlight(AdapterView<?> parent, int position, boolean animate) {
        if (parent.getAdapter() instanceof HighlightAdapter) {
            ((HighlightAdapter<?>) parent.getAdapter()).setHighlight(position, animate);
        }
    }
}
