package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ExcelExporter;
import com.bai.bookshelf.util.BackupInspector;
import com.bai.bookshelf.util.ZipBackup;
import com.bai.bookshelf.util.ZipRestore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 验收 3 / 7：打卡闭环、小结、Excel「打卡」sheet、备份→恢复条数一致。 */
@RunWith(AndroidJUnit4.class)
public class CheckInAcceptanceInstrumentedTest {

    private static final String TAG = "CheckInAcceptance";
    private static final String PREFIX = "验收用书";

    private BookshelfApp app() {
        return (BookshelfApp) ApplicationProvider.getApplicationContext();
    }

    private long addReadingBook(String title, int startKey) {
        Book b = new Book();
        b.title = title;
        b.readStatus = Book.READING;
        b.readingStartDate = DateKeys.formatKey(startKey);
        b.readingProgress = 43;
        b.createdAt = System.currentTimeMillis();
        b.updatedAt = b.createdAt;
        return app().db().bookDao().insert(b);
    }

    @Before
    public void cleanBefore() {
        cleanup();
    }

    @After
    public void cleanAfter() {
        cleanup();
    }

    /** 造的测试书目与打卡全部清除，并逐条打印删除清单 */
    private void cleanup() {
        BookshelfApp app = app();
        List<String> removed = new ArrayList<>();
        for (Book b : app.db().bookDao().getAll()) {
            if (b.title != null && b.title.startsWith(PREFIX)) {
                int n = app.db().checkInDao().getByBook(b.id).size();
                app.db().checkInDao().deleteByBook(b.id);
                app.db().bookDao().deleteById(b.id);
                removed.add(b.title + "(id=" + b.id + ", 关联打卡 " + n + " 条)");
            }
        }
        Log.i(TAG, "验收后删除测试书目：" + removed);
    }

    /** devtest 库被多个测试类共用，断言只看本用例造的这本书 */
    private static int countForBook(List<CheckIn> rows, long bookId) {
        int n = 0;
        for (CheckIn c : rows) {
            if (c.bookId == bookId) {
                n++;
            }
        }
        return n;
    }

    private static CheckIn findForBook(List<CheckIn> rows, long bookId) {
        for (CheckIn c : rows) {
            if (c.bookId == bookId) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void 打卡闭环_今天打卡撤销补卡与拒绝() {
        int today = DateKeys.todayKey();
        long idA = addReadingBook(PREFIX + "A", DateKeys.shiftDayKey(today, -5));
        addReadingBook(PREFIX + "B", DateKeys.shiftDayKey(today, -5));
        Book a = app().db().bookDao().getById(idA);
        String finishBefore = a.readingFinishDate;

        // 今天打卡（只数本用例造的这本书，避免被其它测试类的遗留数据污染）
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(app().db(), a, today, true));
        assertEquals(1, countForBook(app().db().checkInDao().getByDay(today), idA));
        // 第二次点即撤销（幂等）
        assertEquals(CheckInStore.Result.REMOVED,
                CheckInStore.toggle(app().db(), a, today, true));
        assertEquals(0, countForBook(app().db().checkInDao().getByDay(today), idA));

        // 补卡到 3 天前 → backfill=true
        int past = DateKeys.shiftDayKey(today, -3);
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(app().db(), a, past, true));
        CheckIn c = findForBook(app().db().checkInDao().getByDay(past), idA);
        assertNotNull("补卡记录要写库", c);
        assertTrue("补卡标记", c.backfill);
        String summary = CheckInUiState.summaryLine(today / 100,
                new int[]{1, 1, 1}, new int[]{1, past});
        assertTrue("小结要含天数与补卡次数，" + summary,
                summary.contains("已打卡 1 天") && summary.contains("补卡 1 次"));

        // 未来日 / 早于开始阅读日 → 拒绝且不写库
        int before = app().db().checkInDao().countAll();
        assertEquals(CheckInStore.Result.REJECTED_FUTURE,
                CheckInStore.toggle(app().db(), a, DateKeys.shiftDayKey(today, 1), true));
        assertEquals(CheckInStore.Result.REJECTED_BEFORE_START,
                CheckInStore.toggle(app().db(), a, DateKeys.shiftDayKey(today, -10), true));
        assertEquals(before, app().db().checkInDao().countAll());

        // 打卡不改阅读状态机
        Book after = app().db().bookDao().getById(idA);
        assertEquals(Book.READING, after.readStatus);
        assertEquals(finishBefore, after.readingFinishDate);
    }

    @Test
    public void 小结与连续天数按打卡自然日统计() {
        int today = DateKeys.todayKey();
        long id = addReadingBook(PREFIX + "C", DateKeys.shiftDayKey(today, -10));
        Book b = app().db().bookDao().getById(id);
        for (int d : new int[]{-2, -1, 0}) {
            CheckInStore.toggle(app().db(), b, DateKeys.shiftDayKey(today, d), true);
        }
        // 只看本用例造的这本书：共享库里可能有其它测试类留下的历史打卡
        List<CheckIn> all = app().db().checkInDao().getByBook(id);
        int[] streak = CheckInStore.streakDays(all, today);
        assertEquals("连续 3 天", 3, streak[0]);
        assertFalse("今天已打卡，不应待打卡", CheckInStore.pendingToday(all, today));
        int ym = today / 100;
        int[] sum = CheckInStore.monthSummary(all,
                com.bai.bookshelf.util.CheckInMonthGrid.yearOf(ym),
                com.bai.bookshelf.util.CheckInMonthGrid.monthOf(ym));
        assertTrue(CheckInUiState.summaryLine(ym, sum, streak).contains("连续 3 天"));
    }

    @Test
    public void Excel导出含打卡sheet() throws Exception {
        int today = DateKeys.todayKey();
        long id = addReadingBook(PREFIX + "D", DateKeys.shiftDayKey(today, -5));
        CheckInStore.toggle(app().db(), app().db().bookDao().getById(id), today, true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelExporter.exportToStream(app(), out, app().db().bookDao().getAll(), new HashMap<>());
        Set<String> names = new HashSet<>();
        String workbook = "";
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                names.add(e.getName());
                if ("xl/workbook.xml".equals(e.getName())) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] tmp = new byte[1024];
                    int n;
                    while ((n = zis.read(tmp)) > 0) {
                        buf.write(tmp, 0, n);
                    }
                    workbook = buf.toString("UTF-8");
                }
            }
        }
        assertTrue("应有第二张 sheet", names.contains("xl/worksheets/sheet2.xml"));
        assertTrue("workbook 里应有「打卡」", workbook.contains("打卡"));
        assertTrue("content types 要声明 sheet2",
                names.contains("[Content_Types].xml"));
    }

    @Test
    public void 备份恢复后打卡条数一致且预览含打卡() throws Exception {
        int today = DateKeys.todayKey();
        long id = addReadingBook(PREFIX + "E", DateKeys.shiftDayKey(today, -5));
        CheckInStore.toggle(app().db(), app().db().bookDao().getById(id), today, true);
        int before = app().db().checkInDao().countAll();

        File f = new File(app().getCacheDir(), "checkin_acceptance.zip");
        try {
            ZipBackup.createTo(app(), f);
            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue("预览要有打卡条数", info.preview().contains("打卡"));
            assertEquals("meta 里的打卡条数", before, info.meta.checkIns);

            app().db().checkInDao().deleteAll();
            ZipRestore.restore(app(), Uri.fromFile(f), app().db());
            assertEquals("恢复后打卡条数一致", before,
                    app().db().checkInDao().countAll());
        } finally {
            if (f.exists()) {
                assertTrue(f.delete());
            }
        }
    }
}
