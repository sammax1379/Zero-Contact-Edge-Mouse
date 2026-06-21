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
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 100;
    private PreviewView viewFinder;
    private TextView statusText;
    private Switch switchAr;
    private ExecutorService cameraExecutor;
    private HandLandmarker handLandmarker;
    private BluetoothMouseHelper bluetoothMouseHelper;

    private OverlayView overlayView;

    private boolean isFirstLaunch = true;
    private boolean isSystemPopupShowing = false;
    private boolean isDebuggingConnection = true;

    // 右手滑鼠參數
    private float prevX = 0f, prevY = 0f;
    private final float BASE_ALPHA = 0.20f;
    private final int SENSITIVITY = 5200;
    private final int DEADZONE = 1;
    private final float PINCH_THRESHOLD = 0.22f;
    private final float SIDEWAYS_THRESHOLD = 0.40f;

    private float scrollAnchorX = -1f;
    private float scrollAnchorY = -1f;
    private float wheelAccumulator = 0f;
    private boolean isScrollMode = false;
    private boolean wasRightFist = false;
    private boolean wasLeftClicked = false;
    private boolean wasRightClicked = false;
    private long movementLockoutEndTime = 0;
    private final int MOVEMENT_LOCKOUT_MS = 250;

    // 左手搖桿參數
    private float leftAnchorX = -1f;
    private float leftAnchorY = -1f;
    private final float JOYSTICK_THRESHOLD = 0.09f;
    private boolean wasKbActive = false;

    private boolean isWasdEnabled = false;
    private boolean wasLeftOk = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        statusText = findViewById(R.id.statusText);
        switchAr = findViewById(R.id.switchAr);

        // 🌟 將 AR 畫布塞進我們剛剛在 XML 建立的專屬容器裡，保證不會蓋住按鈕
        FrameLayout overlayContainer = findViewById(R.id.overlayContainer);
        overlayView = new OverlayView(this);
        overlayContainer.addView(overlayView);

        // 🌟 綁定 AR 開關邏輯
        switchAr.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) overlayView.setVisibility(View.VISIBLE);
            else overlayView.setVisibility(View.INVISIBLE);
        });

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
            if (currentText.split("\n").length > 8) currentText = "=== 探針紀錄 ===";
            statusText.setText(currentText + "\n" + stepMsg);
            if (isFinished) {
                new android.os.Handler().postDelayed(() -> {
                    isDebuggingConnection = false;
                    statusText.setTextSize(16f); // 字體微調搭配新 UI
                }, 2000);
            }
        }));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isSystemPopupShowing) return;
        if (bluetoothMouseHelper != null) {
            bluetoothMouseHelper.closeConnection();
            bluetoothMouseHelper = null;
        }
    }

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
        statusText.setText("=== 重新連線中 ===");

        startCamera();

        if (bluetoothMouseHelper != null) {
            bluetoothMouseHelper.closeConnection();
        }
        setupBluetoothHelper();
    }

    private void setupHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.65f)
                    .setMinTrackingConfidence(0.65f)
                    .setNumHands(2)
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
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
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

        int rightHandIdx = -1;
        int leftHandIdx = -1;

        if (result.landmarks().size() > 0) {
            for (int i = 0; i < result.landmarks().size(); i++) {
                String side = result.handednesses().get(i).get(0).categoryName();
                if (side.equals("Right")) rightHandIdx = i;
                else if (side.equals("Left")) leftHandIdx = i;
            }

            byte modifier = 0;
            ArrayList<Byte> activeKeys = new ArrayList<>();

            // ==========================================
            // 🐭 右手：滑鼠控制核心
            // ==========================================
            int mouseDx = 0, mouseDy = 0, scrollWheel = 0;
            boolean isLeftClicked = false, isRightClicked = false;
            float rMoveX = -1f, rMoveY = -1f;

            if (rightHandIdx != -1) {
                float tx = result.landmarks().get(rightHandIdx).get(4).x();
                float ty = result.landmarks().get(rightHandIdx).get(4).y();
                float ix = result.landmarks().get(rightHandIdx).get(8).x();
                float iy = result.landmarks().get(rightHandIdx).get(8).y();
                float mx = result.landmarks().get(rightHandIdx).get(12).x();
                float my = result.landmarks().get(rightHandIdx).get(12).y();

                float wristX = result.landmarks().get(rightHandIdx).get(0).x();
                float wristY = result.landmarks().get(rightHandIdx).get(0).y();
                rMoveX = result.landmarks().get(rightHandIdx).get(9).x();
                rMoveY = result.landmarks().get(rightHandIdx).get(9).y();
                float palmHeight = (float) Math.hypot(rMoveX - wristX, rMoveY - wristY);
                if (palmHeight < 0.01f) palmHeight = 0.1f;

                float palmBaseIX = result.landmarks().get(rightHandIdx).get(5).x();
                float palmBaseIY = result.landmarks().get(rightHandIdx).get(5).y();
                float palmBasePX = result.landmarks().get(rightHandIdx).get(17).x();
                float palmBasePY = result.landmarks().get(rightHandIdx).get(17).y();
                float palmWidth = (float) Math.hypot(palmBaseIX - palmBasePX, palmBaseIY - palmBasePY);

                boolean isHandSideways = (palmWidth / palmHeight) < SIDEWAYS_THRESHOLD;
                boolean rRingDown = result.landmarks().get(rightHandIdx).get(16).y() > result.landmarks().get(rightHandIdx).get(14).y();
                boolean rPinkyDown = result.landmarks().get(rightHandIdx).get(20).y() > result.landmarks().get(rightHandIdx).get(18).y();
                boolean rIndexDown = result.landmarks().get(rightHandIdx).get(8).y() > result.landmarks().get(rightHandIdx).get(6).y();
                boolean rMiddleDown = result.landmarks().get(rightHandIdx).get(12).y() > result.landmarks().get(rightHandIdx).get(10).y();
                boolean isFist = rIndexDown && rMiddleDown && rRingDown && rPinkyDown;

                if (!isHandSideways && !rRingDown && !rPinkyDown && !isScrollMode) {
                    isLeftClicked = (Math.hypot(ix - tx, iy - ty) / palmHeight) < PINCH_THRESHOLD;
                    isRightClicked = (Math.hypot(mx - tx, my - ty) / palmHeight) < PINCH_THRESHOLD;
                }

                if (isLeftClicked && !wasLeftClicked) movementLockoutEndTime = System.currentTimeMillis() + MOVEMENT_LOCKOUT_MS;
                if (isRightClicked && !wasRightClicked) movementLockoutEndTime = System.currentTimeMillis() + MOVEMENT_LOCKOUT_MS;
                wasLeftClicked = isLeftClicked; wasRightClicked = isRightClicked;
                boolean isBraking = System.currentTimeMillis() < movementLockoutEndTime;

                if (isFist && !wasRightFist) {
                    isScrollMode = !isScrollMode;
                    scrollAnchorX = -1f; scrollAnchorY = -1f; wheelAccumulator = 0f;
                }
                wasRightFist = isFist;

                if (prevX == 0f && prevY == 0f) { prevX = rMoveX; prevY = rMoveY; }
                float rawVelocity = (float) Math.hypot(rMoveX - prevX, rMoveY - prevY);
                float dynamicAlpha = BASE_ALPHA;
                if (rawVelocity < 0.002f) dynamicAlpha = 0.08f;
                else if (rawVelocity > 0.015f) dynamicAlpha = 0.45f;

                float smoothX = dynamicAlpha * rMoveX + (1 - dynamicAlpha) * prevX;
                float smoothY = dynamicAlpha * rMoveY + (1 - dynamicAlpha) * prevY;
                float dx = smoothX - prevX; float dy = smoothY - prevY;
                prevX = smoothX; prevY = smoothY;

                if (isScrollMode && !isFist) {
                    if (scrollAnchorY == -1f) {
                        scrollAnchorX = rMoveX;
                        scrollAnchorY = rMoveY;
                    }
                    float offset = rMoveY - scrollAnchorY;
                    if (Math.abs(offset) > 0.03f) {
                        wheelAccumulator += offset;
                        if (wheelAccumulator > 0.3f) { scrollWheel = -1; wheelAccumulator = 0f; }
                        else if (wheelAccumulator < -0.3f) { scrollWheel = 1;  wheelAccumulator = 0f; }
                    }
                } else if (!isScrollMode && !isFist) {
                    if (isBraking) { mouseDx = 0; mouseDy = 0; }
                    else {
                        mouseDx = (int) (-dx * SENSITIVITY); mouseDy = (int) (dy * SENSITIVITY);
                        if (Math.abs(mouseDx) < DEADZONE) mouseDx = 0;
                        if (Math.abs(mouseDy) < DEADZONE) mouseDy = 0;
                    }
                }
                if (bluetoothMouseHelper != null) {
                    bluetoothMouseHelper.sendMouseEvent(mouseDx, mouseDy, scrollWheel, isLeftClicked, isRightClicked);
                }
            } else {
                prevX = 0f; prevY = 0f;
            }

            // ==========================================
            // ⌨️ 左手：WASD 控制器與 OK 開關
            // ==========================================
            boolean isLeftFist = false;
            boolean isLeftOk = false;
            float lMoveX = -1f, lMoveY = -1f;

            if (leftHandIdx != -1) {
                float lTx = result.landmarks().get(leftHandIdx).get(4).x();
                float lTy = result.landmarks().get(leftHandIdx).get(4).y();
                float lIx = result.landmarks().get(leftHandIdx).get(8).x();
                float lIy = result.landmarks().get(leftHandIdx).get(8).y();

                lMoveX = result.landmarks().get(leftHandIdx).get(9).x();
                lMoveY = result.landmarks().get(leftHandIdx).get(9).y();

                boolean lIndexDown = result.landmarks().get(leftHandIdx).get(8).y() > result.landmarks().get(leftHandIdx).get(6).y();
                boolean lMiddleDown = result.landmarks().get(leftHandIdx).get(12).y() > result.landmarks().get(leftHandIdx).get(10).y();
                boolean lRingDown = result.landmarks().get(leftHandIdx).get(16).y() > result.landmarks().get(leftHandIdx).get(14).y();
                boolean lPinkyDown = result.landmarks().get(leftHandIdx).get(20).y() > result.landmarks().get(leftHandIdx).get(18).y();

                isLeftFist = lIndexDown && lMiddleDown && lRingDown && lPinkyDown;

                boolean lPinch = Math.hypot(lIx - lTx, lIy - lTy) < 0.05f;
                isLeftOk = lPinch && !lMiddleDown && !lRingDown && !lPinkyDown;

                if (isLeftOk && !wasLeftOk) {
                    isWasdEnabled = !isWasdEnabled;
                    if (isWasdEnabled) {
                        leftAnchorX = lMoveX;
                        leftAnchorY = lMoveY;
                    } else {
                        leftAnchorX = -1f;
                        leftAnchorY = -1f;
                    }
                }
                wasLeftOk = isLeftOk;

                if (isWasdEnabled && !isLeftOk) {
                    if (leftAnchorX == -1f) { leftAnchorX = lMoveX; leftAnchorY = lMoveY; }

                    float leftDx = lMoveX - leftAnchorX;
                    float leftDy = lMoveY - leftAnchorY;

                    if (leftDy < -JOYSTICK_THRESHOLD) activeKeys.add((byte) 0x1A); // W
                    if (leftDy > JOYSTICK_THRESHOLD)  activeKeys.add((byte) 0x16); // S
                    if (leftDx > JOYSTICK_THRESHOLD)  activeKeys.add((byte) 0x04); // A
                    if (leftDx < -JOYSTICK_THRESHOLD) activeKeys.add((byte) 0x07); // D

                    if (isLeftFist) activeKeys.add((byte) 0x2C); // Space
                }
            } else {
                wasLeftOk = false;
            }

            if (bluetoothMouseHelper != null) {
                if (activeKeys.size() > 0 || modifier != 0) {
                    byte[] rawKeys = new byte[activeKeys.size()];
                    for (int k = 0; k < activeKeys.size(); k++) rawKeys[k] = activeKeys.get(k);
                    bluetoothMouseHelper.sendKeyboardEvent(modifier, rawKeys);
                    wasKbActive = true;
                } else if (wasKbActive) {
                    bluetoothMouseHelper.sendKeyboardEvent((byte) 0, new byte[0]);
                    wasKbActive = false;
                }
            }

            overlayView.updateRightHand(rMoveX, rMoveY, scrollAnchorX, scrollAnchorY, isScrollMode, isLeftClicked || isRightClicked);
            overlayView.updateLeftHand(lMoveX, lMoveY, leftAnchorX, leftAnchorY, isWasdEnabled);

            final int finalKeySize = activeKeys.size();
            final boolean finalHasLeftHand = (leftHandIdx != -1);
            final boolean finalLeftFistUI = isLeftFist;
            final boolean finalLeftOkUI = isLeftOk;
            final boolean finalWasdState = isWasdEnabled;

            if (!isDebuggingConnection) {
                runOnUiThread(() -> {
                    if (finalLeftOkUI) {
                        statusText.setText("👌 搖桿狀態切換中...");
                        statusText.setTextColor(0xFFFFA500);
                    } else if (finalHasLeftHand && !finalWasdState) {
                        statusText.setText("🛑 左手搖桿已休眠 (比 OK 重新開啟)");
                        statusText.setTextColor(0xFF888888);
                    } else if (finalHasLeftHand && finalLeftFistUI && finalWasdState) {
                        if (finalKeySize > 1) {
                            statusText.setText("🕹️ 一邊移動一邊跳躍！(WASD + Space)");
                            statusText.setTextColor(0xFFFFD700);
                        } else {
                            statusText.setText("⌨️ 左手握拳：發送 [Spacebar 空白鍵]");
                            statusText.setTextColor(0xFFFFD700);
                        }
                    } else if (finalKeySize > 0 && finalHasLeftHand) {
                        statusText.setText("🕹️ 左手搖桿觸發中：WASD 連續發射...");
                        statusText.setTextColor(0xFF33FF99);
                    } else {
                        statusText.setText("📡 系統全能運作中 (橫向 AR 模式)");
                        statusText.setTextColor(0xFFFFFFFF);
                    }
                });
            }
        } else {
            prevX = 0f; prevY = 0f;
            leftAnchorX = -1f; leftAnchorY = -1f;
            wasLeftOk = false;
            if (bluetoothMouseHelper != null && wasKbActive) {
                bluetoothMouseHelper.sendKeyboardEvent((byte) 0, new byte[0]);
                wasKbActive = false;
            }
            overlayView.updateRightHand(-1, -1, -1, -1, false, false);
            overlayView.updateLeftHand(-1, -1, -1, -1, false);
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

    // ==========================================
    // 🎨 自定義 AR 疊加層 (雷達畫布)
    // ==========================================
    private class OverlayView extends View {
        private Paint rightPaint, rightPinPaint, rightLinePaint;
        private Paint leftPaint, leftPinPaint, leftLinePaint;

        private float rx = -1, ry = -1, rax = -1, ray = -1;
        private boolean rScroll = false, rClick = false;

        private float lx = -1, ly = -1, lax = -1, lay = -1;
        private boolean lWasd = false;

        public OverlayView(Context context) {
            super(context);
            rightPaint = new Paint(); rightPaint.setStyle(Paint.Style.FILL);
            rightPinPaint = new Paint(); rightPinPaint.setStyle(Paint.Style.STROKE); rightPinPaint.setStrokeWidth(8f);
            rightLinePaint = new Paint(); rightLinePaint.setStyle(Paint.Style.STROKE); rightLinePaint.setStrokeWidth(5f);

            leftPaint = new Paint(); leftPaint.setStyle(Paint.Style.FILL);
            leftPinPaint = new Paint(); leftPinPaint.setStyle(Paint.Style.STROKE); leftPinPaint.setStrokeWidth(8f);
            leftLinePaint = new Paint(); leftLinePaint.setStyle(Paint.Style.STROKE); leftLinePaint.setStrokeWidth(5f);
        }

        public void updateRightHand(float x, float y, float ax, float ay, boolean scroll, boolean click) {
            rx = x; ry = y; rax = ax; ray = ay; rScroll = scroll; rClick = click;
            postInvalidate();
        }

        public void updateLeftHand(float x, float y, float ax, float ay, boolean wasd) {
            lx = x; ly = y; lax = ax; lay = ay; lWasd = wasd;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();

            if (lx != -1 && ly != -1) {
                float cx = (1 - lx) * width;
                float cy = ly * height;

                if (lWasd) {
                    leftPaint.setColor(0xFF33FF99);
                    if (lax != -1 && lay != -1) {
                        float cax = (1 - lax) * width;
                        float cay = lay * height;
                        leftLinePaint.setColor(0x8833FF99);
                        canvas.drawLine(cax, cay, cx, cy, leftLinePaint);
                        leftPinPaint.setColor(0xFF33FF99);
                        canvas.drawCircle(cax, cay, 40f, leftPinPaint);
                    }
                } else {
                    leftPaint.setColor(0x88888888);
                }
                canvas.drawCircle(cx, cy, 25f, leftPaint);
            }

            if (rx != -1 && ry != -1) {
                float cx = (1 - rx) * width;
                float cy = ry * height;

                if (rScroll) {
                    rightPaint.setColor(0xFF00FFFF);
                    if (rax != -1 && ray != -1) {
                        float cax = (1 - rax) * width;
                        float cay = ray * height;
                        rightLinePaint.setColor(0x8800FFFF);
                        canvas.drawLine(cax, cay, cx, cy, rightLinePaint);
                        rightPinPaint.setColor(0xFF00FFFF);
                        canvas.drawCircle(cax, cay, 40f, rightPinPaint);
                    }
                } else if (rClick) {
                    rightPaint.setColor(0xFF00FF00);
                } else {
                    rightPaint.setColor(0xFFFFFFFF);
                }
                canvas.drawCircle(cx, cy, 25f, rightPaint);
            }
        }
    }
}