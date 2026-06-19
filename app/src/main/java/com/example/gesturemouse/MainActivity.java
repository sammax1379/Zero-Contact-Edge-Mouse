package com.example.gesturemouse;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 100;
    private PreviewView viewFinder;
    private TextView statusText;
    private ExecutorService cameraExecutor;
    private HandLandmarker handLandmarker;
    private BluetoothMouseHelper bluetoothMouseHelper;

    private boolean isFirstLaunch = true;
    private boolean isSystemPopupShowing = false;
    private boolean isDebuggingConnection = true;

    private float prevX = 0f, prevY = 0f;
    private final float alpha = 0.25f;
    private final float pinchThreshold = 0.05f;
    private final int SENSITIVITY = 4500;
    private final int DEADZONE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);
        statusText.setTextSize(14f);

        cameraExecutor = Executors.newSingleThreadExecutor();
        setupHandLandmarker();

        if (checkPermissions()) {
            initSystem();
        } else {
            requestPermissions();
        }
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
        } else {
            return new String[]{Manifest.permission.CAMERA};
        }
    }

    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        isSystemPopupShowing = true;
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && checkPermissions()) {
            initSystem();
        } else {
            Toast.makeText(this, "權限不足", Toast.LENGTH_LONG).show();
        }
    }

    private void initSystem() {
        startCamera();
        isSystemPopupShowing = true;
        android.content.Intent discoverableIntent = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        startActivity(discoverableIntent);

        statusText.setText("=== 藍牙探針初始化 ===");
        setupBluetoothHelper();
    }

    private void setupBluetoothHelper() {
        bluetoothMouseHelper = new BluetoothMouseHelper(this, (stepMsg, isFinished) -> runOnUiThread(() -> {
            String currentText = statusText.getText().toString();
            if (currentText.split("\n").length > 8) {
                currentText = "=== 探針紀錄 ===";
            }
            statusText.setText(currentText + "\n" + stepMsg);

            if (isFinished) {
                new android.os.Handler().postDelayed(() -> {
                    isDebuggingConnection = false;
                    statusText.setTextSize(20f);
                }, 2000);
            }
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    // 🌟 終極自動修復機制：偵測到死亡，原地自動冷開機！
    @Override
    protected void onResume() {
        super.onResume();
        if (isFirstLaunch || isSystemPopupShowing) {
            isFirstLaunch = false;
            isSystemPopupShowing = false;
            return;
        }

        isDebuggingConnection = true;
        statusText.setTextSize(14f);
        statusText.setText("=== App 返回前景 ===");

        startCamera();

        if (bluetoothMouseHelper != null && bluetoothMouseHelper.isReady()) {
            // 如果管線幸運存活，直接盤點接軌
            statusText.setText(statusText.getText() + "\n⚡ 服務存活，執行狀態盤點...");
            bluetoothMouseHelper.checkAndReconnect();
        } else {
            // 如果管線被 Android 省電機制殺死了，啟動完美冷開機流程！
            statusText.setText(statusText.getText() + "\n🔴 偵測到背景服務遭系統沒收，自動重啟全新連線...");
            if (bluetoothMouseHelper != null) {
                bluetoothMouseHelper.closeConnection();
            }
            setupBluetoothHelper();
        }
    }

    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.7f)
                    .setMinTrackingConfidence(0.7f)
                    .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                    .build();
            handLandmarker = HandLandmarker.createFromOptions(this, options);
        } catch (Exception e) {}
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {}
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        Bitmap bitmap = imageProxy.toBitmap();
        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        HandLandmarkerResult result = handLandmarker.detect(mpImage);

        if (result.landmarks().size() > 0) {
            float ix = result.landmarks().get(0).get(8).x();
            float iy = result.landmarks().get(0).get(8).y();
            float tx = result.landmarks().get(0).get(4).x();
            float ty = result.landmarks().get(0).get(4).y();

            float moveX = result.landmarks().get(0).get(9).x();
            float moveY = result.landmarks().get(0).get(9).y();

            if (prevX == 0f && prevY == 0f) {
                prevX = moveX; prevY = moveY;
            }
            float smoothX = alpha * moveX + (1 - alpha) * prevX;
            float smoothY = alpha * moveY + (1 - alpha) * prevY;

            float dx = smoothX - prevX;
            float dy = smoothY - prevY;
            prevX = smoothX; prevY = smoothY;

            double distance = Math.hypot(ix - tx, iy - ty);
            boolean isClicked = distance < pinchThreshold;

            int mouseDx = (int) (-dx * SENSITIVITY);
            int mouseDy = (int) (dy * SENSITIVITY);

            if (Math.abs(mouseDx) < DEADZONE) mouseDx = 0;
            if (Math.abs(mouseDy) < DEADZONE) mouseDy = 0;

            if (bluetoothMouseHelper != null && (mouseDx != 0 || mouseDy != 0 || isClicked)) {
                bluetoothMouseHelper.sendMouseEvent(mouseDx, mouseDy, isClicked);
            }

            final int displayDx = mouseDx;
            final int displayDy = mouseDy;

            if (!isDebuggingConnection) {
                runOnUiThread(() -> {
                    if (isClicked) {
                        statusText.setText("🟢 左鍵點擊發送中！");
                        statusText.setTextColor(0xFF00FF00);
                    } else {
                        statusText.setText(String.format("📡 游標移動: X:%d Y:%d", displayDx, displayDy));
                        statusText.setTextColor(0xFFFFFFFF);
                    }
                });
            }
        } else {
            prevX = 0f; prevY = 0f;
        }
        imageProxy.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (handLandmarker != null) handLandmarker.close();
        if (bluetoothMouseHelper != null) bluetoothMouseHelper.closeConnection();
    }
}