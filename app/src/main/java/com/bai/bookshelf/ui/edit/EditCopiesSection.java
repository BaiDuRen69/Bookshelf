package com.bai.bookshelf.ui.edit;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.bai.bookshelf.util.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编辑页「逐本信息」栏目的编排：内联表单与二级页的切换、数量变化时的数据抢救、
 * 各本备注图片的加载与文件清理。单个副本栏目的构建与联动由 {@link CopyFormBuilder} 负责。
 */
public class EditCopiesSection {

    /** 宿主提供书名、模式、地点与相册选图入口 */
    public interface Host {
        Book book();

        boolean editMode();

        List<Location> locations();

        void pickCopyNoteImage(int copyIndex);
    }

    /** 超过这个本数就改用二级页填写逐本信息，页面内不再铺开 */
    private static final int INLINE_COPY_LIMIT = 3;

    private final ComponentActivity activity;
    private final ActivityBookEditBinding b;
    private final Host host;

    private final List<CopyFormBuilder.Holder> copyForms = new ArrayList<>(); // 内联副本栏目
    private final boolean[] copySuppress = {false};                           // 构建/回填期间抑制事件
    private boolean built = false;          // 栏目的首次构建是否已完成
    private boolean dirty = false;          // 副本栏目内容有改动
    private boolean expanded = false;       // ≤3 本时是否展开逐本栏目
    private List<BookCopy> pendingCopies = new ArrayList<>(); // 二级页回传的副本数据
    /** 数量变化重建表单前，先抢救当前表单的图片状态 */
    private final Map<Integer, List<NoteImage>> pendingCopyImages = new HashMap<>();

    private final ActivityResultLauncher<Intent> copyEditorLauncher;

    public EditCopiesSection(ComponentActivity activity, ActivityBookEditBinding b, Host host) {
        this.activity = activity;
        this.b = b;
        this.host = host;
        this.copyEditorLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onCopyEditorResult);
    }

    /** 地点列表加载完成后调用：首次就绪时按数量决定内联表单还是二级页入口 */
    public void onLocationsReady() {
        if (built) {
            return;
        }
        built = true;
        refresh();
    }

    /** 数量输入框变化 */
    public void onQuantityChanged() {
        if (built) {
            refresh();
        }
    }

    /** 点「逐本信息」入口：≤3 本展开/收起，>3 本进二级页 */
    public void onEntryClicked() {
        if (quantity() > INLINE_COPY_LIMIT) {
            openEditor();
        } else {
            expanded = !expanded;
            refresh();
        }
    }

    /** UI 线程：收集要保存的副本。内联表单存在时读表单，否则用二级页回传的数据 */
    public List<BookCopy> collect() {
        if (copyForms.isEmpty()) {
            return new ArrayList<>(pendingCopies);
        }
        List<BookCopy> list = new ArrayList<>();
        for (CopyFormBuilder.Holder form : copyForms) {
            list.add(CopyFormBuilder.collect(form, host.locations()));
        }
        return list;
    }

    /** 保存前收集各本备注图片快照（list 为新副本，供后台线程使用） */
    public Map<Integer, List<NoteImage>> imageSnapshot() {
        Map<Integer, List<NoteImage>> map = new HashMap<>();
        if (!copyForms.isEmpty()) {
            for (CopyFormBuilder.Holder h : copyForms) {
                map.put(h.copyIndex, CopyNoteImages.getNoteImages(h));
            }
        } else {
            map.putAll(pendingCopyImages);
        }
        return map;
    }

    /** 副本栏目内容或逐本备注图片是否有改动 */
    public boolean isDirty() {
        if (dirty) {
            return true;
        }
        for (CopyFormBuilder.Holder h : copyForms) {
            if (CopyNoteImages.hasImageChanges(h)) {
                return true;
            }
        }
        return false;
    }

    /** 相册选好的图片追加到指定副本（文件落盘由调用方完成） */
    public void addCopyNoteImage(NoteImage img, int copyIndex) {
        for (CopyFormBuilder.Holder h : copyForms) {
            if (h.copyIndex == copyIndex) {
                CopyNoteImages.addNoteImage(activity, h, img);
                return;
            }
        }
    }

    /** 放弃改动：删掉本次新加但已从列表移除的图片文件 */
    public void cleanupAbandoned() {
        for (CopyFormBuilder.Holder h : copyForms) {
            for (String p : h.addedImagePaths) {
                if (!contains(h.noteImages, p)) {
                    new File(p).delete();
                }
            }
            h.addedImagePaths.clear();
        }
    }

    /** 保存成功：清理未保留的新图与被移除的旧图文件 */
    public void cleanupRemoved() {
        for (CopyFormBuilder.Holder h : copyForms) {
            for (String p : h.addedImagePaths) {
                if (!contains(h.noteImages, p)) {
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

    private static boolean contains(List<NoteImage> list, String path) {
        for (NoteImage img : list) {
            if (img.path.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** 输入框中的拥有数量（空/非法按 1 处理） */
    private int quantity() {
        int q;
        try {
            q = Integer.parseInt(b.etQuantity.getText().toString().trim());
        } catch (Exception e) {
            q = 0;
        }
        return Math.max(1, q);
    }

    /** 数量/展开状态变化后的统一刷新：重建表单并更新入口文案与可见性 */
    private void refresh() {
        int quantity = quantity();
        rebuild(quantity, snapshot());
        // 数量为 1 时没有「逐本」可言（第 1 本即书级信息），入口与容器整体隐藏
        if (quantity <= 1) {
            b.tvCopiesEntry.setVisibility(View.GONE);
            b.containerCopies.setVisibility(View.GONE);
            return;
        }
        b.tvCopiesEntry.setVisibility(View.VISIBLE);
        b.tvCopiesEntry.setText("逐册信息（共 " + quantity + " 册）");
        if (quantity <= INLINE_COPY_LIMIT) {
            // ≤3 本：默认折叠，点入口展开/收起
            b.containerCopies.setVisibility(expanded ? View.VISIBLE : View.GONE);
            setEntryChevron(expanded ? R.drawable.ic_chevron_up_line : R.drawable.ic_chevron_down_line);
        } else {
            // >3 本：页面内不铺开，点入口进二级页统一填写
            b.containerCopies.setVisibility(View.GONE);
            setEntryChevron(R.drawable.ic_chevron_right_line);
        }
    }

    private void setEntryChevron(int drawableRes) {
        b.tvCopiesEntry.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, drawableRes, 0);
        b.tvCopiesEntry.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY)));
    }

    /** 当前副本数据快照：内联表单存在时从表单读，否则用二级页回传的数据 */
    private List<BookCopy> snapshot() {
        if (!copyForms.isEmpty()) {
            return collect();
        }
        return new ArrayList<>(pendingCopies);
    }

    /** 打开逐本信息二级页，填写完回写 pendingCopies 与 pendingCopyImages 并标脏 */
    private void openEditor() {
        Intent intent = new Intent(activity, CopyEditActivity.class);
        intent.putExtra(CopyEditActivity.EXTRA_QUANTITY, quantity());
        intent.putExtra(CopyEditActivity.EXTRA_COPIES, new ArrayList<>(snapshot()));
        intent.putExtra(CopyEditActivity.EXTRA_BOOK_ID, host.book().id);
        copyEditorLauncher.launch(intent);
    }

    private void onCopyEditorResult(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }
        Object extra = result.getData().getSerializableExtra(CopyEditActivity.RESULT_COPIES);
        if (extra instanceof java.util.ArrayList) {
            pendingCopies = new ArrayList<>();
            for (Object o : (java.util.ArrayList<?>) extra) {
                if (o instanceof BookCopy) {
                    pendingCopies.add((BookCopy) o);
                }
            }
            if (!pendingCopies.isEmpty()) {
                dirty = true;
                Toast.makeText(activity, "已更新 " + pendingCopies.size() + " 册的信息",
                        Toast.LENGTH_SHORT).show();
            }
        }
        // 同时接收各本备注图片
        pendingCopyImages.clear();
        Object imgExtra = result.getData().getSerializableExtra(CopyEditActivity.RESULT_COPY_IMAGES);
        if (imgExtra instanceof HashMap) {
            HashMap<?, ?> map = (HashMap<?, ?>) imgExtra;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof Integer && e.getValue() instanceof java.util.ArrayList) {
                    Integer idx = (Integer) e.getKey();
                    List<NoteImage> list = new ArrayList<>();
                    for (Object o : (java.util.ArrayList<?>) e.getValue()) {
                        if (o instanceof NoteImage) {
                            list.add((NoteImage) o);
                        }
                    }
                    pendingCopyImages.put(idx, list);
                }
            }
        }
    }

    /**
     * 按数量重建副本栏目（第 2 本起，共 quantity-1 个）。
     * 重建前先抢救当前栏目已填内容与图片；数量微调不丢输入。
     */
    private void rebuild(int quantity, List<BookCopy> prefill) {
        // 仍是内联形态时先抢救各本备注图片，数量微调后按 copyIndex 还原
        if (quantity <= INLINE_COPY_LIMIT) {
            for (CopyFormBuilder.Holder form : copyForms) {
                pendingCopyImages.put(form.copyIndex, CopyNoteImages.getNoteImages(form));
            }
        }
        copyForms.clear();
        b.containerCopies.removeAllViews();
        // 超过 3 本时不生成内联表单（改由二级页填写），不足 3 本时才按数量铺开
        if (quantity <= 1 || quantity > INLINE_COPY_LIMIT) {
            return;
        }
        for (int idx = 2; idx <= quantity; idx++) {
            BookCopy saved = null;
            for (BookCopy c : prefill) {
                if (c.copyIndex == idx) {
                    saved = c;
                    break;
                }
            }
            CopyFormBuilder.Holder h = CopyFormBuilder.build(activity, b.containerCopies, idx,
                    host.locations(), saved, copySuppress, () -> dirty = true,
                    copyIndex -> host.pickCopyNoteImage(copyIndex));
            // 若数量微调前该本已有图片，优先还原当前会话状态
            List<NoteImage> kept = pendingCopyImages.get(idx);
            if (kept != null && !kept.isEmpty()) {
                h.noteImages.clear();
                h.noteImages.addAll(kept);
                CopyNoteImages.renderNoteThumbs(h);
            }
            copyForms.add(h);
            // 编辑模式：从库加载该本备注图片（pending 优先，不会覆盖）
            if (host.editMode()) {
                CopyNoteImages.loadNoteImagesIfNeeded(activity, com.bai.bookshelf.app.BookshelfApp.get(),
                        host.book().id, h);
            }
        }
    }
}
