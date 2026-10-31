package com.bai.bookshelf.ui.scan;

import android.Manifest;
import androidx.appcompat.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
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
import com.bai.bookshelf.databinding.ActivityScanBinding;
import com.bai.bookshelf.util.InsetsHelper;
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
    /** v5.8.18：盘点只数这个地点的书（从书架二维码进来） */
    public static final String EXTRA_LOCATION_ID = "inventory_location_id";
    /** 盘点模式（v5.6 抽到 ScanInventoryMode） */
    private ScanInventoryMode inventory;
    /** 批量录入（v5.6 抽到 ScanBatchController） */
    private ScanBatchController batch;

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
        batch = new ScanBatchController(this, b, this::resetHandling);
        b.btnBatch.setOnClickListener(v -> batch.toggleMode());
        b.btnBatchQueue.setOnClickListener(v -> batch.showSheet());

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        // v5.8.18：地点二维码（bookshelf://shelf?location=…）
                        Barcode.FORMAT_QR_CODE)
                .build());

        inventory = new ScanInventoryMode(this, b, this::resetHandling);
        inventory.setup();
        batch.updateUi();

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
        // v5.8.18：扫到书架二维码 → 跳书架并按该地点筛选
        long locationId = com.bai.bookshelf.util.LocationQr.parse(raw);
        if (locationId > 0) {
            flashScanOk();
            Intent open = new Intent(this, com.bai.bookshelf.ui.MainActivity.class);
            open.putExtra(com.bai.bookshelf.ui.MainActivity.EXTRA_LOCATION_ID, locationId);
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            finish();
            return true;
        }
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
        if (inventory.isActive()) {
            inventory.handleScan(isbn);
        } else if (batch.isActive()) {
            batch.add(isbn);
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
                    if (inventory.isActive()) {
                        inventory.handleScan(isbn);
                    } else if (batch.isActive()) {
                        batch.add(isbn);
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

    static String normalizeIsbn(String isbn) {
        return isbn == null ? "" : isbn.replaceAll("[^0-9Xx]", "")
                .toUpperCase(java.util.Locale.ROOT);
    }    /** 恢复扫码待机状态（重复提示关闭后调用） */
    private void resetHandling() {
        handling = false;
        if (b != null) {
            b.progress.setVisibility(android.view.View.GONE);
        }
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
