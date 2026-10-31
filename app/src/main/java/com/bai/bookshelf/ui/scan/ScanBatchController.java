package com.bai.bookshelf.ui.scan;

import com.bai.bookshelf.util.Typo;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.ActivityScanBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫码页的批量录入（v4.43）：连续扫码进队列、底部队列面板逐本查看/移除、
 * 选择存放地点后一次保存；重复书可选各加一本副本。
 */
public class ScanBatchController {

    private static final int BATCH_QUERYING = 0;
    private static final int BATCH_READY = 1;
    private static final int BATCH_DUPLICATE = 2;
    private static final int BATCH_FAILED = 3;

    private final BaseActivity activity;
    private final ActivityScanBinding b;
    private final Runnable onScanConsumed; // 恢复扫码待机

    private boolean active = false;
    private final List<BatchItem> queue = new ArrayList<>();
    private BatchSheetView sheet;
    private final List<Location> locations = new ArrayList<>();
    private boolean locationsLoaded = false;
    private long locationId = -1;
    private String locationName = "未设置";

    public ScanBatchController(BaseActivity activity, ActivityScanBinding b,
                               Runnable onScanConsumed) {
        this.activity = activity;
        this.b = b;
        this.onScanConsumed = onScanConsumed;
    }

    public boolean isActive() {
        return active;
    }

    /** 批量模式开关：按钮配色、提示文案与队列入口一起切换 */
    public void toggleMode() {
        active = !active;
        b.btnBatch.setBackgroundResource(active
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_scan_capsule);
        b.btnBatch.setTextColor(active
                ? ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY)
                : Color.WHITE);
        b.tvScanHint.setText(active
                ? "批量模式：连续扫码，扫完点上方队列保存"
                : "将条形码放入框内，即可自动识别");
        updateUi();
        Toast.makeText(activity, active ? "批量模式已开启" : "已退出批量模式",
                Toast.LENGTH_SHORT).show();
    }

    /** 队列入口：有排队中的书就保留入口，避免用户关掉批量模式后找不到队列 */
    public void updateUi() {
        int total = queue.size();
        if (total > 0) {
            b.btnBatchQueue.setVisibility(View.VISIBLE);
            b.btnBatchQueue.setText("已加入 " + total + " 种 · 查看");
        } else {
            b.btnBatchQueue.setVisibility(View.GONE);
        }
    }

    /** 扫码命中一本：查重 + 补全信息，加入队列（不打断连续扫描） */
    public void add(String isbn) {
        for (BatchItem item : queue) {
            if (item.isbn.equals(isbn)) {
                Toast.makeText(activity, "这条已在待录入列表", Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(onScanConsumed, 800);
                return;
            }
        }
        final BatchItem item = new BatchItem(isbn);
        queue.add(item);
        updateUi();
        AddBookHelper.lookup(activity, isbn, new AddBookHelper.LookupCallback() {
            @Override
            public void onDuplicate(Book existing) {
                item.status = BATCH_DUPLICATE;
                item.existingId = existing.id;
                item.title = existing.title;
                item.author = existing.author;
                item.coverUrl = existing.coverUrl;
                afterItemChanged();
            }

            @Override
            public void onBook(Book book) {
                item.status = BATCH_READY;
                item.book = book;
                item.title = book.title;
                item.author = book.author;
                item.coverUrl = book.coverUrl;
                afterItemChanged();
            }

            @Override
            public void onFailed(String message) {
                item.status = BATCH_FAILED;
                item.message = message == null ? "查询失败" : message;
                afterItemChanged();
            }
        });
        // 留 1.2s 让镜头移开，避免同一本书被连续触发
        new Handler(Looper.getMainLooper()).postDelayed(onScanConsumed, 1200);
    }

    private void afterItemChanged() {
        updateUi();
        if (sheet != null && sheet.dialog.isShowing()) {
            renderItems();
            updateSave();
        }
    }

    /** 地点列表只在第一次打开面板时加载（之前漏了这步，选择器里只有「未设置」） */
    private void ensureLocations() {
        if (locationsLoaded) {
            return;
        }
        locationsLoaded = true;
        BookshelfApp app = BookshelfApp.get();
        long last = activity.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
                .getLong("last_location_id", -1);
        app.io(() -> {
            List<Location> all;
            Location def = null;
            try {
                all = app.db().locationDao().getAll();
            } catch (Exception e) {
                all = new ArrayList<>();
            }
            try {
                def = app.db().locationDao().getDefault();
            } catch (Exception ignored) {
            }
            final List<Location> fAll = all;
            final Location fDef = def;
            app.ui(() -> {
                locations.clear();
                locations.addAll(fAll);
                long id = last;
                if (id <= 0 && fDef != null) {
                    id = fDef.id;
                }
                locationId = id;
                locationName = "未设置";
                for (Location loc : fAll) {
                    if (loc.id == id) {
                        locationName = loc.name;
                    }
                }
                if (sheet != null) {
                    sheet.locationRow.setText("存放地点：" + locationName + "（点按更换）");
                }
            });
        });
    }

    /** 待录入队列面板（底部半屏） */
    public void showSheet() {
        if (queue.isEmpty()) {
            Toast.makeText(activity, "还没有扫到书", Toast.LENGTH_SHORT).show();
            return;
        }
        ensureLocations();
        sheet = BatchSheetView.show(activity, locationName);
        sheet.locationRow.setOnClickListener(v -> showLocationPicker());
        sheet.copyCheck.setOnCheckedChangeListener((btn, checked) -> updateSave());
        sheet.saveBtn.setOnClickListener(v -> save(sheet.dialog));
        renderItems();
        updateSave();
    }

    private void showLocationPicker() {
        final String[] names = new String[locations.size() + 1];
        names[0] = "未设置";
        for (int i = 0; i < locations.size(); i++) {
            names[i + 1] = locations.get(i).name;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("存放地点")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        locationId = -1;
                        locationName = "未设置";
                    } else {
                        Location loc = locations.get(which - 1);
                        locationId = loc.id;
                        locationName = loc.name;
                    }
                    if (sheet != null) {
                        sheet.locationRow.setText("存放地点：" + locationName + "（点按更换）");
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void renderItems() {
        if (sheet == null) {
            return;
        }
        sheet.list.removeAllViews();
        int primary = ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurface = ThemeManager.resolveColor(activity, R.attr.on_surface);
        int onVariant = ThemeManager.resolveColor(activity, R.attr.on_surface_variant);
        int danger = ContextCompat.getColor(activity, R.color.danger);
        for (int i = 0; i < queue.size(); i++) {
            final BatchItem item = queue.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            ImageView cover = new ImageView(activity);
            cover.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(60)));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setClipToOutline(true);
            cover.setBackgroundResource(R.drawable.bg_cover);
            loadCover(cover, item);
            row.addView(cover);

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(dp(12));
            row.addView(texts, tlp);

            TextView tvTitle = new TextView(activity);
            tvTitle.setText(titleOf(item));
            tvTitle.setTextColor(onSurface);
            Typo.text(tvTitle, R.dimen.text_body);
            tvTitle.setMaxLines(2);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(tvTitle);

            TextView tvStatus = new TextView(activity);
            tvStatus.setText(statusOf(item));
            tvStatus.setTextColor(item.status == BATCH_FAILED ? danger : onVariant);
            Typo.text(tvStatus, R.dimen.text_body_small);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            texts.addView(tvStatus, slp);

            TextView remove = new TextView(activity);
            remove.setText("移除");
            remove.setTextColor(primary);
            Typo.text(remove, R.dimen.text_caption_large);
            remove.setPadding(dp(8), dp(8), 0, dp(8));
            remove.setClickable(true);
            remove.setOnClickListener(v -> {
                queue.remove(item);
                updateUi();
                renderItems();
                updateSave();
            });
            row.addView(remove, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            sheet.list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < queue.size() - 1) {
                View line = new View(activity);
                line.setBackgroundColor(ThemeManager.resolveColor(activity, R.attr.outline));
                sheet.list.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private void loadCover(ImageView iv, BatchItem item) {
        Book temp = new Book();
        temp.title = titleOf(item);
        temp.coverUrl = item.coverUrl == null ? "" : item.coverUrl;
        if (temp.coverUrl.isEmpty()) {
            CoverLoader.showPlaceholder(iv, temp);
        } else {
            CoverLoader.load(iv, temp);
        }
    }

    private String titleOf(BatchItem item) {
        if (item.status == BATCH_READY || item.status == BATCH_DUPLICATE) {
            return item.title.isEmpty() ? "（未命名）" : item.title;
        }
        return "ISBN " + item.isbn;
    }

    private String statusOf(BatchItem item) {
        switch (item.status) {
            case BATCH_READY:
                return "待保存";
            case BATCH_DUPLICATE:
                return "已在书架（默认跳过）";
            case BATCH_FAILED:
                return "查询失败：" + item.message;
            default:
                return "正在查询…";
        }
    }

    private void updateSave() {
        if (sheet == null) {
            return;
        }
        int ready = 0;
        int dup = 0;
        for (BatchItem item : queue) {
            if (item.status == BATCH_READY) {
                ready++;
            } else if (item.status == BATCH_DUPLICATE) {
                dup++;
            }
        }
        sheet.copyCheck.setVisibility(dup > 0 ? View.VISIBLE : View.GONE);
        sheet.copyCheck.setText("重复的 " + dup + " 种也各加 1 册副本");
        int saveCount = ready + (sheet.copyCheck.isChecked() ? dup : 0);
        sheet.saveBtn.setText("保存 " + saveCount + " 种");
        sheet.saveBtn.setEnabled(saveCount > 0);
        sheet.saveBtn.setAlpha(saveCount > 0 ? 1f : 0.4f);
    }

    private void save(android.app.Dialog dialogToDismiss) {
        final List<BatchItem> ready = new ArrayList<>();
        final List<BatchItem> dups = new ArrayList<>();
        for (BatchItem item : queue) {
            if (item.status == BATCH_READY) {
                ready.add(item);
            } else if (item.status == BATCH_DUPLICATE) {
                dups.add(item);
            }
        }
        final boolean addCopies = sheet != null && sheet.copyCheck.isChecked();
        if (ready.isEmpty() && (!addCopies || dups.isEmpty())) {
            Toast.makeText(activity, "没有可保存的书", Toast.LENGTH_SHORT).show();
            return;
        }
        final long locId = locationId;
        final BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            int saved = 0;
            int copied = 0;
            for (BatchItem item : ready) {
                Book book = item.book;
                if (book == null || book.title == null || book.title.trim().isEmpty()) {
                    continue;
                }
                book.locationId = locId > 0 ? locId : null;
                book.ownType = Book.OWN_BUY;
                book.createdAt = now;
                book.updatedAt = now;
                book.dirty = true;
                if (app.db().bookDao().insert(book) > 0) {
                    saved++;
                }
            }
            if (addCopies) {
                for (BatchItem item : dups) {
                    Book exist = app.db().bookDao().getById(item.existingId);
                    if (exist != null) {
                        exist.quantity = exist.quantity + 1;
                        exist.updatedAt = now;
                        app.db().bookDao().update(exist);
                        copied++;
                    }
                }
            }
            final int fSaved = saved;
            final int fCopied = copied;
            app.ui(() -> {
                dialogToDismiss.dismiss();
                queue.clear();
                updateUi();
                Toast.makeText(activity, "已录入 " + fSaved + " 种"
                        + (fCopied > 0 ? "，另加 " + fCopied + " 册副本" : ""),
                        Toast.LENGTH_LONG).show();
                activity.finish();
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** 批量队列里的一本书 */
    private static final class BatchItem {
        final String isbn;
        int status = BATCH_QUERYING;
        Book book;
        long existingId = -1;
        String title = "";
        String author = "";
        String coverUrl = "";
        String message = "";

        BatchItem(String isbn) {
            this.isbn = isbn;
        }
    }
}
