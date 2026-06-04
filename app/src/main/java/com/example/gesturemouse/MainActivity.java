package com.example.gesturemouse;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_CODE = 100;
    private PreviewView viewFinder;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);

        // 檢查是否有相機權限
        if (checkCameraPermission()) {
            startCamera(); // 有權限，直接開鏡頭
        } else {
            requestCameraPermission(); // 沒權限，跳出視窗要權限
        }
    }

    // ==========================================
    // 權限處理邏輯
    // ==========================================
    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera(); // 拿到權限了，開鏡頭！
            } else {
                Toast.makeText(this, "需要相機權限才能運作手勢滑鼠喔！", Toast.LENGTH_LONG).show();
                statusText.setText("相機權限被拒絕");
                statusText.setTextColor(0xFFFF0000); // 紅字
            }
        }
    }

    // ==========================================
    // CameraX 鏡頭啟動邏輯
    // ==========================================
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // 獲取相機的控制權
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 設定預覽畫面
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // 🎯 強制選擇「前置鏡頭」 (因為是體感滑鼠)
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                // 綁定生命週期 (這行讓 CameraX 自動幫你管好開啟/關閉)
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);

                statusText.setText("鏡頭啟動成功 (Week 2 完成)");

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "啟動相機失敗", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
}