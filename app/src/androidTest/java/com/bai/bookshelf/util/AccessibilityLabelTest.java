package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 读屏标签（v5.8.24 清理轮）：样式里加的 importantForAccessibility 到底有没有落到 View 上。
 *
 * <p>lint 不解析 style，所以那 29 个用 MineRowChevron 的行它照样报警；
 * 这里按真实 inflate 的结果断言，避免「改了样式但运行时没生效」这种看不见的坑。
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityLabelTest {

    private View inflate(String name) {
        Context ctx = ApplicationProvider.getApplicationContext();
        final View[] out = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(ctx,
                    ThemeManager.styleRes(ThemeManager.current(ctx)));
            int id = ctx.getResources().getIdentifier(name, "layout", ctx.getPackageName());
            assertTrue("找不到布局 " + name, id != 0);
            out[0] = LayoutInflater.from(themed).inflate(id, null);
        });
        assertNotNull(out[0]);
        return out[0];
    }

    /** 一行里有两个 ImageView（行首图标 + 行尾箭头），箭头是最后一个 */
    private ImageView imageViewIn(View root, String rowIdName, int whichFromEnd) {
        int rowId = root.getResources().getIdentifier(rowIdName, "id",
                root.getContext().getPackageName());
        assertTrue("找不到行 id " + rowIdName, rowId != 0);
        View row = root.findViewById(rowId);
        assertNotNull("行不在布局里：" + rowIdName, row);
        if (!(row instanceof ViewGroup)) {
            throw new AssertionError(rowIdName + " 不是容器");
        }
        ViewGroup group = (ViewGroup) row;
        java.util.List<ImageView> icons = new java.util.ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) {
                icons.add((ImageView) child);
            }
        }
        assertTrue(rowIdName + " 这一行里的 ImageView 数量变了（" + icons.size() + "）",
                icons.size() > whichFromEnd);
        return icons.get(icons.size() - 1 - whichFromEnd);
    }

    @Test
    public void 行尾箭头不参与朗读且行首图标有名字() {
        View root = inflate("activity_library_manage");
        ImageView chevron = imageViewIn(root, "row_source", 0);
        assertEquals("MineRowChevron 样式里的 importantForAccessibility 没落到 View 上",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, chevron.getImportantForAccessibility());
        ImageView icon = imageViewIn(root, "row_source", 1);
        assertEquals("行首图标丢了朗读标签", "来源渠道管理",
                icon.getContentDescription().toString());
    }

    @Test
    public void 封面图有朗读标签() {
        Context ctx = ApplicationProvider.getApplicationContext();
        View report = inflate("activity_study_report");
        ImageView first = report.findViewById(R.id.iv_report_1);
        assertNotNull("年度报告的封面位不见了", first);
        assertEquals("书籍封面", first.getContentDescription().toString());

        View edit = inflate("activity_book_edit");
        ImageView cover = edit.findViewById(R.id.iv_cover);
        assertNotNull("编辑页封面位不见了", cover);
        assertEquals("书籍封面", cover.getContentDescription().toString());
    }
}
