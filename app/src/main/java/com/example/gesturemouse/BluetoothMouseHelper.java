package com.example.gesturemouse;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.bluetooth.BluetoothClass;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
public class BluetoothMouseHelper {

    private final Context context;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedHost;
    private ScheduledExecutorService heartbeatExecutor;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isAppRegistered = false;

    public interface UIDebugListener {
        void onDebugStep(String stepMsg, boolean isFinished);
    }
    private UIDebugListener uiLogger;

    // 🌟 複合式 HID 描述符：Report ID 1 為滑鼠，Report ID 2 為鍵盤
    private static final byte[] COMBO_REPORT_DESC = {
            // 🐭 滑鼠部分 (Report ID 1)
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x02, (byte) 0xA1, (byte) 0x01,
            (byte) 0x85, (byte) 0x01, // Report ID (1)
            (byte) 0x09, (byte) 0x01, (byte) 0xA1, (byte) 0x00, (byte) 0x05, (byte) 0x09,
            (byte) 0x19, (byte) 0x01, (byte) 0x29, (byte) 0x03, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x01, (byte) 0x95, (byte) 0x03, (byte) 0x75, (byte) 0x01,
            (byte) 0x81, (byte) 0x02, (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x05,
            (byte) 0x81, (byte) 0x03, (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x30,
            (byte) 0x09, (byte) 0x31, (byte) 0x09, (byte) 0x38, (byte) 0x15, (byte) 0x81,
            (byte) 0x25, (byte) 0x7F, (byte) 0x75, (byte) 0x08, (byte) 0x95, (byte) 0x03,
            (byte) 0x81, (byte) 0x06, (byte) 0xC0, (byte) 0xC0,

            // ⌨️ 鍵盤部分 (Report ID 2)
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x06, (byte) 0xA1, (byte) 0x01,
            (byte) 0x85, (byte) 0x02, // Report ID (2)
            (byte) 0x05, (byte) 0x07, // Usage Page (Keyboard)
            (byte) 0x19, (byte) 0xE0, (byte) 0x29, (byte) 0xE7, // 修飾鍵 (Ctrl, Shift, Alt, Win)
            (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75, (byte) 0x01, (byte) 0x95, (byte) 0x08,
            (byte) 0x81, (byte) 0x02, // 輸入修飾鍵 Byte
            (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x08, (byte) 0x81, (byte) 0x03, // 保留進位 Byte
            (byte) 0x95, (byte) 0x06, (byte) 0x75, (byte) 0x08, (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x65,
            (byte) 0x19, (byte) 0x00, (byte) 0x29, (byte) 0x65,
            (byte) 0x81, (byte) 0x00, // 六鍵無衝主陣列 (Keycodes)
            (byte) 0xC0
    };

    public BluetoothMouseHelper(Context context, UIDebugListener logger) {
        this.context = context;
        this.uiLogger = logger;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            uiLogger.onDebugStep("S1: 獲取全能複合代理通道...", false);
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE);
        }
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                uiLogger.onDebugStep("S2: 複合服務掛載，註冊 SDP...", false);
                registerApp();
            }
        }
        @Override
        public void onServiceDisconnected(int profile) {
            stopHeartbeat();
            hidDevice = null;
            isAppRegistered = false;
        }
    };

    private void registerApp() {
        if (hidDevice == null || isAppRegistered) return;

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "GestureCombo", "AI Dual Hand Terminal", "Project Group",
                BluetoothHidDevice.SUBCLASS1_COMBO, COMBO_REPORT_DESC);

        hidDevice.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                super.onAppStatusChanged(pluggedDevice, registered);
                isAppRegistered = registered;
                if (registered) {
                    uiLogger.onDebugStep("R1: 🟢 滑鼠+鍵盤複合身分註冊成功！", false);
                    mainHandler.postDelayed(() -> checkAndReconnect(), 1000);
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                super.onConnectionStateChanged(device, state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedHost = device;
                    uiLogger.onDebugStep("T2: 🟢 成功與 Windows 交握！全能通道解鎖", false);
                    startHeartbeat();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedHost != null && connectedHost.equals(device)) {
                        connectedHost = null;
                        stopHeartbeat();
                        uiLogger.onDebugStep("🔴 電腦通道已中斷", false);
                    }
                }
            }
        });
    }

    public boolean isReady() {
        return hidDevice != null && isAppRegistered;
    }

    public void checkAndReconnect() {
        if (!isReady()) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getBondedDevices() != null) {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device.getBluetoothClass() != null &&
                        device.getBluetoothClass().getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER) {
                    int state = hidDevice.getConnectionState(device);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        connectedHost = device;
                        startHeartbeat();
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        uiLogger.onDebugStep("W3: 發起標準連線請求...", false);
                        try { hidDevice.connect(device); } catch (Exception e) {}
                    }
                }
            }
        }
    }

    private void startHeartbeat() {
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            uiLogger.onDebugStep("💓 複合保活心跳運作中！", true);
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (hidDevice != null && connectedHost != null) {
                    // 心跳維護 Report ID 1 (滑鼠區)
                    try { hidDevice.sendReport(connectedHost, 1, new byte[]{0, 0, 0, 0}); } catch (Exception e) {}
                }
            }, 0, 2, TimeUnit.SECONDS);
        }
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    // 🌟 滑鼠發射口 (帶有 Report ID 1)
    public void sendMouseEvent(int dx, int dy, int wheel, boolean isLeft, boolean isRight) {
        if (hidDevice == null || connectedHost == null) return;
        byte[] report = new byte[4];
        int buttonStatus = 0;
        if (isLeft)  buttonStatus |= 0x01;
        if (isRight) buttonStatus |= 0x02;

        report[0] = (byte) buttonStatus;
        report[1] = (byte) Math.max(-127, Math.min(127, dx));
        report[2] = (byte) Math.max(-127, Math.min(127, dy));
        report[3] = (byte) Math.max(-127, Math.min(127, wheel));
        try { hidDevice.sendReport(connectedHost, 1, report); } catch (Exception e) {}
    }

    // 🌟 鍵盤發射口 (帶有 Report ID 2)
    // modifier: Bit 0=LCtrl, Bit 1=LShift, Bit 2=LAlt, Bit 3=LWin
    // keyCodes: 最多 6 個同時按下的按鍵代碼
    public void sendKeyboardEvent(byte modifier, byte[] keyCodes) {
        if (hidDevice == null || connectedHost == null) return;
        byte[] report = new byte[8];
        report[0] = modifier;
        report[1] = 0; // Reserved
        for (int i = 0; i < Math.min(6, keyCodes.length); i++) {
            report[2 + i] = keyCodes[i];
        }
        try { hidDevice.sendReport(connectedHost, 2, report); } catch (Exception e) {}
    }

    public void closeConnection() {
        stopHeartbeat();
        if (hidDevice != null) {
            try {
                if (connectedHost != null) hidDevice.disconnect(connectedHost);
                hidDevice.unregisterApp();
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
                }
            } catch (Exception e) {}
            hidDevice = null;
            connectedHost = null;
            isAppRegistered = false;
        }
    }
}