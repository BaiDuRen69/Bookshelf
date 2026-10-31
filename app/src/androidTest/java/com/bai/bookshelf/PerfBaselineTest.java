package com.bai.bookshelf;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.util.SearchIndex;
import com.bai.bookshelf.util.SearchText;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 性能基线（v5.8.20）：1000 本规模下的热点——按 bookId 查子表、全量重建搜索索引、
 * 只重算一本。加索引 / 改增量前后各跑一次，同一份数据对比，不凭感觉说变快了。
 *
 * <p>只建自己的临时库（cacheDir/perf_baseline.db），不碰主库、不起界面；
 * 结果写 cacheDir/perf_baseline.txt 并打 logcat（TAG=PerfBaseline）。
 */
@RunWith(AndroidJUnit4.class)
public class PerfBaselineTest {

    private static final String TAG = "PerfBaseline";
    private static final int BOOKS = 1000;
    private static final int QUERIES = 500;

    private final StringBuilder report = new StringBuilder();

    private void line(String name, long nanos, int times) {
        double ms = nanos / 1_000_000.0;
        report.append(name).append(": ")
                .append(String.format(java.util.Locale.US, "%.1f ms", ms))
                .append(String.format(java.util.Locale.US, "  (avg %.3f ms/次, n=%d)%n",
                        ms / times, times));
    }

    private void raw(String text) {
        report.append(text).append('\n');
    }

    @Test
    public void 千本规模热点基线() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        String dbName = "perf_baseline.db";
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File f = ctx.getDatabasePath(dbName + suffix);
            if (f.exists() && !f.delete()) {
                throw new IllegalStateException("清不掉旧临时库：" + f);
            }
        }
        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, dbName).build();
        try {
            seedAndMeasure(db);
        } finally {
            db.close();
        }
        try (FileWriter writer = new FileWriter(
                new File(ctx.getCacheDir(), "perf_baseline.txt"))) {
            writer.write(report.toString());
        }
        Log.i(TAG, report.toString());
    }

    private void seedAndMeasure(AppDatabase db) {
        long now = System.currentTimeMillis();
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < BOOKS; i++) {
            Book book = new Book();
            book.title = "测试书" + i + " · 百年孤独与同类长篇";
            book.author = (i % 7 == 0 ? "" : "作者") + "某" + (i % 40);
            book.publisher = "测试出版社" + (i % 12);
            book.isbn = String.format(java.util.Locale.US, "97871%08d", i);
            book.tags = i % 3 == 0 ? "文学,中国" : "历史";
            book.pages = 180 + (i % 5) * 120;
            book.rating = i % 5;
            book.readStatus = i % 4 == 0 ? Book.READ_DONE : Book.READ_NONE;
            book.createdAt = now - i * 1000L;
            book.updatedAt = now;
            book.id = db.bookDao().insert(book);
            books.add(book);
        }
        List<BookCopy> copies = new ArrayList<>();
        List<NoteImage> images = new ArrayList<>();
        List<Excerpt> excerpts = new ArrayList<>();
        List<BorrowRecord> records = new ArrayList<>();
        for (Book book : books) {
            for (int c = 1; c <= 2; c++) {
                BookCopy copy = new BookCopy();
                copy.bookId = book.id;
                copy.copyIndex = c;
                copy.ownType = Book.OWN_BUY;
                copy.stockStatus = Book.STOCK_IN;
                copies.add(copy);
            }
            NoteImage img = new NoteImage();
            img.bookId = book.id;
            img.ownerKey = "book:" + book.id;
            img.path = "/dev/null";
            img.sort = 0;
            images.add(img);
            Excerpt e = new Excerpt();
            e.bookId = book.id;
            e.content = "这是一段足够长的摘抄内容，用来模拟真实文本长度，包含标点与换行。\n第二行";
            e.note = "批注若干";
            e.tags = "摘抄标签";
            e.createdAt = now;
            excerpts.add(e);
            BorrowRecord rec = new BorrowRecord();
            rec.bookId = book.id;
            rec.borrower = "借阅人" + (int) (book.id % 9);
            rec.lendDate = "2026-09-01";
            rec.dueDate = book.id % 2 == 0 ? "2026-09-20" : "2099-01-01";
            rec.status = book.id % 2 == 0 ? BorrowRecord.STATUS_RETURNED
                    : BorrowRecord.STATUS_OUT;
            rec.copyIndex = 1;
            records.add(rec);
        }
        db.bookCopyDao().insertAll(copies);
        db.noteImageDao().insertAll(images);
        for (Excerpt e : excerpts) {
            db.excerptDao().insert(e);
        }
        for (BorrowRecord rec : records) {
            db.borrowRecordDao().insert(rec);
        }
        assertEquals(BOOKS, db.bookDao().count());

        raw("规模：" + BOOKS + " 本 / " + copies.size() + " 副本 / "
                + excerpts.size() + " 摘抄 / " + records.size() + " 借还");
        raw("索引：" + indexesOf(ApplicationProvider.getApplicationContext()));

        int sink = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            sink += db.bookCopyDao().getByBook(books.get((i * 37) % BOOKS).id).size();
        }
        line("bookCopy.getByBook", System.nanoTime() - t0, QUERIES);

        t0 = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            sink += db.noteImageDao()
                    .getByOwnerKey("book:" + books.get((i * 53) % BOOKS).id).size();
        }
        line("noteImage.getByOwnerKey", System.nanoTime() - t0, QUERIES);

        t0 = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            db.borrowRecordDao().findOpenByBook(books.get((i * 71) % BOOKS).id);
        }
        line("borrow.findOpenByBook", System.nanoTime() - t0, QUERIES);

        t0 = System.nanoTime();
        int open = db.borrowRecordDao().getAllOpen().size();
        line("borrow.getAllOpen", System.nanoTime() - t0, 1);

        t0 = System.nanoTime();
        Map<Long, String> index = SearchIndex.build(books, excerpts);
        line("SearchIndex.build 全量", System.nanoTime() - t0, 1);
        raw("索引条目：" + index.size());

        t0 = System.nanoTime();
        for (int i = 0; i < QUERIES; i++) {
            Book book = books.get((i * 17) % BOOKS);
            index.put(book.id, SearchText.haystack(book, "这是一段足够长的摘抄内容"));
        }
        line("单本重算", System.nanoTime() - t0, QUERIES);

        raw("校验 sink=" + sink + " open=" + open);
        assertTrue("拼音转写没生效（JVM 上才会这样）",
                !index.isEmpty());
    }

    /**
     * 当前库里显式建立的索引（Room @Index 会生成 index_&lt;表&gt;_&lt;列&gt;）。
     * 用框架自带的 SQLiteDatabase 只读打开，不额外引 sqlite 依赖。
     */
    private String indexesOf(Context ctx) {
        StringBuilder sb = new StringBuilder();
        android.database.sqlite.SQLiteDatabase sdb =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        ctx.getDatabasePath("perf_baseline.db").getAbsolutePath(),
                        null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        Cursor c = sdb.rawQuery("SELECT tbl_name, name FROM sqlite_master "
                + "WHERE type='index' AND name NOT LIKE 'sqlite_auto%' ORDER BY tbl_name", null);
        try {
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append('.').append(c.getString(1)).append("  ");
            }
        } finally {
            c.close();
            sdb.close();
        }
        return sb.length() == 0 ? "（一个都没有）" : sb.toString();
    }
}
