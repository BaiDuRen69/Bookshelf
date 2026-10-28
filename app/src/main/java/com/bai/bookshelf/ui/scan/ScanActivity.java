package com.bai.bookshelf.ui.scan;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.databinding.ActivityScanBinding;
import com.bai.bookshelf.util.CoverLoader;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 扫码录入页：CameraX 预览 + ML Kit 本地识别条形码。
 * 识别到 ISBN 后走统一的「查重 → 补全 → 确认」流程；
 * 编辑页返回后自动恢复扫描，实现连扫。
 */
public class ScanActivity extends BaseActivity {

    private ActivityScanBinding b;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private Camera camera;

    /** 正在处理某个条码（查询/编辑中），避免同一本书被重复触发 */
    private volatile boolean handling = false;
    private boolean torchOn = false;

    // ==================== v4.46 盘点模式 ====================
    public static final String EXTRA_INVENTORY = "inventory";
    private boolean inventoryMode = false;
    private final java.util.Set<String> inventoryScanned = new java.util.HashSet<>();
    private int inventoryUnknown = 0;

    // ==================== 相机 / 权限 / 图片识别 ====================
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideOkRunnable = () -> {
        if (b != null) {
            b.llScanOk.setVisibility(android.view.View.GONE);
        }
    };

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    b.llCameraError.setVisibility(android.view.View.GONE);
                    b.overlayScan.setVisibility(android.view.View.VISIBLE);
                    startCamera();
                } else {
                    showCameraError("没有相机权限",
                            "请在系统设置里允许小书房使用相机；也可以先用下方的手动输入 / 从图片识别");
                }
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    recognizeImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsHelper.applyTopInset(b.topBar);
        InsetsHelper.applyBottomInset(b.bottomBar);

        b.btnBack.setOnClickListener(v -> finish());
        b.btnManual.setOnClickListener(v -> showManualInput());
        b.btnTorch.setOnClickListener(v -> toggleTorch());
        b.btnImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        b.btnCameraRetry.setOnClickListener(v -> retryCamera());
        b.btnBatch.setOnClickListener(v -> toggleBatchMode());
        b.btnBatchQueue.setOnClickListener(v -> showBatchSheet());

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A)
                .build());

        setupInventoryMode();
        updateBatchUi();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑页 / 重复提示回来，恢复可扫状态
        resetHandling();
    }

    /** 开始 CameraX 预览 + 图像分析 */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(b.previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) {
                        imageProxy.close();
                        return;
                    }
                    InputImage image = InputImage.fromMediaImage(mediaImage,
                            imageProxy.getImageInfo().getRotationDegrees());
                    scanner.process(image)
                            .addOnSuccessListener(barcodes -> {
                                for (Barcode code : barcodes) {
                                    if (onBarcode(code.getRawValue())) {
                                        break;
                                    }
                                }
                            })
                            .addOnCompleteListener(t -> imageProxy.close());
                });

                provider.unbindAll();
                camera = provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                b.llCameraError.setVisibility(android.view.View.GONE);
                b.overlayScan.setVisibility(android.view.View.VISIBLE);
            } catch (Exception e) {
                android.util.Log.w("ScanActivity", "camera start failed", e);
                showCameraError("相机启动失败",
                        "可能被其他应用占用或相机不可用。可以先用下方的手动输入 / 从图片识别");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 相机不可用提示态 */
    private void showCameraError(String title, String desc) {
        if (b == null) {
            return;
        }
        b.llCameraError.setVisibility(android.view.View.VISIBLE);
        b.overlayScan.setVisibility(android.view.View.GONE);
        b.progress.setVisibility(android.view.View.GONE);
        b.tvCameraErrorTitle.setText(title);
        b.tvCameraErrorDesc.setText(desc);
    }

    private void retryCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            b.llCameraError.setVisibility(android.view.View.GONE);
            b.overlayScan.setVisibility(android.view.View.VISIBLE);
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * 一帧里扫到条码：校验成 ISBN 后按当前模式分发。
     * @return true 表示已消费（跳出本帧剩余条码）
     */
    private boolean onBarcode(String raw) {
        String isbn = normalizeIsbn(raw);
        if (isbn.length() != 10 && isbn.length() != 13) {
            return false;
        }
        if (handling) {
            return false;
        }
        handling = true;
        b.progress.setVisibility(android.view.View.VISIBLE);
        flashScanOk();
        if (inventoryMode) {
            handleInventoryScan(isbn);
        } else if (batchMode) {
            addToBatch(isbn);
        } else {
            AddBookHelper.byIsbn(this, isbn, this::resetHandling);
        }
        return true;
    }

    /** 识别成功的短反馈（对勾浮层） */
    private void flashScanOk() {
        b.llScanOk.setVisibility(android.view.View.VISIBLE);
        mainHandler.removeCallbacks(hideOkRunnable);
        mainHandler.postDelayed(hideOkRunnable, 700);
    }

    /** 手动输入 ISBN（相机不可用 / 条码磨损时兜底） */
    private void showManualInput() {
        DialogInputBinding input = DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("ISBN（10 或 13 位）");
        input.edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("手动输入 ISBN")
                .setView(input.getRoot())
                .setPositiveButton("确定", null)
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String isbn = normalizeIsbn(input.edit.getText().toString());
                    if (isbn.length() != 10 && isbn.length() != 13) {
                        Toast.makeText(this, "ISBN 需要 10 位或 13 位数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    if (handling) {
                        return;
                    }
                    handling = true;
                    b.progress.setVisibility(android.view.View.VISIBLE);
                    if (inventoryMode) {
                        handleInventoryScan(isbn);
                    } else if (batchMode) {
                        addToBatch(isbn);
                    } else {
                        AddBookHelper.byIsbn(this, isbn, this::resetHandling);
                    }
                });
    }

    /** 从相册图片识别 ISBN（本地 ML Kit，不上传） */
    private void recognizeImage(android.net.Uri uri) {
        InputImage image;
        try {
            image = InputImage.fromFilePath(this, uri);
        } catch (Exception e) {
            Toast.makeText(this, "读不出这张图片：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在识别图片…", Toast.LENGTH_SHORT).show();
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode code : barcodes) {
                        if (onBarcode(code.getRawValue())) {
                            return;
                        }
                    }
                    // v4.51：没有条码则按封面/扉页文字识别书名与作者
                    ocrImage(uri);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "识别失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    /** 图片里没有 ISBN：OCR 猜书名/作者，连同图片一起带进录入页 */
    private void ocrImage(final android.net.Uri uri) {
        Toast.makeText(this, "没找到 ISBN，正在识别封面文字…", Toast.LENGTH_SHORT).show();
        com.bai.bookshelf.util.BookOcr.recognize(this, uri,
                new com.bai.bookshelf.util.BookOcr.Callback() {
                    @Override
                    public void onDone(com.bai.bookshelf.util.BookOcr.Result result) {
                        openOcrPrefill(uri, result);
                    }

                    @Override
                    public void onFailed(String message) {
                        Toast.makeText(ScanActivity.this,
                                "这张图里没找到 ISBN，也没认出文字，可以手动输入",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void openOcrPrefill(final android.net.Uri uri,
                                final com.bai.bookshelf.util.BookOcr.Result result) {
        com.bai.bookshelf.app.BookshelfApp app = com.bai.bookshelf.app.BookshelfApp.get();
        app.io(() -> {
            String cover = "";
            try {
                cover = com.bai.bookshelf.util.CoverFiles.copyToCovers(this, uri);
            } catch (Exception ignored) {
            }
            final String fCover = cover;
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Book prefill = new Book();
                prefill.title = result.title;
                prefill.author = result.author;
                prefill.publisher = result.publisher;
                prefill.coverUrl = fCover;
                handling = true;
                android.content.Intent intent = new android.content.Intent(
                        this, com.bai.bookshelf.ui.edit.BookEditActivity.class);
                intent.putExtra(com.bai.bookshelf.ui.edit.BookEditActivity.EXTRA_PREFILL, prefill);
                startActivity(intent);
                Toast.makeText(this, result.title.isEmpty()
                                ? "没认出书名，已把图片设为封面，请手动填写"
                                : "已识别：" + result.title + "（请确认后保存）",
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void setupInventoryMode() {
        inventoryMode = getIntent().getBooleanExtra(EXTRA_INVENTORY, false);
        if (!inventoryMode) {
            return;
        }
        b.btnBatch.setVisibility(android.view.View.GONE);
        b.btnInventoryDone.setVisibility(android.view.View.VISIBLE);
        b.tvScanTitle.setText("盘点模式");
        b.tvScanSub.setText("扫描书房里的书 · 扫完点「结束盘点」");
        b.tvScanHint.setText("借出中 / 遗失的书不计入盘点");
        b.btnInventoryDone.setOnClickListener(v -> showInventoryResult());
        updateInventoryCounter();
    }

    private void updateInventoryCounter() {
        if (b == null) {
            return;
        }
        b.btnInventoryDone.setText("已扫 " + inventoryScanned.size() + " 本 · 结束盘点");
    }

    /** 盘点扫到一本：查库判断是否在书房（归一化 ISBN 比较），只记不写 */
    private void handleInventoryScan(String isbn) {
        final String key = normalizeIsbn(isbn);
        final com.bai.bookshelf.app.BookshelfApp app =
                com.bai.bookshelf.app.BookshelfApp.get();
        app.io(() -> {
            boolean found = false;
            String title = "";
            try {
                for (Book book : app.db().bookDao().getAll()) {
                    if (normalizeIsbn(book.isbn).equals(key)) {
                        found = true;
                        title = book.title;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            final boolean fFound = found;
            final String fTitle = title;
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (fFound) {
                    if (inventoryScanned.add(key)) {
                        Toast.makeText(this, "✓ " + fTitle, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "这本已扫过", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    inventoryUnknown++;
                    Toast.makeText(this, "不在书房：ISBN " + isbn, Toast.LENGTH_SHORT).show();
                }
                updateInventoryCounter();
            });
        });
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::resetHandling, 1000);
    }

    /** 结束盘点：算出缺失（在库且有 ISBN 但没扫到）与不在书房的扫描，可选标记遗失 */
    private void showInventoryResult() {
        final com.bai.bookshelf.app.BookshelfApp app =
                com.bai.bookshelf.app.BookshelfApp.get();
        final int unknown = inventoryUnknown;
        app.io(() -> {
            java.util.List<Book> missing = new java.util.ArrayList<>();
            int scannable = 0;
            int noIsbn = 0;
            try {
                for (Book book : com.bai.bookshelf.util.BookSanitizer
                        .normalizeAll(app.db().bookDao().getAll())) {
                    if (book.stockStatus != Book.STOCK_IN) {
                        continue; // 借出中 / 遗失不参与盘点
                    }
                    if (normalizeIsbn(book.isbn).isEmpty()) {
                        noIsbn++;
                        continue;
                    }
                    scannable++;
                    if (!inventoryScanned.contains(normalizeIsbn(book.isbn))) {
                        missing.add(book);
                    }
                }
            } catch (Exception ignored) {
            }
            final java.util.List<Book> fMissing = missing;
            final int fScannable = scannable;
            final int fNoIsbn = noIsbn;
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                int found = fScannable - fMissing.size();
                StringBuilder msg = new StringBuilder();
                msg.append("可盘点 ").append(fScannable).append(" 本（在库且有 ISBN）");
                if (fNoIsbn > 0) {
                    msg.append("，另有 ").append(fNoIsbn).append(" 本没有 ISBN 无法扫码");
                }
                msg.append("。\n\n找到 ").append(found).append(" 本 · 缺失 ")
                        .append(fMissing.size()).append(" 本");
                if (unknown > 0) {
                    msg.append("\n另有 ").append(unknown).append(" 次扫描不在书房");
                }
                if (!fMissing.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < Math.min(6, fMissing.size()); i++) {
                        if (i > 0) {
                            names.append("、");
                        }
                        names.append(fMissing.get(i).title);
                    }
                    if (fMissing.size() > 6) {
                        names.append(" 等");
                    }
                    msg.append("\n\n缺失：").append(names);
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                                .setTitle("盘点结果")
                                .setMessage(msg.toString())
                                .setNegativeButton("关闭", (d, w) -> finish());
                if (!fMissing.isEmpty()) {
                    builder.setPositiveButton("缺失的标记为遗失", (d, w) ->
                            markInventoryLost(app, fMissing));
                }
                builder.show();
            });
        });
    }

    private void markInventoryLost(com.bai.bookshelf.app.BookshelfApp app,
                                   java.util.List<Book> missing) {
        app.io(() -> {
            long now = System.currentTimeMillis();
            for (Book book : missing) {
                book.stockStatus = Book.STOCK_LOST;
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
            }
            app.ui(() -> {
                Toast.makeText(this, "已把 " + missing.size() + " 本标记为遗失",
                        Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    private static String normalizeIsbn(String isbn) {
        return isbn == null ? "" : isbn.replaceAll("[^0-9Xx]", "")
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** 恢复扫码待机状态（重复提示关闭后调用） */
    private void resetHandling() {
        handling = false;
        if (b != null) {
            b.progress.setVisibility(android.view.View.GONE);
        }
    }

    // ==================== v4.43 批量录入 ====================
    private static final int BATCH_QUERYING = 0;
    private static final int BATCH_READY = 1;
    private static final int BATCH_DUPLICATE = 2;
    private static final int BATCH_FAILED = 3;

    private boolean batchMode = false;
    private final java.util.List<BatchItem> batchQueue = new java.util.ArrayList<>();
    private android.app.Dialog batchDialog;
    private android.widget.LinearLayout batchList;
    private android.widget.TextView batchSaveBtn;
    private android.widget.TextView batchLocationRow;
    private android.widget.CheckBox batchCopyCheck;
    private final java.util.List<Location> batchLocations = new java.util.ArrayList<>();
    private long batchLocationId = -1;
    private String batchLocationName = "未设置";

    // ==================== v4.43 批量录入 ====================

    private void toggleBatchMode() {
        batchMode = !batchMode;
        b.btnBatch.setBackgroundResource(batchMode
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_scan_capsule);
        b.btnBatch.setTextColor(batchMode
                ? ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY)
                : android.graphics.Color.WHITE);
        b.tvScanHint.setText(batchMode
                ? "批量模式：连续扫码，扫完点上方队列保存"
                : "将条形码放入框内，即可自动识别");
        updateBatchUi();
        Toast.makeText(this, batchMode ? "批量模式已开启" : "已退出批量模式",
                Toast.LENGTH_SHORT).show();
    }

    private void updateBatchUi() {
        int total = batchQueue.size();
        // 有排队中的书就保留入口，避免用户关掉批量模式后找不到队列
        if (total > 0) {
            b.btnBatchQueue.setVisibility(android.view.View.VISIBLE);
            b.btnBatchQueue.setText("已加入 " + total + " 本 · 查看");
        } else {
            b.btnBatchQueue.setVisibility(android.view.View.GONE);
        }
    }

    /** 扫码命中一本：查重 + 补全信息，加入队列（不打断连续扫描） */
    private void addToBatch(String isbn) {
        for (BatchItem item : batchQueue) {
            if (item.isbn.equals(isbn)) {
                Toast.makeText(this, "这本已在待录入列表", Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this::resetHandling, 800);
                return;
            }
        }
        final BatchItem item = new BatchItem(isbn);
        batchQueue.add(item);
        updateBatchUi();
        AddBookHelper.lookup(this, isbn, new AddBookHelper.LookupCallback() {
            @Override
            public void onDuplicate(Book existing) {
                item.status = BATCH_DUPLICATE;
                item.existingId = existing.id;
                item.title = existing.title;
                item.author = existing.author;
                item.coverUrl = existing.coverUrl;
                afterBatchItemChanged();
            }

            @Override
            public void onBook(Book book) {
                item.status = BATCH_READY;
                item.book = book;
                item.title = book.title;
                item.author = book.author;
                item.coverUrl = book.coverUrl;
                afterBatchItemChanged();
            }

            @Override
            public void onFailed(String message) {
                item.status = BATCH_FAILED;
                item.message = message == null ? "查询失败" : message;
                afterBatchItemChanged();
            }
        });
        // 留 1.2s 让镜头移开，避免同一本书被连续触发
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::resetHandling, 1200);
    }

    private void afterBatchItemChanged() {
        updateBatchUi();
        if (batchDialog != null && batchDialog.isShowing()) {
            renderBatchItems();
            updateBatchSave();
        }
    }

    private void loadBatchLocation() {
        com.bai.bookshelf.app.BookshelfApp app =
                com.bai.bookshelf.app.BookshelfApp.get();
        long last = getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getLong("last_location_id", -1);
        app.io(() -> {
            java.util.List<Location> all;
            Location def = null;
            try {
                all = app.db().locationDao().getAll();
            } catch (Exception e) {
                all = new java.util.ArrayList<>();
            }
            try {
                def = app.db().locationDao().getDefault();
            } catch (Exception ignored) {
            }
            final java.util.List<Location> fAll = all;
            final Location fDef = def;
            app.ui(() -> {
                batchLocations.clear();
                batchLocations.addAll(fAll);
                long id = last;
                if (id <= 0 && fDef != null) {
                    id = fDef.id;
                }
                batchLocationId = id;
                batchLocationName = "未设置";
                for (Location loc : fAll) {
                    if (loc.id == id) {
                        batchLocationName = loc.name;
                    }
                }
                if (batchLocationRow != null) {
                    batchLocationRow.setText("存放地点：" + batchLocationName + "（点按更换）");
                }
            });
        });
    }

    private void showBatchSheet() {
        if (batchQueue.isEmpty()) {
            Toast.makeText(this, "还没有扫到书", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.view.View scrim = new android.view.View(this);
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(v -> dialog.dismiss());
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int bottomInset = InsetsHelper.systemBars(getWindow().getDecorView()).bottom;

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundResource(R.drawable.bg_bottom_sheet);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(8), pad, dp(12) + bottomInset);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        android.view.View handle = new android.view.View(this);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        android.widget.LinearLayout.LayoutParams hlp = new android.widget.LinearLayout.LayoutParams(
                dp(36), dp(4));
        hlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(4);
        hlp.bottomMargin = dp(10);
        content.addView(handle, hlp);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("待录入");
        title.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.text_title));
        title.setTypeface(android.graphics.Typeface.create("serif",
                android.graphics.Typeface.NORMAL));
        content.addView(title);

        batchLocationRow = new android.widget.TextView(this);
        batchLocationRow.setBackgroundResource(R.drawable.bg_dialog_row);
        batchLocationRow.setPadding(dp(14), dp(12), dp(14), dp(12));
        batchLocationRow.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface));
        batchLocationRow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        batchLocationRow.setText("存放地点：" + batchLocationName + "（点按更换）");
        batchLocationRow.setClickable(true);
        batchLocationRow.setFocusable(true);
        batchLocationRow.setOnClickListener(v -> showBatchLocationPicker());
        android.widget.LinearLayout.LayoutParams llp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        content.addView(batchLocationRow, llp);

        android.widget.ScrollView listScroll = new android.widget.ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        batchList = new android.widget.LinearLayout(this);
        batchList.setOrientation(android.widget.LinearLayout.VERTICAL);
        listScroll.addView(batchList, new android.widget.ScrollView.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams listLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (screenH * 0.42f));
        listLp.topMargin = dp(6);
        content.addView(listScroll, listLp);

        batchCopyCheck = new android.widget.CheckBox(this);
        batchCopyCheck.setText("重复的书也各加一本副本");
        batchCopyCheck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        batchCopyCheck.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        batchCopyCheck.setOnCheckedChangeListener((btn, checked) -> updateBatchSave());
        android.widget.LinearLayout.LayoutParams clp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        content.addView(batchCopyCheck, clp);

        android.widget.LinearLayout footer = new android.widget.LinearLayout(this);
        footer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams flp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        flp.topMargin = dp(10);
        content.addView(footer, flp);

        android.widget.TextView keepScanning = new android.widget.TextView(this);
        keepScanning.setText("继续扫描");
        keepScanning.setTextColor(ThemeManager.resolveColor(this, R.attr.on_surface_variant));
        keepScanning.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        keepScanning.setGravity(android.view.Gravity.CENTER);
        keepScanning.setPadding(dp(12), 0, dp(12), 0);
        keepScanning.setClickable(true);
        keepScanning.setOnClickListener(v -> dialog.dismiss());
        footer.addView(keepScanning, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        footer.addView(new android.view.View(this),
                new android.widget.LinearLayout.LayoutParams(0, 1, 1f));

        batchSaveBtn = new android.widget.TextView(this);
        batchSaveBtn.setText("保存");
        batchSaveBtn.setTextColor(ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY));
        batchSaveBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        batchSaveBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        batchSaveBtn.setGravity(android.view.Gravity.CENTER);
        batchSaveBtn.setPadding(dp(12), 0, dp(12), 0);
        batchSaveBtn.setClickable(true);
        batchSaveBtn.setOnClickListener(v -> saveBatch(dialog));
        footer.addView(batchSaveBtn, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        scroll.measure(android.view.View.MeasureSpec.makeMeasureSpec(screenW,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec((int) (screenH * 0.9f),
                        android.view.View.MeasureSpec.AT_MOST));
        int sheetH = Math.min(scroll.getMeasuredHeight(), (int) (screenH * 0.9f));
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, sheetH,
                android.view.Gravity.BOTTOM));

        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            window.setWindowAnimations(R.style.Animation_Bookshelf_Sheet);
            InsetsHelper.makeSheetWindowFullScreen(window);
        }
        dialog.setCanceledOnTouchOutside(true);
        batchDialog = dialog;
        renderBatchItems();
        updateBatchSave();
        dialog.show();
        if (window != null) {
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void showBatchLocationPicker() {
        final String[] names = new String[batchLocations.size() + 1];
        names[0] = "未设置";
        for (int i = 0; i < batchLocations.size(); i++) {
            names[i + 1] = batchLocations.get(i).name;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("存放地点")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        batchLocationId = -1;
                        batchLocationName = "未设置";
                    } else {
                        Location loc = batchLocations.get(which - 1);
                        batchLocationId = loc.id;
                        batchLocationName = loc.name;
                    }
                    if (batchLocationRow != null) {
                        batchLocationRow.setText("存放地点：" + batchLocationName + "（点按更换）");
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void renderBatchItems() {
        if (batchList == null) {
            return;
        }
        batchList.removeAllViews();
        int primary = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurface = ThemeManager.resolveColor(this, R.attr.on_surface);
        int onVariant = ThemeManager.resolveColor(this, R.attr.on_surface_variant);
        int danger = ContextCompat.getColor(this, R.color.danger);
        for (int i = 0; i < batchQueue.size(); i++) {
            final BatchItem item = batchQueue.get(i);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            android.widget.ImageView cover = new android.widget.ImageView(this);
            cover.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(44), dp(60)));
            cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            cover.setClipToOutline(true);
            cover.setBackgroundResource(R.drawable.bg_cover);
            loadBatchCover(cover, item);
            row.addView(cover);

            android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
            texts.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams tlp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(dp(12));
            row.addView(texts, tlp);

            android.widget.TextView tvTitle = new android.widget.TextView(this);
            tvTitle.setText(batchTitle(item));
            tvTitle.setTextColor(onSurface);
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            tvTitle.setMaxLines(2);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(tvTitle);

            android.widget.TextView tvStatus = new android.widget.TextView(this);
            tvStatus.setText(batchStatus(item));
            tvStatus.setTextColor(item.status == BATCH_FAILED ? danger : onVariant);
            tvStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            android.widget.LinearLayout.LayoutParams slp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            texts.addView(tvStatus, slp);

            android.widget.TextView remove = new android.widget.TextView(this);
            remove.setText("移除");
            remove.setTextColor(primary);
            remove.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            remove.setPadding(dp(8), dp(8), 0, dp(8));
            remove.setClickable(true);
            remove.setOnClickListener(v -> {
                batchQueue.remove(item);
                updateBatchUi();
                renderBatchItems();
                updateBatchSave();
            });
            row.addView(remove, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            batchList.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < batchQueue.size() - 1) {
                android.view.View line = new android.view.View(this);
                line.setBackgroundColor(ThemeManager.resolveColor(this, R.attr.outline));
                batchList.addView(line, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private void loadBatchCover(android.widget.ImageView iv, BatchItem item) {
        Book temp = new Book();
        temp.title = batchTitle(item);
        temp.coverUrl = item.coverUrl == null ? "" : item.coverUrl;
        if (temp.coverUrl.isEmpty()) {
            CoverLoader.showPlaceholder(iv, temp);
        } else {
            CoverLoader.load(iv, temp);
        }
    }

    private String batchTitle(BatchItem item) {
        if (item.status == BATCH_READY || item.status == BATCH_DUPLICATE) {
            return item.title.isEmpty() ? "（未命名）" : item.title;
        }
        return "ISBN " + item.isbn;
    }

    private String batchStatus(BatchItem item) {
        switch (item.status) {
            case BATCH_READY:
                return "待保存";
            case BATCH_DUPLICATE:
                return "已在书架（默认跳过）";
            case BATCH_FAILED:
                return "查询失败：" + item.message;
            default:
                return "正在查询…";
        }
    }

    private void updateBatchSave() {
        if (batchSaveBtn == null) {
            return;
        }
        int ready = 0;
        int dup = 0;
        for (BatchItem item : batchQueue) {
            if (item.status == BATCH_READY) {
                ready++;
            } else if (item.status == BATCH_DUPLICATE) {
                dup++;
            }
        }
        if (batchCopyCheck != null) {
            batchCopyCheck.setVisibility(dup > 0
                    ? android.view.View.VISIBLE : android.view.View.GONE);
            batchCopyCheck.setText("重复的 " + dup + " 本也各加一本副本");
        }
        int saveCount = ready + (batchCopyCheck != null && batchCopyCheck.isChecked() ? dup : 0);
        batchSaveBtn.setText("保存 " + saveCount + " 本");
        batchSaveBtn.setEnabled(saveCount > 0);
        batchSaveBtn.setAlpha(saveCount > 0 ? 1f : 0.4f);
    }

    private void saveBatch(android.app.Dialog dialog) {
        final java.util.List<BatchItem> ready = new java.util.ArrayList<>();
        final java.util.List<BatchItem> dups = new java.util.ArrayList<>();
        for (BatchItem item : batchQueue) {
            if (item.status == BATCH_READY) {
                ready.add(item);
            } else if (item.status == BATCH_DUPLICATE) {
                dups.add(item);
            }
        }
        final boolean addCopies = batchCopyCheck != null && batchCopyCheck.isChecked();
        if (ready.isEmpty() && (!addCopies || dups.isEmpty())) {
            Toast.makeText(this, "没有可保存的书", Toast.LENGTH_SHORT).show();
            return;
        }
        final long locationId = batchLocationId;
        final com.bai.bookshelf.app.BookshelfApp app =
                com.bai.bookshelf.app.BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            int saved = 0;
            int copied = 0;
            for (BatchItem item : ready) {
                Book book = item.book;
                if (book == null || book.title == null || book.title.trim().isEmpty()) {
                    continue;
                }
                book.locationId = locationId > 0 ? locationId : null;
                book.ownType = Book.OWN_BUY;
                book.createdAt = now;
                book.updatedAt = now;
                book.dirty = true;
                if (app.db().bookDao().insert(book) > 0) {
                    saved++;
                }
            }
            if (addCopies) {
                for (BatchItem item : dups) {
                    Book exist = app.db().bookDao().getById(item.existingId);
                    if (exist != null) {
                        exist.quantity = exist.quantity + 1;
                        exist.updatedAt = now;
                        app.db().bookDao().update(exist);
                        copied++;
                    }
                }
            }
            final int fSaved = saved;
            final int fCopied = copied;
            app.ui(() -> {
                dialog.dismiss();
                batchQueue.clear();
                updateBatchUi();
                Toast.makeText(this, "已录入 " + fSaved + " 本"
                        + (fCopied > 0 ? "，另加 " + fCopied + " 本副本" : ""),
                        Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    /** 批量队列里的一本书 */
    private static final class BatchItem {
        final String isbn;
        int status = BATCH_QUERYING;
        Book book;
        long existingId = -1;
        String title = "";
        String author = "";
        String coverUrl = "";
        String message = "";

        BatchItem(String isbn) {
            this.isbn = isbn;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        b.btnTorch.setImageResource(torchOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        b.btnTorch.setBackgroundResource(torchOn
                ? R.drawable.bg_scan_torch_on : R.drawable.bg_scan_torch);
        b.btnTorch.setContentDescription(torchOn ? "关闭闪光灯" : "打开闪光灯");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (scanner != null) {
            scanner.close();
        }
    }
}
