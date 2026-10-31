package com.bai.bookshelf.ui.scan;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.api.IsbnQueryService;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.ActivityPasteIsbnBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BatchImport;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.IsbnList;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.Typo;
import com.bai.bookshelf.util.UndoBar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 粘贴 ISBN 清单批量录入（v5.8.16）：粘一列书号 → 解析 → 逐本查询 → 预览勾选 → 一次入库。
 *
 * <p>扫码批量只能对着实物一本一本扫，书单或表格里的书号此前只能手打。查询串行进行
 * （一次只发一个请求），不打爆免费接口；入库与副本规则同扫码页共用 {@link BatchImport}，
 * 保存后同样进 8 秒撤销窗口，整批可回滚。
 */
public class PasteIsbnActivity extends BaseActivity {

    /** 一行预览持有的控件 */
    private static final class RowRefs {
        ImageView cover;
        TextView title;
        TextView status;
        CheckBox include;
    }

    private ActivityPasteIsbnBinding b;
    private final List<BatchImport.Item> items = new ArrayList<>();
    private final List<RowRefs> rows = new ArrayList<>();

    private final List<Location> locations = new ArrayList<>();
    private long locationId = -1;
    private String locationName = "未设置";

    /** 正在查询的序号；-1 表示没在查 */
    private int cursor = -1;
    /** 重新解析或页面关闭后，让在途的查询回调作废 */
    private long generation;
    /** 「忽略了 N 条位数不对的」这类提示，进度行每次刷新都要带上 */
    private String ignoredNote = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityPasteIsbnBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsHelper.applyTopInset(b.toolbar);
        InsetsHelper.applyBottomInset(b.bottomBar);
        b.toolbar.setNavigationOnClickListener(v -> finish());

        b.btnQuery.setOnClickListener(v -> parseAndQuery());
        b.btnClear.setOnClickListener(v -> {
            b.etIsbn.setText("");
            reset();
        });
        b.btnLocation.setOnClickListener(v -> showLocationPicker());
        b.cbCopies.setOnCheckedChangeListener((btn, checked) -> updateSave());
        b.btnSave.setOnClickListener(v -> save());

        loadLocations();
    }

    @Override
    protected void onDestroy() {
        generation++;
        super.onDestroy();
    }

    /** 清空队列与进度，回到待粘贴状态 */
    private void reset() {
        generation++;
        cursor = -1;
        items.clear();
        rows.clear();
        b.llItems.removeAllViews();
        b.pbQuery.setVisibility(View.GONE);
        b.pbQuery.setProgress(0);
        b.tvProgress.setText("");
        ignoredNote = "";
        updateSave();
    }

    private void parseAndQuery() {
        String raw = b.etIsbn.getText().toString();
        List<String> all = IsbnList.parse(raw, Integer.MAX_VALUE);
        if (all.isEmpty()) {
            Toast.makeText(this, "没识别到有效的 ISBN（要 10 位或 13 位）", Toast.LENGTH_SHORT).show();
            reset();
            return;
        }
        int invalid = countInvalid(raw);
        int dropped = Math.max(0, all.size() - IsbnList.MAX);
        List<String> isbns = all.size() > IsbnList.MAX ? all.subList(0, IsbnList.MAX) : all;

        reset();
        for (String isbn : isbns) {
            items.add(new BatchImport.Item(isbn));
        }
        renderItems();

        if (invalid > 0) {
            ignoredNote = "，忽略 " + invalid + " 条位数不对的";
        }
        if (dropped > 0) {
            ignoredNote += "，超出单次上限只取前 " + IsbnList.MAX + " 条";
        }
        String head = "共 " + items.size() + " 条" + ignoredNote;
        b.pbQuery.setVisibility(View.VISIBLE);
        b.pbQuery.setMax(items.size());

        if (!new IsbnQueryService(this).isConfigured()) {
            b.pbQuery.setVisibility(View.GONE);
            Toast.makeText(this, "未配置 ISBN 数据源，无法自动补全（我的 → API 设置）",
                    Toast.LENGTH_LONG).show();
            markAllFailed("未配置 ISBN 数据源");
            return;
        }
        b.tvProgress.setText(head + "，正在查询…");
        cursor = 0;
        queryNext();
    }

    /** 位数不对、被解析器丢掉的片段条数（只用于提示） */
    private static int countInvalid(String raw) {
        int n = 0;
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= raw.length(); i++) {
            char c = i < raw.length() ? raw.charAt(i) : '\n';
            if (Character.isLetterOrDigit(c) || c == '-') {
                token.append(c);
                continue;
            }
            if (token.length() > 0 && !IsbnList.isValid(IsbnList.normalize(token.toString()))) {
                n++;
            }
            token.setLength(0);
        }
        return n;
    }

    /** 串行查询：上一条落定后再发下一条 */
    private void queryNext() {
        int at = cursor;
        if (at < 0 || at >= items.size()) {
            cursor = -1;
            b.pbQuery.setVisibility(View.GONE);
            updateProgressText();
            return;
        }
        final BatchImport.Item item = items.get(at);
        final long gen = generation;
        AddBookHelper.lookup(this, item.isbn, new AddBookHelper.LookupCallback() {
            @Override
            public void onDuplicate(Book existing) {
                item.status = BatchImport.DUPLICATE;
                item.existingId = existing.id;
                item.title = existing.title;
                item.author = existing.author;
                item.coverUrl = existing.coverUrl;
                done(gen, at);
            }

            @Override
            public void onBook(Book book) {
                item.status = BatchImport.READY;
                item.book = book;
                item.title = book.title;
                item.author = book.author;
                item.coverUrl = book.coverUrl;
                done(gen, at);
            }

            @Override
            public void onFailed(String message) {
                item.status = BatchImport.FAILED;
                item.message = message == null ? "查询失败" : message;
                done(gen, at);
            }
        });
    }

    private void done(long gen, int at) {
        if (gen != generation || isFinishing() || isDestroyed()) {
            return;
        }
        refreshRow(at);
        b.pbQuery.setProgress(at + 1);
        updateProgressText();
        cursor = at + 1;
        queryNext();
    }

    private void updateProgressText() {
        int done = 0;
        int ready = 0;
        int dup = 0;
        int failed = 0;
        for (BatchImport.Item item : items) {
            if (item.status == BatchImport.QUERYING) {
                continue;
            }
            done++;
            if (item.status == BatchImport.READY) {
                ready++;
            } else if (item.status == BatchImport.DUPLICATE) {
                dup++;
            } else {
                failed++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (cursor >= 0) {
            sb.append("正在查询 ").append(Math.min(done + 1, items.size()))
                    .append(" / ").append(items.size()).append(" · ");
        } else {
            sb.append("查询完成 · ");
        }
        sb.append("可入库 ").append(ready).append(" · 已在书架 ").append(dup);
        if (failed > 0) {
            sb.append(" · 查不到 ").append(failed);
        }
        sb.append(ignoredNote);
        b.tvProgress.setText(sb);
        updateSave();
    }

    private void markAllFailed(String message) {
        for (BatchImport.Item item : items) {
            if (item.status == BatchImport.QUERYING) {
                item.status = BatchImport.FAILED;
                item.message = message;
            }
        }
        renderItems();
        updateProgressText();
    }

    /** 整表重绘（解析完 / 批量标记失败时用） */
    private void renderItems() {
        b.llItems.removeAllViews();
        rows.clear();
        for (int i = 0; i < items.size(); i++) {
            RowRefs refs = new RowRefs();
            LinearLayout row = buildRow(i, refs);
            rows.add(refs);
            refreshRow(i);
            b.llItems.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < items.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(ThemeManager.resolveColor(this, R.attr.outline));
                b.llItems.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    /** 查询回来后只换这一行的书名/状态/封面，不动勾选 */
    private void refreshRow(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        RowRefs refs = rows.get(index);
        BatchImport.Item item = items.get(index);
        refs.title.setText(titleOf(item));
        refs.status.setText(statusOf(item));
        refs.status.setTextColor(item.status == BatchImport.FAILED
                ? ContextCompat.getColor(this, R.color.danger)
                : ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        loadCover(refs.cover, item);
    }

    private LinearLayout buildRow(int index, RowRefs refs) {
        BatchImport.Item item = items.get(index);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        refs.cover = new ImageView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(44), dp(60));
        refs.cover.setLayoutParams(clp);
        refs.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        refs.cover.setClipToOutline(true);
        refs.cover.setBackgroundResource(R.drawable.bg_cover);
        row.addView(refs.cover);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);

        refs.title = new TextView(this);
        refs.title.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        Typo.text(refs.title, R.dimen.text_body);
        refs.title.setMaxLines(2);
        refs.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(refs.title);

        refs.status = new TextView(this);
        Typo.text(refs.status, R.dimen.text_body_small);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        texts.addView(refs.status, slp);

        refs.include = new CheckBox(this);
        refs.include.setChecked(true);
        refs.include.setVisibility(View.GONE);
        refs.include.setOnCheckedChangeListener((btn, checked) -> {
            item.included = checked;
            updateSave();
        });
        row.addView(refs.include, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> {
            if (item.status == BatchImport.READY) {
                refs.include.setChecked(!refs.include.isChecked());
            }
        });
        return row;
    }

    private void loadCover(ImageView iv, BatchImport.Item item) {
        Book temp = new Book();
        temp.title = titleOf(item);
        temp.coverUrl = item.coverUrl == null ? "" : item.coverUrl;
        if (temp.coverUrl.isEmpty()) {
            CoverLoader.showPlaceholder(iv, temp);
        } else {
            CoverLoader.load(iv, temp);
        }
    }

    private String titleOf(BatchImport.Item item) {
        if (item.status == BatchImport.READY || item.status == BatchImport.DUPLICATE) {
            return item.title == null || item.title.isEmpty() ? "（未命名）" : item.title;
        }
        return "ISBN " + item.isbn;
    }

    private String statusOf(BatchImport.Item item) {
        switch (item.status) {
            case BatchImport.READY:
                String author = item.author == null ? "" : item.author.trim();
                return author.isEmpty() ? "待保存" : author + " · 待保存";
            case BatchImport.DUPLICATE:
                return "已在书架，默认跳过";
            case BatchImport.FAILED:
                return "查不到：" + item.message;
            default:
                return "正在查询…";
        }
    }

    /** 地点列表：进页就读，底栏直接可换 */
    private void loadLocations() {
        BookshelfApp app = BookshelfApp.get();
        long last = getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
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
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                locations.clear();
                locations.addAll(fAll);
                long id = locationId > 0 ? locationId : last;
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
                b.btnLocation.setText("地点：" + locationName + " ›");
            });
        });
    }

    private void showLocationPicker() {
        final String[] names = new String[locations.size() + 1];
        names[0] = "未设置";
        for (int i = 0; i < locations.size(); i++) {
            names[i + 1] = locations.get(i).name;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("存放地点")
                .setItems(names, (d, which) -> {
                    locationId = which == 0 ? -1 : locations.get(which - 1).id;
                    locationName = which == 0 ? "未设置" : locations.get(which - 1).name;
                    b.btnLocation.setText("地点：" + locationName + " ›");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updateSave() {
        int ready = BatchImport.saveableCount(items);
        int dup = 0;
        for (BatchImport.Item item : items) {
            if (item.status == BatchImport.DUPLICATE) {
                dup++;
            }
        }
        // 勾选框只在有可入库的书时出现，纯查询阶段不让用户误点
        for (RowRefs refs : rows) {
            refs.include.setVisibility(ready > 0 ? View.VISIBLE : View.GONE);
        }
        b.cbCopies.setVisibility(dup > 0 ? View.VISIBLE : View.GONE);
        b.cbCopies.setText("重复的 " + dup + " 种也各加 1 册");
        int count = ready + (b.cbCopies.isChecked() ? dup : 0);
        b.btnSave.setText("保存 " + count + " 种");
        b.btnSave.setEnabled(count > 0);
        b.btnSave.setAlpha(count > 0 ? 1f : 0.4f);
    }

    private void save() {
        List<BatchImport.Item> chosen = new ArrayList<>();
        for (BatchImport.Item item : items) {
            if (item.status == BatchImport.READY || item.status == BatchImport.DUPLICATE) {
                chosen.add(item);
            }
        }
        if (BatchImport.saveableCount(chosen) == 0
                && BatchImport.copyCount(chosen, b.cbCopies.isChecked()) == 0) {
            Toast.makeText(this, "没有可保存的书", Toast.LENGTH_SHORT).show();
            return;
        }
        b.btnSave.setEnabled(false);
        final BookshelfApp app = BookshelfApp.get();
        final long locId = locationId;
        final boolean addCopies = b.cbCopies.isChecked();
        app.io(() -> {
            final BatchImport.Result res = BatchImport.save(app, chosen, locId, addCopies);
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                String msg = "已录入 " + res.saved + " 种"
                        + (res.copied > 0 ? "，另加 " + res.copied + " 册副本" : "");
                // 保存即入库，8 秒内整批回滚；本页随即关闭，撤销条由书架补弹
                UndoBar.offer(null, msg, () -> app.io(() -> {
                    BatchImport.rollback(app, res);
                    app.ui(() -> Toast.makeText(app, "已撤销本次录入",
                            Toast.LENGTH_SHORT).show());
                }), null);
                finish();
            });
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
