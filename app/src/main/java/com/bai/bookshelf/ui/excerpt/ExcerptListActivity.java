package com.bai.bookshelf.ui.excerpt;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.databinding.ActivityExcerptListBinding;
import com.bai.bookshelf.databinding.DialogExcerptBinding;
import com.bai.bookshelf.databinding.DialogImagePreviewBinding;
import com.bai.bookshelf.databinding.DialogReviewBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.ExcerptFiles;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 摘抄与书评（v4.50）：一本书的短评 + 按时间倒序的摘抄时间线。
 * 点条目编辑、长按删除；右上角分享单条摘抄；图片本地保存，不上传。
 */
public class ExcerptListActivity extends BaseActivity {

    public static final String EXTRA_BOOK_ID = "excerpt_book_id";
    public static final String EXTRA_BOOK_TITLE = "excerpt_book_title";

    private ActivityExcerptListBinding b;
    private DialogExcerptBinding dialog;
    private long bookId;
    private String bookTitle = "";
    private Book book;
    private final List<Excerpt> items = new ArrayList<>();

    /** 编辑中的摘抄原有图片（取消/换图时清文件用） */
    private String originalImagePath = "";
    /** 弹窗里当前选中的图片（新复制进 files 的） */
    private String pendingImagePath = "";
    private boolean saved;
    private boolean dialogActive;
    private long dialogSession;
    private long pickSession;

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    copyPickedImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookId = getIntent().getLongExtra(EXTRA_BOOK_ID, -1);
        bookTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);
        if (bookTitle == null) {
            bookTitle = "";
        }
        b = ActivityExcerptListBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.tvExcerptBook.setText(bookTitle.isEmpty() ? "" : "《" + bookTitle + "》");
        InsetsHelper.applyBottomInset((View) b.btnAddExcerpt.getParent());
        b.btnAddExcerpt.setOnClickListener(v -> showExcerptDialog(null));
        b.cardReview.setOnClickListener(v -> showReviewDialog());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (bookId <= 0) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            Book loaded = null;
            List<Excerpt> list;
            try {
                loaded = app.db().bookDao().getById(bookId);
                list = app.db().excerptDao().getByBook(bookId);
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            final Book fBook = loaded;
            final List<Excerpt> fList = list;
            app.ui(() -> {
                if (b == null || isFinishing()) {
                    return;
                }
                book = fBook;
                items.clear();
                items.addAll(fList);
                render();
            });
        });
    }

    private void render() {
        if (book != null && !book.title.isEmpty()) {
            bookTitle = book.title;
            b.tvExcerptBook.setText("《" + bookTitle + "》");
        }
        String review = book == null || book.review == null ? "" : book.review.trim();
        b.tvReview.setText(review);
        b.tvReview.setVisibility(review.isEmpty() ? View.GONE : View.VISIBLE);
        b.tvReviewHint.setVisibility(review.isEmpty() ? View.VISIBLE : View.GONE);

        b.tvExcerptCount.setText("摘抄 · " + items.size() + " 条");
        b.llExcerpts.removeAllViews();
        b.llExcerptEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (Excerpt e : items) {
            b.llExcerpts.addView(buildRow(e));
        }
    }

    private View buildRow(Excerpt e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showExcerptDialog(e));
        card.setOnLongClickListener(v -> {
            confirmDelete(e);
            return true;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (e.page > 0) {
            TextView page = new TextView(this);
            page.setText("P." + e.page);
            page.setTextSize(11);
            page.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
            page.setBackgroundResource(R.drawable.bg_filter_pill);
            page.setPadding(dp(8), dp(2), dp(8), dp(2));
            header.addView(page);
        }

        TextView date = new TextView(this);
        date.setText(formatDate(e.createdAt));
        date.setTextSize(12);
        date.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart(e.page > 0 ? dp(8) : 0);
        header.addView(date, dlp);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        ImageButton share = new ImageButton(this);
        share.setImageResource(R.drawable.ic_share_line);
        share.setBackground(null);
        share.setImageTintList(android.content.res.ColorStateList.valueOf(
                ThemeManager.resolveColor(this, R.attr.on_surface_variant)));
        share.setPadding(dp(8), dp(8), dp(8), dp(8));
        share.setContentDescription("分享这条摘抄");
        share.setOnClickListener(v -> shareExcerpt(e));
        header.addView(share, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView content = new TextView(this);
        content.setText(e.content);
        content.setTextSize(16);
        content.setTypeface(android.graphics.Typeface.create("serif",
                android.graphics.Typeface.NORMAL));
        content.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        content.setLineSpacing(dp(4), 1f);
        content.setMaxLines(8);
        content.setEllipsize(TextUtils.TruncateAt.END);
        content.setBackgroundResource(R.drawable.bg_quote_card);
        content.setPadding(dp(28), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.addView(content, clp);

        if (e.hasNote()) {
            TextView note = new TextView(this);
            note.setText("批注：" + e.note.trim());
            note.setTextSize(13);
            note.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
            note.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(8);
            nlp.leftMargin = dp(2);
            card.addView(note, nlp);
        }

        if (e.hasTags()) {
            TextView tags = new TextView(this);
            String[] parts = e.tags.split("[,，]");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("   ");
                }
                sb.append('#').append(t);
            }
            tags.setText(sb);
            tags.setTextSize(12);
            tags.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(6);
            tlp.leftMargin = dp(2);
            card.addView(tags, tlp);
        }

        if (e.hasImage()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_excerpt_thumb);
            iv.setClipToOutline(true);
            iv.setContentDescription("摘抄配图");
            Glide.with(this).load(new File(e.imagePath))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(iv);
            iv.setOnClickListener(v -> previewImage(e.imagePath));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(170));
            ilp.topMargin = dp(10);
            card.addView(iv, ilp);
        }
        return card;
    }

    // ======================= 摘抄编辑 =======================

    private void showExcerptDialog(Excerpt existing) {
        dialog = DialogExcerptBinding.inflate(getLayoutInflater());
        saved = false;
        dialogActive = true;
        dialogSession++;
        originalImagePath = existing == null ? "" : existing.imagePath;
        pendingImagePath = originalImagePath == null ? "" : originalImagePath;

        if (existing != null) {
            if (existing.page > 0) {
                dialog.etExcerptPage.setText(String.valueOf(existing.page));
            }
            dialog.etExcerptContent.setText(existing.content);
            dialog.etExcerptNote.setText(existing.note);
            dialog.etExcerptTags.setText(existing.tags);
        }
        dialog.btnPickImage.setOnClickListener(v -> {
            pickSession = dialogSession;
            pickImageLauncher.launch("image/*");
        });
        dialog.btnRemoveImage.setOnClickListener(v -> {
            pendingImagePath = "";
            renderDialogImage();
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "添加摘抄" : "编辑摘抄")
                .setView(dialog.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null);
        if (existing != null) {
            builder.setNeutralButton("分享", (d, w) -> shareExcerpt(existing));
        }
        androidx.appcompat.app.AlertDialog alert = builder.show();
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (saveExcerpt(existing, alert)) {
                        alert.dismiss();
                    }
                });
        alert.setOnDismissListener(d -> {
            dialogActive = false;
            // 取消/返回：回收这次新复制但没保存的图片
            if (!saved && !pendingImagePath.equals(originalImagePath)) {
                ExcerptFiles.deleteQuietly(this, pendingImagePath);
            }
        });
        renderDialogImage();
    }

    private void renderDialogImage() {
        boolean has = !pendingImagePath.isEmpty();
        dialog.ivExcerptImage.setVisibility(has ? View.VISIBLE : View.GONE);
        dialog.btnRemoveImage.setVisibility(has ? View.VISIBLE : View.GONE);
        dialog.btnPickImage.setText(has ? "更换图片" : "添加图片");
        if (has) {
            Glide.with(this).load(new File(pendingImagePath))
                    .placeholder(R.drawable.ic_cover_placeholder)
                    .error(R.drawable.ic_cover_placeholder)
                    .into(dialog.ivExcerptImage);
        }
    }

    private void copyPickedImage(Uri uri) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                String path = ExcerptFiles.copyToFilesDir(this, uri);
                app.ui(() -> {
                    if (dialog == null || !dialogActive || pickSession != dialogSession) {
                        ExcerptFiles.deleteQuietly(this, path);
                        return;
                    }
                    // 换图时先把上一张未保存的新图删掉
                    if (!pendingImagePath.equals(originalImagePath)) {
                        ExcerptFiles.deleteQuietly(this, pendingImagePath);
                    }
                    pendingImagePath = path;
                    renderDialogImage();
                });
            } catch (Exception e) {
                app.ui(() -> Toast.makeText(this, "图片读取失败：" + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private boolean saveExcerpt(Excerpt existing, androidx.appcompat.app.AlertDialog alert) {
        if (dialog == null) {
            return false;
        }
        String content = dialog.etExcerptContent.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(this, "摘抄原文不能为空", Toast.LENGTH_SHORT).show();
            return false;
        }
        int page = 0;
        try {
            page = Integer.parseInt(dialog.etExcerptPage.getText().toString().trim());
        } catch (Exception ignored) {
        }
        final Excerpt e = existing == null ? new Excerpt() : existing;
        e.bookId = bookId;
        e.page = Math.max(0, page);
        e.content = content;
        e.note = dialog.etExcerptNote.getText().toString().trim();
        e.tags = dialog.etExcerptTags.getText().toString().trim();
        e.imagePath = pendingImagePath;
        long now = System.currentTimeMillis();
        e.updatedAt = now;
        if (existing == null) {
            e.createdAt = now;
        }
        saved = true;
        final String oldPath = originalImagePath;
        final String newPath = pendingImagePath;
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            if (existing == null) {
                app.db().excerptDao().insert(e);
            } else {
                app.db().excerptDao().update(e);
            }
            if (!newPath.equals(oldPath)) {
                ExcerptFiles.deleteQuietly(this, oldPath);
            }
            app.ui(() -> {
                Toast.makeText(this, "已保存摘抄", Toast.LENGTH_SHORT).show();
                load();
            });
        });
        return true;
    }

    private void confirmDelete(Excerpt e) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除摘抄")
                .setMessage("删掉这条摘抄" + (e.hasImage() ? "和它的配图" : "") + "？")
                .setPositiveButton("删除", (d, w) -> {
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().excerptDao().deleteById(e.id);
                        ExcerptFiles.deleteQuietly(this, e.imagePath);
                        app.ui(this::load);
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ======================= 短评 =======================

    private void showReviewDialog() {
        DialogReviewBinding db = DialogReviewBinding.inflate(getLayoutInflater());
        String current = book == null || book.review == null ? "" : book.review;
        db.etReview.setText(current);
        androidx.appcompat.app.AlertDialog alert = new MaterialAlertDialogBuilder(this)
                .setTitle("我的短评")
                .setView(db.getRoot())
                .setPositiveButton("保存", null)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (book == null) {
                        return;
                    }
                    String review = db.etReview.getText().toString().trim();
                    Book updated = book;
                    updated.review = review;
                    updated.updatedAt = System.currentTimeMillis();
                    BookshelfApp app = BookshelfApp.get();
                    app.io(() -> {
                        app.db().bookDao().update(updated);
                        app.ui(() -> {
                            Toast.makeText(this, review.isEmpty() ? "已清空短评" : "短评已保存",
                                    Toast.LENGTH_SHORT).show();
                            load();
                        });
                    });
                    alert.dismiss();
                });
    }

    // ======================= 分享 / 图片预览 =======================

    private void shareExcerpt(Excerpt e) {
        StringBuilder sb = new StringBuilder();
        String title = book == null || book.title == null ? bookTitle : book.title;
        sb.append("《").append(title).append("》");
        if (e.page > 0) {
            sb.append(" · 第 ").append(e.page).append(" 页");
        }
        sb.append("\n\n「").append(e.content).append("」");
        if (e.hasNote()) {
            sb.append("\n\n批注：").append(e.note.trim());
        }
        if (e.hasTags()) {
            sb.append("\n#").append(e.tags.replace(",", "  #").replace("，", "  #"));
        }
        sb.append("\n\n—— 摘抄自「小书房」");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(intent, "分享摘抄"));
    }

    private void previewImage(String path) {
        DialogImagePreviewBinding pb = DialogImagePreviewBinding.inflate(getLayoutInflater());
        Glide.with(this).load(new File(path))
                .placeholder(R.drawable.ic_cover_placeholder)
                .error(R.drawable.ic_cover_placeholder)
                .into(pb.ivPreview);
        new MaterialAlertDialogBuilder(this)
                .setTitle("摘抄配图")
                .setView(pb.getRoot())
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(millis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
