package com.bai.bookshelf.ui.edit;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.core.content.FileProvider;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
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
    private List<Location> locations = new ArrayList<>();
    private List<SourceChannel> channels = new ArrayList<>();

    /** 拍照暂存文件（TakePicture 契约需要先给出输出 Uri） */
    private File pendingPhotoFile;

    /** 同页新增地点/来源后，等列表重载完成要选中的目标 */
    private Long pendingLocationId;
    private String pendingChannelName;

    /** 从管理页返回时需要刷新两个下拉列表（保留当前选择） */
    private boolean needRefreshLists = false;

    /** 退出时的脏检查基准值（列表异步加载完成后记录） */
    private String initialTags = "";
    private Long initialLocationId;
    private String initialChannel;
    private boolean baselineLocSet = false; // 基准只记录一次，同页新增/管理页刷新不重置
    private boolean baselineChSet = false;

    /** 相册选图当前目标：-1=书级备注；>=2=对应 copyIndex 的逐本备注 */
    private int pendingCopyImageIndex = -1;
    /** 数量变化重建副本表单前，先抢救当前表单的图片状态 */
    private final java.util.Map<Integer, java.util.List<NoteImage>> pendingCopyImages = new java.util.HashMap<>();

    /** 来源大类对应的二级标签文案（与 own_type_entries 顺序一致，主表单用） */
    private static final String[] CHANNEL_LABELS = CopyFormBuilder.CHANNEL_LABELS;

    // ======================= 逐本信息（拥有数量 > 1） =======================
    // 表单的构建/联动/收集统一由 CopyFormBuilder 负责，这里只管理
    // 「内联栏目（≤3 本）」与「二级页（>3 本）」两条路径的数据与切换。

    private final List<CopyFormBuilder.Holder> copyForms = new ArrayList<>(); // 内联副本栏目
    private final boolean[] copySuppress = {false};                           // 构建/回填期间抑制事件
    private List<com.bai.bookshelf.data.entity.BookCopy> existingCopies = new ArrayList<>(); // 编辑模式下库里的副本
    private boolean locationsReady = false;   // 地点列表加载完成
    private boolean copiesReady = false;      // 已有副本加载完成（新增模式立即为 true）
    private boolean copyFormsBuilt = false;   // 副本栏目已完成首次构建
    private boolean copySectionsDirty = false;  // 副本栏目内容有改动
    private List<com.bai.bookshelf.data.entity.BookCopy> pendingCopies = new ArrayList<>(); // 二级页回传的副本数据
    private boolean copiesExpanded = false;     // ≤3 本时是否展开逐本栏目

    // ======================= 备注图片 =======================
    // 图片统一复制到应用私有目录 note_images/，列表里的条目不一定已入库（id 可能为 0）：
    // 保存时整批重写该书的记录；放弃保存时清掉本次会话新加但未保留的文件。

    private final List<com.bai.bookshelf.data.entity.NoteImage> noteImages = new ArrayList<>();
    private final java.util.Set<String> addedImagePaths = new java.util.HashSet<>();   // 本次会话新加的文件
    private final java.util.Set<String> removedImagePaths = new java.util.HashSet<>(); // 本次会话移除的旧文件
    private int initialNoteImageCount = 0; // 脏检查基准：打开页面时的图片张数

    /** 拍照结果：成功后把本地路径写入封面字段 */
    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && pendingPhotoFile != null) {
                    b.etCoverUrl.setText(pendingPhotoFile.getAbsolutePath());
                    loadCover();
                }
            });

    /** 相册选图结果：复制到应用 covers 目录后写入封面字段 */
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    copyCoverToLocal(uri);
                }
            });

    /**
     * 备注图片：相册选图 → 复制到应用 note_images 目录 → 加入缩略图列表。
     * pendingCopyImageIndex = -1 表示书级备注图片；>= 2 表示第 N 本逐本备注图片。
     */
    private final ActivityResultLauncher<String> pickNoteImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) {
                    return;
                }
                if (pendingCopyImageIndex < 0) {
                    addNoteImage(uri);
                } else {
                    addCopyNoteImage(uri, pendingCopyImageIndex);
                }
                pendingCopyImageIndex = -1;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityBookEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
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

        setupSpinners();
        bindFields();
        initialTags = nz(book.tags); // 脏检查基准：标签由选择器直接写 book，需单独留底
        loadNoteImagesIfEdit();
        loadCopiesIfEdit();
        loadLocations();
        setupInteractions();
        setupDoubanFetch();
        loadSplitHint();
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

    /** 书级备注图片的归属键 */
    private String ownerKeyForBookNote() {
        return "book:" + book.id;
    }

    /** 编辑模式预载备注图片（新增模式无图），加载完成后记脏检查基准 */
    private void loadNoteImagesIfEdit() {
        if (!editMode) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.NoteImage> list =
                    app.db().noteImageDao().getByOwnerKey(ownerKeyForBookNote());
            app.ui(() -> {
                noteImages.addAll(list);
                initialNoteImageCount = noteImages.size();
                renderNoteThumbs();
            });
        });
    }

    /** 编辑模式预载已有副本（新增模式直接标记就绪），与地点列表就绪后共同触发副本栏目构建 */
    private void loadCopiesIfEdit() {
        if (!editMode) {
            copiesReady = true;
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<com.bai.bookshelf.data.entity.BookCopy> copies =
                    app.db().bookCopyDao().getByBook(book.id);
            app.ui(() -> {
                existingCopies = copies;
                copiesReady = true;
                tryBuildCopyForms();
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
                .setPositiveButton("保存", (d, w) -> save()) // 保存成功会自行 finish
                .setNegativeButton("不保存", (d, w) -> {
                    cleanupAbandonedNoteImages();
                    finish();
                })
                .setNeutralButton("继续编辑", null)
                .show();
    }

    // ======================= 备注图片 =======================

    /** 打开相册选一张图作为备注图片（target=-1 书级；>=2 逐本） */
    private void openNoteImagePicker(int target) {
        pendingCopyImageIndex = target;
        try {
            pickNoteImageLauncher.launch("image/*");
        } catch (Exception e) {
            pendingCopyImageIndex = -1;
            Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 选中图片后复制到应用私有目录，再加入列表（原图 Uri 权限会随时间失效，必须落盘） */
    private void addNoteImage(Uri uri) {
        addNoteImageToTarget(uri, -1);
    }

    private void addCopyNoteImage(Uri uri, int copyIndex) {
        addNoteImageToTarget(uri, copyIndex);
    }

    /** target = -1 表示书级备注；>= 2 表示对应 copyIndex 的逐本备注 */
    private void addNoteImageToTarget(Uri uri, int target) {
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
                    if (target < 0) {
                        img.sort = noteImages.size();
                        noteImages.add(img);
                        addedImagePaths.add(img.path);
                        renderNoteThumbs();
                    } else {
                        for (CopyFormBuilder.Holder h : copyForms) {
                            if (h.copyIndex == target) {
                                CopyFormBuilder.addNoteImage(this, h, img);
                                break;
                            }
                        }
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 渲染备注图片缩略图（横向滚动，点缩略图可预览/移除） */
    private void renderNoteThumbs() {
        b.containerNoteThumbs.removeAllViews();
        int size = (int) (72 * getResources().getDisplayMetrics().density);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (com.bai.bookshelf.data.entity.NoteImage img : noteImages) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            Glide.with(this)
                    .load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showNoteImagePreview(img));
            b.containerNoteThumbs.addView(iv);
        }
        b.scrollNoteThumbs.setVisibility(noteImages.isEmpty() ? android.view.View.GONE
                : android.view.View.VISIBLE);
        b.btnAddNoteImage.setText(noteImages.isEmpty()
                ? "＋ 添加备注图片" : "＋ 继续添加备注图片（" + noteImages.size() + " 张）");
    }

    /** 点缩略图：大图预览，可直接移除这张 */
    private void showNoteImagePreview(com.bai.bookshelf.data.entity.NoteImage img) {
        // v4.18: 图片放进圆角卡片底
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
                .setNegativeButton("移除", (d, w) -> removeNoteImage(img))
                .show();
    }

    /** 移除一张备注图片（本次新加的直接记账，旧图的文件在保存时删除） */
    private void removeNoteImage(com.bai.bookshelf.data.entity.NoteImage img) {
        noteImages.remove(img);
        if (!addedImagePaths.contains(img.path)) {
            removedImagePaths.add(img.path); // 已是库里的图，文件在保存时一并删除
        }
        renderNoteThumbs();
    }

    /** 把备注图片写入库：整批重写（先删该 ownerKey 旧行再插当前列表），保存成功后调用 */
    private void saveNoteImages(BookshelfApp app, String ownerKey,
                                java.util.List<com.bai.bookshelf.data.entity.NoteImage> toSave) {
        app.db().noteImageDao().deleteByOwnerKey(ownerKey);
        if (!toSave.isEmpty()) {
            for (int i = 0; i < toSave.size(); i++) {
                com.bai.bookshelf.data.entity.NoteImage ni = toSave.get(i);
                ni.ownerKey = ownerKey;
                ni.sort = i;
            }
            app.db().noteImageDao().insertAll(toSave);
        }
    }

    /** 放弃改动时清掉「本次会话新加但没留在列表里」的临时图片文件（书级 + 逐本） */
    private void cleanupAbandonedNoteImages() {
        for (String p : addedImagePaths) {
            boolean kept = false;
            for (NoteImage img : noteImages) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        addedImagePaths.clear();
        for (CopyFormBuilder.Holder h : copyForms) {
            for (String p : h.addedImagePaths) {
                boolean kept = false;
                for (NoteImage img : h.noteImages) {
                    if (img.path.equals(p)) {
                        kept = true;
                        break;
                    }
                }
                if (!kept) {
                    new File(p).delete();
                }
            }
            h.addedImagePaths.clear();
        }
    }

    /** 保存成功后清理：本次新加却已移除的文件 + 用户删掉的旧图文件（书级 + 逐本） */
    private void cleanupRemovedNoteImageFiles() {
        for (String p : addedImagePaths) {
            boolean kept = false;
            for (NoteImage img : noteImages) {
                if (img.path.equals(p)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                new File(p).delete();
            }
        }
        for (String p : removedImagePaths) {
            new File(p).delete();
        }
        addedImagePaths.clear();
        removedImagePaths.clear();
        for (CopyFormBuilder.Holder h : copyForms) {
            for (String p : h.addedImagePaths) {
                boolean kept = false;
                for (NoteImage img : h.noteImages) {
                    if (img.path.equals(p)) {
                        kept = true;
                        break;
                    }
                }
                if (!kept) {
                    new File(p).delete();
                }
            }
            for (String p : h.removedImagePaths) {
                new File(p).delete();
            }
            h.addedImagePaths.clear();
            h.removedImagePaths.clear();
        }
    }

    /**
     * 表单内容与初始值比较，任何一项不同才算有改动。
     *
     * 注意：老数据里 TEXT 列可能是 NULL（Room 的 String 字段在库里是可空列），
     * 一律用 {@link #nz} 归一化后再比对，否则「NULL 对空串」会被误判成改动，
     * 出现「进编辑页什么都没改、返回却弹未保存确认框」的问题。
     */
    private boolean isDirty() {
        // 文本类字段：展示侧已 trim，存储侧也 trim 再比对，避免首尾空格（如 Excel 导入带来的空格）被误判为改动
        if (!b.etTitle.getText().toString().trim().equals(nz(book.title).trim())) {
            return true;
        }
        if (!b.etIsbn.getText().toString().trim().equals(nz(book.isbn).trim())) {
            return true;
        }
        if (!b.etAuthor.getText().toString().trim().equals(nz(book.author).trim())) {
            return true;
        }
        if (!b.etTranslator.getText().toString().trim().equals(nz(book.translator).trim())) {
            return true;
        }
        if (!b.etCountry.getText().toString().trim().equals(nz(book.country).trim())) {
            return true;
        }
        // 分类栏显示「一级-二级」格式（v4.9），真实值在 tag，脏检查按真实值比对
        if (!categoryRawFromField().trim().equals(nz(book.category).trim())) {
            return true;
        }
        if (!b.etPublisher.getText().toString().trim().equals(nz(book.publisher).trim())) {
            return true;
        }
        // 版次/印次：三段拼回的值与「规整后的存储值」比对（v3.7——已存脏数据「2022-|」
        // 在回显时已拆成三段，未改动时拼回是「2022」，必须与自愈后的存储值比对，
        // 否则会被误判为改动而弹未保存提示）；自由文本（非标准串）兜底比对年框原文
        String pubJoined = joinEdition(b.etPubYear, b.etPubMonth, b.etPubEdition);
        if (!pubJoined.equals(BookSanitizer.sanitizeEdition(nz(book.publishDate)))
                && !b.etPubYear.getText().toString().trim().equals(nz(book.publishDate).trim())) {
            return true;
        }
        String printJoined = joinEdition(b.etPrintYear, b.etPrintMonth, b.etPrintNo);
        if (!printJoined.equals(BookSanitizer.sanitizeEdition(nz(book.printing)))
                && !b.etPrintYear.getText().toString().trim().equals(nz(book.printing).trim())) {
            return true;
        }
        // 字数：剥离「万字/万」后缀后再比对，避免「30万」与「30万字」被误判为改动
        if (!stripWanSuffix(wordCountToStore()).equals(stripWanSuffix(nz(book.wordCount).trim()))) {
            return true;
        }
        if (!b.etDouban.getText().toString().trim().equals(nz(book.doubanRating).trim())) {
            return true;
        }
        if (!b.etCoverUrl.getText().toString().trim().equals(nz(book.coverUrl).trim())) {
            return true;
        }
        if (!b.etPurchaseDate.getText().toString().trim().equals(nz(book.purchaseDate).trim())) {
            return true;
        }
        if (!b.etNote.getText().toString().trim().equals(nz(book.note).trim())) {
            return true;
        }
        if (parseInt(b.etPages.getText().toString()) != book.pages) {
            return true;
        }
        if (Double.compare(parseDouble(b.etListPrice.getText().toString()), book.listPrice) != 0) {
            return true;
        }
        if (Double.compare(parseDouble(b.etPurchasePrice.getText().toString()), book.purchasePrice) != 0) {
            return true;
        }
        // 数量：bindFields 将 <=0 统一显示为 1，比对时也按此归一化，避免 quantity=0 被误判为改动
        int qtyDisplay = book.quantity <= 0 ? 1 : book.quantity;
        if (parseInt(b.etQuantity.getText().toString()) != qtyDisplay
                && !(b.etQuantity.getText().toString().trim().isEmpty() && qtyDisplay <= 1)) {
            return true;
        }
        if (!nz(book.tags).equals(nz(initialTags))) {
            return true;
        }
        if (noteImages.size() != initialNoteImageCount) {
            return true; // 书级备注图片有增删
        }
        for (CopyFormBuilder.Holder h : copyForms) {
            if (CopyFormBuilder.hasImageChanges(h)) {
                return true; // 逐本备注图片有增删
            }
        }
        if (b.spStock.getSelectedItemPosition() != book.stockStatus) {
            return true;
        }
        if (b.spRead.getSelectedItemPosition() != book.readStatus) {
            return true;
        }
        if (b.spOwn.getSelectedItemPosition() != book.ownType) {
            return true;
        }
        if (b.seekRating.getProgress() != book.rating) {
            return true;
        }
        // 异步列表加载完成前 initialLocationId/initialChannel 可能为 null，
        // 此时不应视为改动，避免刚进编辑页还没加载完就弹未保存框
        if (initialLocationId != null
                && !java.util.Objects.equals(currentSelectedLocationId(), initialLocationId)) {
            return true;
        }
        if (initialChannel != null
                && !java.util.Objects.equals(currentSelectedChannelName(), initialChannel)) {
            return true;
        }
        if (copySectionsDirty) {
            return true;
        }
        return false;
    }

    /** 当前地点下拉的选中 id（未选/未设置为 null） */
    private Long currentSelectedLocationId() {
        int pos = b.spLocation.getSelectedItemPosition();
        return (pos > 0 && pos <= locations.size()) ? locations.get(pos - 1).id : null;
    }

    /** 当前来源二级下拉的选中名称（未选为 ""） */
    private String currentSelectedChannelName() {
        int pos = b.spSourceChannel.getSelectedItemPosition();
        return (pos > 0 && pos <= channels.size()) ? channels.get(pos - 1).name : "";
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
        return com.bai.bookshelf.util.BookSanitizer.normalize(b);
    }

    /** 状态类下拉框（选项来自 arrays.xml，位置即枚举值） */
    private void setupSpinners() {
        b.spStock.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.stock_status_entries, android.R.layout.simple_spinner_dropdown_item));
        b.spRead.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.read_status_entries, android.R.layout.simple_spinner_dropdown_item));
        b.spOwn.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.own_type_entries, android.R.layout.simple_spinner_dropdown_item));

        // 来源大类切换 → 二级选项联动刷新 + 标签文案切换
        b.spOwn.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                b.tvChannelLabel.setText(CHANNEL_LABELS[position] + "（点 + 可直接新增）");
                loadChannels(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    /** 把 book 对象的值填进表单（老数据的 TEXT 列可能是 NULL，一律经 nz 归一化防显示成 "null"） */
    private void bindFields() {
        b.etTitle.setText(nz(book.title));
        b.etIsbn.setText(nz(book.isbn));
        b.etAuthor.setText(nz(book.author));
        b.etTranslator.setText(nz(book.translator));
        b.etCountry.setText(nz(book.country));
        // 分类：tag 存真实分类名（保存/脏检查用），文本框显示「一级-二级」格式
        b.etCategory.setText(nz(book.category));
        b.etCategory.setTag(nz(book.category));
        updateCategoryDisplay();
        b.etPublisher.setText(nz(book.publisher));
        // 版次/印次：存储格式 yyyy-mm|N，拆进三段输入框；旧格式原文放年框保留
        fillEditionFields(b.etPubYear, b.etPubMonth, b.etPubEdition, book.publishDate);
        fillEditionFields(b.etPrintYear, b.etPrintMonth, b.etPrintNo, book.printing);
        if (book.pages > 0) {
            b.etPages.setText(String.valueOf(book.pages));
        }
        if (book.listPrice > 0) {
            b.etListPrice.setText(String.valueOf(book.listPrice));
        }
        // 字数统一存「X万字」，编辑时剥掉单位
        b.etWordCount.setText(stripWanSuffix(book.wordCount));
        b.etDouban.setText(nz(book.doubanRating));
        // 标签存储为逗号分隔，展示为「, 」分隔
        b.etTags.setText(nz(book.tags).replace(",", ", "));
        b.etCoverUrl.setText(nz(book.coverUrl));
        b.etPurchaseDate.setText(nz(book.purchaseDate));
        if (book.purchasePrice > 0) {
            b.etPurchasePrice.setText(String.valueOf(book.purchasePrice));
        }
        b.etQuantity.setText(String.valueOf(book.quantity <= 0 ? 1 : book.quantity));
        b.etNote.setText(nz(book.note));
        b.spStock.setSelection(book.stockStatus);
        b.spRead.setSelection(book.readStatus);
        b.spOwn.setSelection(book.ownType);
        b.seekRating.setProgress(book.rating);
        b.tvRatingValue.setText(book.rating + " 分");
        loadCover();
    }

    /** 地点下拉：第一项「未设置」；新增模式下自动选中默认地点 */
    private void loadLocations() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Location> locs = app.db().locationDao().getAll();
            app.ui(() -> {
                locations = locs;
                List<String> names = new ArrayList<>();
                names.add("（未设置）");
                for (Location loc : locs) {
                    names.add(loc.name + (loc.isDefault ? "（默认）" : ""));
                }
                b.spLocation.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, names));
                if (pendingLocationId != null) {
                    // 同页新增后自动选中新地点
                    for (int i = 0; i < locs.size(); i++) {
                        if (locs.get(i).id == pendingLocationId) {
                            b.spLocation.setSelection(i + 1);
                            break;
                        }
                    }
                    pendingLocationId = null;
                } else if (book.locationId != null) {
                    // 编辑模式：回显已选地点
                    for (int i = 0; i < locs.size(); i++) {
                        if (locs.get(i).id == book.locationId) {
                            b.spLocation.setSelection(i + 1);
                            break;
                        }
                    }
                } else if (!editMode) {
                    // 新增模式：优先选「上次使用的地点」，没有则选默认地点
                    android.content.SharedPreferences sp = getSharedPreferences("ui_prefs", MODE_PRIVATE);
                    long lastId = sp.getLong("last_location_id", -1);
                    int sel = -1;
                    if (lastId > 0) {
                        for (int i = 0; i < locs.size(); i++) {
                            if (locs.get(i).id == lastId) {
                                sel = i + 1;
                                break;
                            }
                        }
                    }
                    if (sel < 0) {
                        for (int i = 0; i < locs.size(); i++) {
                            if (locs.get(i).isDefault) {
                                sel = i + 1;
                                break;
                            }
                        }
                    }
                    if (sel > 0) {
                        b.spLocation.setSelection(sel);
                    }
                }
                // 记录脏检查基准（仅首次加载）
                if (!baselineLocSet) {
                    baselineLocSet = true;
                    initialLocationId = currentSelectedLocationId();
                }
                locationsReady = true;
                tryBuildCopyForms();
            });
        });
    }

    // ======================= 国家/地区选择器（单选，结构同分类选择器） =======================

    private void showCountryPicker() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            java.util.List<com.bai.bookshelf.data.entity.Country> all = app.db().countryDao().getAll();
            app.ui(() -> showCountryDialog(all));
        });
    }

    private void showCountryDialog(java.util.List<com.bai.bookshelf.data.entity.Country> all) {
        com.bai.bookshelf.databinding.DialogTagsBinding db =
                com.bai.bookshelf.databinding.DialogTagsBinding.inflate(getLayoutInflater());
        String[] selected = {b.etCountry.getText().toString().trim()};
        java.util.List<com.google.android.material.chip.Chip> chips = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.Country c : all) {
            com.google.android.material.chip.Chip chip = buildCountryChip(c, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        com.google.android.material.chip.Chip addChip = new com.google.android.material.chip.Chip(this);
        addChip.setText("＋ 新建国家/地区");
        addChip.setOnClickListener(v -> showNewCountryInput(db, selected, chips));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择国家/地区（单选）")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> b.etCountry.setText(selected[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private com.google.android.material.chip.Chip buildCountryChip(
            com.bai.bookshelf.data.entity.Country c, String[] selected,
            java.util.List<com.google.android.material.chip.Chip> chips) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(c.name);
        chip.setCheckable(true);
        chip.setChecked(c.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (c.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = c.name;
                for (com.google.android.material.chip.Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showCountryOptions(c, chip, selected);
            return true;
        });
        return chip;
    }

    private void showCountryOptions(com.bai.bookshelf.data.entity.Country c,
                                    com.google.android.material.chip.Chip chip,
                                    String[] selected) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("国家/地区「" + c.name + "」")
                .setItems(new String[]{"重命名", "删除"}, (d, which) -> {
                    if (which == 0) {
                        showRenameCountryInput(c, chip, selected);
                    } else {
                        confirmDeleteCountry(c, chip, selected);
                    }
                })
                .show();
    }

    /** 重命名国家：改库 + 同步所有书籍上的旧国家名 */
    private void showRenameCountryInput(com.bai.bookshelf.data.entity.Country c,
                                        com.google.android.material.chip.Chip chip,
                                        String[] selected) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.edit.setText(c.name);
        ib.edit.setSelection(c.name.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名国家/地区")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(c.name)) {
                        return;
                    }
                    String oldName = c.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        c.name = newName;
                        app.db().countryDao().update(c);
                        for (com.bai.bookshelf.data.entity.Book bk : com.bai.bookshelf.util.BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (oldName.equals(bk.country)) {
                                bk.country = newName;
                                app.db().bookDao().update(bk);
                            }
                        }
                        app.ui(() -> {
                            chip.setText(newName);
                            if (selected[0].equals(oldName)) {
                                selected[0] = newName;
                            }
                            Toast.makeText(this, "已重命名为「" + newName + "」", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除国家：从库中删除，并清空所有书籍上的该国家名 */
    private void confirmDeleteCountry(com.bai.bookshelf.data.entity.Country c,
                                      com.google.android.material.chip.Chip chip,
                                      String[] selected) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除国家/地区")
                .setMessage("从国家库删除「" + c.name + "」？所有书籍上的这个国家/地区也会一并清空。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().countryDao().deleteById(c.id);
                        for (com.bai.bookshelf.data.entity.Book bk : com.bai.bookshelf.util.BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (c.name.equals(bk.country)) {
                                bk.country = "";
                                app.db().bookDao().update(bk);
                            }
                        }
                        app.ui(() -> {
                            if (selected[0].equals(c.name)) {
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

    private void showNewCountryInput(com.bai.bookshelf.databinding.DialogTagsBinding db,
                                     String[] selected,
                                     java.util.List<com.google.android.material.chip.Chip> chips) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.inputLayout.setHint("国家/地区名（如 中国 / 日本）");
        new MaterialAlertDialogBuilder(this)
                .setTitle("新建国家/地区")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        com.bai.bookshelf.data.entity.Country c =
                                new com.bai.bookshelf.data.entity.Country();
                        c.name = name;
                        c.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().countryDao().insert(c);
                        c.id = id;
                        app.ui(() -> {
                            selected[0] = name;
                            com.google.android.material.chip.Chip chip =
                                    buildCountryChip(c, selected, chips);
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

    // ======================= 出版社选择器（单选，结构同国家/地区选择器） =======================

    private void showPublisherPicker() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            java.util.List<com.bai.bookshelf.data.entity.Publisher> all = app.db().publisherDao().getAll();
            app.ui(() -> showPublisherDialog(all));
        });
    }

    private void showPublisherDialog(java.util.List<com.bai.bookshelf.data.entity.Publisher> all) {
        com.bai.bookshelf.databinding.DialogTagsBinding db =
                com.bai.bookshelf.databinding.DialogTagsBinding.inflate(getLayoutInflater());
        String[] selected = {b.etPublisher.getText().toString().trim()};
        java.util.List<com.google.android.material.chip.Chip> chips = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.Publisher p : all) {
            com.google.android.material.chip.Chip chip = buildPublisherChip(p, selected, chips);
            chips.add(chip);
            db.chipGroup.addView(chip);
        }
        com.google.android.material.chip.Chip addChip = new com.google.android.material.chip.Chip(this);
        addChip.setText("＋ 新建出版社");
        addChip.setOnClickListener(v -> showNewPublisherInput(db, selected, chips));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择出版社（单选）")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> b.etPublisher.setText(selected[0]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private com.google.android.material.chip.Chip buildPublisherChip(
            com.bai.bookshelf.data.entity.Publisher p, String[] selected,
            java.util.List<com.google.android.material.chip.Chip> chips) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(p.name);
        chip.setCheckable(true);
        chip.setChecked(p.name.equals(selected[0]));
        chip.setOnClickListener(v -> {
            if (p.name.equals(selected[0])) {
                selected[0] = "";
                chip.setChecked(false);
            } else {
                selected[0] = p.name;
                for (com.google.android.material.chip.Chip other : chips) {
                    other.setChecked(other == chip);
                }
            }
        });
        chip.setOnLongClickListener(v -> {
            showPublisherOptions(p, chip, selected);
            return true;
        });
        return chip;
    }

    private void showPublisherOptions(com.bai.bookshelf.data.entity.Publisher p,
                                      com.google.android.material.chip.Chip chip,
                                      String[] selected) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("出版社「" + p.name + "」")
                .setItems(new String[]{"重命名", "删除"}, (d, which) -> {
                    if (which == 0) {
                        showRenamePublisherInput(p, chip, selected);
                    } else {
                        confirmDeletePublisher(p, chip, selected);
                    }
                })
                .show();
    }

    /** 重命名出版社：改库 + 同步所有书籍上的旧出版社名 */
    private void showRenamePublisherInput(com.bai.bookshelf.data.entity.Publisher p,
                                          com.google.android.material.chip.Chip chip,
                                          String[] selected) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.edit.setText(p.name);
        ib.edit.setSelection(p.name.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名出版社")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(p.name)) {
                        return;
                    }
                    String oldName = p.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        p.name = newName;
                        app.db().publisherDao().update(p);
                        for (com.bai.bookshelf.data.entity.Book bk : com.bai.bookshelf.util.BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (oldName.equals(bk.publisher)) {
                                bk.publisher = newName;
                                app.db().bookDao().update(bk);
                            }
                        }
                        app.ui(() -> {
                            chip.setText(newName);
                            if (selected[0].equals(oldName)) {
                                selected[0] = newName;
                            }
                            if (oldName.equals(b.etPublisher.getText().toString().trim())) {
                                b.etPublisher.setText(newName);
                            }
                            Toast.makeText(this, "已重命名为「" + newName + "」", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除出版社：从库中删除，并清空所有书籍上的该出版社名 */
    private void confirmDeletePublisher(com.bai.bookshelf.data.entity.Publisher p,
                                        com.google.android.material.chip.Chip chip,
                                        String[] selected) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除出版社")
                .setMessage("从出版社库删除「" + p.name + "」？所有书籍上的这个出版社也会一并清空。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().publisherDao().deleteById(p.id);
                        for (com.bai.bookshelf.data.entity.Book bk : com.bai.bookshelf.util.BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                            if (p.name.equals(bk.publisher)) {
                                bk.publisher = "";
                                app.db().bookDao().update(bk);
                            }
                        }
                        app.ui(() -> {
                            if (selected[0].equals(p.name)) {
                                selected[0] = "";
                            }
                            if (p.name.equals(b.etPublisher.getText().toString().trim())) {
                                b.etPublisher.setText("");
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

    private void showNewPublisherInput(com.bai.bookshelf.databinding.DialogTagsBinding db,
                                       String[] selected,
                                       java.util.List<com.google.android.material.chip.Chip> chips) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.inputLayout.setHint("出版社名（如 人民文学出版社）");
        new MaterialAlertDialogBuilder(this)
                .setTitle("新建出版社")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        if (app.db().publisherDao().getByName(name) != null) {
                            app.ui(() -> Toast.makeText(this, "该出版社已存在", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        com.bai.bookshelf.data.entity.Publisher p =
                                new com.bai.bookshelf.data.entity.Publisher();
                        p.name = name;
                        p.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().publisherDao().insert(p);
                        p.id = id;
                        app.ui(() -> {
                            selected[0] = name;
                            com.google.android.material.chip.Chip chip =
                                    buildPublisherChip(p, selected, chips);
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

    // ======================= 逐本信息栏目（数量 > 1 时动态生成） =======================

    /** 地点列表与已有副本都就绪后，进行首次副本栏目构建 */
    private void tryBuildCopyForms() {
        if (copyFormsBuilt || !locationsReady || !copiesReady) {
            return;
        }
        copyFormsBuilt = true;
        refreshCopySections(); // 按数量决定「内联表单」还是「二级页入口」
    }

    /** 输入框中的拥有数量（空/非法按 1 处理） */
    private int currentQuantity() {
        return Math.max(1, parseInt(b.etQuantity.getText().toString()));
    }

    /** 超过这个本数就改用二级页填写逐本信息，页面内不再铺开 */
    private static final int INLINE_COPY_LIMIT = 3;

    /**
     * 数量变化时的统一入口：先把现有内容抢救成数据，再按规则决定
     * 「内联表单（≤3 本）」还是「二级页入口（>3 本）」。
     */
    private void refreshCopySections() {
        int quantity = currentQuantity();
        List<com.bai.bookshelf.data.entity.BookCopy> snapshot = snapshotCopies();
        rebuildCopyForms(quantity, snapshot);
        // v3.6：数量为 1 时没有「逐本」可言（第 1 本即书级信息，内联表单也只会是空的），
        // 入口与容器整体隐藏
        if (quantity <= 1) {
            b.tvCopiesEntry.setVisibility(android.view.View.GONE);
            b.containerCopies.setVisibility(android.view.View.GONE);
            return;
        }
        if (quantity <= INLINE_COPY_LIMIT) {
            // ≤3 本：默认折叠，点入口展开/收起
            b.tvCopiesEntry.setVisibility(android.view.View.VISIBLE);
            boolean show = copiesExpanded;
            b.containerCopies.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            b.tvCopiesEntry.setText(show
                    ? "逐本信息（共 " + quantity + " 本）‹ 收起"
                    : "逐本信息（共 " + quantity + " 本）› 展开");
        } else {
            // >3 本：页面内不铺开，点入口进二级页统一填写
            b.tvCopiesEntry.setVisibility(android.view.View.VISIBLE);
            b.containerCopies.setVisibility(android.view.View.GONE);
            b.tvCopiesEntry.setText("逐本信息（共 " + quantity + " 本）› 打开逐本编辑页");
        }
    }

    /** 当前副本数据快照：内联表单存在时从表单读，否则用二级页回传的数据 */
    private List<com.bai.bookshelf.data.entity.BookCopy> snapshotCopies() {
        if (!copyForms.isEmpty()) {
            return collectCopies();
        }
        return new ArrayList<>(pendingCopies);
    }

    /** 打开逐本信息二级页，填写完回写 pendingCopies 与 pendingCopyImages 并标脏 */
    private void openCopyEditor() {
        Intent intent = new Intent(this, CopyEditActivity.class);
        intent.putExtra(CopyEditActivity.EXTRA_QUANTITY, currentQuantity());
        intent.putExtra(CopyEditActivity.EXTRA_COPIES, new ArrayList<>(snapshotCopies()));
        intent.putExtra(CopyEditActivity.EXTRA_BOOK_ID, book.id);
        copyEditorLauncher.launch(intent);
    }

    private final ActivityResultLauncher<Intent> copyEditorLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) {
                    return;
                }
                Object extra = result.getData().getSerializableExtra(CopyEditActivity.RESULT_COPIES);
                if (extra instanceof java.util.ArrayList) {
                    pendingCopies = new ArrayList<>();
                    for (Object o : (java.util.ArrayList<?>) extra) {
                        if (o instanceof com.bai.bookshelf.data.entity.BookCopy) {
                            pendingCopies.add((com.bai.bookshelf.data.entity.BookCopy) o);
                        }
                    }
                    if (!pendingCopies.isEmpty()) {
                        copySectionsDirty = true;
                        Toast.makeText(this, "已更新 " + pendingCopies.size() + " 本的信息",
                                Toast.LENGTH_SHORT).show();
                    }
                }
                // 同时接收各本备注图片
                pendingCopyImages.clear();
                Object imgExtra = result.getData().getSerializableExtra(CopyEditActivity.RESULT_COPY_IMAGES);
                if (imgExtra instanceof java.util.HashMap) {
                    java.util.HashMap<?, ?> map = (java.util.HashMap<?, ?>) imgExtra;
                    for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                        if (e.getKey() instanceof Integer && e.getValue() instanceof java.util.ArrayList) {
                            Integer idx = (Integer) e.getKey();
                            java.util.ArrayList<NoteImage> list = new java.util.ArrayList<>();
                            for (Object o : (java.util.ArrayList<?>) e.getValue()) {
                                if (o instanceof NoteImage) {
                                    list.add((NoteImage) o);
                                }
                            }
                            pendingCopyImages.put(idx, list);
                        }
                    }
                }
            });

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

    /**
     * 按数量重建副本栏目（第 2 本起，共 quantity-1 个）。
     * prefill 为 null 时优先保留当前栏目里已填的内容（数量微调不丢输入），
     * 首次构建时用库里的 existingCopies 回填。
     */
    private void rebuildCopyForms(int quantity, List<com.bai.bookshelf.data.entity.BookCopy> prefill) {
        // 重建前抢救当前已填内容（按 copyIndex 对齐），同时保留各本备注图片
        if (prefill == null) {
            prefill = new ArrayList<>();
            for (CopyFormBuilder.Holder form : copyForms) {
                prefill.add(CopyFormBuilder.collect(form, locations));
                pendingCopyImages.put(form.copyIndex, CopyFormBuilder.getNoteImages(form));
            }
        }
        copyForms.clear();
        b.containerCopies.removeAllViews();
        int extra = quantity - 1;
        // 超过 3 本时不生成内联表单（改由二级页填写），不足 3 本时才按数量铺开
        if (extra <= 0 || quantity > INLINE_COPY_LIMIT) {
            return;
        }

        for (int idx = 2; idx <= quantity; idx++) {
            // 找到该序号的已存/已填内容
            com.bai.bookshelf.data.entity.BookCopy saved = null;
            for (com.bai.bookshelf.data.entity.BookCopy c : prefill) {
                if (c.copyIndex == idx) {
                    saved = c;
                    break;
                }
            }
            CopyFormBuilder.Holder h = CopyFormBuilder.build(this, b.containerCopies, idx,
                    locations, saved, copySuppress, () -> copySectionsDirty = true,
                    copyIndex -> openNoteImagePicker(copyIndex));
            // 若数量微调前该本已有图片，优先还原当前会话状态
            java.util.List<NoteImage> kept = pendingCopyImages.get(idx);
            if (kept != null && !kept.isEmpty()) {
                h.noteImages.clear();
                h.noteImages.addAll(kept);
                CopyFormBuilder.renderNoteThumbs(h);
            }
            copyForms.add(h);
            // 编辑模式：从库加载该本备注图片（pending 优先，不会覆盖）
            if (editMode) {
                CopyFormBuilder.loadNoteImagesIfNeeded(this, BookshelfApp.get(), book.id, h);
            }
        }
    }

    /** UI 线程：收集要保存的副本。内联表单存在时读表单，否则用二级页回传的数据 */
    private List<com.bai.bookshelf.data.entity.BookCopy> collectCopies() {
        if (copyForms.isEmpty()) {
            return new ArrayList<>(pendingCopies);
        }
        List<com.bai.bookshelf.data.entity.BookCopy> list = new ArrayList<>();
        for (CopyFormBuilder.Holder form : copyForms) {
            list.add(CopyFormBuilder.collect(form, locations));
        }
        return list;
    }

    /**
     * 后台线程：整批重建某书的副本（数量减到 1 时自然清空）。
     * 同时保存每本副本的备注图片（ownerKey = copy:{bookId}:{copyIndex}）。
     *
     * <p>v4.23 修复：副本是「全删全插」，数量减少后被删副本
     * （copy:{bookId}:{index}，index >= 新数量）的备注图没人管，
     * 记录和文件都永久泄漏。现在重建前按 ownerKey 前缀查出全部旧图，
     * 超出新数量的连同磁盘文件一起回收。
     */
    private void saveCopies(BookshelfApp app, long bookId,
                            List<com.bai.bookshelf.data.entity.BookCopy> copies,
                            java.util.Map<Integer, java.util.List<NoteImage>> copyImages) {
        // 先取旧副本备注图（deleteByBook 之前，否则查不到）
        java.util.List<NoteImage> oldCopyImgs = app.db().noteImageDao()
                .getByOwnerKeyPrefix("copy:" + bookId + ":%");

        app.db().bookCopyDao().deleteByBook(bookId);
        for (com.bai.bookshelf.data.entity.BookCopy c : copies) {
            c.bookId = bookId;
        }
        if (!copies.isEmpty()) {
            app.db().bookCopyDao().insertAll(copies);
        }

        // 回收被删副本的备注图：index >= 新副本数量的逐行清掉（记录 + 文件）。
        // ⚠ 不能按前缀整批删——保留副本若本次没在 UI 打开（图不在 copyImages map），
        //   整批删会连它的历史图一起丢，且无处重建。
        int kept = copies.size();
        for (NoteImage img : oldCopyImgs) {
            int idx = parseCopyIndex(img.ownerKey, bookId);
            if (idx >= kept) {
                app.db().noteImageDao().deleteById(img.id);
                if (img.path != null && !img.path.isEmpty()) {
                    new File(img.path).delete();
                }
            }
        }

        // 保存每本副本的备注图片；数量为 1 或二级页没图时 map 为空/没项
        if (copyImages != null) {
            for (java.util.Map.Entry<Integer, java.util.List<NoteImage>> e : copyImages.entrySet()) {
                saveNoteImages(app, "copy:" + bookId + ":" + e.getKey(), e.getValue());
            }
        }
    }

    /** 从 ownerKey「copy:{bookId}:{index}」解析副本下标；格式不符返回 Integer.MAX_VALUE（视为要删） */
    private static int parseCopyIndex(String ownerKey, long bookId) {
        if (ownerKey == null) {
            return Integer.MAX_VALUE;
        }
        String prefix = "copy:" + bookId + ":";
        if (!ownerKey.startsWith(prefix)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(ownerKey.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ======================= 套装分摊快捷填充 =======================

    /** 读取最近一次套装分摊结果，在实付价格下方生成可点击填入的 Chip（无记录则不显示） */
    private void loadSplitHint() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("split_prefs", MODE_PRIVATE);
            org.json.JSONArray history = new org.json.JSONArray(sp.getString("history", "[]"));
            if (history.length() == 0) {
                return;
            }
            org.json.JSONArray items = history.getJSONObject(0).getJSONArray("items"); // 最新一次
            b.containerSplitHint.setVisibility(android.view.View.VISIBLE);
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.getJSONObject(i);
                String name = item.getString("name");
                double alloc = item.getDouble("alloc");
                com.google.android.material.chip.Chip chip =
                        new com.google.android.material.chip.Chip(this);
                chip.setText("《" + name + "》¥" + fmtMoney(alloc));
                chip.setCheckable(false);
                chip.setOnClickListener(v -> b.etPurchasePrice.setText(fmtMoney(alloc)));
                b.chipGroupSplit.addView(chip);
            }
        } catch (Exception ignored) {
            // 历史数据异常就不显示提示，不影响录入
        }
    }

    /** 金额展示：去掉多余的 .00 */
    private static String fmtMoney(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.getDefault(), "%.2f", v);
    }

    /** 来源二级选项下拉：按大类过滤，第一项「（无）」，值为名称字符串 */
    private void loadChannels(int ownType) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<SourceChannel> list = app.db().sourceChannelDao().getByType(ownType);
            app.ui(() -> {
                channels = list;
                List<String> names = new ArrayList<>();
                names.add("（无）");
                for (SourceChannel ch : list) {
                    names.add(ch.name);
                }
                b.spSourceChannel.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, names));
                // 同页新增后自动选中
                if (pendingChannelName != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equals(pendingChannelName)) {
                            b.spSourceChannel.setSelection(i + 1);
                            break;
                        }
                    }
                    pendingChannelName = null;
                } else if (!book.sourceChannel.isEmpty()) {
                    // 回显已有选项（切了大类后可能匹配不上，则回到「无」）
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equals(book.sourceChannel)) {
                            b.spSourceChannel.setSelection(i + 1);
                            break;
                        }
                    }
                }
                // 记录脏检查基准（仅首次加载；切换大类不重置——那本身就是改动）
                if (!baselineChSet) {
                    baselineChSet = true;
                    initialChannel = currentSelectedChannelName();
                }
            });
        });
    }

    /** 同页新增存放地点 */
    private void showAddLocationDialog() {
        com.bai.bookshelf.databinding.DialogInputBinding db =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        db.inputLayout.setHint("如：书房-书架A-第2层");
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加地点")
                .setView(db.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String name = db.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        Location loc = new Location();
                        loc.name = name;
                        loc.sort = locations.size();
                        long id = app.db().locationDao().insert(loc);
                        app.ui(() -> {
                            pendingLocationId = id;
                            loadLocations();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 同页新增当前大类下的来源选项 */
    private void showAddChannelDialog() {
        int ownType = b.spOwn.getSelectedItemPosition();
        String[] hints = {"拼多多、淘宝", "市图书馆", "鉴书团"};
        com.bai.bookshelf.databinding.DialogInputBinding db =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        db.inputLayout.setHint("如：" + hints[ownType]);
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加" + CHANNEL_LABELS[ownType])
                .setView(db.getRoot())
                .setPositiveButton("添加", (d, w) -> {
                    String name = db.edit.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        SourceChannel ch = new SourceChannel();
                        ch.name = name;
                        ch.ownType = ownType;
                        ch.sort = channels.size();
                        app.db().sourceChannelDao().insert(ch);
                        app.ui(() -> {
                            pendingChannelName = name;
                            loadChannels(ownType);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void setupInteractions() {
        // 备注图片：从相册选图
        b.btnAddNoteImage.setOnClickListener(v -> openNoteImagePicker(-1));
        renderNoteThumbs();

        // 评分滑条实时显示
        b.seekRating.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                b.tvRatingValue.setText(progress + " 分");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // 购买日期：弹出日期选择器
        b.etPurchaseDate.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(this, (view, year, month, dayOfMonth) ->
                    b.etPurchaseDate.setText(String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)),
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                    .show();
        });

        // 封面：链接预览 / 拍照 / 相册
        b.btnPreviewCover.setOnClickListener(v -> loadCover());
        b.btnTakePhoto.setOnClickListener(v -> takePhoto());
        b.btnPickImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        // 同页新增地点 / 来源选项；跳管理页（返回后刷新列表并保留选择）
        b.btnAddLocation.setOnClickListener(v -> showAddLocationDialog());
        b.btnAddChannel.setOnClickListener(v -> showAddChannelDialog());
        b.btnManageLocation.setOnClickListener(v -> {
            needRefreshLists = true;
            startActivity(new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.LocationActivity.class));
        });
        b.btnManageChannel.setOnClickListener(v -> {
            needRefreshLists = true;
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.SourceChannelActivity.class);
            intent.putExtra("ownType", b.spOwn.getSelectedItemPosition()); // 管理页直接定位到当前大类
            startActivity(intent);
        });

        // 标签：点击弹微信式选择器；分类：进入二级分类页（选择与管理同页，v4.8）
        b.etTags.setOnClickListener(v -> showTagPicker());
        b.etCategory.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.location.CategoryActivity.class);
            intent.putExtra("picker", true);
            intent.putExtra(com.bai.bookshelf.ui.location.CategoryActivity.EXTRA_SELECTED,
                    b.etCategory.getText().toString().trim());
            categoryPickerLauncher.launch(intent);
        });
        b.etCountry.setOnClickListener(v -> showCountryPicker()); // 国家/地区：标签式单选
        b.etPublisher.setFocusable(false);
        b.etPublisher.setClickable(true);
        b.etPublisher.setOnClickListener(v -> showPublisherPicker()); // 出版社：标签式单选

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
                if (copyFormsBuilt) {
                    refreshCopySections();
                }
            }
        });

        // 逐本信息入口：≤3 本时展开/收起内联栏目，>3 本时进二级页填写
        b.tvCopiesEntry.setOnClickListener(v -> {
            int quantity = currentQuantity();
            if (quantity > INLINE_COPY_LIMIT) {
                openCopyEditor();
            } else {
                copiesExpanded = !copiesExpanded;
                refreshCopySections();
            }
        });

        b.btnSave.setOnClickListener(v -> save());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needRefreshLists) {
            needRefreshLists = false;
            // 记住当前选择，刷新后恢复
            int locPos = b.spLocation.getSelectedItemPosition();
            if (locPos > 0 && locPos <= locations.size()) {
                pendingLocationId = locations.get(locPos - 1).id;
            }
            int chPos = b.spSourceChannel.getSelectedItemPosition();
            if (chPos > 0 && chPos <= channels.size()) {
                pendingChannelName = channels.get(chPos - 1).name;
            }
            loadLocations();
            loadChannels(b.spOwn.getSelectedItemPosition());
        }
    }

    // ======================= 微信式标签选择器 =======================

    /** 从标签库加载全部标签并弹出选择对话框 */
    private void showTagPicker() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            java.util.List<com.bai.bookshelf.data.entity.Tag> all = app.db().tagDao().getAll();
            app.ui(() -> showTagDialog(all));
        });
    }

    private void showTagDialog(java.util.List<com.bai.bookshelf.data.entity.Tag> all) {
        com.bai.bookshelf.databinding.DialogTagsBinding db =
                com.bai.bookshelf.databinding.DialogTagsBinding.inflate(getLayoutInflater());

        // 当前已选标签（book.tags 是逗号分隔存储）
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String t : book.tags.split(",")) {
            String s = t.trim();
            if (!s.isEmpty()) {
                selected.add(s);
            }
        }

        // 标签库中的每个标签生成一个可勾选 Chip（长按可重命名/删除）
        for (com.bai.bookshelf.data.entity.Tag tag : all) {
            db.chipGroup.addView(buildTagChip(tag, selected));
        }
        // 末尾的「＋ 新建标签」Chip
        com.google.android.material.chip.Chip addChip = new com.google.android.material.chip.Chip(this);
        addChip.setText("＋ 新建标签");
        addChip.setOnClickListener(v -> showNewTagInput(db, selected));
        db.chipGroup.addView(addChip);

        new MaterialAlertDialogBuilder(this)
                .setTitle("选择标签")
                .setView(db.getRoot())
                .setPositiveButton("完成", (d, w) -> {
                    // 选中集合 → 逗号分隔存储，展示用「, 」分隔
                    book.tags = String.join(",", selected);
                    b.etTags.setText(String.join(", ", selected));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 生成一个可勾选标签 Chip：
     * - 点击：选中/取消，实时同步到 selected 集合
     * - 长按：弹出「重命名 / 删除」操作（直接改标签库）
     */
    private com.google.android.material.chip.Chip buildTagChip(
            com.bai.bookshelf.data.entity.Tag tag, java.util.Set<String> selected) {
        com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
        chip.setText(tag.name);
        chip.setCheckable(true);
        chip.setChecked(selected.contains(tag.name));
        chip.setOnCheckedChangeListener((c, isChecked) -> {
            // 以 Chip 当前文本为准（重命名后文本已更新）
            String name = c.getText().toString();
            if (isChecked) {
                selected.add(name);
            } else {
                selected.remove(name);
            }
        });
        chip.setOnLongClickListener(v -> {
            showTagOptions(tag, chip, selected);
            return true;
        });
        return chip;
    }

    /** 标签长按菜单：重命名 / 删除 */
    private void showTagOptions(com.bai.bookshelf.data.entity.Tag tag,
                                com.google.android.material.chip.Chip chip,
                                java.util.Set<String> selected) {
        String[] options = {"重命名", "删除"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("标签「" + tag.name + "」")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showRenameTagInput(tag, chip, selected);
                    } else {
                        confirmDeleteTag(tag, chip, selected);
                    }
                })
                .show();
    }

    /** 重命名标签：改库 + 改 Chip 文本 + 同步选中集合 */
    private void showRenameTagInput(com.bai.bookshelf.data.entity.Tag tag,
                                    com.google.android.material.chip.Chip chip,
                                    java.util.Set<String> selected) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.edit.setText(tag.name);
        ib.edit.setSelection(tag.name.length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("重命名标签")
                .setView(ib.getRoot())
                .setPositiveButton("确定", (d, w) -> {
                    String newName = ib.edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (newName.isEmpty() || newName.equals(tag.name)) {
                        return;
                    }
                    String oldName = tag.name;
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        tag.name = newName;
                        app.db().tagDao().update(tag);
                        // 联动：所有书籍上的旧标签名同步换新
                        com.bai.bookshelf.util.TagPropagator.renameTagEverywhere(
                                app.db(), oldName, newName);
                        app.ui(() -> {
                            chip.setText(newName);
                            // 选中集合里的旧名同步换新
                            if (selected.remove(oldName)) {
                                selected.add(newName);
                            }
                            Toast.makeText(this, "已重命名为「" + newName + "」", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 删除标签：确认后从库中删除并移除 Chip */
    private void confirmDeleteTag(com.bai.bookshelf.data.entity.Tag tag,
                                  com.google.android.material.chip.Chip chip,
                                  java.util.Set<String> selected) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除标签")
                .setMessage("从标签库删除「" + tag.name + "」？所有书籍上的这个标签也会一并移除。")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().tagDao().deleteById(tag.id);
                        // 联动：把该标签从所有书籍上摘掉
                        com.bai.bookshelf.util.TagPropagator.removeTagEverywhere(app.db(), tag.name);
                        app.ui(() -> {
                            selected.remove(tag.name);
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

    /** 新建标签：入库 → 生成一个默认勾选的 Chip 插到「＋」之前 */
    private void showNewTagInput(com.bai.bookshelf.databinding.DialogTagsBinding db,
                                 java.util.Set<String> selected) {
        com.bai.bookshelf.databinding.DialogInputBinding ib =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        ib.inputLayout.setHint("标签名（不能含逗号）");
        new MaterialAlertDialogBuilder(this)
                .setTitle("新建标签")
                .setView(ib.getRoot())
                .setPositiveButton("创建", (d, w) -> {
                    String name = ib.edit.getText().toString().trim()
                            .replace(",", "").replace("，", "");
                    if (name.isEmpty()) {
                        return;
                    }
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        com.bai.bookshelf.data.entity.Tag tag = new com.bai.bookshelf.data.entity.Tag();
                        tag.name = name;
                        tag.sort = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
                        long id = app.db().tagDao().insert(tag);
                        tag.id = id; // 回写主键，长按重命名/删除要用
                        app.ui(() -> {
                            selected.add(name); // 新标签默认选中
                            db.chipGroup.addView(buildTagChip(tag, selected),
                                    db.chipGroup.getChildCount() - 1);
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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

    /** 拍照：先创建输出文件，再经 FileProvider 调起相机（此契约无需 CAMERA 权限） */
    private void takePhoto() {
        File dir = new File(getFilesDir(), "covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        pendingPhotoFile = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingPhotoFile);
        takePictureLauncher.launch(uri);
    }

    /** 相册选图后复制到应用私有目录（原图 Uri 权限可能随时间失效） */
    private void copyCoverToLocal(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                File dir = new File(getFilesDir(), "covers");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File out = new File(dir, "cover_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }
                app.ui(() -> {
                    b.etCoverUrl.setText(out.getAbsolutePath());
                    loadCover();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 加载封面：支持 http(s) 链接与本地文件路径 */
    private void loadCover() {
        String url = b.etCoverUrl.getText().toString().trim();
        Glide.with(this)
                .load(url.isEmpty() ? null : (url.startsWith("http") ? url : new File(url)))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(b.ivCover);
    }

    /** 校验并保存到数据库 */
    private void save() {
        String title = b.etTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "书名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 表单 → 对象
        book.title = title;
        book.isbn = b.etIsbn.getText().toString().trim();
        book.author = b.etAuthor.getText().toString().trim();
        book.translator = b.etTranslator.getText().toString().trim();
        book.country = b.etCountry.getText().toString().trim();
        book.category = categoryRawFromField().trim();
        book.publisher = b.etPublisher.getText().toString().trim();
        book.publishDate = joinEdition(b.etPubYear, b.etPubMonth, b.etPubEdition); // 版次 yyyy-mm|N
        book.printing = joinEdition(b.etPrintYear, b.etPrintMonth, b.etPrintNo);   // 印次 yyyy-mm|N
        book.pages = parseInt(b.etPages.getText().toString());
        book.listPrice = parseDouble(b.etListPrice.getText().toString());
        book.wordCount = wordCountToStore(); // 统一存「X万字」
        book.doubanRating = b.etDouban.getText().toString().trim();
        // 标签由选择器直接维护在 book.tags，不从输入框回读
        book.coverUrl = b.etCoverUrl.getText().toString().trim();
        book.purchaseDate = b.etPurchaseDate.getText().toString().trim();
        book.purchasePrice = parseDouble(b.etPurchasePrice.getText().toString());
        // 拥有数量：留空或填 0 按 1 本处理
        book.quantity = Math.max(1, parseInt(b.etQuantity.getText().toString()));
        book.note = b.etNote.getText().toString().trim();
        book.stockStatus = b.spStock.getSelectedItemPosition();
        book.readStatus = b.spRead.getSelectedItemPosition();
        book.ownType = b.spOwn.getSelectedItemPosition();
        int locPos = b.spLocation.getSelectedItemPosition();
        book.locationId = (locPos > 0 && locPos <= locations.size()) ? locations.get(locPos - 1).id : null;
        int chPos = b.spSourceChannel.getSelectedItemPosition();
        book.sourceChannel = (chPos > 0 && chPos <= channels.size()) ? channels.get(chPos - 1).name : "";

        long now = System.currentTimeMillis();
        book.updatedAt = now;
        book.dirty = true; // 二期云同步用

        // 副本栏目内容在 UI 线程收集（涉及视图读取），入库在后台线程
        final List<com.bai.bookshelf.data.entity.BookCopy> copiesToSave = collectCopies();
        // 逐本备注图片快照：内联表单取当前 holder，二级页取 pendingCopyImages
        final java.util.Map<Integer, java.util.List<NoteImage>> copyImagesToSave = new java.util.HashMap<>();
        if (!copyForms.isEmpty()) {
            for (CopyFormBuilder.Holder h : copyForms) {
                copyImagesToSave.put(h.copyIndex, CopyFormBuilder.getNoteImages(h));
            }
        } else {
            copyImagesToSave.putAll(pendingCopyImages);
        }
        // 备注图片同样先取快照，避免后台线程读 UI 列表
        final List<NoteImage> imagesToSave = new ArrayList<>(noteImages);
        for (int i = 0; i < imagesToSave.size(); i++) {
            NoteImage ni = imagesToSave.get(i);
            ni.bookId = book.id; // 新增模式下 bookId 此时还未落定，统一在这里对齐
            ni.sort = i;
        }

        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (editMode) {
                app.db().bookDao().update(book);
                saveCopies(app, book.id, copiesToSave, copyImagesToSave);
                saveNoteImages(app, ownerKeyForBookNote(), imagesToSave);
            } else {
                // 新增前按 ISBN 查重（ISBN 为空则跳过）
                Book exist = book.isbn.isEmpty() ? null : app.db().bookDao().getByIsbn(book.isbn);
                if (exist != null) {
                    // 已录入过：提示是否改为编辑已有书籍
                    app.ui(() -> new MaterialAlertDialogBuilder(this)
                            .setTitle("书籍已录入")
                            .setMessage("《" + exist.title + "》已在书架中，要改为修改它吗？")
                            .setPositiveButton("修改已有书籍", (d, w) -> {
                                android.content.Intent intent = new android.content.Intent(
                                        this, BookEditActivity.class);
                                intent.putExtra(EXTRA_BOOK, exist);
                                startActivity(intent);
                                finish();
                            })
                            .setNegativeButton("返回继续编辑", null)
                            .show());
                    return;
                }
                book.createdAt = now;
                book.id = app.db().bookDao().insert(book);
                saveCopies(app, book.id, copiesToSave, copyImagesToSave);
                if (book.id > 0) {
                    saveNoteImages(app, ownerKeyForBookNote(), imagesToSave);
                }
                // 记住本次地点，下次录入自动选中
                getSharedPreferences("ui_prefs", MODE_PRIVATE).edit()
                        .putLong("last_location_id", book.locationId == null ? -1 : book.locationId)
                        .apply();
            }
            app.ui(() -> {
                cleanupRemovedNoteImageFiles(); // 移除的旧图文件 / 未保留的新图文件落地清理
                // v2.8：保存提示不再额外包书名号（用户反馈），统一走 BookText 剥掉自带书名号
                Toast.makeText(this, "已保存 " + com.bai.bookshelf.util.BookText.bareTitle(book.title),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    /** 把版次/印次存储串拆进三段输入框；自由文本旧格式原文塞进年框保留（不丢数据） */
    private void fillEditionFields(android.widget.EditText etYear, android.widget.EditText etMonth,
                                   android.widget.EditText etNo, String stored) {
        if (stored != null && !stored.isEmpty()) {
            // v3.7：走公共拆分（兼容空段与旧脏数据「2022-|」，能拆回就分段回显）
            String[] p = BookSanitizer.splitEdition(stored);
            if (p != null) {
                etYear.setText(p[0]);
                etMonth.setText(p[1]);
                etNo.setText(p[2]);
                return;
            }
            etYear.setText(stored); // 自由文本兜底：原文保留在年框
        }
    }

    /** 三段输入拼成存储串；三段全空返回空串（v3.7：空段不再产生悬空分隔符） */
    private String joinEdition(android.widget.EditText etYear, android.widget.EditText etMonth,
                               android.widget.EditText etNo) {
        return BookSanitizer.joinEdition(
                etYear.getText().toString().trim(),
                etMonth.getText().toString().trim(),
                etNo.getText().toString().trim());
    }

    /** 剥掉字数的「万字/万」后缀，用于编辑回显 */
    private String stripWanSuffix(String wordCount) {
        if (wordCount == null) {
            return "";
        }
        String s = wordCount.trim();
        if (s.endsWith("万字")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("万")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 字数输入 → 统一存储格式「X万字」（已带单位的不重复加） */
    private String wordCountToStore() {
        String s = b.etWordCount.getText().toString().trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains("万") || s.contains("千")) {
            return s;
        }
        return s + "万字";
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** null 安全：Room 的 String 字段在库里是可空列，老数据可能为 NULL，统一按空串处理 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
