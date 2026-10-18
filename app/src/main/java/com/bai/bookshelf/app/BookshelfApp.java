package com.bai.bookshelf.app;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.room.Room;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Country;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.Publisher;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.util.BackupManager;
import com.bai.bookshelf.util.BookSanitizer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application 入口：持有数据库单例与后台线程池，
 * 所有 DB/网络耗时操作统一走 {@link #io(Runnable)}，回主线程用 {@link #ui(Runnable)}。
 */
public class BookshelfApp extends Application {

    /** 首次启动时间戳的 SharedPreferences 名与键（v2.9「与小书房相伴了多少天」统计用） */
    private static final String PREFS_APP = "app_prefs";
    private static final String KEY_FIRST_LAUNCH_AT = "first_launch_at";

    private static BookshelfApp instance;

    private AppDatabase db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static BookshelfApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 记录首次打开 App 的时间（只写一次，老用户升级后记为近似值）
        SharedPreferences prefs = appPrefs();
        if (!prefs.contains(KEY_FIRST_LAUNCH_AT)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH_AT, System.currentTimeMillis()).apply();
        }
        db = Room.databaseBuilder(this, AppDatabase.class, "bookshelf.db")
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                        AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                        AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11,
                        AppDatabase.MIGRATION_11_12) // 保数据迁移
                // v4.0（评审 #1）：兜底策略收紧。fallbackToDestructiveMigration 会在
                // 迁移路径覆盖不到时（典型：用旧包覆盖安装新版造成降级）直接删表重建、
                // 藏书数据静默全失——对个人藏书管理 App 不可接受。改为仅在「降级」时
                // 重建：正常升级失败会抛异常暴露问题，而不是悄悄抹掉数据。
                .fallbackToDestructiveMigrationOnDowngrade()
                .build();
        ensureDefaultData();
        // 开启自动备份后，每次启动静默备份一次
        BackupManager.autoBackupIfEnabled(this);
    }

    public AppDatabase db() {
        return db;
    }

    /** 首次打开 App 的时间戳（0 表示尚未记录，理论上不会发生） */
    public static long firstLaunchAt() {
        return instance.appPrefs().getLong(KEY_FIRST_LAUNCH_AT, 0);
    }

    private SharedPreferences appPrefs() {
        return getSharedPreferences(PREFS_APP, MODE_PRIVATE);
    }

    /** 在后台线程执行 */
    public void io(Runnable r) {
        io.execute(r);
    }

    /** 切回主线程执行 */
    public void ui(Runnable r) {
        mainHandler.post(r);
    }

    /** 首次启动时写入默认数据：一个默认存放地点 + 各来源大类的预置二级选项 + 从存量书籍收编各字典库 */
    private void ensureDefaultData() {
        io(() -> {
            if (db.locationDao().count() == 0) {
                Location loc = new Location();
                loc.name = "默认书架";
                loc.sort = 0;
                db.locationDao().insert(loc);
            }
            // 每个来源大类独立检查、独立补种（老设备升级也会补齐缺失的分组）
            seedChannels(0, new String[]{"拼多多", "淘宝", "京东", "当当", "多抓鱼", "线下书店"});
            seedChannels(1, new String[]{"图书馆", "朋友", "学校"});
            seedChannels(2, new String[]{"鉴书团", "亲友赠送", "活动赠书"});
            harvestTags();
            harvestCategories();
            harvestCountries();
            harvestPublishers();
        });
    }

    /** 读取全部书籍（已做空安全清洗），供各 harvest 使用 */
    private List<Book> books() {
        return BookSanitizer.normalizeAll(db.bookDao().getAll());
    }

    /** 从书籍里取一个字典字段 */
    private interface FieldExtractor {
        String extract(Book book);
    }

    /** 把名称与排序序号写进某个字典库 */
    private interface LibraryInserter {
        void insert(String name, int sort);
    }

    /**
     * 收编单个字典库的通用流程：库为空时，把存量书籍上的字段值去重后按出现顺序写入。
     *
     * @param libraryCount 当前库条目数；&gt;0 表示已初始化，直接跳过
     * @param extractor    从书籍取字段值
     * @param inserter     写入库（传入名称与 sort 序号）
     * @param afterInsert  收编完成后的一次性收尾（如预置项），可为 null
     */
    private void harvest(int libraryCount, FieldExtractor extractor,
                         LibraryInserter inserter, Runnable afterInsert) {
        if (libraryCount > 0) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Book b : books()) {
            String s = extractor.extract(b).trim();
            if (!s.isEmpty()) {
                names.add(s);
            }
        }
        int i = 0;
        for (String n : names) {
            inserter.insert(n, i++);
        }
        if (afterInsert != null) {
            afterInsert.run();
        }
    }

    /**
     * 把存量书籍上的逗号标签收编进标签库（仅标签库为空时执行一次）。
     * 标签是<b>逗号分隔的多值字段</b>，需先拆分再收编，因此不走单值字段的通用 harvest。
     */
    private void harvestTags() {
        if (db.tagDao().count() > 0) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Book b : books()) {
            for (String t : b.tags.split(",")) {
                String s = t.trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
        }
        int i = 0;
        for (String n : names) {
            Tag tag = new Tag();
            tag.name = n;
            tag.sort = i++;
            db.tagDao().insert(tag);
        }
    }

    /** 把存量书籍上的分类收编进分类库（仅分类库为空时执行一次） */
    private void harvestCategories() {
        harvest(db.categoryDao().count(), b -> b.category, (name, sort) -> {
            Category cat = new Category();
            cat.name = name;
            cat.sort = sort;
            db.categoryDao().insert(cat);
        }, null);
    }

    /** 把存量书籍上的国家/地区收编进国家库（仅国家库为空时执行一次） */
    private void harvestCountries() {
        harvest(db.countryDao().count(), b -> b.country, (name, sort) -> {
            Country c = new Country();
            c.name = name;
            c.sort = sort;
            db.countryDao().insert(c);
        }, BookshelfApp.this::presetCountriesIfEmpty);
    }

    /** 全新安装（无存量数据）时预置一批常用国家/地区，方便选择器直接点选 */
    private void presetCountriesIfEmpty() {
        if (db.countryDao().count() > 0) {
            return;
        }
        String[] presets = {"中国", "中国台湾", "中国香港", "日本", "美国", "英国",
                "法国", "德国", "俄罗斯", "韩国"};
        for (int k = 0; k < presets.length; k++) {
            Country c = new Country();
            c.name = presets[k];
            c.sort = k;
            db.countryDao().insert(c);
        }
    }

    /** 把存量书籍上的出版社收编进出版社库（仅出版社库为空时执行一次） */
    private void harvestPublishers() {
        harvest(db.publisherDao().count(), b -> b.publisher, (name, sort) -> {
            Publisher p = new Publisher();
            p.name = name;
            p.sort = sort;
            db.publisherDao().insert(p);
        }, null);
    }

    /** 某大类下没有任何选项时，写入预置项 */
    private void seedChannels(int ownType, String[] names) {
        if (db.sourceChannelDao().countByType(ownType) > 0) {
            return;
        }
        for (int i = 0; i < names.length; i++) {
            SourceChannel ch = new SourceChannel();
            ch.name = names[i];
            ch.ownType = ownType;
            ch.sort = i;
            db.sourceChannelDao().insert(ch);
        }
    }
}
