package com.bai.bookshelf.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.databinding.ItemDetailCopyBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
    /** 已加载的封面标识（url|书名）：reload 重绘时封面没变就不重复请求 Glide */
    private String loadedCoverKey = "";
    /** 已渲染的标签串：reload 重绘时标签没变就不重建 Chip */
    private String loadedTags;

    /** 分类展示串「一级-二级」（v4.9，reload 时解析好再给 bind 用） */
    private String categoryDisplay = "";
    private List<BorrowRecord> openRecords = new ArrayList<>(); // 当前所有未归还的借出记录（含 copyIndex）
    private int lendCopyIndex = 1;                              // 本次借出操作针对第几本
    private List<BookCopy> copies = new ArrayList<>();
    private Map<Long, String> copyLocNames = new HashMap<>();   // 副本地点 id → 名称
    private List<NoteImage> noteImages = new ArrayList<>();     // 备注图片（默认折叠）
    private List<Excerpt> excerpts = new ArrayList<>();         // 摘抄（v4.50，详情页只预览前 2 条）
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
        InsetsHelper.applyBottomInset(b.bottomBar);

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
        // 先用 Intent 里的数据把详情画出来（首帧立即有内容），
        // 地点名/借出记录/逐本/备注图后台刷新后再重绘。
        bind();

        b.btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(this, BookEditActivity.class);
            intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
            startActivity(intent);
        });
        // 单本时的借出按钮（多本时隐藏，改为逐本信息里的行内按钮）
        b.btnLend.setOnClickListener(v -> toggleLend());
        b.btnMore.setOnClickListener(v -> showMoreActions());
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
            // v4.39：查询收敛。借出记录一次取全再在内存里拆「借出中 / 总数」；
            // 地点一次取全；备注图一次取全按 ownerKey 分组——原先是逐本、逐条记录
            // 各查一次（N+1），套装书要发几十条查询。
            List<BorrowRecord> allRecords = app.db().borrowRecordDao().getByBook(book.id);
            int recordCount = allRecords.size();
            List<BorrowRecord> openList = new ArrayList<>();
            for (BorrowRecord r : allRecords) {
                if (r.status == BorrowRecord.STATUS_OUT) {
                    openList.add(r);
                }
            }
            // 逐本信息 + 各地点名称解析
            List<BookCopy> copyList = app.db().bookCopyDao().getByBook(book.id);
            Map<Long, String> locNames = new HashMap<>();
            for (Location l : app.db().locationDao().getAll()) {
                locNames.put(l.id, l.name);
            }
            for (BookCopy c : copyList) {
                if (c.locationId != null && !locNames.containsKey(c.locationId)) {
                    locNames.put(c.locationId, "未设置地点"); // 地点已被删除
                }
            }
            // 备注图片（书级 + 各借出记录）一次取全
            Map<String, List<NoteImage>> imgsByOwner = new HashMap<>();
            for (NoteImage img : app.db().noteImageDao().getByBook(book.id)) {
                List<NoteImage> list = imgsByOwner.get(img.ownerKey);
                if (list == null) {
                    list = new ArrayList<>();
                    imgsByOwner.put(img.ownerKey, list);
                }
                list.add(img);
            }
            List<NoteImage> foundImgs = imgsByOwner.get("book:" + book.id);
            final List<NoteImage> imgs = foundImgs == null ? new ArrayList<>() : foundImgs;
            List<Excerpt> excerptList = app.db().excerptDao().getByBook(book.id);
            // v4.9：分类展示格式「一级-二级」（书里只存名字，需查分类库解析上级）
            String catDisplay = com.bai.bookshelf.util.CategoryPropagator
                    .displayPath(book.category, app.db().categoryDao().getAll());
            final String fCatDisplay = catDisplay;
            // 加载每本当前借出记录的备注图片
            Map<Integer, List<NoteImage>> lendImgs = new HashMap<>();
            for (BorrowRecord r : openList) {
                List<NoteImage> list = imgsByOwner.get("borrow:" + r.id);
                if (list != null && !list.isEmpty()) {
                    lendImgs.put(r.copyIndex, list);
                }
            }
            app.ui(() -> {
                locationName = loc == null ? "未设置地点" : loc.name;
                openRecords = openList;
                copies = copyList;
                copyLocNames = locNames;
                noteImages = imgs;
                excerpts = excerptList;
                copyLendImages = lendImgs;
                categoryDisplay = fCatDisplay;
                // 借出过才显示「借出记录」入口
                b.rowBorrowHistory.setVisibility(recordCount > 0 ? View.VISIBLE : View.GONE);
                b.rowBorrowHistory.setText("借出记录（" + recordCount + " 条）");
                bind();
            });
        });
    }

    /** 把书籍信息渲染到界面 */
    private void bind() {
        b.tvTitle.setText(com.bai.bookshelf.util.BookText.displayTitle(book.title));

        // hero：作者/译者/国家 + 状态 chips（书名与大信息量交给下面的两列信息块）
        List<String> authors = new ArrayList<>();
        addIfNotEmpty(authors, book.author);
        addIfNotEmpty(authors, book.translator);
        addIfNotEmpty(authors, book.country);
        String authorLine = joinWithDot(authors);
        b.tvHeroAuthor.setText(authorLine);
        b.tvHeroAuthor.setVisibility(authorLine.isEmpty() ? View.GONE : View.VISIBLE);
        bindHeroChip(b.tvHeroStock, StatusText.stock(book.stockStatus),
                book.stockStatus == Book.STOCK_LENT);
        bindHeroChip(b.tvHeroRead, StatusText.read(book.readStatus), false);
        bindHeroChip(b.tvHeroOwn, StatusText.own(book.ownType), false);

        // 封面：统一走 CoverLoader（180ms 淡入 / 布面首字占位）。
        // v4.39：reload 重绘时封面没变就跳过——重复 into() 会让封面重新淡入，闪一下。
        String coverKey = book.coverUrl + "|" + book.title;
        if (!coverKey.equals(loadedCoverKey)) {
            loadedCoverKey = coverKey;
            com.bai.bookshelf.util.CoverLoader.load(b.ivCover, book);
            // v4.30：hero 用封面做模糊底，叠 85% 纸色遮罩（每本书有自己的颜色情绪）
            int scrimColor = ThemeManager.resolveColor(
                    this, R.attr.primary_container);
            b.vHeroScrim.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    scrimColor, 217));
            com.bai.bookshelf.util.CoverLoader.loadBlurred(b.ivHeroBg, book);
        }
        boolean multi = book.quantity > 1;
        boolean hasLent = !openRecords.isEmpty();

        // 系列（v4.53）：有系列时在书目信息上方给一条可点入口
        String seriesName = com.bai.bookshelf.util.BookSeries.name(book);
        if (seriesName.isEmpty()) {
            b.cardSeries.setVisibility(View.GONE);
        } else {
            String seriesVolume = com.bai.bookshelf.util.BookSeries.volumeLabel(book);
            b.tvSeriesInfo.setText(seriesVolume.isEmpty()
                    ? seriesName : seriesName + " · " + seriesVolume);
            b.cardSeries.setVisibility(View.VISIBLE);
            b.cardSeries.setOnClickListener(v -> {
                android.content.Intent intent = new android.content.Intent(this,
                        com.bai.bookshelf.ui.series.SeriesActivity.class);
                intent.putExtra(com.bai.bookshelf.ui.series.SeriesActivity.EXTRA_SERIES_NAME,
                        seriesName);
                startActivity(intent);
            });
        }

        // ===== 书目信息（两列 label/value） =====
        List<String[]> info = new ArrayList<>();
        addInfo(info, "出版社", book.publisher);
        addInfo(info, "出版年", book.publishDate);
        addInfo(info, "印次", printingText(book.printing));
        if (book.pages > 0) {
            addInfo(info, "页数", book.pages + " 页");
        }
        addInfo(info, "字数", book.wordCount);
        if (book.listPrice > 0) {
            addInfo(info, "定价", money(book.listPrice));
        }
        if (multi) {
            addInfo(info, "拥有数量", book.quantity + " 本");
        }
        addInfo(info, "豆瓣评分", book.doubanRating);
        addInfo(info, "分类", categoryDisplay);
        addInfo(info, "ISBN", formatIsbn(book.isbn));
        buildInfoGrid(b.llInfoGrid, info);
        b.cardInfo.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

        // ===== 藏书信息 =====
        List<String[]> core = new ArrayList<>();
        // 书籍被借出时隐藏存放地点（v2.1 修复）
        if (!hasLent) {
            addInfo(core, "存放地点", locationName);
        }
        // 来源：单本时展示；多本时不展示（逐本信息里各自有来源）
        if (!multi) {
            String source = book.sourceChannel.isEmpty()
                    ? StatusText.own(book.ownType) + "（" + channelHint(book.ownType) + "）"
                    : StatusText.own(book.ownType) + " · " + book.sourceChannel;
            addInfo(core, "来源", source);
        }
        if (multi) {
            addInfo(core, "藏书状态", multiCopyStockSummary());
        } else {
            addInfo(core, "藏书状态", StatusText.stock(book.stockStatus));
            BorrowRecord open1 = openRecordForCopy(1);
            if (book.stockStatus == Book.STOCK_LENT && open1 != null) {
                addInfo(core, "借给", open1.borrower + "（" + open1.lendDate + "）");
            }
        }
        addInfo(core, "录入时间", StatusText.formatTime(book.createdAt));
        buildInfoGrid(b.llCoreGrid, core);
        b.cardCore.setVisibility(core.isEmpty() ? View.GONE : View.VISIBLE);

        // ===== 我的书房 =====
        List<String[]> manage = new ArrayList<>();
        addInfo(manage, "阅读状态", StatusText.read(book.readStatus));
        if (!book.readingStartDate.isEmpty()) {
            addInfo(manage, "开始阅读", book.readingStartDate);
        }
        if (!book.readingFinishDate.isEmpty()) {
            String finish = book.readingFinishDate;
            int startKey = com.bai.bookshelf.util.BorrowReminder
                    .parseKey(book.readingStartDate);
            int finishKey = com.bai.bookshelf.util.BorrowReminder
                    .parseKey(book.readingFinishDate);
            if (startKey > 0 && finishKey >= startKey) {
                finish += "（" + com.bai.bookshelf.util.BorrowReminder
                        .daysBetween(startKey, finishKey) + " 天）";
            }
            addInfo(manage, "读完日期", finish);
        }
        addInfo(manage, "购买日期", book.purchaseDate);
        if (book.purchasePrice > 0) {
            String paid = money(book.purchasePrice);
            if (book.listPrice > 0) {
                paid += String.format(Locale.getDefault(), "（%.2f折）",
                        book.purchasePrice / book.listPrice * 10);
            }
            addInfo(manage, "购入价", paid);
        }
        buildInfoGrid(b.llManageGrid, manage);
        b.cardManage.setVisibility(manage.isEmpty() ? View.GONE : View.VISIBLE);

        // 单本借出备注与图片：独立于管理信息块，文本前 20 字折叠、图片默认折叠
        BorrowRecord open1 = openRecordForCopy(1);
        if (!multi && open1 != null) {
            bindBorrowDue(open1);
        } else {
            b.tvBorrowDue.setVisibility(View.GONE);
        }
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

        // 摘抄与书评（v4.50）：短评 + 最新 2 条摘抄预览
        bindExcerpts();

        // 逐本信息块：多本时按本铺开，第 1 本也在此显示（行尾带借出/归还按钮）；>3 本默认只显前 3 本
        b.btnLend.setVisibility(multi ? View.GONE : View.VISIBLE);
        b.cardCopies.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (multi) {
            b.containerCopies.setVisibility(View.VISIBLE);
            buildCopyRows();
        } else {
            b.containerCopies.setVisibility(View.GONE);
            b.containerCopies.removeAllViews();
        }
        // 借出按钮文案随状态切换（单本才有此按钮）
        b.btnLend.setText(book.stockStatus == Book.STOCK_LENT ? "归还" : "借出");

        // 标签独立区域展示（不与管理信息混在一起）；标签没变就不重建 Chip
        String tags = book.tags.trim();
        if (!tags.equals(loadedTags)) {
            loadedTags = tags;
            b.chipGroupTags.removeAllViews();
            if (tags.isEmpty()) {
                b.cardTags.setVisibility(View.GONE);
                b.tvTagsLabel.setVisibility(View.GONE);
                b.chipGroupTags.setVisibility(View.GONE);
            } else {
                b.cardTags.setVisibility(View.VISIBLE);
                b.tvTagsLabel.setVisibility(View.VISIBLE);
                b.chipGroupTags.setVisibility(View.VISIBLE);
                for (String t : tags.split(",")) {
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
        }

        // v3.6：内容简介已停用（API 不再导入、布局中 tv_summary 永久 gone）
    }

    /**
     * 借阅到期横幅（v4.42）：显示应还/逾期天数；点按复制催还文案，长按修改应还日期。
     * 未设日期时点按直接补设。
     */
    private void bindBorrowDue(BorrowRecord open) {
        b.tvBorrowDue.setVisibility(View.VISIBLE);
        int due = com.bai.bookshelf.util.BorrowReminder.parseKey(open.dueDate);
        int today = com.bai.bookshelf.util.BorrowReminder.todayKey();
        boolean overdue = due > 0 && due < today;
        String text;
        if (due == 0) {
            text = "借出中 · 未设置应还日期（点按设置）";
        } else if (overdue) {
            text = "已逾期 " + com.bai.bookshelf.util.BorrowReminder.daysBetween(due, today)
                    + " 天 · 应还 " + open.dueDate + " · 点按复制催还文案";
        } else if (due == today) {
            text = "今天到期 · 点按复制催还文案";
        } else {
            text = "应还 " + open.dueDate + " · 还有 "
                    + com.bai.bookshelf.util.BorrowReminder.daysBetween(today, due)
                    + " 天 · 点按复制催还文案";
        }
        b.tvBorrowDue.setText(text);
        b.tvBorrowDue.setTextColor(overdue
                ? ContextCompat.getColor(this, R.color.danger)
                : ThemeManager.resolveColor(this, R.attr.on_surface));
        b.tvBorrowDue.setOnClickListener(v -> {
            if (due == 0) {
                showDuePicker(open);
            } else {
                copyReminder(open);
            }
        });
        b.tvBorrowDue.setOnLongClickListener(v -> {
            showDuePicker(open);
            return true;
        });
    }

    private void copyReminder(BorrowRecord open) {
        String text = com.bai.bookshelf.util.BorrowReminder.reminderText(book, open, null);
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("催还文案", text));
            Toast.makeText(this, "催还文案已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDuePicker(BorrowRecord open) {
        int key = com.bai.bookshelf.util.BorrowReminder.parseKey(open.dueDate);
        com.bai.bookshelf.ui.widget.CalendarPicker.showSingle(this, "应还日期", key,
                picked -> {
                    open.dueDate = com.bai.bookshelf.util.BorrowReminder.formatKey(picked);
                    saveDueDate(open);
                },
                () -> {
                    open.dueDate = "";
                    saveDueDate(open);
                });
    }

    private void saveDueDate(BorrowRecord open) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            app.db().borrowRecordDao().update(open);
            app.ui(() -> {
                reload();
                com.bai.bookshelf.util.BorrowReminder.refresh(this);
            });
        });
    }

    /** Android 13+：借出时若设了应还日期，就申请通知权限；拒绝则靠书架「超期」角标 */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9101);
        }
    }

    /** 单本借出备注：超过 20 字默认截断，点击切换 */
    private void renderManageLendNote(String note) {        if (note.length() <= LEND_NOTE_PREVIEW_MAX || manageLendNoteExpanded) {
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
                ? "借出备注图片（" + imgs.size() + " 张）· 收起"
                : "借出备注图片（" + imgs.size() + " 张）· 展开");
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
        new MaterialAlertDialogBuilder(this)
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
        // v4.39：只 inflate 真正显示的行。之前 8 本套装会把 5 行隐藏项也建出来，
        // 首帧和 reload 重绘各多花 20ms+。
        for (int i = 1; i <= showLimit; i++) {
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
            rb.getRoot().setVisibility(View.VISIBLE);
            b.containerCopies.addView(rb.getRoot());
            // 行间细分割线（最后一行不画）
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
                expandRow.setText("收起逐本信息");
            } else {
                expandRow.setText("展开剩余 " + (book.quantity - COPY_COLLAPSE_LIMIT) + " 本");
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
            int due = com.bai.bookshelf.util.BorrowReminder.parseKey(open.dueDate);
            if (due > 0) {
                line.append("｜应还 ").append(open.dueDate);
                if (due < com.bai.bookshelf.util.BorrowReminder.todayKey()) {
                    line.append("（逾期）");
                }
            }
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
                ? "借出备注图片（" + imgs.size() + " 张）· 收起"
                : "借出备注图片（" + imgs.size() + " 张）· 展开");
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
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("借出备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ======================= 备注图片（默认折叠） =======================

    // ======================= 摘抄与书评（v4.50） =======================

    /** 详情页只做入口与预览：短评 + 最新 2 条摘抄，全部内容在摘抄页 */
    private void bindExcerpts() {
        List<Excerpt> list = excerpts == null ? new ArrayList<>() : excerpts;
        b.tvExcerptCount.setText(list.isEmpty() ? "还没记录" : list.size() + " 条");

        String review = book.review == null ? "" : book.review.trim();
        b.tvDetailReview.setVisibility(review.isEmpty() ? View.GONE : View.VISIBLE);
        if (!review.isEmpty()) {
            b.tvDetailReview.setText("短评：" + review);
        }

        b.llExcerptPreview.removeAllViews();
        for (int i = 0; i < Math.min(2, list.size()); i++) {
            Excerpt e = list.get(i);
            TextView tv = new TextView(this);
            String prefix = e.page > 0 ? "P." + e.page + "  " : "";
            tv.setText(prefix + e.content.trim().replace('\n', ' '));
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.text_body_small));
            tv.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                    this, R.attr.on_surface_variant));
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            b.llExcerptPreview.addView(tv, lp);
        }

        b.rowAllExcerpts.setText(list.isEmpty()
                ? "写下第一条摘抄 ›" : "查看全部 " + list.size() + " 条 ›");
        b.cardExcerpts.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(
                    this, com.bai.bookshelf.ui.excerpt.ExcerptListActivity.class);
            intent.putExtra(com.bai.bookshelf.ui.excerpt.ExcerptListActivity.EXTRA_BOOK_ID, book.id);
            intent.putExtra(com.bai.bookshelf.ui.excerpt.ExcerptListActivity.EXTRA_BOOK_TITLE,
                    book.title);
            startActivity(intent);
        });
    }

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
                ? "备注图片（" + noteImages.size() + " 张）· 收起"
                : "备注图片（" + noteImages.size() + " 张）· 展开");
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
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("备注图片")
                .setView(pb.getRoot())
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

    private void addIfNotEmpty(List<String> list, String value) {
        if (value != null && !value.trim().isEmpty()) {
            list.add(value.trim());
        }
    }

    private static String joinWithDot(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private void addInfo(List<String[]> items, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            items.add(new String[]{label, value.trim()});
        }
    }

    /** 词条式信息列表：label 固定 72dp，value 占满右侧，长值不再把两列撑得参差 */
    private void buildInfoGrid(LinearLayout container, List<String[]> items) {
        int size = items.size();
        while (container.getChildCount() < size) {
            container.addView(new InfoCell(this));
        }
        // v4.39：信息行改为代码构建 + 复用。原先每行一次 XML inflate，
        // 实机 17 行要 22~27ms（每次 reload 重绘还要再来一遍）；
        // 现在首帧只建一次、之后只改文字/显隐。
        for (int i = 0; i < container.getChildCount(); i++) {
            InfoCell row = (InfoCell) container.getChildAt(i);
            if (i < size) {
                row.setVisibility(View.VISIBLE);
                row.label.setText(items.get(i)[0]);
                row.value.setText(items.get(i)[1]);
            } else {
                row.setVisibility(View.GONE);
            }
        }
    }

    /** 词条式信息行：label 固定 72dp，value 占满右侧，长值不再把两列撑得参差 */
    private static final class InfoCell extends LinearLayout {
        final TextView label;
        final TextView value;

        InfoCell(Context ctx) {
            super(ctx);
            float density = ctx.getResources().getDisplayMetrics().density;
            int padV = Math.round(7 * density);
            setOrientation(HORIZONTAL);
            setPadding(0, padV, 0, padV);
            label = new TextView(ctx);
            label.setLayoutParams(new LayoutParams(Math.round(72 * density), LayoutParams.WRAP_CONTENT));
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setLetterSpacing(0.04f);
            label.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimensionPixelSize(R.dimen.text_body_small));
            value = new TextView(ctx);
            value.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            value.setLineSpacing(0, 1.35f);
            value.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface));
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    ctx.getResources().getDimensionPixelSize(R.dimen.text_body));
            addView(label);
            addView(value);
        }
    }

    /** hero 状态 chip：借出用黄铜底白字，其余用白底主色字 */
    private void bindHeroChip(TextView tv, String text, boolean emphasize) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        if (emphasize) {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.badge_borrowed)));
            tv.setTextColor(Color.WHITE);
        } else {
            tv.setBackgroundResource(R.drawable.bg_status_chip);
            tv.setBackgroundTintList(null);
            tv.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
        }
    }

    private String money(double value) {
        return "¥" + String.format(Locale.getDefault(), "%.2f", value);
    }

    /** 印次存储格式 yyyy-mm|N：能解析出 N 时展示「第 N 次印刷」，否则原样显示 */
    private String printingText(String printing) {
        int count = com.bai.bookshelf.util.BookSanitizer.parsePrintingCount(printing);
        return count > 0 ? "第 " + count + " 次印刷" : printing;
    }

    /** 底栏「更多」：删除收在这里，红字，不与编辑同权重 */
    private void showMoreActions() {
        PopupMenu popup = new PopupMenu(this, b.btnMore);
        SpannableString delete = new SpannableString("删除这本书");
        delete.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.danger)),
                0, delete.length(), 0);
        popup.getMenu().add(0, 2, 0, "分享书卡");
        popup.getMenu().add(0, 1, 1, delete);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                confirmDelete();
                return true;
            }
            if (item.getItemId() == 2) {
                shareBookCard();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** v4.52：把当前这本书画成一张 1080×1440 的分享卡 */
    private void shareBookCard() {
        final Book snapshot = book;
        BookshelfApp.get().io(() -> {
            android.graphics.Bitmap cover = null;
            try {
                String url = snapshot.coverUrl;
                if (url != null && !url.isEmpty()) {
                    cover = Glide.with(getApplicationContext()).asBitmap()
                            .load(url.startsWith("http") ? url : new File(url))
                            .submit(640, 880)
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
            final android.graphics.Bitmap fCover = cover;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                android.graphics.Bitmap card =
                        com.bai.bookshelf.util.BookShareCard.render(snapshot, fCover);
                try {
                    File dir = new File(getFilesDir(), "share");
                    if (!dir.exists() && !dir.mkdirs()) {
                        return;
                    }
                    File outFile = new File(dir,
                            "book_" + snapshot.id + "_" + System.currentTimeMillis() + ".png");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                        card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", outFile);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("image/png");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TEXT,
                            "《" + com.bai.bookshelf.util.BookText.bareTitle(snapshot.title)
                                    + "》" + (snapshot.author.isEmpty()
                                    ? "" : " · " + snapshot.author) + " —— 来自「小书房」");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "分享书卡"));
                    b.btnMore.performHapticFeedback(
                            android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Exception e) {
                    Toast.makeText(this, "分享失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
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
                com.bai.bookshelf.util.BorrowReminder.refresh(this);
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

        // v4.42：选中借阅人时显示其联系方式（长按 Chip 可补充）
        Runnable refreshContact = () -> {
            String phone = "";
            for (com.bai.bookshelf.data.entity.Borrower borrower : all) {
                if (borrower.name.equals(selected[0])) {
                    phone = borrower.phone == null ? "" : borrower.phone;
                    break;
                }
            }
            if (selected[0].isEmpty()) {
                db.tvBorrowerContact.setText("未选择借阅人");
            } else if (phone.isEmpty()) {
                db.tvBorrowerContact.setText("联系方式：未填写（长按借阅人可补充）");
            } else {
                db.tvBorrowerContact.setText("联系方式：" + phone);
            }
        };
        refreshContact.run();
        db.chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> refreshContact.run());

        // 借出备注图片
        db.btnAddNoteImage.setOnClickListener(v -> {
            try {
                pickLendImageLauncher.launch("image/*");
            } catch (Exception e) {
                Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        renderLendNoteThumbs(db);

        // v4.42：应还日期（默认 30 天后，可清空；到期后由本地通知提醒）
        final String[] dueDate = {defaultDueDate()};
        Runnable refreshDue = () -> db.tvDueDate.setText(dueDate[0].isEmpty()
                ? "应还日期：未设置"
                : "应还日期：" + dueDate[0]);
        refreshDue.run();
        db.tvDueDate.setOnClickListener(v -> com.bai.bookshelf.ui.widget.CalendarPicker.showSingle(
                this, "应还日期", com.bai.bookshelf.util.BorrowReminder.parseKey(dueDate[0]),
                key -> {
                    dueDate[0] = com.bai.bookshelf.util.BorrowReminder.formatKey(key);
                    refreshDue.run();
                },
                () -> {
                    dueDate[0] = "";
                    refreshDue.run();
                }));

        new MaterialAlertDialogBuilder(this)
                .setTitle("借出登记（第 " + lendCopyIndex + " 本）")
                .setView(db.getRoot())
                .setPositiveButton("借出", (d, w) ->
                        doLend(lendCopyIndex, selected[0], db.etNote.getText().toString().trim(),
                                dueDate[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 默认应还日期：今天 + 30 天 */
    private String defaultDueDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_MONTH, 30);
        return com.bai.bookshelf.util.BorrowReminder.formatKey(
                c.get(java.util.Calendar.YEAR) * 10000
                        + (c.get(java.util.Calendar.MONTH) + 1) * 100
                        + c.get(java.util.Calendar.DAY_OF_MONTH));
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
        // v4.18: 图片放进圆角卡片底（dialog_image_preview）
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("备注图片")
                .setView(pb.getRoot())
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
        String[] options = {"重命名", "编辑联系方式", "删除"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("借阅人「" + borrower.name + "」")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRenameBorrowerInput(borrower, chip, selected);
                    } else if (which == 1) {
                        showBorrowerPhoneInput(borrower);
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
        new MaterialAlertDialogBuilder(this)
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

    /** 编辑借阅人联系方式（v4.42）：催还时可直接照着联系 */
    private void showBorrowerPhoneInput(com.bai.bookshelf.data.entity.Borrower borrower) {
        DialogInputBinding ib = DialogInputBinding.inflate(getLayoutInflater());
        ib.inputLayout.setHint("电话 / 微信等联系方式");
        ib.edit.setText(borrower.phone == null ? "" : borrower.phone);
        new MaterialAlertDialogBuilder(this)
                .setTitle("「" + borrower.name + "」的联系方式")
                .setView(ib.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    String phone = ib.edit.getText().toString().trim();
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        borrower.phone = phone;
                        app.db().borrowerDao().update(borrower);
                        app.ui(() -> Toast.makeText(this,
                                phone.isEmpty() ? "已清空联系方式" : "联系方式已保存",
                                Toast.LENGTH_SHORT).show());
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除借阅人：确认后从库中删除并移除 Chip */
    private void confirmDeleteBorrower(com.bai.bookshelf.data.entity.Borrower borrower,
                                       com.google.android.material.chip.Chip chip,
                                       String[] selected) {
        new MaterialAlertDialogBuilder(this)
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
        ib.inputLayout.setHint("姓名或称呼（如 小王、表姐）");
        new MaterialAlertDialogBuilder(this)
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
    private void doLend(int copyIndex, String borrower, String note, String dueDate) {
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
            record.dueDate = dueDate == null ? "" : dueDate;
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
                // v4.42：有应还日期就确保通知权限并重排提醒
                if (dueDate != null && !dueDate.isEmpty()) {
                    ensureNotifyPermission();
                    com.bai.bookshelf.util.BorrowReminder.refresh(this);
                }
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除书籍")
                .setMessage("确定把" + com.bai.bookshelf.util.BookText.quotedTitle(book.title)
                        + "从书架删除吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        // v4.23：级联清理收敛到 BookCascade（备注图+副本+借出记录+封面文件）
                        com.bai.bookshelf.util.BookCascade.deleteBook(app, book.id, book.coverUrl);
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
