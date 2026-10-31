package com.bai.bookshelf.ui.shelf;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * 书架封面项的离屏渲染（v5.8.14）：书脊 6/8/10dp、2dp 进度线、右下角百分比。
 * 只 inflate + bind + draw 到 Bitmap，不动桌面、不切前台。
 */
@RunWith(AndroidJUnit4.class)
public class ShelfItemRenderTest {

    private Book book(long id, int quantity, String series) {
        Book b = new Book();
        b.id = id;
        b.title = series.isEmpty() ? quantity + " 册书" : "套装（3 卷）";
        b.quantity = quantity;
        b.seriesName = series;
        b.readStatus = Book.READING;
        b.readingProgress = 42;
        b.createdAt = System.currentTimeMillis();
        b.updatedAt = b.createdAt;
        return b;
    }

    @Test
    public void 三种书脊与进度线渲染() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                render(ctx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void 选中项有主色蒙层与勾选圈() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(ctx,
                    ThemeManager.styleRes(ThemeManager.current(ctx)));
            BookGridAdapter adapter = new BookGridAdapter((b, cover) -> {
            });
            java.util.List<Book> books = java.util.Arrays.asList(
                    book(11, 1, ""), book(12, 3, ""), book(13, 1, "套装"));
            adapter.submit(books);
            java.util.Set<Long> ids = new java.util.HashSet<>();
            ids.add(books.get(0).id);
            ids.add(books.get(1).id);
            adapter.setSelectionState(true, ids);

            FrameLayout parent = new FrameLayout(themed);
            int[] expected = {View.VISIBLE, View.VISIBLE, View.GONE};
            for (int i = 0; i < 3; i++) {
                BookGridAdapter.VH vh = adapter.onCreateViewHolder(parent, 0);
                adapter.onBindViewHolder(vh, i);
                assertEquals("第 " + i + " 项蒙层", expected[i],
                        vh.itemView.findViewById(R.id.v_select_scrim).getVisibility());
                assertEquals("第 " + i + " 项勾选圈", expected[i],
                        vh.itemView.findViewById(R.id.iv_select_check).getVisibility());
            }
        });
    }

    private void render(Context ctx) throws Exception {
        BookGridAdapter adapter = new BookGridAdapter((b, cover) -> {
        });
        adapter.submit(Arrays.asList(book(1, 1, ""), book(2, 3, ""), book(3, 1, "套装")));

        Context themed = new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
        FrameLayout parent = new FrameLayout(themed);
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int itemWidth = Math.round(300 * density);
        for (int i = 0; i < 3; i++) {
            BookGridAdapter.VH vh = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(vh, i);
            row.addView(vh.itemView, new LinearLayout.LayoutParams(itemWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        row.measure(View.MeasureSpec.makeMeasureSpec(itemWidth * 3, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());

        int[] expectedDp = {6, 8, 10};
        for (int i = 0; i < 3; i++) {
            View item = row.getChildAt(i);
            View spine = item.findViewById(R.id.v_spine);
            assertEquals("第 " + i + " 项书脊", Math.round(expectedDp[i] * density),
                    spine.getLayoutParams().width);
            assertEquals(View.VISIBLE,
                    item.findViewById(R.id.pb_cover_progress).getVisibility());
            TextView percent = item.findViewById(R.id.tv_progress_percent);
            assertEquals(View.VISIBLE, percent.getVisibility());
            assertEquals("42%", percent.getText().toString());
        }

        Bitmap bitmap = Bitmap.createBitmap(row.getMeasuredWidth(),
                row.getMeasuredHeight() + Math.round(20 * density), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ctx.getColor(R.color.green_page));
        canvas.translate(0, 10 * density);
        row.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getCacheDir(), "shelf_items.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
