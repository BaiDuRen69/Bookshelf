package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 想读清单进备份（v5.9.5 缺口 B）：老包恢复绝不能删掉本机清单，新包整表替换，
 * 「包里 0 条」与「包里没这个键」必须分得清。
 *
 * <p>恢复用内存库跑，devtest 真库一个字节都不动；只有最后一条验打包端的用例
 * 往真库插一条探针想读，finally 按 id 删掉并核对条数回到原值。
 * 包一律写在 cacheDir，用完即删。设置块用越界版本号（99），
 * {@link SettingsBackup#restore} 整块跳过，所以真偏好也不会被改。
 */
@RunWith(AndroidJUnit4.class)
public class WishBookBackupInstrumentedTest {

    private Context ctx;
    private AppDatabase db;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries().build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ==================== 老包兼容：这条是数据丢失闸 ====================

    @Test
    public void 老包不含想读键时本机两条清单一条都不能少() throws Exception {
        db.wishBookDao().insert(wish(11, "本机想读甲"));
        db.wishBookDao().insert(wish(12, "本机想读乙"));
        File f = pack(data(null, false, null), null);
        try {
            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue("自造的包没过校验，后面的断言没意义：" + info.reject, info.ok());
            assertFalse("老包不该报含想读", info.wishesPresent);
            assertEquals("读不出来必须是 -1，不能谎报 0 条", -1, info.wishes);
            assertFalse("老包不该报含设置", info.settingsPresent);
            String preview = info.preview(ctx);
            assertTrue("预览没说清包里没有想读：" + preview,
                    preview.contains(ctx.getString(R.string.backup_preview_wishes_absent)));
            assertTrue("预览没说清包里没有设置：" + preview,
                    preview.contains(ctx.getString(R.string.backup_preview_settings_no)));

            ZipRestore.Result r = ZipRestore.restore(ctx, Uri.fromFile(f), db);
            assertFalse(r.wishesPresent);
            assertEquals(0, r.wishes);
            assertFalse(r.settingsPresent);
            assertFalse(r.settingsApplied);
            assertEquals("恢复老包把本机想读清单删了", 2, db.wishBookDao().count());
            assertEquals(1, r.books);
            List<String> titles = titles();
            assertTrue("本机想读甲没了：" + titles, titles.contains("本机想读甲"));
            assertTrue("本机想读乙没了：" + titles, titles.contains("本机想读乙"));
        } finally {
            assertTrue(f.delete());
        }
    }

    // ==================== 新包：整表替换 ====================

    @Test
    public void 新包带想读时整表替换成包里那两条() throws Exception {
        db.wishBookDao().insert(wish(11, "本机想读甲"));
        File f = pack(data(Arrays.asList(wish(901, "包内想读甲"), wish(902, "包内想读乙")),
                true, null), 2);
        try {
            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue(info.ok());
            assertTrue(info.wishesPresent);
            assertEquals(2, info.wishes);
            assertEquals("meta 里的想读条数", Integer.valueOf(2), info.meta.wishes);
            assertTrue(info.preview(ctx).contains(ctx.getString(R.string.backup_preview_wishes, 2)));

            ZipRestore.Result r = ZipRestore.restore(ctx, Uri.fromFile(f), db);
            assertTrue(r.wishesPresent);
            assertEquals(2, r.wishes);
            assertEquals(2, db.wishBookDao().count());
            List<String> titles = titles();
            assertTrue(titles.contains("包内想读甲"));
            assertTrue(titles.contains("包内想读乙"));
            assertFalse("整包替换没把本机旧条目清掉：" + titles, titles.contains("本机想读甲"));
        } finally {
            assertTrue(f.delete());
        }
    }

    @Test
    public void 新包空清单也要清掉本机旧条目且预览说的是零条() throws Exception {
        db.wishBookDao().insert(wish(11, "本机想读甲"));
        File f = pack(data(Collections.emptyList(), true, null), 0);
        try {
            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue(info.ok());
            assertTrue("空数组也是「包里有这个键」", info.wishesPresent);
            assertEquals("0 条不能被当成读不出来", 0, info.wishes);
            String preview = info.preview(ctx);
            assertTrue(preview.contains(ctx.getString(R.string.backup_preview_wishes, 0)));
            assertFalse("空清单被说成了「包里没有」：" + preview,
                    preview.contains(ctx.getString(R.string.backup_preview_wishes_absent)));

            ZipRestore.Result r = ZipRestore.restore(ctx, Uri.fromFile(f), db);
            assertTrue(r.wishesPresent);
            assertEquals(0, r.wishes);
            assertEquals("包里的空清单没覆盖本机", 0, db.wishBookDao().count());
        } finally {
            assertTrue(f.delete());
        }
    }

    // ==================== 缺口 A：设置有块但没写成，与根本没块分开 ====================

    @Test
    public void 设置块写不进去时报有块但未恢复且真偏好一个字节不动() throws Exception {
        SharedPreferences ui = ctx.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE);
        boolean hadTheme = ui.contains("theme_id");
        int themeBefore = ui.getInt("theme_id", -1);
        db.wishBookDao().insert(wish(11, "本机想读甲"));

        // 版本号越界 ⇒ SettingsBackup.restore 整块跳过（与 SettingsBackupInstrumentedTest 同口径）
        JsonObject settings = new JsonObject();
        settings.addProperty("version", 99);
        settings.add("prefs", new JsonObject());
        File f = pack(data(null, false, settings), null);
        try {
            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue(info.ok());
            assertTrue(info.settingsPresent);
            assertTrue(info.preview(ctx)
                    .contains(ctx.getString(R.string.backup_preview_settings_yes)));

            ZipRestore.Result r = ZipRestore.restore(ctx, Uri.fromFile(f), db);
            assertTrue("有块就该报有块", r.settingsPresent);
            assertFalse("越界版本不该报已恢复", r.settingsApplied);
            assertEquals("设置没恢复也不能连累想读", 1, db.wishBookDao().count());
            assertEquals(hadTheme, ui.contains("theme_id"));
            assertEquals("恢复动了本机主题", themeBefore, ui.getInt("theme_id", -1));
        } finally {
            assertTrue(f.delete());
        }
    }

    // ==================== 打包端：真库真包，探针用完即删 ====================

    @Test
    public void 真实打包端把想读写进包与meta() throws Exception {
        AppDatabase real = BookshelfApp.get().db();
        int before = real.wishBookDao().count();
        String probeTitle = "__wish_probe_" + System.nanoTime();
        long probeId = real.wishBookDao().insert(wish(0, probeTitle));
        File f = new File(ctx.getCacheDir(), "wish_export_probe.zip");
        try {
            BackupMeta meta = ZipBackup.createTo(ctx, f);
            assertEquals("meta 少了想读条数", before + 1, meta.wishes.intValue());

            BackupInspector.Info info = BackupInspector.inspect(f);
            assertTrue("真包没过校验：" + info.reject, info.ok());
            assertTrue("真包里没有 wish_books 键", info.wishesPresent);
            assertEquals(before + 1, info.wishes);
            assertTrue("真包里没有 settings 块", info.settingsPresent);

            JsonArray arr;
            try (InputStream in = new FileInputStream(f)) {
                arr = JsonParser.parseString(BackupInspector.read(in).dataJson)
                        .getAsJsonObject().getAsJsonArray("wish_books");
            }
            assertEquals(before + 1, arr.size());
            boolean found = false;
            for (JsonElement e : arr) {
                if (probeTitle.equals(e.getAsJsonObject().get("title").getAsString())) {
                    found = true;
                }
            }
            assertTrue("包里的 wish_books 没有刚插进去的探针条目", found);
        } finally {
            real.wishBookDao().deleteById(probeId);
            assertEquals("探针没清干净，真库想读条数变了", before, real.wishBookDao().count());
            if (f.exists()) {
                assertTrue(f.delete());
            }
        }
    }

    // ==================== 工具 ====================

    private static WishBook wish(long id, String title) {
        WishBook w = new WishBook();
        w.id = id;
        w.title = title;
        w.author = "想读作者";
        w.isbn = "9787000000002";
        w.priority = WishBook.PRIORITY_HIGH;
        w.expectedPrice = 42;
        w.createdAt = 1700000000000L;
        w.updatedAt = 1700000000000L;
        return w;
    }

    /** data.json：wishes 为 null 且 withWishKey=false ⇒ 老包（整键不存在） */
    private static JsonObject data(List<WishBook> wishes, boolean withWishKey, JsonObject settings) {
        Book b = new Book();
        b.title = "包内藏书";
        b.quantity = 1;
        b.readStatus = Book.READ_NONE;
        b.createdAt = 1700000000000L;
        b.updatedAt = 1700000000000L;
        JsonObject root = new JsonObject();
        root.add("books", new Gson().toJsonTree(Collections.singletonList(b)));
        root.add("check_ins", new JsonArray());
        if (withWishKey) {
            root.add("wish_books", new Gson().toJsonTree(wishes));
        }
        if (settings != null) {
            root.add("settings", settings);
        }
        return root;
    }

    /** 走打包端同一条 writeZip 路径造包，校验和自然是对的 */
    private File pack(JsonObject data, Integer wishesInMeta) throws IOException {
        BackupMeta meta = new BackupMeta();
        meta.format = 1;
        meta.appVersion = BuildConfig.VERSION_NAME;
        meta.appCode = BuildConfig.VERSION_CODE;
        meta.dbVersion = AppDatabase.VERSION;
        meta.createdAt = System.currentTimeMillis();
        meta.books = data.getAsJsonArray("books").size();
        meta.checkIns = 0;
        meta.wishes = wishesInMeta;
        File f = new File(ctx.getCacheDir(), "wish_restore_" + System.nanoTime() + ".zip");
        try (OutputStream out = new FileOutputStream(f)) {
            ZipBackup.writeZip(out, data.toString().getBytes(StandardCharsets.UTF_8),
                    new ArrayList<>(), meta);
        }
        return f;
    }

    private List<String> titles() {
        List<String> out = new ArrayList<>();
        for (WishBook w : db.wishBookDao().getAll()) {
            out.add(w.title);
        }
        return out;
    }
}