package com.bai.bookshelf.ui.detail;

import com.bai.bookshelf.util.Typo;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.databinding.ActivityBookDetailBinding;
import com.bai.bookshelf.databinding.ItemDetailCopyBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 详情页的「逐本信息」区：按拥有数量铺开每一本（含第 1 本），行尾是借出/收回按钮；
 * 每本附借出备注（折叠预览）与借出备注图片（点击展开）。借还动作交给宿主。
 */
public class DetailCopyRows {

    /** 宿主提供当前书籍数据与借还入口 */
    public interface Host {
        Book book();

        List<BookCopy> copies();

        List<BorrowRecord> openRecords();

        Map<Long, String> copyLocNames();

        String locationName();

        Map<Integer, List<NoteImage>> copyLendImages();

        /** 来源借阅且已归还：禁止再借出 */
        boolean lendingDisabled();

        void showLendDialog(int copyIndex);

        void returnCopy(int copyIndex, BorrowRecord open);
    }

    private static final int COPY_COLLAPSE_LIMIT = 3; // 逐本信息默认最多展开几本
    private static final int NOTE_MAX_LINES = 3;
    /** 借出备注先显示的字数（单本借出区与管理区共用） */
    public static final int LEND_NOTE_PREVIEW_MAX = 20;

    private final BaseActivity activity;
    private final ActivityBookDetailBinding b;
    private final Host host;

    private boolean expanded = false;                                          // >3 本时是否展开全部
    private final Map<Integer, Boolean> noteExpanded = new HashMap<>();         // 第 N 本借出备注是否展开
    private final Map<Integer, Boolean> imagesExpanded = new HashMap<>();       // 第 N 本借出备注图片是否展开

    public DetailCopyRows(BaseActivity activity, ActivityBookDetailBinding b, Host host) {
        this.activity = activity;
        this.b = b;
        this.host = host;
    }

    /** 多本时显示并重绘逐本区；单本时隐藏并清空（借出按钮交给底栏） */
    public void render(boolean multi, boolean lendingDisabled) {
        b.btnLend.setVisibility(multi || lendingDisabled ? View.GONE : View.VISIBLE);
        b.cardCopies.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (!multi) {
            b.containerCopies.setVisibility(View.GONE);
            b.containerCopies.removeAllViews();
            return;
        }
        b.containerCopies.setVisibility(View.VISIBLE);
        build();
    }

    /** 按拥有数量铺开每一本（含第 1 本），行尾是借出/归还按钮；>3 本默认只显示前 3 本 */
    private void build() {
        Book book = host.book();
        b.containerCopies.removeAllViews();
        int showLimit = expanded ? book.quantity : Math.min(COPY_COLLAPSE_LIMIT, book.quantity);
        // v4.39：只 inflate 真正显示的行。之前 8 本套装会把 5 行隐藏项也建出来，
        // 首帧和 reload 重绘各多花 20ms+。
        for (int i = 1; i <= showLimit; i++) {
            ItemDetailCopyBinding rb = ItemDetailCopyBinding.inflate(
                    activity.getLayoutInflater(), b.containerCopies, false);
            BorrowRecord open = DetailCopyInfo.openRecordForCopy(host.openRecords(), i);
            rb.tvCopyInfo.setText(DetailCopyInfo.copyLine(book, host.copies(),
                    host.openRecords(), host.copyLocNames(), host.locationName(), i, open));
            boolean lendingDisabled = host.lendingDisabled();
            rb.btnCopyLend.setVisibility(lendingDisabled ? View.GONE : View.VISIBLE);
            rb.btnCopyLend.setText(open == null ? "借出" : "收回");
            final int index = i;
            final BorrowRecord openRecord = open;
            rb.btnCopyLend.setOnClickListener(v -> {
                if (openRecord == null) {
                    host.showLendDialog(index);
                } else {
                    host.returnCopy(index, openRecord);
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
            List<NoteImage> lendImgs = host.copyLendImages().get(i);
            if (lendImgs != null && !lendImgs.isEmpty()) {
                rb.tvLendNoteImages.setVisibility(View.VISIBLE);
                rb.containerLendNoteImages.setVisibility(
                        Boolean.TRUE.equals(imagesExpanded.get(i)) ? View.VISIBLE : View.GONE);
                renderLendNoteImagesArea(rb.tvLendNoteImages, rb.containerLendNoteImages, lendImgs, i);
            } else {
                rb.tvLendNoteImages.setVisibility(View.GONE);
                rb.containerLendNoteImages.setVisibility(View.GONE);
            }

            // 逐本备注：单独一行，最多 3 行，点击展开
            String copyNote = DetailCopyInfo.copyNoteText(book, host.copies(), i);
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
            TextView expandRow = new TextView(activity);
            expandRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            expandRow.setPadding(dp(8), dp(10), dp(8), dp(6));
            expandRow.setTextColor(ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY));
            Typo.text(expandRow, R.dimen.text_body_small);
            if (expanded) {
                expandRow.setText("收起逐册信息");
            } else {
                expandRow.setText("展开剩余 " + (book.quantity - COPY_COLLAPSE_LIMIT) + " 册");
            }
            expandRow.setOnClickListener(v -> {
                expanded = !expanded;
                build();
            });
            b.containerCopies.addView(expandRow);
        }
    }

    /** 借出备注预览：超过 20 字截断显示，点击切换展开/收起 */
    private void renderLendNotePreview(TextView tv, String note, int copyIndex) {
        boolean isExpanded = Boolean.TRUE.equals(noteExpanded.get(copyIndex));
        if (note.length() <= LEND_NOTE_PREVIEW_MAX || isExpanded) {
            tv.setText("借出备注：" + note);
            tv.setOnClickListener(null);
            if (note.length() > LEND_NOTE_PREVIEW_MAX) {
                tv.setOnClickListener(v -> {
                    noteExpanded.put(copyIndex, false);
                    renderLendNotePreview(tv, note, copyIndex);
                });
            }
        } else {
            tv.setText("借出备注：" + note.substring(0, LEND_NOTE_PREVIEW_MAX) + "…");
            tv.setOnClickListener(v -> {
                noteExpanded.put(copyIndex, true);
                renderLendNotePreview(tv, note, copyIndex);
            });
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
        View line = new View(activity);
        line.setBackgroundColor(ContextCompat.getColor(activity, R.color.divider_gray));
        int height = (int) (0.8f * activity.getResources().getDisplayMetrics().density);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return line;
    }

    /** 渲染借出备注图片入口与内容区 */
    private void renderLendNoteImagesArea(TextView entry, LinearLayout container,
                                          List<NoteImage> imgs, int copyIndex) {
        boolean isExpanded = Boolean.TRUE.equals(imagesExpanded.get(copyIndex));
        entry.setText(isExpanded
                ? "借出备注图片（" + imgs.size() + " 张）· 收起"
                : "借出备注图片（" + imgs.size() + " 张）· 展开");
        entry.setOnClickListener(v -> {
            imagesExpanded.put(copyIndex, !isExpanded);
            build(); // 重新渲染该本区域
        });
        if (isExpanded) {
            renderImages(container, imgs);
        } else {
            container.removeAllViews();
        }
    }

    /** 借出备注图片内容区（逐本区与单本管理区共用） */
    public void renderImages(LinearLayout container, List<NoteImage> imgs) {
        container.removeAllViews();
        for (NoteImage img : imgs) {
            ImageView iv = new ImageView(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(6));
            iv.setLayoutParams(lp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Glide.with(activity).load(new File(img.path))
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
                com.bai.bookshelf.databinding.DialogImagePreviewBinding.inflate(
                        activity.getLayoutInflater());
        Glide.with(activity).load(new File(img.path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("借出备注图片")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
