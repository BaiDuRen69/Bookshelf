package com.bai.bookshelf.ui.scan;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityScanBinding;
import com.bai.bookshelf.util.BookSanitizer;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫码页的盘点模式（v4.46）：扫到的书只在内存里计数，结束后算出缺失，
 * 可把缺失的书一键标记为遗失。借出中 / 遗失的书不参与盘点。
 */
public class ScanInventoryMode {

    private final ScanActivity activity;
    private final ActivityScanBinding b;
    private final Runnable onScanConsumed; // 一秒后恢复扫码待机

    private boolean active = false;
    private final Set<String> scanned = new HashSet<>();
    private int unknown = 0;
    /** v5.8.18：从书架二维码进来时只盘这一个地点（-1 = 全盘） */
    private long locationId = -1;

    public ScanInventoryMode(ScanActivity activity, ActivityScanBinding b,
                             Runnable onScanConsumed) {
        this.activity = activity;
        this.b = b;
        this.onScanConsumed = onScanConsumed;
    }

    /** 按 Intent 配置盘点模式；返回是否启用 */
    public boolean setup() {
        active = activity.getIntent().getBooleanExtra(ScanActivity.EXTRA_INVENTORY, false);
        if (!active) {
            return false;
        }
        b.btnBatch.setVisibility(View.GONE);
        b.btnInventoryDone.setVisibility(View.VISIBLE);
        b.tvScanTitle.setText("盘点模式");
        b.tvScanSub.setText("扫描书房里的书 · 扫完点「结束盘点」");
        b.tvScanHint.setText("借出中 / 遗失的书不计入盘点");
        b.btnInventoryDone.setOnClickListener(v -> showResult());
        locationId = activity.getIntent().getLongExtra(ScanActivity.EXTRA_LOCATION_ID, -1);
        if (locationId > 0) {
            showScopedHint();
        }
        updateCounter();
        return true;
    }

    /** 只盘一个地点：把地点名显示出来，逐架扫的时候知道自己数到哪一排 */
    private void showScopedHint() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            String name = null;
            try {
                com.bai.bookshelf.data.entity.Location loc =
                        app.db().locationDao().getById(locationId);
                if (loc != null) {
                    name = loc.name;
                }
            } catch (Exception ignored) {
            }
            final String fName = name;
            app.ui(() -> {
                b.tvScanTitle.setText("盘点 · " + (fName == null ? "该地点" : fName));
                b.tvScanSub.setText("只数这一处的书 · 扫完点「结束盘点」");
            });
        });
    }

    public boolean isActive() {
        return active;
    }

    /** 盘点扫到一本：查库判断是否在书房（归一化 ISBN 比较），只记不写 */
    public void handleScan(String isbn) {
        final String key = ScanActivity.normalizeIsbn(isbn);
        final BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            boolean found = false;
            String title = "";
            try {
                for (Book book : app.db().bookDao().getAll()) {
                    if (ScanActivity.normalizeIsbn(book.isbn).equals(key)) {
                        found = true;
                        title = book.title;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            final boolean fFound = found;
            final String fTitle = title;
            app.ui(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (fFound) {
                    if (scanned.add(key)) {
                        Toast.makeText(activity, "✓ " + fTitle, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, "这条已扫过", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    unknown++;
                    Toast.makeText(activity, "不在书房：ISBN " + isbn, Toast.LENGTH_SHORT).show();
                }
                updateCounter();
            });
        });
        new Handler(Looper.getMainLooper()).postDelayed(onScanConsumed, 1000);
    }

    private void updateCounter() {
        if (b == null) {
            return;
        }
        b.btnInventoryDone.setText("已扫 " + scanned.size() + " 种 · 结束盘点");
    }

    /** 结束盘点：算出缺失（在库且有 ISBN 但没扫到）与不在书房的扫描，可选标记遗失 */
    private void showResult() {
        final BookshelfApp app = BookshelfApp.get();
        final int unknownCount = unknown;
        app.io(() -> {
            List<Book> missing = new ArrayList<>();
            int scannable = 0;
            int noIsbn = 0;
            try {
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (book.stockStatus != Book.STOCK_IN) {
                        continue; // 借出中 / 遗失不参与盘点
                    }
                    // v5.8.18：从某架的二维码进来时，只数这一架的书
                    if (locationId > 0
                            && (book.locationId == null || book.locationId != locationId)) {
                        continue;
                    }
                    if (ScanActivity.normalizeIsbn(book.isbn).isEmpty()) {
                        noIsbn++;
                        continue;
                    }
                    scannable++;
                    if (!scanned.contains(ScanActivity.normalizeIsbn(book.isbn))) {
                        missing.add(book);
                    }
                }
            } catch (Exception ignored) {
            }
            final List<Book> fMissing = missing;
            final int fScannable = scannable;
            final int fNoIsbn = noIsbn;
            app.ui(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                int found = fScannable - fMissing.size();
                StringBuilder msg = new StringBuilder();
                msg.append("可盘点 ").append(fScannable).append(" 种（在库且有 ISBN）");
                if (fNoIsbn > 0) {
                    msg.append("，另有 ").append(fNoIsbn).append(" 种没有 ISBN 无法扫码");
                }
                msg.append("。\n\n找到 ").append(found).append(" 种 · 缺失 ")
                        .append(fMissing.size()).append(" 种");
                if (unknownCount > 0) {
                    msg.append("\n另有 ").append(unknownCount).append(" 次扫描不在书房");
                }
                if (!fMissing.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < Math.min(6, fMissing.size()); i++) {
                        if (i > 0) {
                            names.append("、");
                        }
                        names.append(fMissing.get(i).title);
                    }
                    if (fMissing.size() > 6) {
                        names.append(" 等");
                    }
                    msg.append("\n\n缺失：").append(names);
                }
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                        .setTitle("盘点结果")
                        .setMessage(msg.toString())
                        .setNegativeButton("关闭", (d, w) -> activity.finish());
                if (!fMissing.isEmpty()) {
                    builder.setPositiveButton("缺失的标记为遗失", (d, w) ->
                            markLost(app, fMissing));
                }
                builder.show();
            });
        });
    }

    private void markLost(BookshelfApp app, List<Book> missing) {
        app.io(() -> {
            long now = System.currentTimeMillis();
            for (Book book : missing) {
                book.stockStatus = Book.STOCK_LOST;
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
            }
            app.ui(() -> {
                Toast.makeText(activity, "已把 " + missing.size() + " 种标记为遗失",
                        Toast.LENGTH_LONG).show();
                activity.finish();
            });
        });
    }
}
