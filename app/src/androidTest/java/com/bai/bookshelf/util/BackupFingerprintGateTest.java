package com.bai.bookshelf.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 自动备份前置指纹的判定闸（v5.8.21 评审 #7）：
 * 手动备份成功后指纹应记下来、内容没变时自动备份可以整个跳过；一加书就必须照常打包。
 *
 * <p>用真库真备份目录跑（devtest 独立包，不与正式版数据相混），
 * 测试自己新建的备份文件与探针书籍用完即删，已存在的备份一份不动。
 */
@RunWith(AndroidJUnit4.class)
public class BackupFingerprintGateTest {

    /** 与 BackupManager 私有的 PREFS / KEY_CONTENT_FP 对齐（改了那边这里会立刻测挂） */
    private static final String PREFS = "backup_prefs";
    private static final String KEY_CONTENT_FP = "content_fp";

    @Test
    public void 内容变了就必须重新打包() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        AppDatabase db = BookshelfApp.get().db();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fpBefore = p.getString(KEY_CONTENT_FP, null);
        Set<String> before = backupNames(ctx);

        // 探针书：唯一标题，测完按标题删掉
        Book probe = new Book();
        probe.title = "__fp_probe_" + System.currentTimeMillis();
        probe.author = "指纹测试";
        probe.isbn = "9787000000001";
        long probeId = db.bookDao().insert(probe);
        try {
            String fp1 = BackupFingerprint.of(ctx, db);
            assertNotNull("指纹算不出来（这种状态自动备份会照常打包，不会漏）", fp1);
            assertFalse("刚改过库，绝不能跳过打包", BackupManager.canSkipAuto(ctx, fp1));

            // 走一次真实手动备份：成功后必须把「这次的内容指纹」记下来
            BackupManager.Outcome o = BackupManager.backupNow(ctx);
            assertFalse("手动备份失败：" + o.failed, o.failed);
            assertNotNull("手动备份没产出 meta", o.meta);
            String fp2 = BackupFingerprint.of(ctx, db);
            assertTrue("备份成功后指纹没记上（下次自动备份就白打一次包）",
                    BackupManager.canSkipAuto(ctx, fp2));

            // 再加一本书：指纹必须变，判定必须翻回「要打包」
            Book second = new Book();
            second.title = probe.title + "_2";
            second.author = "指纹测试";
            long secondId = db.bookDao().insert(second);
            String fp3 = BackupFingerprint.of(ctx, db);
            assertFalse("新增一本书后还能跳过，指纹不敏感", BackupManager.canSkipAuto(ctx, fp3));
            db.bookDao().deleteById(secondId);
        } finally {
            db.bookDao().deleteById(probeId);
            for (File f : new File(ctx.getFilesDir(), "backups").listFiles()) {
                if (!before.contains(f.getName())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // 只删这次新建的备份
                }
            }
            SharedPreferences.Editor e = p.edit();
            if (fpBefore == null) {
                e.remove(KEY_CONTENT_FP);
            } else {
                e.putString(KEY_CONTENT_FP, fpBefore);
            }
            e.apply();
        }
    }

    private Set<String> backupNames(Context ctx) {
        Set<String> names = new HashSet<>();
        File[] files = new File(ctx.getFilesDir(), "backups").listFiles();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName());
            }
        }
        return names;
    }
}
