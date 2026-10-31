package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CategoryPropagator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 详情页的数据加载（v5.6 从 BookDetailActivity 抽出）：
 * 书籍、地点、借出记录、逐本信息、地点名、备注图片、摘抄、分类展示串一次取全。
 * 书籍已被删除时回调 null。
 */
public final class DetailDataLoader {

    public static final class Data {
        public Book book;
        public Location location;
        public List<BorrowRecord> openRecords = new ArrayList<>();
        public int recordCount;
        public List<BookCopy> copies = new ArrayList<>();
        public Map<Long, String> copyLocNames = new HashMap<>();
        public List<NoteImage> noteImages = new ArrayList<>();
        public List<Excerpt> excerpts = new ArrayList<>();
        public Map<Integer, List<NoteImage>> copyLendImages = new HashMap<>();
        public String categoryDisplay = "";
    }

    public interface Callback {
        void onLoaded(Data data);
    }

    private DetailDataLoader() {
    }

    /** io 线程查询，主线程回调 */
    public static void load(BookshelfApp app, long bookId, Callback callback) {
        app.io(() -> {
            Book fresh = BookSanitizer.normalize(app.db().bookDao().getById(bookId));
            if (fresh != null) {
                // 详情页使用汇总字段/评分：只在单本加载时按 JSON 校正一次
                com.bai.bookshelf.util.ReadingRecords.refreshSummary(fresh);
                com.bai.bookshelf.util.BorrowSourceRecords.refreshSummary(fresh);
            }
            if (fresh == null) { // 已被删除
                app.ui(() -> callback.onLoaded(null));
                return;
            }
            Data d = new Data();
            d.book = fresh;
            d.location = fresh.locationId == null
                    ? null : app.db().locationDao().getById(fresh.locationId);
            // v4.39：查询收敛。借出记录一次取全再在内存里拆「借出中 / 总数」；
            // 地点一次取全；备注图一次取全按 ownerKey 分组——原先是逐本、逐条记录
            // 各查一次（N+1），套装书要发几十条查询。
            List<BorrowRecord> allRecords = app.db().borrowRecordDao().getByBook(bookId);
            d.recordCount = allRecords.size();
            for (BorrowRecord r : allRecords) {
                if (r.status == BorrowRecord.STATUS_OUT) {
                    d.openRecords.add(r);
                }
            }
            // 逐本信息 + 各地点名称解析
            d.copies = app.db().bookCopyDao().getByBook(bookId);
            for (Location l : app.db().locationDao().getAll()) {
                d.copyLocNames.put(l.id, l.name);
            }
            for (BookCopy c : d.copies) {
                if (c.locationId != null && !d.copyLocNames.containsKey(c.locationId)) {
                    d.copyLocNames.put(c.locationId, "未设置地点"); // 地点已被删除
                }
            }
            // 备注图片（书级 + 各借出记录）一次取全
            Map<String, List<NoteImage>> imgsByOwner = new HashMap<>();
            for (NoteImage img : app.db().noteImageDao().getByBook(bookId)) {
                List<NoteImage> list = imgsByOwner.get(img.ownerKey);
                if (list == null) {
                    list = new ArrayList<>();
                    imgsByOwner.put(img.ownerKey, list);
                }
                list.add(img);
            }
            List<NoteImage> foundImgs = imgsByOwner.get("book:" + bookId);
            d.noteImages = foundImgs == null ? new ArrayList<>() : foundImgs;
            d.excerpts = app.db().excerptDao().getByBook(bookId);
            // v4.9：分类展示格式「一级-二级」（书里只存名字，需查分类库解析上级）
            d.categoryDisplay = CategoryPropagator.displayPath(
                    fresh.category, app.db().categoryDao().getAll());
            // 加载每本当前借出记录的备注图片
            for (BorrowRecord r : d.openRecords) {
                List<NoteImage> list = imgsByOwner.get("borrow:" + r.id);
                if (list != null && !list.isEmpty()) {
                    d.copyLendImages.put(r.copyIndex, list);
                }
            }
            app.ui(() -> callback.onLoaded(d));
        });
    }
}
