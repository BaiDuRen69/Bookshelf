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
import com.bai.bookshelf.databinding.ActivityScanBinding;
import com.bai.bookshelf.databinding.DialogInputBinding;
import com.bai.bookshelf.ui.AddBookHelper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
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

    /** 相机权限申请结果 */
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "没有相机权限，无法扫码", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    /** 从相册选图识别条码 */
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    scanFromImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        scanner = BarcodeScanning.getClient();

        b.btnBack.setOnClickListener(v -> finish());
        b.btnManual.setOnClickListener(v -> showManualInput());
        b.btnTorch.setOnClickListener(v -> toggleTorch());
        b.btnImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        // 相机权限：有则直接开，没有则申请
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
        // 从编辑页返回：恢复扫描（连扫模式）
        handling = false;
        b.progress.setVisibility(android.view.View.GONE);
    }

    /** 初始化 CameraX：预览 + 图像分析 */
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
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                Toast.makeText(this, "相机启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** 逐帧分析：识别条形码中的 ISBN */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(androidx.camera.core.ImageProxy imageProxy) {
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        InputImage inputImage = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!handling) {
                        for (Barcode barcode : barcodes) {
                            String isbn = extractIsbn(barcode.getRawValue());
                            if (isbn != null) {
                                onIsbnDetected(isbn);
                                break;
                            }
                        }
                    }
                })
                .addOnCompleteListener(t -> imageProxy.close());
    }

    /**
     * 从条码内容提取 ISBN：
     * 书号条形码是 978/979 开头的 EAN-13；也兼容 10 位老 ISBN。
     */
    private String extractIsbn(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9Xx]", "");
        if (digits.length() == 13 && (digits.startsWith("978") || digits.startsWith("979"))) {
            return digits;
        }
        if (digits.length() == 10) {
            return digits;
        }
        return null;
    }

    private void onIsbnDetected(String isbn) {
        handling = true; // 阻断后续帧，直到流程结束
        runOnUiThread(() -> {
            b.progress.setVisibility(android.view.View.VISIBLE);
            // 命中重复时不会跳编辑页（onResume 不会触发），靠回调恢复扫描状态
            AddBookHelper.byIsbn(this, isbn, this::resetHandling);
            // 编辑页打开后 onPause；返回时 onResume 里恢复 handling=false
        });
    }

    /** 恢复扫码待机状态（重复提示关闭后调用） */
    private void resetHandling() {
        handling = false;
        b.progress.setVisibility(android.view.View.GONE);
    }

    /** 从本地图片识别条形码（ML Kit 同样支持静态图片输入） */
    private void scanFromImage(android.net.Uri uri) {
        handling = true;
        b.progress.setVisibility(android.view.View.VISIBLE);
        cameraExecutor.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = decodeBitmap(uri);
                InputImage image = InputImage.fromBitmap(bitmap, 0);
                scanner.process(image)
                        .addOnSuccessListener(barcodes -> {
                            String isbn = null;
                            for (Barcode barcode : barcodes) {
                                isbn = extractIsbn(barcode.getRawValue());
                                if (isbn != null) {
                                    break;
                                }
                            }
                            if (isbn != null) {
                                onIsbnDetected(isbn);
                            } else {
                                handling = false;
                                runOnUiThread(() -> {
                                    b.progress.setVisibility(android.view.View.GONE);
                                    Toast.makeText(this, "图片里没有识别到条形码，换一张试试", Toast.LENGTH_LONG).show();
                                });
                            }
                        })
                        .addOnFailureListener(e -> {
                            handling = false;
                            runOnUiThread(() -> {
                                b.progress.setVisibility(android.view.View.GONE);
                                Toast.makeText(this, "识别失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        });
            } catch (Exception e) {
                handling = false;
                runOnUiThread(() -> {
                    b.progress.setVisibility(android.view.View.GONE);
                    Toast.makeText(this, "图片读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 解码 Uri 为 Bitmap（ML Kit 需要软件位图） */
    private android.graphics.Bitmap decodeBitmap(android.net.Uri uri) throws java.io.IOException {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) -> decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE));
        }
        // 低版本回退（已废弃但可用）
        @SuppressWarnings("deprecation")
        android.graphics.Bitmap bmp = android.provider.MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
        return bmp;
    }

    /** 手动输入 ISBN 兜底 */
    private void showManualInput() {
        DialogInputBinding db = DialogInputBinding.inflate(getLayoutInflater());
        EditText edit = db.edit;
        db.inputLayout.setHint("输入 10 或 13 位 ISBN");
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(this)
                .setTitle("手动输入 ISBN")
                .setView(db.getRoot())
                .setPositiveButton("查询", (d, w) -> {
                    String isbn = edit.getText().toString().trim();
                    if (isbn.length() >= 10) {
                        handling = true;
                        // 命中重复时靠回调恢复，避免加载状态卡死
                        AddBookHelper.byIsbn(this, isbn, this::resetHandling);
                    } else {
                        Toast.makeText(this, "ISBN 位数不对", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        b.btnTorch.setText(torchOn ? "关闭闪光" : "闪光灯");
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
