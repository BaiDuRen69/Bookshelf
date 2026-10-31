package com.bai.bookshelf.ui.edit;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.bai.bookshelf.ui.BaseActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 书籍编辑页，三种场景共用：
 * 1. 扫码/查询后确认入库（带 EXTRA_PREFILL 预填的 Book）
 * 2. 完全手动录入（不带 extra）
 * 3. 编辑已有藏书（带 EXTRA_BOOK，id > 0）
 *
 * 封面支持三种来源：网络链接、拍照、相册（后两者保存为本地文件路径）。
 */
public class BookEditActivity extends BaseActivity {

    public static final String EXTRA_PREFILL = "prefill"; // 新增（预填信息）
    public static final String EXTRA_BOOK = "book";       // 编辑（已有书籍）

    private ActivityBookEditBinding b;
    private Book book;                    // 正在编辑的书籍对象（保存时直接写库）
    private boolean editMode;             // true=编辑已有书
    private CoverEditor coverEditor;
    /** 地点/来源下拉与脏检查基准（v5.6 从本类抽出） */
    private EditPlaceChannels places;
    /** 表单绑定与折叠区（v5.6 从本类抽出） */
    private BookFormBinder formBinder;
    /** 保存流程（v5.6 从本类抽出） */
    private BookSaveFlow saveFlow;
    /** 阅读记录（v5.7） */
    private ReadingRecordSection readingRecords;
    /** 借书记录（v5.7） */
    private BorrowRecordSection borrowRecords;

    /** 退出时的脏检查基准值（列表异步加载完成后记录） */
    private String initialTags = "";

    /** 来源大类对应的二级标签文案（与 own_type_entries 顺序一致，主表单用） */
    private static final String[] CHANNEL_LABELS = CopyFormBuilder.CHANNEL_LABELS;

    /** 备注图片（书级 + 逐本共用一个相册入口） */
    private EditNoteImages noteImageEditor;
    /** 逐本信息栏目：内联表单 / 二级页切换、逐本备注图片 */
    private EditCopiesSection copySection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBookEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        coverEditor = new CoverEditor(this, b);
        // v4.25：固定保存底栏需要避开系统手势条
        // v4.28：Android 15+ 键盘不会再压缩窗口，取 ime 与导航栏的较大值，保存按钮始终在键盘上方
        com.bai.bookshelf.util.InsetsHelper.applyBottomInsetWithIme(b.bottomBar);
        b.toolbar.setNavigationOnClickListener(v -> attemptExit());

        // 系统返回键同样走未保存确认
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        attemptExit();
                    }
                });

        // 解析进入场景
        Book extra = extraBook(EXTRA_BOOK);
        if (extra != null) {
            editMode = true;
            book = extra;
            b.toolbar.setTitle("编辑书籍");
        } else {
            editMode = false;
            book = extraBook(EXTRA_PREFILL);
            if (book == null) {
                book = new Book();
            }
        }

        formBinder = new BookFormBinder(this, b, book, coverEditor);
        readingRecords = new ReadingRecordSection(this, b, book);
        borrowRecords = new BorrowRecordSection(this, b, book);
        formBinder.setupSections();
        places = new EditPlaceChannels(this, b, new EditPlaceChannels.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public boolean editMode() {
                return editMode;
            }

            @Override
            public void onLocationsReady() {
                copySection.onLocationsReady();
            }
        });
        noteImageEditor = new EditNoteImages(this, b, new EditNoteImages.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public void addCopyNoteImage(NoteImage img, int copyIndex) {
                copySection.addCopyNoteImage(img, copyIndex);
            }
        });
        copySection = new EditCopiesSection(this, b, new EditCopiesSection.Host() {
            @Override
            public Book book() {
                return book;
            }

            @Override
            public boolean editMode() {
                return editMode;
            }

            @Override
            public List<Location> locations() {
                return places.locations();
            }

            @Override
            public void pickCopyNoteImage(int copyIndex) {
                noteImageEditor.pickForCopy(copyIndex);
            }
        });
        saveFlow = new BookSaveFlow(this, new BookSaveFlow.Host() {
            @Override
            public ActivityBookEditBinding b() {
                return b;
            }

            @Override
            public Book book() {
                return book;
            }

            @Override
            public boolean editMode() {
                return editMode;
            }

            @Override
            public String categoryRaw() {
                return categoryRawFromField();
            }

            @Override
            public List<Location> locations() {
                return places.locations();
            }

            @Override
            public List<SourceChannel> channels() {
                return places.channels();
            }

            @Override
            public List<com.bai.bookshelf.data.entity.BookCopy> collectCopies() {
                return copySection.collect();
            }

            @Override
            public Map<Integer, List<NoteImage>> copyImages() {
                return copySection.imageSnapshot();
            }

            @Override
            public List<NoteImage> noteImages() {
                return noteImageEditor.snapshotForSave();
            }

            @Override
            public void syncReadingRecords() {
                readingRecords.syncToBook();
            }

            @Override
            public void syncBorrowRecords() {
                borrowRecords.syncToBook();
            }

            @Override
            public void onSaved() {
                finishAfterSave();
            }

            @Override
            public void onCopyAdded(Book existing) {
                cleanupRemovedNoteImageFiles();
                Toast.makeText(BookEditActivity.this, "已增加到《"
                                + com.bai.bookshelf.util.BookText.bareTitle(existing.title)
                                + "》，共 " + (existing.quantity + 1) + " 本",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        setupSpinners();
        formBinder.bindFields();
        updateCategoryDisplay();
        initialTags = nz(book.tags); // 脏检查基准：标签由选择器直接写 book，需单独留底
        noteImageEditor.loadIfEdit(editMode);
        places.loadLocations();
        setupInteractions();
        setupDoubanFetch();
        setupSectionChips();
        SplitHint.show(this, b);
    }

    /** 编辑页吸顶分区导航：基本信息 / 出版 / 藏书 / 阅读 */
    private void setupSectionChips() {
        b.chipEditBasic.setOnClickListener(v -> scrollEditTo(null));
        b.chipEditPublish.setOnClickListener(v -> {
            if (b.llPublishGroup.getVisibility() != android.view.View.VISIBLE) {
                b.tvPublishToggle.performClick();
            }
            scrollEditTo(b.tvPublishToggle);
        });
        b.chipEditStock.setOnClickListener(v -> scrollEditTo(b.tvEditMineInfo));
        b.chipEditReading.setOnClickListener(v -> scrollEditTo(b.llReadingRecords));
    }

    private void scrollEditTo(android.view.View target) {
        b.scrollEdit.post(() -> {
            if (target == null) {
                b.scrollEdit.smoothScrollTo(0, 0);
                return;
            }
            android.graphics.Rect rect = new android.graphics.Rect();
            b.scrollEdit.offsetDescendantRectToMyCoords(target, rect);
            b.scrollEdit.smoothScrollTo(0, Math.max(0, rect.top));
        });
    }

    // ======================= 豆瓣评分手动获取（v3.9，仅测试版） =======================

    /**
     * 「获取」按钮：按当前输入的书名（+作者）查豆瓣评分并填入评分框，
     * 用于为已录入过的旧书补评分。正式版（FEATURE_DOUBAN=false）按钮保持隐藏。
     */
    private void setupDoubanFetch() {
        if (!com.bai.bookshelf.api.DoubanService.enabled()) {
            return;
        }
        b.btnFetchDouban.setVisibility(android.view.View.VISIBLE);
        b.btnFetchDouban.setOnClickListener(v -> {
            String title = b.etTitle.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(this, "请先填写书名（豆瓣按书名查询）", Toast.LENGTH_SHORT).show();
                return;
            }
            final String author = b.etAuthor.getText().toString().trim();
            b.btnFetchDouban.setEnabled(false);
            b.btnFetchDouban.setText("查询中");
            BookshelfApp.get().io(() -> {
                String rating = com.bai.bookshelf.api.DoubanService.fetchRatingSync(title, author);
                BookshelfApp.get().ui(() -> {
                    b.btnFetchDouban.setEnabled(true);
                    b.btnFetchDouban.setText("获取");
                    if (rating == null) {
                        Toast.makeText(this, "未查到该书的豆瓣评分，可稍后重试或手动填写",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    b.etDouban.setText(rating);
                    Toast.makeText(this, "已填入豆瓣评分 " + rating, Toast.LENGTH_SHORT).show();
                });
            });
        });
    }

    // ======================= 退出时的未保存确认 =======================

    /** 返回键/工具栏返回统一入口：有改动先询问 */
    private void attemptExit() {
        if (!isDirty()) {
            cleanupAbandonedNoteImages(); // 无改动也要清掉本次会话新加但未保留的临时图片
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("未保存的更改")
                .setMessage("内容有修改，要保存后再离开吗？")
                .setPositiveButton("保存", (d, w) -> saveFlow.save()) // 保存成功会自行 finish
                .setNegativeButton("不保存", (d, w) -> {
                    cleanupAbandonedNoteImages();
                    finish();
                })
                .setNeutralButton("继续编辑", null)
                .show();
    }

    // 备注图片见 EditNoteImages（v5.6 从本类抽出）

    /** 放弃改动时清掉「本次会话新加但没留在列表里」的临时图片文件（书级 + 逐本） */
    private void cleanupAbandonedNoteImages() {
        noteImageEditor.cleanupAbandoned();
        copySection.cleanupAbandoned();
    }

    /** 保存成功后清理：本次新加却已移除的文件 + 用户删掉的旧图文件（书级 + 逐本） */
    private void cleanupRemovedNoteImageFiles() {
        noteImageEditor.cleanupRemoved();
        copySection.cleanupRemoved();
    }

    private boolean isDirty() {
        return BookEditDirty.check(b, book, initialTags, places, noteImageEditor, copySection,
                readingRecords, borrowRecords, categoryRawFromField());
    }

    /** 兼容各 Android 版本取 Serializable extra */
    @SuppressWarnings("deprecation")
    private Book extraBook(String key) {
        Book b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b = getIntent().getSerializableExtra(key, Book.class);
        } else {
            b = (Book) getIntent().getSerializableExtra(key);
        }
        // v3.0：Intent 传来的 Book 同样可能带 null 字段（老数据），统一清洗后再用
        Book normalized = com.bai.bookshelf.util.BookSanitizer.normalize(b);
        if (normalized != null) {
            com.bai.bookshelf.util.ReadingRecords.refreshSummary(normalized);
            com.bai.bookshelf.util.BorrowSourceRecords.refreshSummary(normalized);
        }
        return normalized;
    }

    /** 借阅 / 赠书没有「购买日期 / 实付价格」，只在自购时显示 */
    private void applyPurchaseFieldsVisibility(int ownType) {
        int buyVis = ownType == Book.OWN_BUY ? android.view.View.VISIBLE : android.view.View.GONE;
        b.tilPurchaseDate.setVisibility(buyVis);
        b.tilPurchasePrice.setVisibility(buyVis);
        b.llBorrowRecords.setVisibility(
                ownType == Book.OWN_BORROW ? android.view.View.VISIBLE : android.view.View.GONE);
        // 借阅时「拥有数量」改为每条借书记录里的「借阅数量」
        b.tilQuantity.setVisibility(ownType == Book.OWN_BORROW
                ? android.view.View.GONE : android.view.View.VISIBLE);
        // 借阅来源在「借书记录」的每条记录里选择，主表单不再单列
        int channelVis = ownType == Book.OWN_BORROW
                ? android.view.View.GONE : android.view.View.VISIBLE;
        b.tvChannelLabel.setVisibility(channelVis);
        b.llChannelRow.setVisibility(channelVis);
    }

    /** 点击日期框弹自绘日历；清空按钮直接清空文本 */
    private void bindDateField(android.widget.EditText field, String title) {
        field.setOnClickListener(v -> {
            int current = DateKeys.parseStartKey(field.getText().toString().trim());
            com.bai.bookshelf.ui.widget.CalendarPicker.showSingle(this, title, current,
                    key -> {
                        field.setText(String.format(java.util.Locale.ROOT,
                                "%04d-%02d-%02d", key / 10000, key / 100 % 100, key % 100));
                    },
                    () -> field.setText(""));
        });
    }

    /** Android 13+ 借阅到期提醒需要通知权限；拒绝时静默跳过通知 */
    void ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9102);
        }
    }

    /** 状态类下拉框（选项来自 arrays.xml，位置即枚举值） */
    private void setupSpinners() {
        b.spStock.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.stock_status_entries, android.R.layout.simple_spinner_dropdown_item));
        b.spStock.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                                       android.view.View view, int position, long id) {
                applyStockVisibility(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        b.spOwn.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.own_type_entries, android.R.layout.simple_spinner_dropdown_item));

        // 来源大类切换 → 二级选项联动刷新 + 标签文案切换
        b.spOwn.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                b.tvChannelLabel.setText(CHANNEL_LABELS[position] + "（点 + 可直接新增）");
                applyPurchaseFieldsVisibility(position);
                places.loadChannels(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    // 系列选择器见 SeriesPicker（v5.1 从本类抽出）
    // 逐本信息栏目见 EditCopiesSection（v5.6 从本类抽出）

    /** 电子书签不再需要存放地点 */
    private void applyStockVisibility(int stockStatus) {
        b.llLocationBlock.setVisibility(stockStatus == Book.STOCK_BOOKMARK
                ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    /** v4.8：分类选择二级页（CategoryActivity）返回后回填分类栏 */
    private final ActivityResultLauncher<Intent> categoryPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) {
                    return;
                }
                // 选中名（可为空串 = 清除分类）：tag 存真实值，文本框异步补层级前缀
                String sel = result.getData().getStringExtra(
                        com.bai.bookshelf.ui.location.CategoryActivity.EXTRA_SELECTED);
                String raw = sel == null ? "" : sel;
                b.etCategory.setTag(raw);
                b.etCategory.setText(raw);
                updateCategoryDisplay();
            });

    private void setupInteractions() {
        // 备注图片：从相册选图
        b.btnAddNoteImage.setOnClickListener(v -> noteImageEditor.pickForBook());

        // 日期：v4.40 改用与筛选同款的自绘日历（跟随主题，比系统 DatePickerDialog 好看）
        bindDateField(b.etPurchaseDate, "购买日期");

        // 封面：链接预览 / 拍照 / 相册
        b.btnPreviewCover.setOnClickListener(v -> coverEditor.load());
        b.btnTakePhoto.setOnClickListener(v -> coverEditor.takePhoto());
        b.btnPickImage.setOnClickListener(v -> coverEditor.pickImage());

        // 同页新增地点 / 来源选项；跳管理页（返回后刷新列表并保留选择）
        b.btnAddLocation.setOnClickListener(v -> AddOptionDialogs.showAddLocation(
                this, places.locations().size(), id -> places.addAndSelectLocation(id)));
        b.btnAddChannel.setOnClickListener(v -> {
            int ownType = b.spOwn.getSelectedItemPosition();
            AddOptionDialogs.showAddChannel(this, ownType, CHANNEL_LABELS[ownType],
                    places.channels().size(), name -> places.addAndSelectChannel(name, ownType));
        });
        b.btnManageLocation.setOnClickListener(v -> {
            places.markNeedRefresh();
            startActivity(new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.LocationActivity.class));
        });
        b.btnManageChannel.setOnClickListener(v -> {
            places.markNeedRefresh();
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.SourceChannelActivity.class);
            intent.putExtra("ownType", b.spOwn.getSelectedItemPosition()); // 管理页直接定位到当前大类
            startActivity(intent);
        });

        // 标签：点击弹微信式选择器；分类：进入二级分类页（选择与管理同页，v4.8）
        b.etTags.setOnClickListener(v -> TagPicker.show(this, book, b.etTags));
        b.etCategory.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.CategoryActivity.class);
            intent.putExtra("picker", true);
            intent.putExtra(com.bai.bookshelf.ui.location.CategoryActivity.EXTRA_SELECTED,
                    b.etCategory.getText().toString().trim());
            categoryPickerLauncher.launch(intent);
        });
        b.etCountry.setOnClickListener(v -> CountryPicker.show(this, b.etCountry)); // 国家/地区：标签式单选
        b.etSeriesName.setOnClickListener(v -> SeriesPicker.show(this,
                b.etSeriesName, b.etSeriesVolume, b.etSeriesOrder)); // 系列：已有系列 + 新建
        b.etPublisher.setFocusable(false);
        b.etPublisher.setClickable(true);
        b.etPublisher.setOnClickListener(v -> PublisherPicker.show(this, b.etPublisher)); // 出版社：标签式单选

        // 拥有数量变化 → 重建副本栏目（尽量保留已填内容）
        b.etQuantity.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b4, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                copySection.onQuantityChanged();
            }
        });

        // 逐本信息入口：≤3 本时展开/收起内联栏目，>3 本时进二级页填写
        b.tvCopiesEntry.setOnClickListener(v -> copySection.onEntryClicked());

        b.btnSave.setOnClickListener(v -> saveFlow.save());
    }

    @Override
    protected void onResume() {
        super.onResume();
        places.refreshIfNeeded();
        if (borrowRecords != null) {
            borrowRecords.reloadChannels();
        }
    }

    // ======================= 分类选择（v4.8 起移入 CategoryActivity 二级页） =======================

    /** 分类栏真实值：显示层是「一级-二级」格式，实际分类名存在 EditText 的 tag 里 */
    private String categoryRawFromField() {
        Object tag = b.etCategory.getTag();
        return tag != null ? tag.toString() : b.etCategory.getText().toString();
    }

    /** 分类栏异步刷新为「一级-二级」展示（真实值不变；分类被改后重进本页也会更新） */
    private void updateCategoryDisplay() {
        final String raw = categoryRawFromField().trim();
        if (raw.isEmpty() || b == null) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            String display = com.bai.bookshelf.util.CategoryPropagator
                    .displayPath(raw, app.db().categoryDao().getAll());
            app.ui(() -> {
                // 仅当真实值未被用户再次改动时才刷新显示，避免覆盖新选择
                if (b != null && raw.equals(categoryRawFromField().trim())) {
                    b.etCategory.setText(display);
                }
            });
        });
    }

    private void finishAfterSave() {
        cleanupRemovedNoteImageFiles(); // 移除的旧图文件 / 未保留的新图文件落地清理
        // 借阅时间段可能刚改过：重排每日检查并立即核对一次到期
        com.bai.bookshelf.util.BorrowReminder.refresh(this);
        // v2.8：保存提示不再额外包书名号（用户反馈），统一走 BookText 剥掉自带书名号
        Toast.makeText(this, "已保存 " + com.bai.bookshelf.util.BookText.bareTitle(book.title),
                Toast.LENGTH_SHORT).show();
        // v4.49：调用方（想读「转为藏书」）据此判断是否要从清单里移除
        setResult(RESULT_OK);
        finish();
    }

    /** null 安全：Room 的 String 字段在库里是可空列，老数据可能为 NULL，统一按空串处理 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
