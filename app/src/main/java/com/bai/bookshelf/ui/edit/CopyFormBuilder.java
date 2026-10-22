package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 「逐本信息」副本表单的公共构建器。
 * BookEditActivity（≤3 本时的内联栏目）与 CopyEditActivity（>3 本时的二级页）共用，
 * 消除两处重复的视图绑定 / 渠道联动 / 回填 / 收集逻辑。
 *
 * 用法：
 * 1. {@link #build} 生成一个副本栏目（自动绑事件 + 回填 saved 数据）；
 * 2. 来源大类切换时内部自动调 {@link #loadChannels} 联动渠道选项；
 * 3. 保存时用 {@link #collect} 把栏目内容收集成 {@link BookCopy}。
 */
public final class CopyFormBuilder {

    private CopyFormBuilder() {
    }

    /** 来源大类对应的二级标签文案（与 own_type_entries 顺序一致） */
    public static final String[] CHANNEL_LABELS = {"购买渠道", "借阅自", "赠送来源"};

    /** 一个副本栏目的视图引用与联动状态 */
    public static final class Holder {
        public View root;
        public Spinner spStock;
        public Spinner spLocation;
        public Spinner spOwn;
        public Spinner spChannel;
        public EditText etDate;
        public EditText etPrice;
        public EditText etNote;
        public TextView tvChannelLabel;
        public android.widget.Button btnAddNoteImage;
        public android.widget.HorizontalScrollView scrollNoteThumbs;
        public android.widget.LinearLayout containerNoteThumbs;
        public final List<SourceChannel> channels = new ArrayList<>(); // 当前大类下的渠道选项
        public String pendingChannel;                                  // 渠道列表加载完后要选中的名称
        public int lastOwnType = -1;                                   // 上次渠道对应的大类（判断是否换类）
        public int copyIndex;                                          // 副本序号（从 2 起）

        // 该本备注图片（编辑模式从库加载，新增模式为空）
        public final List<com.bai.bookshelf.data.entity.NoteImage> noteImages = new ArrayList<>();
        public final java.util.Set<String> addedImagePaths = new java.util.HashSet<>();
        public final java.util.Set<String> removedImagePaths = new java.util.HashSet<>();
        public int initialNoteImageCount = 0;
    }

    /** 用户手动改动回调（供调用方做脏标记；构建/回填期间不会触发） */
    public interface OnUserChanged {
        void onChanged();
    }

    /** 点击「添加备注图片」时回调，调用方负责拉起相册并把选中的图片加进 holder */
    public interface OnAddImageClick {
        void onAddImage(int copyIndex);
    }

    /**
     * 生成一个副本栏目并加入 parent。
     *
     * @param suppress     长度为 1 的数组，构建/回填期间会被置 true（调用方也可用来批量抑制事件）
     * @param onUserChanged 用户手动改动时的回调（可为 null）
     * @param onAddImage  点击「添加备注图片」时回调（调用方负责相册选图）
     */
    public static Holder build(Activity act, ViewGroup parent, int copyIndex,
                               List<Location> locations, BookCopy saved,
                               boolean[] suppress, OnUserChanged onUserChanged,
                               OnAddImageClick onAddImage) {
        Holder h = new Holder();
        h.copyIndex = copyIndex;
        h.root = act.getLayoutInflater().inflate(R.layout.item_copy_form, parent, false);
        h.spStock = h.root.findViewById(R.id.sp_copy_stock);
        h.spLocation = h.root.findViewById(R.id.sp_copy_location);
        h.spOwn = h.root.findViewById(R.id.sp_copy_own);
        h.spChannel = h.root.findViewById(R.id.sp_copy_channel);
        h.etDate = h.root.findViewById(R.id.et_copy_date);
        h.etPrice = h.root.findViewById(R.id.et_copy_price);
        h.etNote = h.root.findViewById(R.id.et_copy_note);
        h.tvChannelLabel = h.root.findViewById(R.id.tv_copy_channel_label);
        h.btnAddNoteImage = h.root.findViewById(R.id.btn_copy_add_note_image);
        h.scrollNoteThumbs = h.root.findViewById(R.id.scroll_copy_note_thumbs);
        h.containerNoteThumbs = h.root.findViewById(R.id.container_copy_note_thumbs);
        ((TextView) h.root.findViewById(R.id.tv_copy_title)).setText("第 " + copyIndex + " 本");

        // 统一的「用户改动」包装：构建/回填期间不触发
        Runnable userChanged = () -> {
            if (onUserChanged != null && !suppress[0]) {
                onUserChanged.onChanged();
            }
        };

        // 状态 / 地点
        h.spStock.setAdapter(ArrayAdapter.createFromResource(act,
                R.array.stock_status_entries, android.R.layout.simple_spinner_dropdown_item));
        List<String> locNames = new ArrayList<>();
        locNames.add("（未设置）");
        for (Location loc : locations) {
            locNames.add(loc.name + (loc.isDefault ? "（默认）" : ""));
        }
        h.spLocation.setAdapter(new ArrayAdapter<>(act,
                android.R.layout.simple_spinner_dropdown_item, locNames));
        h.spStock.setOnItemSelectedListener(simpleSpinnerListener(userChanged));
        h.spLocation.setOnItemSelectedListener(simpleSpinnerListener(userChanged));

        // 来源大类：切换时联动渠道选项
        h.spOwn.setAdapter(ArrayAdapter.createFromResource(act,
                R.array.own_type_entries, android.R.layout.simple_spinner_dropdown_item));
        h.spOwn.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                loadChannels(act, h, position);
                userChanged.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });

        // 购买日期：点击弹日期选择器
        h.etDate.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(act, (DatePicker dp, int year, int month, int day) ->
                    h.etDate.setText(String.format("%04d-%02d-%02d", year, month + 1, day)),
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                    .show();
        });
        // 文本改动标脏（日期/实付/备注）
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b4, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                userChanged.run();
            }
        };
        h.etDate.addTextChangedListener(watcher);
        h.etPrice.addTextChangedListener(watcher);
        h.etNote.addTextChangedListener(watcher);

        // 回填已存内容（期间抑制事件）
        boolean oldSuppress = suppress[0];
        suppress[0] = true;
        if (saved != null) {
            h.spStock.setSelection(saved.stockStatus);
            if (saved.locationId != null) {
                for (int i = 0; i < locations.size(); i++) {
                    if (locations.get(i).id == saved.locationId) {
                        h.spLocation.setSelection(i + 1);
                        break;
                    }
                }
            }
            h.pendingChannel = saved.sourceChannel; // 渠道列表异步加载完后选中
            h.lastOwnType = saved.ownType;          // 回填不算切换大类，渠道保留
            h.spOwn.setSelection(saved.ownType);
            h.etDate.setText(saved.purchaseDate == null ? "" : saved.purchaseDate);
            if (saved.purchasePrice > 0) {
                h.etPrice.setText(String.valueOf(saved.purchasePrice));
            }
            h.etNote.setText(saved.note == null ? "" : saved.note);
        } else {
            h.spOwn.setSelection(0); // 触发首次渠道加载
        }

        // 备注图片：点击按钮通知调用方拉起相册
        h.btnAddNoteImage.setOnClickListener(v -> {
            if (onAddImage != null) {
                onAddImage.onAddImage(h.copyIndex);
            }
        });
        renderNoteThumbs(h);

        suppress[0] = oldSuppress;

        parent.addView(h.root);
        return h;
    }

    /** 只做「用户改动」回调的下拉监听（不承担联动逻辑） */
    private static android.widget.AdapterView.OnItemSelectedListener simpleSpinnerListener(Runnable userChanged) {
        return new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                userChanged.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        };
    }

    /**
     * 加载某副本当前来源大类下的渠道选项：
     * 标题随大类变化（购买渠道/借阅自/赠送来源）；用户主动换大类时清空不适用的旧渠道；
     * pendingChannel 在列表加载完后回选（用于编辑回填）。
     */
    public static void loadChannels(Activity act, Holder h, int ownType) {
        h.tvChannelLabel.setText(ownType >= 0 && ownType < CHANNEL_LABELS.length
                ? CHANNEL_LABELS[ownType] : "来源渠道");
        boolean categoryChanged = h.lastOwnType >= 0 && h.lastOwnType != ownType;
        h.lastOwnType = ownType;
        if (categoryChanged) {
            h.pendingChannel = null;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<SourceChannel> list = app.db().sourceChannelDao().getByType(ownType);
            app.ui(() -> {
                h.channels.clear();
                h.channels.addAll(list);
                List<String> names = new ArrayList<>();
                names.add("（无）");
                for (SourceChannel ch : list) {
                    names.add(ch.name);
                }
                h.spChannel.setAdapter(new ArrayAdapter<>(act,
                        android.R.layout.simple_spinner_dropdown_item, names));
                if (h.pendingChannel != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equals(h.pendingChannel)) {
                            h.spChannel.setSelection(i + 1);
                            break;
                        }
                    }
                    h.pendingChannel = null;
                } else if (categoryChanged) {
                    h.spChannel.setSelection(0); // 换了大类，原渠道不再适用
                }
            });
        });
    }

    /** 把栏目当前内容收集成实体（copyIndex 取自 Holder；bookId 由调用方补） */
    public static BookCopy collect(Holder h, List<Location> locations) {
        BookCopy c = new BookCopy();
        c.copyIndex = h.copyIndex;
        c.stockStatus = h.spStock.getSelectedItemPosition();
        int locPos = h.spLocation.getSelectedItemPosition();
        c.locationId = (locPos > 0 && locPos <= locations.size())
                ? locations.get(locPos - 1).id : null;
        c.ownType = h.spOwn.getSelectedItemPosition();
        // 取渠道显示文本本身，避免「渠道列表异步刷新」与选中下标错位（更换来源大类后仍正确）
        Object sel = h.spChannel.getSelectedItem();
        String chName = sel == null ? "" : sel.toString();
        if ("（无）".equals(chName) || "未设置渠道".equals(chName)) {
            chName = "";
        }
        c.sourceChannel = chName;
        c.purchaseDate = h.etDate.getText().toString().trim();
        String price = h.etPrice.getText().toString().trim();
        try {
            c.purchasePrice = price.isEmpty() ? 0 : Double.parseDouble(price);
        } catch (Exception e) {
            c.purchasePrice = 0;
        }
        c.note = h.etNote.getText().toString().trim();
        return c;
    }

    // ======================= 备注图片（第 N 本备注的图片附件） =======================

    /**
     * 编辑模式时从库加载该副本的备注图片。
     * 新增模式 bookId <= 0 时直接返回，避免无效查询。
     */
    public static void loadNoteImagesIfNeeded(Activity act, BookshelfApp app, long bookId, Holder h) {
        if (bookId <= 0) {
            h.initialNoteImageCount = h.noteImages.size();
            return;
        }
        // 若当前 holder 已有图片（来自 pendingCopyImages 抢救），以当前会话状态为准，不再覆盖
        if (!h.noteImages.isEmpty()) {
            h.initialNoteImageCount = h.noteImages.size();
            renderNoteThumbs(h);
            return;
        }
        String ownerKey = "copy:" + bookId + ":" + h.copyIndex;
        app.io(() -> {
            List<NoteImage> list = app.db().noteImageDao().getByOwnerKey(ownerKey);
            act.runOnUiThread(() -> {
                h.noteImages.clear();
                h.noteImages.addAll(list);
                h.initialNoteImageCount = list.size();
                renderNoteThumbs(h);
            });
        });
    }

    /** 把一张新选中的图片追加到该副本的备注图片列表（调用方已负责落盘） */
    public static void addNoteImage(Activity act, Holder h, NoteImage img) {
        h.noteImages.add(img);
        h.addedImagePaths.add(img.path);
        renderNoteThumbs(h);
    }

    /** 渲染备注图片缩略图横向条（点图预览/移除） */
    public static void renderNoteThumbs(Holder h) {
        if (h.containerNoteThumbs == null) {
            return;
        }
        h.containerNoteThumbs.removeAllViews();
        Activity act = (Activity) h.root.getContext();
        int size = (int) (72 * act.getResources().getDisplayMetrics().density);
        int gap = (int) (8 * act.getResources().getDisplayMetrics().density);
        for (NoteImage img : h.noteImages) {
            android.widget.ImageView iv = new android.widget.ImageView(act);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            Glide.with(act).load(new File(img.path))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> showNoteImagePreview(act, h, img));
            h.containerNoteThumbs.addView(iv);
        }
        boolean has = !h.noteImages.isEmpty();
        h.scrollNoteThumbs.setVisibility(has ? android.view.View.VISIBLE : android.view.View.GONE);
        h.btnAddNoteImage.setText(has
                ? "＋ 继续添加备注图片（" + h.noteImages.size() + " 张）"
                : "＋ 添加备注图片");
    }

    /** 预览大图并支持移除 */
    private static void showNoteImagePreview(Activity act, Holder h, NoteImage img) {
        // v4.18: 图片放进圆角卡片底
        com.bai.bookshelf.databinding.DialogImagePreviewBinding pb =
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(act.getLayoutInflater());
        Glide.with(act).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(act)
                .setTitle("备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .setNegativeButton("移除", (d, w) -> removeNoteImage(h, img))
                .show();
    }

    private static void removeNoteImage(Holder h, NoteImage img) {
        h.noteImages.remove(img);
        if (!h.addedImagePaths.contains(img.path)) {
            h.removedImagePaths.add(img.path);
        }
        renderNoteThumbs(h);
    }

    /** 取出当前要保存的图片列表（bookId/sort 由调用方补，ownerKey 由保存时补） */
    public static List<NoteImage> getNoteImages(Holder h) {
        return new ArrayList<>(h.noteImages);
    }

    /** 该副本的备注图片是否有增删（用于脏检查） */
    public static boolean hasImageChanges(Holder h) {
        return h.noteImages.size() != h.initialNoteImageCount;
    }
}
