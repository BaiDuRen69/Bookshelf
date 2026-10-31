package com.bai.bookshelf.ui.lend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityBorrowersBinding;
import com.bai.bookshelf.databinding.ItemBorrowerBinding;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 借阅人档案页的离屏渲染（v5.8.18）：整页与行布局能在主题下 inflate 并量出高度，
 * 主题属性写错只会在真机上炸，这里提前拦住。
 */
@RunWith(AndroidJUnit4.class)
public class BorrowerRenderTest {

    @Test
    public void 档案页与行可离屏渲染() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(ctx,
                    ThemeManager.styleRes(ThemeManager.current(ctx)));
            LayoutInflater inflater = LayoutInflater.from(themed);

            ActivityBorrowersBinding page = ActivityBorrowersBinding.inflate(inflater);
            assertNotNull(page.toolbar.getNavigationIcon());
            assertEquals("借阅人档案", page.toolbar.getTitle().toString());
            assertEquals("添加借阅人", page.btnAdd.getText().toString());
            assertEquals(View.GONE, page.tvEmpty.getVisibility());
            float density = ctx.getResources().getDisplayMetrics().density;
            int width = ctx.getResources().getDisplayMetrics().widthPixels;
            page.getRoot().measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.round(800 * density),
                            View.MeasureSpec.EXACTLY));
            assertEquals(width, page.getRoot().getMeasuredWidth());

            ItemBorrowerBinding row = ItemBorrowerBinding.inflate(inflater);
            row.tvName.setText("张三");
            row.tvStats.setText("借过 3 本 · 逾期 1 次");
            row.tvInitial.setText("张");
            row.tvOut.setText("在外 2");
            row.getRoot().measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            assertTrue("行高至少 64dp",
                    row.getRoot().getMeasuredHeight() >= Math.round(64 * density));
        });
    }
}
