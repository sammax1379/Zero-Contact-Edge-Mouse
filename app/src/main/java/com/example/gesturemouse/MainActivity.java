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
import android.util.Log;
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

    // 📡 宣告我們的藍牙發射塔
    private BluetoothMouseHelper bluetoothMouseHelper;

    private float prevX = 0f, prevY = 0f;
    private final float alpha = 0.4f;
    private final float pinchThreshold = 0.05f;

    // ⚙️ 游標靈敏度 (把 0~1 的小數比例，放大成滑鼠看得懂的像素移動量)
    private final int SENSITIVITY = 2500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);
        cameraExecutor = Executors.newSingleThreadExecutor();

        setupHandLandmarker();

        // 檢查並要求所有權限 (包含相機與藍牙)
        if (checkPermissions()) {
            initSystem();
        } else {
            requestPermissions();
        }
    }

    // ==========================================
    // 權限大總管：處理 Android 12+ (S20 FE) 嚴格的藍牙權限
    // ==========================================
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
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && checkPermissions()) {
            initSystem();
        } else {
            Toast.makeText(this, "必須允許所有權限才能啟動滑鼠功能！", Toast.LENGTH_LONG).show();
            statusText.setText("權限不足，系統停擺");
        }
    }

    // ==========================================
    // 系統初始化：開鏡頭 + 開藍牙
    // ==========================================
    private void initSystem() {
        startCamera();

        // 🌟 破解隱形魔法：強制讓手機對外廣播 120 秒
        android.content.Intent discoverableIntent = new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        startActivity(discoverableIntent);

        // 實體化藍牙發射塔
        bluetoothMouseHelper = new BluetoothMouseHelper(this);
        runOnUiThread(() -> statusText.setText("系統啟動，等待電腦藍牙配對..."));
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
        } catch (Exception e) {
            Log.e("MediaPipe", "AI 模型載入失敗", e);
        }
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

            } catch (Exception e) {
                Log.e("CameraX", "相機啟動失敗", e);
            }
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

            if (prevX == 0f && prevY == 0f) {
                prevX = ix; prevY = iy;
            }
            float smoothX = alpha * ix + (1 - alpha) * prevX;
            float smoothY = alpha * iy + (1 - alpha) * prevY;

            float dx = smoothX - prevX;
            float dy = smoothY - prevY;
            prevX = smoothX; prevY = smoothY;

            double distance = Math.hypot(smoothX - tx, smoothY - ty);
            boolean isClicked = distance < pinchThreshold;

            // 🎯 將小數比例放大轉換為滑鼠的真實像素位移 (螢幕X軸與鏡頭是左右相反的，所以 dx 加負號)
            int mouseDx = (int) (-dx * SENSITIVITY);
            int mouseDy = (int) (dy * SENSITIVITY);

            // 📡 呼叫發射塔！將算好的數值打給電腦
            if (bluetoothMouseHelper != null && (Math.abs(mouseDx) > 0 || Math.abs(mouseDy) > 0 || isClicked)) {
                bluetoothMouseHelper.sendMouseEvent(mouseDx, mouseDy, isClicked);
            }

            runOnUiThread(() -> {
                if (isClicked) {
                    statusText.setText("🟢 左鍵點擊發送中！");
                    statusText.setTextColor(0xFF00FF00);
                } else {
                    statusText.setText(String.format("📡 游標移動發送中: X:%d Y:%d", mouseDx, mouseDy));
                    statusText.setTextColor(0xFFFFFFFF);
                }
            });
        } else {
            prevX = 0f; prevY = 0f;
        }

        imageProxy.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (handLandmarker != null) {
            handLandmarker.close();
        }
    }
}