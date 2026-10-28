package com.bai.bookshelf.ui.lock;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityAppLockBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.AppLock;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 应用锁验证页（v4.54）：全屏遮挡，指纹 / 面容优先，手机锁屏密码兜底。
 * 忘记密码不需要单独找回——验证用的是手机本身的锁屏方式。
 */
public class AppLockActivity extends BaseActivity {

    private static final int REQ_DEVICE_CREDENTIAL = 5401;

    private ActivityAppLockBinding b;
    private boolean prompting;
    private boolean autoPrompted;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppLock.isEnabled(this)) {
            finish();
            return;
        }
        b = ActivityAppLockBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnUnlock.setOnClickListener(v -> unlockWithBestMethod());
        b.btnDisableLock.setOnClickListener(v -> confirmDisableLock());
        // 返回键/返回手势只把 App 送回后台，不能露出下面的书架
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        moveTaskToBack(true);
                    }
                });
        b.getRoot().postDelayed(() -> {
            if (!isFinishing() && !autoPrompted) {
                autoPrompted = true;
                unlockWithBestMethod();
            }
        }, 280);
    }

    private void unlockWithBestMethod() {
        if (prompting) {
            return;
        }
        if (AppLock.hasBiometric(this)) {
            showBiometricPrompt();
        } else if (AppLock.hasDeviceCredential(this)) {
            showDeviceCredential();
        } else {
            showNoAuth();
        }
    }

    private void showBiometricPrompt() {
        prompting = true;
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        prompting = false;
                        unlockAndFinish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        prompting = false;
                        if (isFinishing()) {
                            return;
                        }
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            showDeviceCredential();
                        } else if (errorCode == BiometricPrompt.ERROR_LOCKOUT
                                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                            b.tvLockHint.setText("尝试次数过多，请用手机锁屏密码解锁");
                            showDeviceCredential();
                        } else if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
                                || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT
                                || errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                                || errorCode == BiometricPrompt.ERROR_UNABLE_TO_PROCESS) {
                            b.tvLockHint.setText("指纹 / 面容暂时不可用，请用手机密码解锁");
                            showDeviceCredential();
                        } else if (errorCode != BiometricPrompt.ERROR_CANCELED) {
                            b.tvLockHint.setText("验证未完成，可以再试一次");
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        b.tvLockHint.setText("没有识别成功，请再试一次");
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁小书房")
                .setSubtitle("验证身份后继续")
                .setNegativeButtonText("使用手机密码")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setConfirmationRequired(false)
                .build();
        prompt.authenticate(info);
    }

    private void showDeviceCredential() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            showNoAuth();
            return;
        }
        Intent intent = km.createConfirmDeviceCredentialIntent(
                "解锁小书房", "输入手机锁屏密码");
        if (intent == null) {
            showNoAuth();
            return;
        }
        startActivityForResult(intent, REQ_DEVICE_CREDENTIAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DEVICE_CREDENTIAL && resultCode == RESULT_OK) {
            unlockAndFinish();
        }
    }

    private void showNoAuth() {
        b.btnUnlock.setVisibility(View.GONE);
        b.btnDisableLock.setVisibility(View.VISIBLE);
        b.tvLockSubtitle.setText("这台手机没有设置锁屏密码或指纹");
        b.tvLockHint.setText("应用锁无法验证身份，可以关闭后继续使用小书房");
    }

    private void confirmDisableLock() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("关闭应用锁")
                .setMessage("关闭后，进入小书房将不再需要指纹或密码。")
                .setPositiveButton("关闭", (d, w) -> {
                    AppLock.setEnabled(this, false);
                    unlockAndFinish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void unlockAndFinish() {
        AppLock.markUnlocked(this);
        setResult(RESULT_OK);
        finish();
        overridePendingTransition(0, 0);
    }
}
