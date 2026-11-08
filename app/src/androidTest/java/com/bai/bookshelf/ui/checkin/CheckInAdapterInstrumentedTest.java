package com.bai.bookshelf.ui.checkin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * 打卡列表行内刷新（v5.9.1 第 5 步）：点打卡后副标题的天数要立刻换，且只重绑这一行。
 *
 * <p>放在 {@code ui.checkin} 包里，才能直接调 {@code onCreateViewHolder / onBindViewHolder}
 * 验证「不整表重建」；不引入 Espresso，也不启动 Activity。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInAdapterInstrumentedTest {

    private Context themed() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return new android.view.ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
    }

    @Test
    public void 点打卡后副标题天数立即更新且行数不变() {
        final int today = DateKeys.todayKey();
        final String[] afterCheck = new String[1];
        final String[] afterUndo = new String[1];
        final int[] countAfterChecks = new int[1];
        final int[] countAfterRemove = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RecyclerView parent = new RecyclerView(themed());
            parent.setLayoutManager(new LinearLayoutManager(parent.getContext()));
            final CheckInAdapter adapter = new CheckInAdapter();
            Book book = new Book();
            book.id = 901L;
            book.title = "测试书";
            book.readingProgress = 43;
            book.readStatus = Book.READING;
            CheckInAdapter.Row row = new CheckInAdapter.Row(book, false);
            row.readDays = 3;
            row.seq = 1;
            List<CheckInAdapter.Row> rows = new ArrayList<>();
            rows.add(row);
            adapter.submit(rows, today, today);
            adapter.setListener(new CheckInAdapter.Listener() {
                @Override
                public void onToggle(int position, CheckInAdapter.Row r) {
                    // 与 CheckInFragment.onToggle 一致：天数按新日集合重算后回写同一行。
                    // 这里的 holder 没挂进 RecyclerView，位置固定按第一行（0）算
                    boolean checked = !r.checked;
                    int days = Math.max(0, r.readDays + (checked ? 1 : -1));
                    adapter.applyToggleResult(r.book.id, checked, days);
                }

                @Override
                public void onBackfillLongPress(int dayKey) {
                }
            });
            CheckInAdapter.VH vh = adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(vh, 0);
            TextView subtitle = vh.b.tvSubtitle;
            View button = vh.b.btnCheckin;
            // 未挂到 RecyclerView 时 getBindingAdapterPosition() 是 NO_POSITION，
            // 真实列表里点击拿到的是有效位置，这里直接按 0 回写，语义一致
            button.performClick();
            adapter.onBindViewHolder(vh, 0);
            afterCheck[0] = subtitle.getText().toString();
            button.performClick();
            adapter.onBindViewHolder(vh, 0);
            afterUndo[0] = subtitle.getText().toString();
            countAfterChecks[0] = adapter.getItemCount();
            assertFalse("不存在的 bookId 不移除", adapter.removeRowByBook(999L));
            assertTrue("按 bookId 移除当前行", adapter.removeRowByBook(901L));
            countAfterRemove[0] = adapter.getItemCount();
        });
        assertTrue("打卡后副标题要立刻加一天：" + afterCheck[0],
                afterCheck[0].contains("已读 4 天"));
        assertTrue("今天的行还要挂上已打卡：" + afterCheck[0],
                afterCheck[0].contains("今天已打卡"));
        assertTrue("撤销后副标题要立刻减一天：" + afterUndo[0],
                afterUndo[0].contains("已读 3 天"));
        assertFalse("撤销后不再挂已打卡：" + afterUndo[0],
                afterUndo[0].contains("已打卡"));
        assertEquals("点打卡到撤销之间行数不变，不整表重建", 1, countAfterChecks[0]);
        assertEquals("越界移除不动、正常移除一行", 0, countAfterRemove[0]);
    }
}
