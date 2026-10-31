package com.bai.bookshelf.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
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
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.ui.edit.BookEditActivity;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ReadingRecords;
import com.bai.bookshelf.util.StatusText;
import com.bai.bookshelf.util.ThemeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private ActivityBookDetailBinding b;
    private Book book;
    private String locationName = "未设置地点";
    /** 已加载的封面标识（url|书名）：reload 重绘时封面没变就不重复请求 Glide */
    private String loadedCoverKey = "";

    /** 分类展示串「一级-二级」（v4.9，reload 时解析好再给 bind 用） */
    private String categoryDisplay = "";
    private List<BorrowRecord> openRecords = new ArrayList<>(); // 当前所有未归还的借出记录（含 copyIndex）
    private List<BookCopy> copies = new ArrayList<>();
    private Map<Long, String> copyLocNames = new HashMap<>();   // 副本地点 id → 名称
    private List<NoteImage> noteImages = new ArrayList<>();     // 备注图片（默认折叠）
    private final DetailNoteImages noteImagesView = new DetailNoteImages();
    private final BookNoteBlock bookNoteBlock = new BookNoteBlock();
    private final DetailTags tagsView = new DetailTags();
    private List<Excerpt> excerpts = new ArrayList<>();         // 摘抄（v4.50，详情页只预览前 2 条）
    private boolean manageLendNoteExpanded = false;               // 单本借出备注是否展开
    private boolean manageLendImagesExpanded = false;           // 单本借出备注图片是否展开
    private Map<Integer, List<NoteImage>> copyLendImages = new HashMap<>(); // 第 N 本当前借出记录的备注图片

    /** v5.8.5：书架封面共享元素过渡的名字（没有就按普通方式打开） */
    public static final String EXTRA_TRANSITION_NAME = "transition_name";

    /** 借出 ⇄ 归还流程（登记弹窗、借阅人、备注图片） */
    private DetailLending lending;
    /** 逐本信息区（第 N 本的借还按钮、借出备注与图片） */
    private DetailCopyRows copyRows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBookDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setupSharedElementTransition();
        b.toolbar.setNavigationOnClickListener(v -> finishAfterTransition());
        // 系统返回键也走反向共享元素（默认 finish() 会跳过返回动画）
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        finishAfterTransition();
                    }
                });
        InsetsHelper.applyBottomInset(b.bottomBar);

        book = extraBook();
        if (book == null) {
            finish();
            return;
        }
        lending = new DetailLending(this, new DetailLending.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public List<BorrowRecord> openRecords() {
                return openRecords;
            }

            @Override
            public List<BookCopy> copies() {
                return copies;
            }

            @Override
            public void reload() {
                BookDetailActivity.this.reload();
            }

            @Override
            public void refreshReminders() {
                com.bai.bookshelf.util.BorrowReminder.refresh(BookDetailActivity.this);
            }

            @Override
            public void ensureNotifyPermission() {
                BookDetailActivity.this.ensureNotifyPermission();
            }
        });
        copyRows = new DetailCopyRows(this, b, new DetailCopyRows.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public List<BookCopy> copies() {
                return copies;
            }

            @Override
            public List<BorrowRecord> openRecords() {
                return openRecords;
            }

            @Override
            public Map<Long, String> copyLocNames() {
                return copyLocNames;
            }

            @Override
            public String locationName() {
                return locationName;
            }

            @Override
            public Map<Integer, List<NoteImage>> copyLendImages() {
                return copyLendImages;
            }

            @Override
            public boolean lendingDisabled() {
                return isSourceReturned();
            }

            @Override
            public void showLendDialog(int copyIndex) {
                lending.showLendDialog(copyIndex);
            }

            @Override
            public void returnCopy(int copyIndex, BorrowRecord open) {
                lending.returnCopy(copyIndex, open);
            }
        });
        // 先用 Intent 里的数据把详情画出来（首帧立即有内容），
        // 地点名/借出记录/逐本/备注图后台刷新后再重绘。
        bind();

        b.btnEdit.setOnClickListener(v -> {
            Intent intent = new Intent(this, BookEditActivity.class);
            intent.putExtra(BookEditActivity.EXTRA_BOOK, book);
            startActivity(intent);
        });
        // 单本时的借出按钮（多本时隐藏，改为逐本信息里的行内按钮）
        b.btnLend.setOnClickListener(v -> lending.toggleLend());
        b.btnReturnSource.setOnClickListener(v -> confirmReturnToSource());
        b.btnMore.setOnClickListener(v -> showMoreActions());
        b.rowBorrowHistory.setOnClickListener(v -> {
            Intent intent = new Intent(this, BorrowHistoryActivity.class);
            intent.putExtra(BorrowHistoryActivity.EXTRA_BOOK_ID, book.id);
            startActivity(intent);
        });
        b.tvNote.setOnClickListener(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(); // 编辑返回后刷新
    }

    @SuppressWarnings("deprecation")
    /**
     * 书架封面 → 详情 hero 的共享元素（v5.8.5）。
     *
     * <p>封面是异步加载的，所以用 postponeEnterTransition + 300ms 兜底：
     * 无论图片有没有回来，详情页都不会被卡住；过渡结束后把圆角从列表的 8dp
     * 补间到详情的 12dp。
     */
    private void setupSharedElementTransition() {
        final String name = getIntent().getStringExtra(EXTRA_TRANSITION_NAME);
        if (name == null || name.isEmpty()) {
            return;
        }
        androidx.core.view.ViewCompat.setTransitionName(b.ivCover, name);
        androidx.core.app.ActivityCompat.postponeEnterTransition(this);
        setEnterSharedElementCallback(new android.app.SharedElementCallback() {
            @Override
            public void onSharedElementEnd(java.util.List<String> names,
                                           java.util.List<android.view.View> views,
                                           java.util.List<android.view.View> snapshots) {
                animateCoverRadius();
            }
        });
        // 下一帧就放行：封面图还在异步加载，但共享元素视图已经就位，
        // 等 Glide 会白白多花 300ms（实测 Displayed 485ms）。
        b.ivCover.post(() -> androidx.core.app.ActivityCompat
                .startPostponedEnterTransition(BookDetailActivity.this));
    }

    /** 圆角从列表的 8dp 补间到 hero 的 12dp */
    private void animateCoverRadius() {
        android.view.View parent = (android.view.View) b.ivCover.getParent();
        if (!(parent instanceof androidx.cardview.widget.CardView)) {
            return;
        }
        final androidx.cardview.widget.CardView card = (androidx.cardview.widget.CardView) parent;
        float from = getResources().getDimension(R.dimen.radius_sm);
        float to = getResources().getDimension(R.dimen.radius_md);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(180);
        anim.addUpdateListener(a -> card.setRadius((Float) a.getAnimatedValue()));
        anim.start();
    }

    private Book extraBook() {
        Book b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b = getIntent().getSerializableExtra(EXTRA_BOOK, Book.class);
        } else {
            b = (Book) getIntent().getSerializableExtra(EXTRA_BOOK);
        }
        // v3.0：Intent 传来的 Book 同样可能带 null 字段（老数据），统一清洗后再用
        Book normalized = com.bai.bookshelf.util.BookSanitizer.normalize(b);
        if (normalized != null) {
            // 单本详情才需要按记录校正汇总，列表路径不解析 JSON
            ReadingRecords.refreshSummary(normalized);
            com.bai.bookshelf.util.BorrowSourceRecords.refreshSummary(normalized);
        }
        return normalized;
    }

    /** 重新从数据库读取最新数据（含地点名、借出记录、逐本信息、备注图片） */
    /** 阅读进度（v5.8.9）：只对「在读」显示，点按用滑杆改百分比 */
    private void bindReadingProgress() {
        boolean reading = book != null && book.readStatus == Book.READING;
        b.tvReadingProgress.setVisibility(reading ? View.VISIBLE : View.GONE);
        if (!reading) {
            return;
        }
        final int p = Math.max(0, Math.min(100, book.readingProgress));
        b.tvReadingProgress.setText(p > 0
                ? "阅读进度 " + p + "% · 点按修改"
                : "记录阅读进度（点按设置）");
        b.tvReadingProgress.setOnClickListener(v -> showProgressDialog(p));
    }

    private void showProgressDialog(int current) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        final android.widget.TextView label = new android.widget.TextView(this);
        label.setText(current + "%");
        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(100);
        bar.setProgress(current);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int value, boolean fromUser) {
                label.setText(value + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        wrap.addView(label);
        wrap.addView(bar);
        new MaterialAlertDialogBuilder(this)
                .setTitle("阅读进度")
                .setView(wrap)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("保存", (d, w) -> saveProgress(bar.getProgress()))
                .show();
    }

    private void saveProgress(int percent) {
        final BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                book.readingProgress = Math.max(0, Math.min(100, percent));
                book.updatedAt = System.currentTimeMillis();
                app.db().bookDao().update(book);
            } catch (Exception e) {
                app.ui(() -> android.widget.Toast.makeText(this,
                        "保存失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            app.ui(this::reload);
        });
    }

    private void reload() {
        DetailDataLoader.load(BookshelfApp.get(), book.id, data -> {
            if (data == null) { // 已被删除
                finish();
                return;
            }
            book = data.book;
            locationName = data.location == null ? "未设置地点" : data.location.name;
            openRecords = data.openRecords;
            copies = data.copies;
            copyLocNames = data.copyLocNames;
            noteImages = data.noteImages;
            excerpts = data.excerpts;
            copyLendImages = data.copyLendImages;
            categoryDisplay = data.categoryDisplay;
            // 借出过才显示「借出记录」入口
            b.rowBorrowHistory.setVisibility(data.recordCount > 0 ? View.VISIBLE : View.GONE);
            b.rowBorrowHistory.setText("借出记录（" + data.recordCount + " 条）");
            bind();
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
        // v5.7：来源借阅且已归还 → 藏书状态显示「电子书签」，来源 chip 隐藏
        boolean sourceReturned = isSourceReturned();
        if (sourceReturned) {
            bindHeroChip(b.tvHeroStock, StatusText.stock(Book.STOCK_BOOKMARK), false);
            b.tvHeroOwn.setVisibility(View.GONE);
        } else {
            bindHeroChip(b.tvHeroStock, StatusText.stock(book.stockStatus),
                    book.stockStatus == Book.STOCK_LENT);
            bindHeroChip(b.tvHeroOwn, StatusText.own(book.ownType), false);
        }
        bindHeroChip(b.tvHeroRead, StatusText.read(book.readStatus), false);
        // v5.7：平均评分（多次阅读记录的评分均值，0.5 步进）
        double avgRating = ReadingRecords.averageRating(ReadingRecords.load(book));
        b.tvHeroRating.setVisibility(avgRating > 0 ? View.VISIBLE : View.GONE);
        if (avgRating > 0) {
            bindHeroChip(b.tvHeroRating,
                    "★ " + ReadingRecords.formatRating(avgRating), false);
        }

        // 封面：统一走 CoverLoader（180ms 淡入 / 布面首字占位）。
        // v4.39：reload 重绘时封面没变就跳过——重复 into() 会让封面重新淡入，闪一下。
        String coverKey = book.coverUrl + "|" + book.title;
        if (!coverKey.equals(loadedCoverKey)) {
            loadedCoverKey = coverKey;
            com.bai.bookshelf.util.CoverLoader.load(b.ivCover, book);
            // v5.7 无障碍：TalkBack 聚焦封面时能报出是哪本书，而不是笼统的「书籍封面」
            b.ivCover.setContentDescription("《" + book.title + "》封面");
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

        // ===== 书目 / 藏书 / 我的书房（v5.6 抽到 DetailInfoBlocks） =====
        DetailInfoBlocks.bind(b, book, multi, hasLent, locationName, categoryDisplay,
                copies, openRecords);
        DetailRecordLists.bindReading(this, b, book);
        DetailRecordLists.bindBorrow(this, b, book);
        // v5.7：四个分区可折叠，折叠状态跨书沿用
        DetailSectionCollapse.bind(this, b.ivToggleInfo, b.llInfoGrid,
                DetailSectionCollapse.INFO, "书目信息");
        DetailSectionCollapse.bind(this, b.ivToggleCore, b.llCoreGrid,
                DetailSectionCollapse.CORE, "藏书信息");
        DetailSectionCollapse.bind(this, b.ivToggleManage, b.llManageGrid,
                DetailSectionCollapse.MANAGE, "我的书房");
        DetailSectionCollapse.bind(this, b.ivToggleRecords, b.containerReadingRecordsDetail,
                DetailSectionCollapse.RECORDS, "阅读记录");
        DetailSectionCollapse.bind(this, b.ivToggleBorrowRecords,
                b.containerBorrowRecordsDetail,
                DetailSectionCollapse.BORROW_RECORDS, "借书记录");
        View.OnClickListener readingCalendar = v -> showReadingCalendar();
        b.tvReadingRecordsTitle.setOnClickListener(readingCalendar);
        bindReadingProgress();
        b.btnReadingCalendar.setOnClickListener(readingCalendar);
        b.containerReadingRecordsDetail.setOnClickListener(readingCalendar);
        bindSectionChips();

        // 单本借出备注与图片：独立于管理信息块，文本前 20 字折叠、图片默认折叠
        BorrowRecord open1 = DetailCopyInfo.openRecordForCopy(openRecords, 1);
        if (!multi && open1 != null) {
            BorrowDueBanner.bind(this, b.tvBorrowDue, book, open1, this::reload);
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
        bookNoteBlock.bind(b, book);

        // 备注图片：默认折叠，位于备注下方
        noteImagesView.bind(this, b, noteImages);

        // 摘抄与书评（v4.50）：短评 + 最新 2 条摘抄预览
        DetailExcerpts.bind(this, b, book, excerpts);

        // v5.7：有未归还的借阅记录 → 显示「归还来源」；电子书签状态下不可借出
        b.btnReturnSource.setVisibility(
                book.ownType == Book.OWN_BORROW
                        && com.bai.bookshelf.util.BorrowSourceRecords.hasOpen(book)
                        ? View.VISIBLE : View.GONE);
        // 逐本信息块：多本时按本铺开，第 1 本也在此显示（行尾带借出/收回按钮）；>3 本默认只显前 3 本
        copyRows.render(multi, sourceReturned);
        // 借出按钮文案随状态切换（单本才有此按钮）
        b.btnLend.setText(book.stockStatus == Book.STOCK_LENT ? "收回" : "借出");

        // 标签独立区域展示（不与管理信息混在一起）
        tagsView.bind(this, b, book, this::showFullTag);

        // v3.6：内容简介已停用（API 不再导入、布局中 tv_summary 永久 gone）
    }

    /**
     * 借阅到期横幅已抽到 {@link BorrowDueBanner}（v5.2）。
     * Android 13+：借出时若设了应还日期，就申请通知权限；拒绝则靠书架「超期」角标
     */
    private void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9101);
        }
    }

    /** 单本借出备注：超过 20 字默认截断，点击切换 */
    private void renderManageLendNote(String note) {        if (note.length() <= DetailCopyRows.LEND_NOTE_PREVIEW_MAX || manageLendNoteExpanded) {
            b.tvManageLendNote.setText("借出备注：" + note);
            b.tvManageLendNote.setOnClickListener(note.length() > DetailCopyRows.LEND_NOTE_PREVIEW_MAX
                    ? v -> {
                        manageLendNoteExpanded = false;
                        renderManageLendNote(note);
                    }
                    : null);
        } else {
            b.tvManageLendNote.setText("借出备注：" + note.substring(0, DetailCopyRows.LEND_NOTE_PREVIEW_MAX) + "…");
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
            copyRows.renderImages(b.containerManageLendNoteImages, imgs);
        } else {
            b.containerManageLendNoteImages.removeAllViews();
        }
    }

    private void showFullTag(String fullTag) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("完整标签")
                .setMessage(fullTag)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ======================= 备注图片（默认折叠，UI 见 DetailNoteImages） =======================

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

    /** 底栏「更多」：删除收在这里，红字，不与编辑同权重 */
    private void showMoreActions() {
        PopupMenu popup = new PopupMenu(this, b.btnMore);
        SpannableString delete = new SpannableString("删除此藏书");
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
                DetailShare.shareBookCard(this, book, b.btnMore);
                return true;
            }
            return false;
        });
        popup.show();
    }

    // 借出 ⇄ 归还与借出登记见 DetailLending（v5.6 从本类抽出）

    /** 吸顶分区导航：概览 / 阅读 / 借阅 / 笔记只跳到当前存在的分区 */
    private void bindSectionChips() {
        b.chipOverview.setOnClickListener(v -> scrollDetailTo(b.hero));
        b.chipReading.setOnClickListener(v -> scrollDetailTo(b.cardReadingRecords));
        b.chipBorrow.setOnClickListener(v -> scrollDetailTo(b.cardBorrowRecords));

        View notesTarget = null;
        for (View candidate : new View[]{b.cardExcerpts, b.tvNote, b.cardTags}) {
            if (candidate.getVisibility() == View.VISIBLE) {
                notesTarget = candidate;
                break;
            }
        }
        b.chipNotes.setVisibility(notesTarget == null ? View.GONE : View.VISIBLE);
        if (notesTarget != null) {
            final View target = notesTarget;
            b.chipNotes.setOnClickListener(v -> scrollDetailTo(target));
        }
        b.chipReading.setVisibility(b.cardReadingRecords.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        b.chipBorrow.setVisibility(b.cardBorrowRecords.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    private void scrollDetailTo(View target) {
        b.scrollDetail.post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            b.scrollDetail.offsetDescendantRectToMyCoords(target, rect);
            b.scrollDetail.smoothScrollTo(0, Math.max(0, rect.top));
        });
    }

    /** 本书的阅读日历（按阅读记录起止日期铺出每天） */
    private void showReadingCalendar() {
        Map<String, java.util.List<String>> titles =
                com.bai.bookshelf.util.ReadingCalendar.titlesByDay(book);
        Map<String, Integer> days = new java.util.LinkedHashMap<>();
        for (String day : titles.keySet()) {
            days.put(day, 1);
        }
        int[] ymd = com.bai.bookshelf.util.ReadingCalendar.latestYmd(days);
        com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.show(this,
                getString(R.string.reading_calendar_title),
                getString(R.string.reading_calendar_book_summary, days.size()),
                days, titles,
                new com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.Start(false, ymd[0], ymd[1]),
                com.bai.bookshelf.ui.stats.PurchaseCalendarSheet.readingLabels(),
                null);
    }

    /** 来源为借阅且已归还：不能再借出、也不再展示存放/来源 */
    private boolean isSourceReturned() {
        return com.bai.bookshelf.util.BookFlags.isReturnedBorrow(book);
    }

    /** 归还给借阅来源：把最新未归还的借书记录标记归还，状态变为电子书签 */
    private void confirmReturnToSource() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("归还给借阅来源")
                .setMessage("确认已把" + com.bai.bookshelf.util.BookText.quotedTitle(book.title)
                        + "归还给借阅来源？归还后藏书状态变为「电子书签」，不再显示存放地点与来源；"
                        + "之后再添加新的借书记录即可重新借阅。")
                .setPositiveButton("确认归还", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        String time = StatusText.today() + " "
                                + new java.text.SimpleDateFormat("HH:mm",
                                java.util.Locale.getDefault()).format(new java.util.Date());
                        String returned = com.bai.bookshelf.util.BorrowSourceRecords
                                .returnLatest(book, time);
                        if (returned == null) {
                            app.ui(() -> Toast.makeText(this, "没有待归还的借书记录",
                                    Toast.LENGTH_SHORT).show());
                            return;
                        }
                        book.updatedAt = System.currentTimeMillis();
                        book.dirty = true;
                        app.db().bookDao().update(book);
                        app.ui(() -> {
                            Toast.makeText(this, "已归还，记录归还时间 " + returned,
                                    Toast.LENGTH_SHORT).show();
                            com.bai.bookshelf.util.BorrowReminder.refresh(this);
                            reload();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
