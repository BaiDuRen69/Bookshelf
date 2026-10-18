package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityCopyEditBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 逐本信息二级编辑页。
 * 场景：录入/编辑页里拥有数量 > 3 本时，页面下方不再铺开一堆副本表单，
 * 改为从这里统一填写第 2 本及以后的信息（表单构建/渠道联动/收集复用 {@link CopyFormBuilder}）。
 * 结果通过 setResult 回传给 BookEditActivity，最终随书籍一起保存。
 */
public class CopyEditActivity extends BaseActivity {

    public static final String EXTRA_COPIES = "copies";       // ArrayList<BookCopy>
    public static final String EXTRA_QUANTITY = "quantity";   // 拥有数量
    public static final String EXTRA_BOOK_ID = "book_id";     // 用于加载该本已有备注图片
    public static final String RESULT_COPIES = "result_copies";
    public static final String RESULT_COPY_IMAGES = "result_copy_images"; // HashMap<Integer, ArrayList<NoteImage>>

    private ActivityCopyEditBinding b;
    private int quantity = 1;
    private long bookId = 0;
    private List<BookCopy> copies = new ArrayList<>();
    private List<Location> locations = new ArrayList<>();
    private final List<CopyFormBuilder.Holder> rows = new ArrayList<>();
    /** 相册选图当前目标 copyIndex（>=2） */
    private int pendingCopyImageIndex = -1;
    private final ActivityResultLauncher<String> pickNoteImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || pendingCopyImageIndex < 0) {
                    return;
                }
                addCopyNoteImage(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCopyEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish()); // 未点保存则返回，不改动数据

        quantity = getIntent().getIntExtra(EXTRA_QUANTITY, 1);
        bookId = getIntent().getLongExtra(EXTRA_BOOK_ID, 0);
        Object extra = extraSerializable(EXTRA_COPIES);
        if (extra instanceof ArrayList) {
            for (Object o : (ArrayList<?>) extra) {
                if (o instanceof BookCopy) {
                    copies.add((BookCopy) o);
                }
            }
        }
        b.toolbar.setTitle("逐本信息（共 " + quantity + " 本）");

        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Location> locs = app.db().locationDao().getAll();
            app.ui(() -> {
                locations = locs;
                buildRows();
            });
        });

        b.btnSave.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra(RESULT_COPIES, new ArrayList<>(collect()));
            data.putExtra(RESULT_COPY_IMAGES, collectImages());
            setResult(Activity.RESULT_OK, data);
            Toast.makeText(this, "已保存逐本信息，回到录入页后记得保存书籍", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @SuppressWarnings("deprecation")
    private Object extraSerializable(String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getIntent().getSerializableExtra(key, java.util.ArrayList.class);
        }
        return getIntent().getSerializableExtra(key);
    }

    /** 按数量生成第 2 本起的副本表单（公共构建器统一处理绑定/联动/回填/图片） */
    private void buildRows() {
        rows.clear();
        b.container.removeAllViews();
        // 本页无需脏标记，回调传 null；图片点击回调交给本页统一拉起相册
        boolean[] unused = {false};
        BookshelfApp app = BookshelfApp.get();
        for (int idx = 2; idx <= quantity; idx++) {
            BookCopy saved = null;
            for (BookCopy c : copies) {
                if (c.copyIndex == idx) {
                    saved = c;
                    break;
                }
            }
            CopyFormBuilder.Holder h = CopyFormBuilder.build(this, b.container, idx,
                    locations, saved, unused, null, copyIndex -> {
                pendingCopyImageIndex = copyIndex;
                try {
                    pickNoteImageLauncher.launch("image/*");
                } catch (Exception e) {
                    pendingCopyImageIndex = -1;
                    Toast.makeText(this, "无法打开相册：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            // 编辑模式加载该本已有备注图片（bookId 来自上一页）
            if (bookId > 0) {
                CopyFormBuilder.loadNoteImagesIfNeeded(this, app, bookId, h);
            }
            rows.add(h);
        }
    }

    /** 把相册选中的图片落盘并加入对应副本的备注图片列表 */
    private void addCopyNoteImage(Uri uri) {
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
                    img.bookId = bookId;
                    img.path = out.getAbsolutePath();
                    for (CopyFormBuilder.Holder h : rows) {
                        if (h.copyIndex == pendingCopyImageIndex) {
                            CopyFormBuilder.addNoteImage(this, h, img);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 收集所有副本表单内容 */
    private List<BookCopy> collect() {
        List<BookCopy> list = new ArrayList<>();
        for (CopyFormBuilder.Holder row : rows) {
            list.add(CopyFormBuilder.collect(row, locations));
        }
        return list;
    }

    /** 收集每个副本当前要保存的备注图片 */
    private HashMap<Integer, ArrayList<NoteImage>> collectImages() {
        HashMap<Integer, ArrayList<NoteImage>> map = new HashMap<>();
        for (CopyFormBuilder.Holder row : rows) {
            map.put(row.copyIndex, new ArrayList<>(CopyFormBuilder.getNoteImages(row)));
        }
        return map;
    }
}
