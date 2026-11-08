package com.bai.bookshelf;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.widget.ReadingWidgetProvider;
import com.bai.bookshelf.util.ReadingGoal;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 桌面小组件的数据快照（v5.8.8）：真机上插一本书，断言快照里出现书名与目标。
 * 小组件只认数据，RemoteViews 的拼装在 provider 里，逻辑都在这条快照上。
 */
@RunWith(AndroidJUnit4.class)
public class ReadingWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void 小组件快照包含在读书名与年度目标() {
        Book b = new Book();
        b.title = "小组件测试在读";
        b.isbn = "9781111111111";
        b.pages = 200;
        b.listPrice = 45;
        b.purchasePrice = 30;
        b.readStatus = Book.READING;
        b.createdAt = System.currentTimeMillis();
        b.updatedAt = b.createdAt;
        BookshelfApp.get().db().bookDao().insert(b);

        ReadingGoal.save(ctx(), 40);

        ReadingWidgetProvider.Snapshot s = ReadingWidgetProvider.snapshot(ctx());
        assertTrue("应显示在读的那本书：" + s.reading,
                s.reading.contains("小组件测试在读"));
        assertTrue("目标行应带 40：" + s.goal, s.goal.contains("/ 40"));
        assertTrue("进度应在 0~100：" + s.progress,
                s.progress >= 0 && s.progress <= 100);
        assertTrue("今日打卡行要有文案：" + s.todayCheckIn,
                s.todayCheckIn != null
                        && (s.todayCheckIn.equals("今天还没打卡")
                        || s.todayCheckIn.startsWith("今日已打卡 ")));
    }
}
