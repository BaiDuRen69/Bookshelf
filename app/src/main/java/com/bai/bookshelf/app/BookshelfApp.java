package com.bai.bookshelf.app;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
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
    // v4.4：后台维护线程，与 io 分离。启动期的长任务（默认数据补种、自动备份
    // 整库 zip——含全部封面图片的压缩）原先和书架首屏查询挤同一个 io 线程，
    // 「正在加载书架」会一直转到备份结束。现在长任务走 bg，io 专职 UI 驱动的短查询。
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 数据库已成功打开过（修复期间为 false，页面据此跳过注册与首查） */
    private volatile boolean dbReady;
    /** 等库就绪再执行的页面回调（冷启动时 Fragment 早于开库完成） */
    private final java.util.List<Runnable> dbReadyCallbacks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static BookshelfApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // v4.19：装全局崩溃兜底，把未捕获异常的堆栈写进 files/crash.log，
        // 用户可在「我的 → 设置 → 崩溃日志」里看到并复制出来反馈
        com.bai.bookshelf.util.CrashHandler.install(this);
        // v4.54：应用锁。新进程从锁定状态开始；所有 Activity 都停止时记录后台时间
        com.bai.bookshelf.util.AppLock.onProcessStart();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private int started = 0;

            @Override
            public void onActivityStarted(Activity activity) {
                started++;
            }

            @Override
            public void onActivityStopped(Activity activity) {
                started = Math.max(0, started - 1);
                if (started == 0 && !activity.isChangingConfigurations()) {
                    com.bai.bookshelf.util.AppLock.onAppBackgrounded(activity);
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
        // 记录首次打开 App 的时间（只写一次，老用户升级后记为近似值）
        SharedPreferences prefs = appPrefs();
        if (!prefs.contains(KEY_FIRST_LAUNCH_AT)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH_AT, System.currentTimeMillis()).apply();
        }
        db = buildDb();
        // v5.8.3：上次恢复在「换文件」窗口里被杀过，库文件处于未知状态。
        // 见到标记不冒险开库，直接把用户送到修复页（那里会读副本描述与坏库路径）。
        if (com.bai.bookshelf.util.DbRepair.isPending(this)) {
            com.bai.bookshelf.util.DbRepair.restoreBrokenState(this);
            ui(this::openDatabaseRepair);
            return;
        }
        // v5.8：Room 是懒打开的，迁移失败原先只会在某个后台查询里抛出来，
        // 用户看到的是空书架或闪退。这里主动开一次库把失败接住，直接进修复页。
        bg(() -> {
            // v5.8.12：升级前副本必须在「Room 第一次打开」之前，但不必在主线程——
            // 大库同步复制是升级后首启的 ANR 候选。这里先复制再开库，顺序不变，
            // 主线程只是把任务丢进 bg 就返回。
            int versionBefore = -1;
            try {
                versionBefore = com.bai.bookshelf.util.PreMigrationBackup
                        .prepareBeforeOpen(this);
            } catch (Throwable ignored) {
                // 副本失败不阻断启动（与旧行为一致），继续开库
            }
            final int dbVersionBefore = versionBefore;
            try {
                db.getOpenHelper().getWritableDatabase();
            } catch (Throwable t) {
                com.bai.bookshelf.util.DbRepair.markBroken(t);
                ui(this::openDatabaseRepair);
                return;
            }
            markDbReady();
            // 只有真正打开成功才记版本：上次如果迁移失败，这次启动才仍会留升级前副本
            com.bai.bookshelf.util.PreMigrationBackup.markOpened(this, AppDatabase.VERSION);
            if (com.bai.bookshelf.util.PreMigrationBackup.migrated(
                    dbVersionBefore, AppDatabase.VERSION)) {
                // 迁移成功后立刻做一次整包备份（无视「每天一次」），
                // 让「升级后的第一次启动」就有一个新 schema 的可恢复快照
                mainHandler.postDelayed(() -> bg(() -> BackupManager.backupNow(this)), 6000);
            }
            ensureDefaultData();
            // v5.8.8：应用启动后顺手刷新桌面小组件（数据可能已经变了）
            com.bai.bookshelf.ui.widget.ReadingWidgetProvider.refresh(this);
            // v4.42：每天 9:00 检查一次借阅到期（进程被杀也由闹钟拉起）
            com.bai.bookshelf.util.BorrowReminder.schedule(this);
            // 第 4.4 步：每日打卡提醒（默认关，schedule 内部会自行 cancel 闹钟）
            com.bai.bookshelf.util.CheckInReminder.schedule(this);
            // v4.4：自动备份延后 3 秒再跑——整库 JSON + 全部封面图打 zip 是启动期最重的
            // 任务，冷启动先让路给首屏渲染，避免磁盘 I/O 与 Glide 加载封面互相争抢
            mainHandler.postDelayed(() -> BackupManager.autoBackupIfEnabled(this), 3000);
            // v5.8.20：存量图片一次性压缩（老版本是原样复制相机图的）。排在首屏与
            // 自动备份之后，跑一次就记下来；压不动的图会原样保留，不会丢封面。
            if (!com.bai.bookshelf.util.ImageMigration.alreadyDone(this)) {
                mainHandler.postDelayed(() -> bg(() -> com.bai.bookshelf.util.ImageMigration
                        .runIfNeeded(getApplicationContext())), 12000);
            }
        });
    }

    /** 按当前约定建库（迁移清单在这里，别处不要再 new） */
    private AppDatabase buildDb() {
        return Room.databaseBuilder(this, AppDatabase.class, AppDatabase.DB_NAME)
                .addMigrations(AppDatabase.migrations()) // 保数据迁移
                // v4.0（评审 #1）：兜底策略收紧。fallbackToDestructiveMigration 会在
                // 迁移路径覆盖不到时（典型：用旧包覆盖安装新版造成降级）直接删表重建、
                // 藏书数据静默全失——对个人藏书管理 App 不可接受。改为仅在「降级」时
                // 重建：正常升级失败会抛异常暴露问题，而不是悄悄抹掉数据。
                .fallbackToDestructiveMigrationOnDowngrade()
                .build();
    }

    /** 修复页用：关掉当前库，文件才搬得动 */
    public synchronized void closeDatabase() {
        dbReady = false;
        try {
            db.close();
        } catch (Exception ignored) {
            // 库本来就打不开时 close 也可能抛，这里只求把句柄放掉
        }
    }

    /** 修复页用：换一个干净的 Room 实例（旧实例的失败状态会被缓存，不能复用） */
    public synchronized void reopenDatabase() {
        db = buildDb();
    }

    /**
     * v5.8.3：数据库是否已经成功打开过一次。
     *
     * <p>页面在注册 LiveData / 首次查询前先问一句，库没就绪就什么都不做——
     * 否则修复页还在前台，书架和统计页会抢着打开坏库、各自抛一遍异常。
     */
    public static boolean dbReady() {
        return instance != null && instance.dbReady;
    }

    /**
     * 库就绪后执行（已就绪则立刻回主线程执行）。
     *
     * <p>冷启动时 Fragment 的 onViewCreated 早于后台开库完成，直接跳过注册会让
     * 书架永远空白（show/hide 切 Tab 不会重建 Fragment），所以这里排队等一次。
     */
    public void whenDbReady(Runnable r) {
        if (dbReady) {
            ui(r);
        } else {
            dbReadyCallbacks.add(r);
        }
    }

    /** 数据库打开成功（启动自检或修复后重开）：放行排队中的页面回调 */
    public void markDbReady() {
        dbReady = true;
        for (Runnable r : dbReadyCallbacks) {
            ui(r);
        }
        dbReadyCallbacks.clear();
    }

    /** 迁移失败后把用户带到修复页，而不是让他对着空书架 */
    private void openDatabaseRepair() {
        android.content.Intent intent = new android.content.Intent(this,
                com.bai.bookshelf.ui.repair.DatabaseRepairActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
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

    /** 在后台线程执行（UI 驱动的短查询） */
    public void io(Runnable r) {
        io.execute(guard(r));
    }

    /** 在后台维护线程执行（v4.4：启动期长任务专用，不阻塞 UI 查询的 io 线程） */
    public void bg(Runnable r) {
        bg.execute(guard(r));
    }

    /**
     * v5.8：数据库打不开时，各页面的查询会接二连三地抛异常。
     *
     * <p>以前这些异常会变成闪退，用户连「修复」的入口都看不到。这里在提交时统一兜一层：
     * 认得出是数据库本身的问题就顺手拉起修复页并吞掉异常；其他异常原样抛给默认处理器，
     * 保持原来的崩溃可见性。
     */
    private Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                String hint = com.bai.bookshelf.util.DbFailure.userMessage(e);
                if (hint != null) {
                    ui(() -> android.widget.Toast.makeText(this, hint,
                            android.widget.Toast.LENGTH_LONG).show());
                    return;
                }
                // 修复期间所有后台异常都是预期的：库还没换回来，查询必然失败
                if (com.bai.bookshelf.util.DbRepair.isRepairing()
                        || com.bai.bookshelf.util.DbRepair.isBroken()) {
                    return;
                }
                if (!com.bai.bookshelf.util.DbFailure.isDatabaseBroken(e)) {
                    throw e;
                }
                com.bai.bookshelf.util.DbRepair.markBroken(e);
                ui(this::openDatabaseRepair);
            }
        };
    }

    /** 切回主线程执行 */
    public void ui(Runnable r) {
        mainHandler.post(r);
    }

    /** 在后台维护线程写入默认数据：默认分类树 + 一个默认存放地点 + 各来源大类的预置二级选项 + 从存量书籍收编各字典库 */
    private void ensureDefaultData() {
        bg(() -> {
            // v4.8：先补种默认二级分类树（幂等，已存在的名字跳过；升级用户与
            // 旧 zip 恢复后的老平铺分类会保留为一级），再走既有的字典收编逻辑
            com.bai.bookshelf.util.CategorySeeder.seedIfMissing(db);
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
