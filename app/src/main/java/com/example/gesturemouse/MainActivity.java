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

    private static final int CAMERA_PERMISSION_CODE = 100;
    private PreviewView viewFinder;
    private TextView statusText;
    private ExecutorService cameraExecutor;
    private HandLandmarker handLandmarker;

    // ==========================================
    // 🧠 系統全域變數 (與 Python 版邏輯相同)
    // ==========================================
    private float prevX = 0f, prevY = 0f;
    private final float alpha = 0.4f; // 低通濾波器係數
    private final float pinchThreshold = 0.05f; // 捏合閾值 (注意：手機端取得的座標是 0.0~1.0 的比例值)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);
        cameraExecutor = Executors.newSingleThreadExecutor();

        setupHandLandmarker(); // 初始化 AI 大腦

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    // 初始化 MediaPipe 手勢辨識器
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

                // 1. 預覽畫面設定
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 2. 影像分析設定 (把每一幀交給 AI)
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // 直接輸出 RGBA 方便轉換
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

    // ==========================================
    // 🧠 核心邏輯：處理每一張圖片與手勢數學
    // ==========================================
    private void processImageProxy(ImageProxy imageProxy) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        // 將 CameraX 畫面轉成 MediaPipe 看得懂的格式
        Bitmap bitmap = imageProxy.toBitmap();
        MPImage mpImage = new BitmapImageBuilder(bitmap).build();

        // 進行手勢辨識
        HandLandmarkerResult result = handLandmarker.detect(mpImage);

        // 如果有抓到手
        if (result.landmarks().size() > 0) {
            // 🎯 抓取 食指指尖 (8) 與 大拇指指尖 (4)
            float ix = result.landmarks().get(0).get(8).x();
            float iy = result.landmarks().get(0).get(8).y();
            float tx = result.landmarks().get(0).get(4).x();
            float ty = result.landmarks().get(0).get(4).y();

            // 數學魔法 1：EMA 低通濾波器
            if (prevX == 0f && prevY == 0f) {
                prevX = ix; prevY = iy;
            }
            float smoothX = alpha * ix + (1 - alpha) * prevX;
            float smoothY = alpha * iy + (1 - alpha) * prevY;

            // 計算相對位移 (這裡算出的是比例，未來發送藍牙前會乘上螢幕解析度)
            float dx = smoothX - prevX;
            float dy = smoothY - prevY;
            prevX = smoothX; prevY = smoothY;

            // 數學魔法 2：歐式距離判定點擊
            double distance = Math.hypot(smoothX - tx, smoothY - ty);
            boolean isClicked = distance < pinchThreshold;

            // 將結果顯示在螢幕最上方的文字中
            runOnUiThread(() -> {
                if (isClicked) {
                    statusText.setText("🟢 [點擊] 左鍵觸發！");
                    statusText.setTextColor(0xFF00FF00); // 綠色
                } else {
                    statusText.setText(String.format("➡️ [移動] dx: %.3f, dy: %.3f", dx, dy));
                    statusText.setTextColor(0xFFFFFFFF); // 白色
                }
            });
        } else {
            // 手離開畫面，重置歷史座標
            prevX = 0f; prevY = 0f;
            runOnUiThread(() -> {
                statusText.setText("尋找手勢中...");
                statusText.setTextColor(0xFFFFFF00); // 黃色
            });
        }

        imageProxy.close(); // ⚠️ 必須關閉，否則相機會卡死
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "需要相機權限！", Toast.LENGTH_LONG).show();
        }
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