package com.bai.bookshelf.ui.detail;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.bai.bookshelf.ui.BaseActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemDetailCopyBinding;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍详情页：展示书目与管理信息，提供 编辑 / 借出归还 / 删除 操作。
 *
 * 借出规则（v2.1）：
 * - 单本书：底部「借出/归还」按钮操作书本身（第 1 本 = 主记录）；
 * - 多本书：底部不再显示借出按钮，逐本信息里每一本（含第 1 本）行尾各有一个借出/归还按钮，
 *   借出记录通过 borrow_records.copyIndex 挂到具体某一本，互不干扰。
 */
public class BookDetailActivity extends BaseActivity {

    public static final String EXTRA_BOOK = "book";
    private static final int COPY_COLLAPSE_LIMIT = 3; // 逐本信息默认最多展开几本
    private static final int NOTE_MAX_LINES = 3;

    private ActivityBookDetailBinding b;
    private Book book;
    private String locationName = "未设置地点";

    /** 分类展示串「一级-二级」（v4.9，reload 时解析好再给 bind 用） */
    private String categoryDisplay = "";
    private List<BorrowRecord> openRecords = new ArrayList<>(); // 当前所有未归还的借出记录（含 copyIndex）
    private int lendCopyIndex = 1;                              // 本次借出操作针对第几本
    private List<BookCopy> copies = new ArrayList<>();
    private Map<Long, String> copyLocNames = new HashMap<>();   // 副本地点 id → 名称
    private List<NoteImage> noteImages = new ArrayList<>();     // 备注图片（默认折叠）
    private boolean noteImagesExpanded = false;
    private boolean copiesExpanded = false;                     // 逐本信息 >3 本时是否展开全部
    private boolean bookNoteExpanded = false;                   // 书籍备注是否展开
    private boolean manageLendNoteExpanded = false;               // 单本借出备注是否展开
    private boolean manageLendImagesExpanded = false;           // 单本借出备注图片是否展开
    private final Map<Integer, Boolean> copyLendNoteExpanded = new HashMap<>();   // 第 N 本借出备注是否展开
    private final Map<Integer, Boolean> copyLendImagesExpanded = new HashMap<>(); // 第 N 本借出备注图片是否展开
    private Map<Integer, List<NoteImage>> copyLendImages = new HashMap<>(); // 第 N 本当前借出记录的备注图片
    private static final int LEND_NOTE_PREVIEW_MAX = 20;        // 借出备注最多先显示 20 字

    // 借出登记弹窗中临时收集的备注图片
    private final List<NoteImage> pendingLendImages = new ArrayList<>();
    private final java.util.Set<String> pendingLendAddedPaths = new java.util.HashSet<>();
    private final java.util.Set<String> pendingLendRemovedPaths = new java.util.HashSet<>();
    private int pendingLendCopyIndex = 1;
    private androidx.activity.result.ActivityResultLauncher<String> pickLendImageLauncher;
    private com.bai.bookshelf.databinding.DialogLendBinding currentLendDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBookDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        pickLendImageLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    addLendNoteImage(uri);
                });

        book = extraBook();
        if (book == null) {
            finish();
            return;
        }

        b.btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(this, BookEditActivity.class);
            intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
            startActivity(intent);
        });
        // 单本时的借出按钮（多本时隐藏，改为逐本信息里的行内按钮）
        b.btnLend.setOnClickListener(v -> toggleLend());
        b.btnDelete.setOnClickListener(v -> confirmDelete());
        b.rowBorrowHistory.setOnClickListener(v -> {
            Intent intent = new Intent(this, BorrowHistoryActivity.class);
            intent.putExtra(BorrowHistoryActivity.EXTRA_BOOK_ID, book.id);
            startActivity(intent);
        });
        b.rowNoteImages.setOnClickListener(v -> {
            noteImagesExpanded = !noteImagesExpanded;
            renderNoteImagesArea();
        });
        b.tvNote.setOnClickListener(v -> toggleBookNote());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(); // 编辑返回后刷新
    }

    @SuppressWarnings("deprecation")
    private Book extraBook() {
        Book b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b = getIntent().getSerializableExtra(EXTRA_BOOK, Book.class);
        } else {
            b = (Book) getIntent().getSerializableExtra(EXTRA_BOOK);
        }
        // v3.0：Intent 传来的 Book 同样可能带 null 字段（老数据），统一清洗后再用
        return com.bai.bookshelf.util.BookSanitizer.normalize(b);
    }

    /** 重新从数据库读取最新数据（含地点名、借出记录、逐本信息、备注图片） */
    private void reload() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Book fresh = com.bai.bookshelf.util.BookSanitizer
                    .normalize(app.db().bookDao().getById(book.id));
            if (fresh == null) { // 已被删除
                app.ui(this::finish);
                return;
            }
            book = fresh;
            Location loc = book.locationId == null ? null : app.db().locationDao().getById(book.locationId);
            // 多本时可同时存在多条借出中记录（逐本借出）
            List<BorrowRecord> openList = app.db().borrowRecordDao().findOpenListByBook(book.id);
            int recordCount = app.db().borrowRecordDao().getByBook(book.id).size();
            // 逐本信息 + 各地点名称解析
            List<BookCopy> copyList = app.db().bookCopyDao().getByBook(book.id);
            Map<Long, String> locNames = new HashMap<>();
            for (BookCopy c : copyList) {
                if (c.locationId != null && !locNames.containsKey(c.locationId)) {
                    Location l = app.db().locationDao().getById(c.locationId);
                    locNames.put(c.locationId, l == null ? "未设置地点" : l.name);
                }
            }
            List<NoteImage> imgs = app.db().noteImageDao().getByOwnerKey("book:" + book.id);
            // v4.9：分类展示格式「一级-二级」（书里只存名字，需查分类库解析上级）
            String catDisplay = com.bai.bookshelf.util.CategoryPropagator
                    .displayPath(book.category, app.db().categoryDao().getAll());
            final String fCatDisplay = catDisplay;
            // 加载每本当前借出记录的备注图片
            Map<Integer, List<NoteImage>> lendImgs = new HashMap<>();
            for (BorrowRecord r : openList) {
                List<NoteImage> list = app.db().noteImageDao().getByOwnerKey("borrow:" + r.id);
                if (!list.isEmpty()) {
                    lendImgs.put(r.copyIndex, list);
                }
            }
            app.ui(() -> {
                locationName = loc == null ? "未设置地点" : loc.name;
                openRecords = openList;
                copies = copyList;
                copyLocNames = locNames;
                noteImages = imgs;
                copyLendImages = lendImgs;
                categoryDisplay = fCatDisplay;
                // 借出过才显示「借出记录」入口
                b.rowBorrowHistory.setVisibility(recordCount > 0 ? View.VISIBLE : View.GONE);
                b.rowBorrowHistory.setText("借出记录（" + recordCount + " 条）›");
                bind();
            });
        });
    }

    /** 把书籍信息渲染到界面 */
    private void bind() {
        b.tvTitle.setText(book.title);
        // 封面：支持网络链接与本地文件路径
        Glide.with(this)
                .load(book.coverUrl.isEmpty() ? null
                        : (book.coverUrl.startsWith("http") ? book.coverUrl : new File(book.coverUrl)))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(b.ivCover);

        // 书目信息块
        StringBuilder info = new StringBuilder();
        appendLine(info, "作者", book.author);
        appendLine(info, "译者", book.translator);
        appendLine(info, "国家/地区", book.country);
        appendLine(info, "出版社", book.publisher);
        appendLine(info, "版次", book.publishDate);
        appendLine(info, "印次", book.printing);
        if (book.pages > 0) {
            appendLine(info, "页数", book.pages + " 页");
        }
        appendLine(info, "字数", book.wordCount);
        if (book.listPrice > 0) {
            appendLine(info, "定价", "¥" + book.listPrice);
        }
        if (book.quantity > 1) {
            appendLine(info, "拥有数量", book.quantity + " 本");
        }
        appendLine(info, "豆瓣评分", book.doubanRating);
        appendLine(info, "分类", categoryDisplay);
        appendLine(info, "ISBN", formatIsbn(book.isbn));
        b.tvInfo.setText(info.toString().trim());

        boolean multi = book.quantity > 1;
        boolean hasLent = !openRecords.isEmpty();

        // 藏书核心信息块（独立展示，全书只出现一次）：先来源，再状态；多本时不显示来源
        StringBuilder core = new StringBuilder();
        // 书籍被借出时隐藏存放地点（v2.1 修复）
        if (!hasLent) {
            appendLine(core, "存放地点", locationName);
        }
        // 来源：单本时展示；多本时不展示（逐本信息里各自有来源）
        if (!multi) {
            // 未填渠道时按拥有类型给出明确提示，避免只显示「自购」看不出从哪里购买
            String source = book.sourceChannel.isEmpty()
                    ? StatusText.own(book.ownType) + "（" + channelHint(book.ownType) + "）"
                    : StatusText.own(book.ownType) + " · " + book.sourceChannel;
            appendLine(core, "来源", source);
        }
        if (multi) {
            // 多本：汇总各本状态，借给谁下放到逐本信息里按本显示
            appendLine(core, "藏书状态", multiCopyStockSummary());
        } else {
            appendLine(core, "藏书状态", StatusText.stock(book.stockStatus));
            BorrowRecord open1 = openRecordForCopy(1);
            if (book.stockStatus == Book.STOCK_LENT && open1 != null) {
                appendLine(core, "借给", open1.borrower + "（" + open1.lendDate + "）");
            }
        }
        appendLine(core, "录入时间", StatusText.formatTime(book.createdAt));
        b.tvCore.setText(core.toString().trim());

        // 管理信息块（不再包含「备注」与「各本存放位置」，这两处已分别下线/独立成块）
        StringBuilder mg = new StringBuilder();
        appendLine(mg, "阅读状态", StatusText.read(book.readStatus));
        appendLine(mg, "购买日期", book.purchaseDate);
        if (book.purchasePrice > 0) {
            // 有定价时追加折扣（实付 ÷ 定价 × 10，保留两位小数，如 2.99折）
            String paid = "¥" + book.purchasePrice;
            if (book.listPrice > 0) {
                paid += String.format(java.util.Locale.getDefault(),
                        "（%.2f折）", book.purchasePrice / book.listPrice * 10);
            }
            appendLine(mg, "实付", paid);
        }
        // 我的评分不在详情页展示（仍在编辑页和书架排序中可用）
        b.tvManage.setText(mg.toString().trim());
        // 管理信息为空（书刚录入、无借出无备注）时整块隐藏，避免空白卡片
        b.tvManage.setVisibility(mg.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);

        // 单本借出备注与图片：独立于管理信息块，文本前 20 字折叠、图片默认折叠
        BorrowRecord open1 = openRecordForCopy(1);
        if (!multi && open1 != null && !open1.note.isEmpty()) {
            b.tvManageLendNote.setVisibility(View.VISIBLE);
            renderManageLendNote(open1.note);
        } else {
            b.tvManageLendNote.setVisibility(View.GONE);
        }
        List<NoteImage> manageLendImgs = open1 == null ? null : copyLendImages.get(1);
        if (!multi && manageLendImgs != null && !manageLendImgs.isEmpty()) {
            b.rowManageLendNoteImages.setVisibility(View.VISIBLE);
            b.containerManageLendNoteImages.setVisibility(
                    manageLendImagesExpanded ? View.VISIBLE : View.GONE);
            renderManageLendNoteImagesArea(manageLendImgs);
        } else {
            b.rowManageLendNoteImages.setVisibility(View.GONE);
            b.containerManageLendNoteImages.setVisibility(View.GONE);
            b.containerManageLendNoteImages.removeAllViews();
        }

        // 备注：单独成块，默认最多 3 行，点击展开
        renderBookNote();

        // 备注图片：默认折叠，位于备注下方
        renderNoteImagesArea();

        // 逐本信息块：多本时按本铺开，第 1 本也在此显示（行尾带借出/归还按钮）；>3 本默认只显前 3 本
        b.btnLend.setVisibility(multi ? View.GONE : View.VISIBLE);
        if (multi) {
            b.containerCopies.setVisibility(View.VISIBLE);
            buildCopyRows();
        } else {
            b.containerCopies.setVisibility(View.GONE);
            b.containerCopies.removeAllViews();
        }
        // 借出按钮文案随状态切换（单本才有此按钮）
        b.btnLend.setText(book.stockStatus == Book.STOCK_LENT ? "归还" : "借出");

        // 标签独立区域展示（不与管理信息混在一起）
        b.chipGroupTags.removeAllViews();
        if (book.tags.trim().isEmpty()) {
            b.tvTagsLabel.setVisibility(View.GONE);
            b.chipGroupTags.setVisibility(View.GONE);
        } else {
            b.tvTagsLabel.setVisibility(View.VISIBLE);
            b.chipGroupTags.setVisibility(View.VISIBLE);
            for (String t : book.tags.split(",")) {
                String name = t.trim();
                if (name.isEmpty()) {
                    continue;
                }
                com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
                // 标签过长：截断显示前 20 字，点击看完整（同时避免超长 Chip 导致布局 bug）
                if (name.length() > 20) {
                    chip.setText("# " + name.substring(0, 20) + "…");
                    chip.setOnClickListener(v -> showFullTag(name));
                } else {
                    chip.setText("# " + name);
                    chip.setClickable(false);
                }
                chip.setMaxLines(1);
                chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.setCheckable(false);
                b.chipGroupTags.addView(chip);
            }
        }

        // v3.6：内容简介已停用（API 不再导入、布局中 tv_summary 永久 gone）
    }

    /** 单本借出备注：超过 20 字默认截断，点击切换 */
    private void renderManageLendNote(String note) {
        if (note.length() <= LEND_NOTE_PREVIEW_MAX || manageLendNoteExpanded) {
            b.tvManageLendNote.setText("借出备注：" + note);
            b.tvManageLendNote.setOnClickListener(note.length() > LEND_NOTE_PREVIEW_MAX
                    ? v -> {
                        manageLendNoteExpanded = false;
                        renderManageLendNote(note);
                    }
                    : null);
        } else {
            b.tvManageLendNote.setText("借出备注：" + note.substring(0, LEND_NOTE_PREVIEW_MAX) + "…");
            b.tvManageLendNote.setOnClickListener(v -> {
                manageLendNoteExpanded = true;
                renderManageLendNote(note);
            });
        }
    }

    /** 单本借出备注图片入口与内容区 */
    private void renderManageLendNoteImagesArea(List<NoteImage> imgs) {
        b.rowManageLendNoteImages.setText(manageLendImagesExpanded
                ? "借出备注图片（" + imgs.size() + " 张）‹ 收起"
                : "借出备注图片（" + imgs.size() + " 张）› 展开");
        b.rowManageLendNoteImages.setOnClickListener(v -> {
            manageLendImagesExpanded = !manageLendImagesExpanded;
            renderManageLendNoteImagesArea(imgs);
        });
        if (manageLendImagesExpanded) {
            renderLendNoteImages(b.containerManageLendNoteImages, imgs);
        } else {
            b.containerManageLendNoteImages.removeAllViews();
        }
    }

    /** 渲染书籍备注块（最多 3 行，点击展开/收起） */
    private void renderBookNote() {
        String note = book.note == null ? "" : book.note.trim();
        if (note.isEmpty()) {
            b.tvNote.setVisibility(View.GONE);
            return;
        }
        b.tvNote.setVisibility(View.VISIBLE);
        b.tvNote.setText("备注：" + note);
        b.tvNote.setMaxLines(bookNoteExpanded ? Integer.MAX_VALUE : NOTE_MAX_LINES);
        b.tvNote.setEllipsize(bookNoteExpanded ? null : android.text.TextUtils.TruncateAt.END);
    }

    private void toggleBookNote() {
        bookNoteExpanded = !bookNoteExpanded;
        renderBookNote();
    }

    private void showFullTag(String fullTag) {
        new AlertDialog.Builder(this)
                .setTitle("完整标签")
                .setMessage(fullTag)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 借出备注预览：超过 20 字截断显示，点击切换展开/收起 */
    private void renderLendNotePreview(TextView tv, String note, int copyIndex) {
        boolean expanded = Boolean.TRUE.equals(copyLendNoteExpanded.get(copyIndex));
        if (note.length() <= LEND_NOTE_PREVIEW_MAX || expanded) {
            tv.setText("借出备注：" + note);
            tv.setOnClickListener(null);
            if (note.length() > LEND_NOTE_PREVIEW_MAX) {
                tv.setOnClickListener(v -> {
                    copyLendNoteExpanded.put(copyIndex, false);
                    renderLendNotePreview(tv, note, copyIndex);
                });
            }
        } else {
            tv.setText("借出备注：" + note.substring(0, LEND_NOTE_PREVIEW_MAX) + "…");
            tv.setOnClickListener(v -> {
                copyLendNoteExpanded.put(copyIndex, true);
                renderLendNotePreview(tv, note, copyIndex);
            });
        }
    }

    // ======================= 逐本信息 =======================

    /** 按拥有数量铺开每一本（含第 1 本），行尾是借出/归还按钮；>3 本默认只显示前 3 本 */
    private void buildCopyRows() {
        b.containerCopies.removeAllViews();
        int showLimit = copiesExpanded ? book.quantity : Math.min(COPY_COLLAPSE_LIMIT, book.quantity);
        for (int i = 1; i <= book.quantity; i++) {
            ItemDetailCopyBinding rb = ItemDetailCopyBinding.inflate(getLayoutInflater(), b.containerCopies, false);
            BorrowRecord open = openRecordForCopy(i);
            rb.tvCopyInfo.setText(copyLine(i, open));
            rb.btnCopyLend.setText(open == null ? "借出" : "归还");
            final int index = i;
            rb.btnCopyLend.setOnClickListener(v -> {
                if (open == null) {
                    showLendDialog(index);
                } else {
                    returnCopy(index, open);
                }
            });
            // 借出备注：单独一行，默认只显示前 20 字，点击看完整
            if (open != null && !open.note.isEmpty()) {
                rb.tvLendNote.setVisibility(View.VISIBLE);
                renderLendNotePreview(rb.tvLendNote, open.note, i);
            } else {
                rb.tvLendNote.setVisibility(View.GONE);
            }
            // 借出备注图片：不直接展示，点击入口后展开
            List<NoteImage> lendImgs = copyLendImages.get(i);
            if (lendImgs != null && !lendImgs.isEmpty()) {
                rb.tvLendNoteImages.setVisibility(View.VISIBLE);
                rb.containerLendNoteImages.setVisibility(
                        Boolean.TRUE.equals(copyLendImagesExpanded.get(i)) ? View.VISIBLE : View.GONE);
                renderLendNoteImagesArea(rb.tvLendNoteImages, rb.containerLendNoteImages, lendImgs, i);
            } else {
                rb.tvLendNoteImages.setVisibility(View.GONE);
                rb.containerLendNoteImages.setVisibility(View.GONE);
            }

            // 逐本备注：单独一行，最多 3 行，点击展开
            String copyNote = copyNoteText(i);
            if (!copyNote.isEmpty()) {
                rb.tvCopyNote.setVisibility(View.VISIBLE);
                rb.tvCopyNote.setText("备注：" + copyNote);
                rb.tvCopyNote.setMaxLines(NOTE_MAX_LINES);
                rb.tvCopyNote.setEllipsize(android.text.TextUtils.TruncateAt.END);
                rb.tvCopyNote.setOnClickListener(v -> toggleCopyNote(rb.tvCopyNote));
            } else {
                rb.tvCopyNote.setVisibility(View.GONE);
            }
            rb.getRoot().setVisibility(i <= showLimit ? View.VISIBLE : View.GONE);
            b.containerCopies.addView(rb.getRoot());
            // 行间细分割线（最后一行不画；隐藏行不需要分隔线）
            if (i < showLimit) {
                b.containerCopies.addView(divider());
            }
        }
        // 展开/收起入口
        if (book.quantity > COPY_COLLAPSE_LIMIT) {
            TextView expandRow = new TextView(this);
            expandRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            expandRow.setPadding(dp(8), dp(10), dp(8), dp(6));
            expandRow.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
            expandRow.setTextSize(13);
            if (copiesExpanded) {
                expandRow.setText("‹ 收起逐本信息");
            } else {
                expandRow.setText("展开剩余 " + (book.quantity - COPY_COLLAPSE_LIMIT) + " 本 ›");
            }
            expandRow.setOnClickListener(v -> {
                copiesExpanded = !copiesExpanded;
                buildCopyRows();
            });
            b.containerCopies.addView(expandRow);
        }
    }

    private void toggleCopyNote(TextView tv) {
        int current = tv.getMaxLines();
        boolean expand = current == NOTE_MAX_LINES;
        tv.setMaxLines(expand ? Integer.MAX_VALUE : NOTE_MAX_LINES);
        tv.setEllipsize(expand ? null : android.text.TextUtils.TruncateAt.END);
    }

    /** 行间隔开用的 1dp 灰线（颜色统一走 @color/divider_gray，避免散落魔法值） */
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(ContextCompat.getColor(this, R.color.divider_gray));
        int height = (int) (0.8f * getResources().getDisplayMetrics().density);
        line.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, height));
        return line;
    }

    /** 第 index 本的实体数据（第 1 本取主记录；第 2 本起取副本表，缺失则用默认值构造） */
    private BookCopy copyByIndex(int index) {
        if (index == 1) {
            BookCopy c = new BookCopy();
            c.bookId = book.id;
            c.copyIndex = 1;
            c.stockStatus = book.stockStatus;
            c.locationId = book.locationId;
            c.ownType = book.ownType;
            c.sourceChannel = book.sourceChannel;
            c.purchaseDate = book.purchaseDate;
            c.purchasePrice = book.purchasePrice;
            return c;
        }
        for (BookCopy c : copies) {
            if (c.copyIndex == index) {
                return c;
            }
        }
        // 用户只填了拥有数量、没录过这一本的详情：按默认值给出一行
        BookCopy empty = new BookCopy();
        empty.bookId = book.id;
        empty.copyIndex = index;
        empty.stockStatus = Book.STOCK_IN;
        empty.ownType = Book.OWN_BUY;
        return empty;
    }

    /** 第 index 本当前未归还的借出记录（没有则 null） */
    private BorrowRecord openRecordForCopy(int index) {
        for (BorrowRecord r : openRecords) {
            if (r.copyIndex == index) {
                return r;
            }
        }
        return null;
    }

    /** 第 index 本的备注文字（第 1 本取 book.note，第 2 本起取 BookCopy.note） */
    private String copyNoteText(int index) {
        if (index == 1) {
            return "";
        }
        BookCopy c = copyByIndex(index);
        return c.note == null ? "" : c.note.trim();
    }

    /** 第 index 本的一行摘要文案（状态｜地点｜来源｜日期｜实付｜借给｜借出备注） */
    private String copyLine(int index, BorrowRecord open) {
        BookCopy c = copyByIndex(index);
        StringBuilder line = new StringBuilder("第 " + index + " 本：");
        line.append(StatusText.stock(c.stockStatus));
        if (index == 1) {
            if (book.locationId != null && !openRecords.isEmpty()) {
                // 被借出时：第 1 本也不显示地点（整体逻辑已在核心块隐藏，这里保持风格一致）
            } else if (book.locationId != null) {
                line.append("｜").append(locationName);
            }
        } else if (c.locationId != null) {
            line.append("｜").append(copyLocNames.get(c.locationId));
        }
        // 来源：拥有类型 + 具体渠道（从哪里购买/借阅/获赠）；未填渠道时按类型给出明确提示，
        // 避免只显示「自购」而看不出购买来源（v2.6 完善）
        String src = c.sourceChannel.isEmpty()
                ? StatusText.own(c.ownType) + "（" + channelHint(c.ownType) + "）"
                : StatusText.own(c.ownType) + " · " + c.sourceChannel;
        line.append("｜").append(src);
        if (!c.purchaseDate.isEmpty()) {
            line.append("｜").append(c.purchaseDate);
        }
        if (c.purchasePrice > 0) {
            line.append("｜实付 ¥").append(c.purchasePrice);
        }
        if (open != null) {
            line.append("｜借给 ").append(open.borrower).append("（").append(open.lendDate).append("）");
        }
        return line.toString();
    }

    /** 未填渠道时的类型化提示：自购→未填购买渠道；借阅→未填借阅来源；赠书→未填赠送来源 */
    private static String channelHint(int ownType) {
        if (ownType == Book.OWN_BORROW) {
            return "未填借阅来源";
        }
        if (ownType == Book.OWN_GIFT) {
            return "未填赠送来源";
        }
        return "未填购买渠道";
    }

    /** 多本时的藏书状态汇总：共 N 本（在库 x、借出 y、遗失 z） */
    private String multiCopyStockSummary() {
        int in = 0;
        int lent = 0;
        int lost = 0;
        for (int i = 1; i <= book.quantity; i++) {
            int st = copyByIndex(i).stockStatus;
            if (st == Book.STOCK_LENT) {
                lent++;
            } else if (st == Book.STOCK_LOST) {
                lost++;
            } else {
                in++;
            }
        }
        StringBuilder sb = new StringBuilder("共 " + book.quantity + " 本（");
        sb.append("在库 ").append(in);
        if (lent > 0) {
            sb.append("、借出 ").append(lent);
        }
        if (lost > 0) {
            sb.append("、遗失 ").append(lost);
        }
        return sb.append("）").toString();
    }

    // ======================= 借出备注图片（逐本，默认折叠） =======================

    /** 渲染借出备注图片入口与内容区 */
    private void renderLendNoteImagesArea(TextView entry, LinearLayout container,
                                          List<NoteImage> imgs, int copyIndex) {
        boolean expanded = Boolean.TRUE.equals(copyLendImagesExpanded.get(copyIndex));
        entry.setText(expanded
                ? "借出备注图片（" + imgs.size() + " 张）‹ 收起"
                : "借出备注图片（" + imgs.size() + " 张）› 展开");
        entry.setOnClickListener(v -> {
            copyLendImagesExpanded.put(copyIndex, !expanded);
            buildCopyRows(); // 重新渲染该本区域
        });
        if (expanded) {
            renderLendNoteImages(container, imgs);
        } else {
            container.removeAllViews();
        }
    }

    private void renderLendNoteImages(LinearLayout container, List<NoteImage> imgs) {
        container.removeAllViews();
        for (NoteImage img : imgs) {
            File file = new File(img.path);
            ImageView iv = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(6));
            iv.setLayoutParams(lp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glide.with(this).load(file)
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> previewLendNoteImage(img));
            container.addView(iv);
        }
    }

    private void previewLendNoteImage(NoteImage img) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(iv);
        new AlertDialog.Builder(this)
                .setTitle("借出备注图片")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ======================= 备注图片（默认折叠） =======================

    /** 渲染备注图片入口行与内容区（有图才有入口；默认折叠） */
    private void renderNoteImagesArea() {
        if (noteImages.isEmpty()) {
            noteImagesExpanded = false;
            b.rowNoteImages.setVisibility(View.GONE);
            b.containerNoteImages.setVisibility(View.GONE);
            return;
        }
        b.rowNoteImages.setVisibility(View.VISIBLE);
        b.rowNoteImages.setText(noteImagesExpanded
                ? "备注图片（" + noteImages.size() + " 张）‹ 收起"
                : "备注图片（" + noteImages.size() + " 张）› 展开");
        b.containerNoteImages.setVisibility(noteImagesExpanded ? View.VISIBLE : View.GONE);
        if (noteImagesExpanded) {
            renderNoteImages();
        }
    }

    /** 展开时渲染全部备注图片 */
    private void renderNoteImages() {
        b.containerNoteImages.removeAllViews();
        for (NoteImage img : noteImages) {
            File file = new File(img.path);
            ImageView iv = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(6));
            iv.setLayoutParams(lp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glide.with(this)
                    .load(file)
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> previewNoteImage(img));
            b.containerNoteImages.addView(iv);
        }
    }

    /** 点击图片：放大预览（文件缺失时由 Glide 兜底占位图） */
    private void previewNoteImage(NoteImage img) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(iv);
        new AlertDialog.Builder(this)
                .setTitle("备注图片")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** ISBN 统一显示为带分隔样式：无连字符时按 13/10 位规则插入连字符；已有连字符则直接显示 */
    private String formatIsbn(String isbn) {
        if (isbn == null) {
            return "";
        }
        if (isbn.contains("-")) {
            return isbn;
        }
        String digits = isbn.replaceAll("[^0-9]", "");
        if (digits.length() == 13 && (digits.startsWith("978") || digits.startsWith("979"))) {
            return digits.substring(0, 3) + "-" + digits.charAt(3) + "-"
                    + digits.substring(4, 7) + "-" + digits.substring(7, 12) + "-" + digits.charAt(12);
        }
        if (digits.length() == 10) {
            return digits.charAt(0) + "-" + digits.substring(1, 4) + "-"
                    + digits.substring(4, 9) + "-" + digits.charAt(9);
        }
        return isbn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    // ======================= 借出 ⇄ 归还 =======================

    /** 单本书底部的借出 ⇄ 归还 切换（操作第 1 本/主记录） */
    private void toggleLend() {
        BorrowRecord open = openRecordForCopy(1);
        if (book.stockStatus == Book.STOCK_LENT || open != null) {
            returnCopy(1, open);
        } else {
            showLendDialog(1);
        }
    }

    /** 归还第 index 本：关闭该本的借出记录 + 状态改回在库 */
    private void returnCopy(int index, BorrowRecord record) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (record != null) {
                record.status = BorrowRecord.STATUS_RETURNED;
                record.returnDate = StatusText.today();
                app.db().borrowRecordDao().update(record);
            }
            if (index == 1) {
                book.stockStatus = Book.STOCK_IN;
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } else {
                BookCopy c = findSavedCopy(index);
                if (c != null) {
                    c.stockStatus = Book.STOCK_IN;
                    app.db().bookCopyDao().update(c);
                }
            }
            app.ui(() -> {
                Toast.makeText(this, "第 " + index + " 本已归还", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    /** 在副本表里找第 index 本（没录过则返回 null，借出时会新增一行） */
    private BookCopy findSavedCopy(int index) {
        for (BookCopy c : copies) {
            if (c.copyIndex == index) {
                return c;
            }
        }
        return null;
    }

    // ======================= 借出登记（借阅人选择器 + 备注） =======================

    /** 借出登记对话框：常用借阅人 Chip 单选（可新建/长按管理）+ 借出备注 + 备注图片 */
    private void showLendDialog(int copyIndex) {
        lendCopyIndex = copyIndex;
        pendingLendCopyIndex = copyIndex;
        pendingLendImages.clear();
        pendingLendAddedPaths.clear();
        pendingLendRemovedPaths.clear();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.Borrower> all = app.db().borrowerDao().getAll();
            app.ui(() -> showLendDialogWithBorrowers(all));
        });
    }

    private void showLendDialogWithBorrowers(List<com.bai.bookshelf.data.entity.Borrower> all) {
        currentLendDialog = com.bai.bookshelf.databinding.DialogLendBinding.inflate(getLayoutInflater());
        final com.bai.bookshelf.databinding.DialogLendBinding db = currentLendDialog;
        BorrowRecord openNow = openRecordForCopy(lendCopyIndex);

        // 单选：当前选中的借阅人（借出记录可能带入上次借用人）
        String[] selected = {openNow != null ? openNow.borrower : ""};
        List<com.google.android.material.chip.Chip> chips = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.Borrower borrower : all) {
            com.google.android.material.chip.Chip chip = buildBorrowerChip(borrower, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        // 末尾的「＋ 新建借阅人」Chip
        com.google.android.material.chip.Chip addChip = new com.google.android.material.chip.Chip(this);
        addChip.setText("＋ 新建借阅人");
        addChip.setOnClickListener(v -> showNewBorrowerInput(db, selected, chips));
        db.chipGroup.addView(addChip);

        // 借出备注图片
        db.btnAddNoteImage.setOnClickListener(v -> {
            try {
                pickLendImageLauncher.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        renderLendNoteThumbs(db);

        new AlertDialog.Builder(this)
                .setTitle("借出登记（第 " + lendCopyIndex + " 本）")
                .setView(db.getRoot())
                .setPositiveButton("借出", (d, w) ->
                        doLend(lendCopyIndex, selected[0], db.etNote.getText().toString().trim()))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 把相册图落盘并加入借出登记弹窗的临时图片列表 */
    private void addLendNoteImage(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                File dir = new File(getFilesDir(), "note_images");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File out = new File(dir, "note_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                app.ui(() -> {
                    NoteImage img = new NoteImage();
                    img.bookId = book.id;
                    img.path = out.getAbsolutePath();
                    pendingLendImages.add(img);
                    pendingLendAddedPaths.add(img.path);
                    if (currentLendDialog != null) {
                        renderLendNoteThumbs(currentLendDialog);
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 渲染借出登记弹窗的备注图片缩略图 */
    private void renderLendNoteThumbs(com.bai.bookshelf.databinding.DialogLendBinding db) {
        db.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * getResources().getDisplayMetrics().density);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (NoteImage img : pendingLendImages) {
            ImageView iv = new ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(this).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showLendNotePreview(img, db));
            db.containerNoteThumbs.addView(iv);
        }
        boolean has = !pendingLendImages.isEmpty();
        db.scrollNoteThumbs.setVisibility(has ? View.VISIBLE : View.GONE);
        db.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + pendingLendImages.size() + " 张）"
                : "＋ 添加备注图片");
    }

    private void showLendNotePreview(NoteImage img, com.bai.bookshelf.databinding.DialogLendBinding db) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(iv);
        new AlertDialog.Builder(this)
                .setTitle("备注图片")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> {
                    pendingLendImages.remove(img);
                    if (!pendingLendAddedPaths.contains(img.path)) {
                        pendingLendRemovedPaths.add(img.path);
                    }
                    renderLendNoteThumbs(db);
                })
                .show();
    }

    /** 借阅人 Chip：点击单选互斥，长按重命名/删除（与分类选择器同套交互） */
    private com.google.android.material.chip.Chip buildBorrowerChip(
            com.bai.bookshelf.data.entity.Borrower borrower, String[] selected,
            List<com.google.android.material.chip.Chip> chips) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(borrower.name);
        chip.setCheckable(true);
        chip.setChecked(borrower.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (borrower.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = borrower.name;
                for (com.google.android.material.chip.Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showBorrowerOptions(borrower, chip, selected);
            return true;
        });
        return chip;
    }

    /** 借阅人长按菜单：重命名 / 删除 */
    private void showBorrowerOptions(com.bai.bookshelf.data.entity.Borrower borrower,
                                     com.google.android.material.chip.Chip chip,
                                     String[] selected) {
        String[] options = {"重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle("借阅人「" + borrower.name + "」")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRenameBorrowerInput(borrower, chip, selected);
                    } else {
                        confirmDeleteBorrower(borrower, chip, selected);
                    }
                })
                .show();
    }

    /** 重命名借阅人：改库 + 改 Chip 文本（历史借出记录里的名字保留原样） */
    private void showRenameBorrowerInput(com.bai.bookshelf.data.entity.Borrower borrower,
                                         com.google.android.material.chip.Chip chip,
                                         String[] selected) {
        DialogInputBinding ib = DialogInputBinding.inflate(getLayoutInflater());
        ib.edit.setText(borrower.name);
        ib.edit.setSelection(borrower.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名借阅人")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(borrower.name)) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        borrower.name = newName;
                        app.db().borrowerDao().update(borrower);
                        app.ui(() -> {
                            chip.setText(newName);
                            Toast.makeText(this, "已重命名为「" + newName + "」", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除借阅人：确认后从库中删除并移除 Chip */
    private void confirmDeleteBorrower(com.bai.bookshelf.data.entity.Borrower borrower,
                                       com.google.android.material.chip.Chip chip,
                                       String[] selected) {
        new AlertDialog.Builder(this)
                .setTitle("删除借阅人")
                .setMessage("从常用借阅人里删除「" + borrower.name + "」？历史借出记录不受影响。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().borrowerDao().deleteById(borrower.id);
                        app.ui(() -> {
                            if (selected[0].equals(borrower.name)) {
                                selected[0] = "";
                            }
                            android.view.ViewGroup parent = (android.view.ViewGroup) chip.getParent();
                            if (parent != null) {
                                parent.removeView(chip);
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 新建借阅人：入库 → 生成一个默认选中的 Chip 插到「＋」之前 */
    private void showNewBorrowerInput(com.bai.bookshelf.databinding.DialogLendBinding db,
                                      String[] selected,
                                      List<com.google.android.material.chip.Chip> chips) {
        DialogInputBinding ib = DialogInputBinding.inflate(getLayoutInflater());
        ib.edit.setHint("姓名或称呼（如 小王、表姐）");
        new AlertDialog.Builder(this)
                .setTitle("新建借阅人")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        com.bai.bookshelf.data.entity.Borrower borrower =
                                new com.bai.bookshelf.data.entity.Borrower();
                        borrower.name = name;
                        borrower.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().borrowerDao().insert(borrower);
                        borrower.id = id;
                        app.ui(() -> {
                            selected[0] = name; // 新借阅人默认选中
                            com.google.android.material.chip.Chip chip =
                                    buildBorrowerChip(borrower, selected, chips);
                            chips.add(chip);
                            for (com.google.android.material.chip.Chip other : chips) {
                                other.setChecked(other == chip);
                            }
                            db.chipGroup.addView(chip, db.chipGroup.getChildCount() - 1);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 登记借出某一本：该书这一本已借出时不能再次借出（需先归还） */
    private void doLend(int copyIndex, String borrower, String note) {
        if (borrower.isEmpty()) {
            Toast.makeText(this, "请选择或新建借阅人", Toast.LENGTH_SHORT).show();
            return;
        }
        if (openRecordForCopy(copyIndex) != null) {
            Toast.makeText(this, "第 " + copyIndex + " 本已借出，归还后才能再次借出", Toast.LENGTH_SHORT).show();
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            BorrowRecord record = new BorrowRecord();
            record.bookId = book.id;
            record.borrower = borrower;
            record.lendDate = StatusText.today();
            record.note = note;
            record.copyIndex = copyIndex;
            long recordId = app.db().borrowRecordDao().insert(record);
            // 保存借出备注图片
            if (!pendingLendImages.isEmpty() && recordId > 0) {
                for (int i = 0; i < pendingLendImages.size(); i++) {
                    NoteImage img = pendingLendImages.get(i);
                    img.bookId = book.id;
                    img.ownerKey = "borrow:" + recordId;
                    img.sort = i;
                }
                app.db().noteImageDao().insertAll(pendingLendImages);
                cleanupLendRemovedImageFiles();
            } else {
                cleanupLendRemovedImageFiles();
            }
            if (copyIndex == 1) {
                // 第 1 本的状态写在主记录上
                book.stockStatus = Book.STOCK_LENT;
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } else {
                BookCopy c = findSavedCopy(copyIndex);
                if (c == null) {
                    // 这一本此前没录过详情，先建一行最小副本再标借出
                    c = new BookCopy();
                    c.bookId = book.id;
                    c.copyIndex = copyIndex;
                    c.stockStatus = Book.STOCK_LENT;
                    c.ownType = Book.OWN_BUY;
                    long id = app.db().bookCopyDao().insert(c);
                    c.id = id;
                } else {
                    c.stockStatus = Book.STOCK_LENT;
                    app.db().bookCopyDao().update(c);
                }
            }
            app.ui(() -> {
                Toast.makeText(this, "第 " + copyIndex + " 本已借出给 " + borrower,
                        Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    /** 借出登记保存后：清掉本次新加却未保留的临时文件 */
    private void cleanupLendRemovedImageFiles() {
        for (String p : pendingLendAddedPaths) {
            boolean kept = false;
            for (NoteImage img : pendingLendImages) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        for (String p : pendingLendRemovedPaths) {
            new File(p).delete();
        }
        pendingLendAddedPaths.clear();
        pendingLendRemovedPaths.clear();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除书籍")
                .setMessage("确定把" + com.bai.bookshelf.util.BookText.quotedTitle(book.title)
                        + "从书架删除吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        // 联动清理：该书全部备注图片文件与记录、逐本信息
                        List<NoteImage> imgs = app.db().noteImageDao().getByBook(book.id);
                        app.db().noteImageDao().deleteByBook(book.id);
                        for (NoteImage img : imgs) {
                            new File(img.path).delete();
                        }
                        app.db().bookCopyDao().deleteByBook(book.id);
                        app.db().bookDao().deleteById(book.id);
                        app.ui(() -> {
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
