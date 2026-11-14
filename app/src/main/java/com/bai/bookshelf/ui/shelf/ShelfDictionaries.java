package com.bai.bookshelf.ui.shelf;

import android.util.Log;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.data.entity.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架第二段渲染的字典数据：地点 / 标签 / 分类 / 来源库 / 国家与渠道去重 / 未归还借出记录。
 * 每项独立容错，查询失败退回空列表（不影响藏书展示）。
 */
public final class ShelfDictionaries {

    private static final String TAG = "ShelfDictionaries";

    private ShelfDictionaries() {
    }

    public static final class Data {
        public List<Location> locations = new ArrayList<>();
        public List<Tag> tags = new ArrayList<>();
        public List<Category> categories = new ArrayList<>();
        /** 来源渠道库全量；查询失败为 null（保留调用方旧值） */
        public List<SourceChannel> channelRows;
        public List<String> countries = new ArrayList<>();
        public List<String> channels = new ArrayList<>();
        public List<BorrowRecord> openLoans = new ArrayList<>();
    }

    public interface Callback {
        void onLoaded(Data data);
    }

    /** io 线程查询，主线程回调 */
    public static void loadAsync(BookshelfApp app, Callback callback) {
        app.io(() -> {
            Data d = new Data();
            try {
                d.locations = app.db().locationDao().getAll();
            } catch (Exception e) {
                Log.w(TAG, "locationDao.getAll failed: " + e.getClass().getSimpleName());
            }
            try {
                d.tags = app.db().tagDao().getAll();
            } catch (Exception e) {
                Log.w(TAG, "tagDao.getAll failed: " + e.getClass().getSimpleName());
            }
            try {
                d.categories = app.db().categoryDao().getAll();
            } catch (Exception e) {
                Log.w(TAG, "categoryDao.getAll failed: " + e.getClass().getSimpleName());
            }
            try {
                d.channelRows = app.db().sourceChannelDao().getAll();
            } catch (Exception e) {
                Log.w(TAG, "sourceChannelDao.getAll failed: " + e.getClass().getSimpleName());
            }
            // 国家/渠道去重交给 SQL（见 BookDao）
            try {
                d.countries = app.db().bookDao().getDistinctCountries();
            } catch (Exception e) {
                Log.w(TAG, "bookDao.getDistinctCountries failed: " + e.getClass().getSimpleName());
            }
            try {
                d.channels = app.db().bookDao().getDistinctChannels();
            } catch (Exception e) {
                Log.w(TAG, "bookDao.getDistinctChannels failed: " + e.getClass().getSimpleName());
            }
            try {
                d.openLoans = app.db().borrowRecordDao().getAllOpen();
            } catch (Exception e) {
                Log.w(TAG, "borrowRecordDao.getAllOpen failed: " + e.getClass().getSimpleName());
            }
            if (d.locations == null) {
                d.locations = new ArrayList<>();
            }
            if (d.tags == null) {
                d.tags = new ArrayList<>();
            }
            if (d.categories == null) {
                d.categories = new ArrayList<>();
            }
            if (d.countries == null) {
                d.countries = new ArrayList<>();
            }
            if (d.channels == null) {
                d.channels = new ArrayList<>();
            }
            if (d.openLoans == null) {
                d.openLoans = new ArrayList<>();
            }
            final Data loaded = d;
            app.ui(() -> callback.onLoaded(loaded));
        });
    }
}
