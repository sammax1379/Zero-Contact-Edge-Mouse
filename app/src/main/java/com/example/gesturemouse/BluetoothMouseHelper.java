package com.example.gesturemouse;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
public class BluetoothMouseHelper {

    private static final String TAG = "BluetoothMouse";
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

    private static final byte[] MOUSE_REPORT_DESC = {
            (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x02, (byte) 0xA1, (byte) 0x01,
            (byte) 0x09, (byte) 0x01, (byte) 0xA1, (byte) 0x00, (byte) 0x05, (byte) 0x09,
            (byte) 0x19, (byte) 0x01, (byte) 0x29, (byte) 0x03, (byte) 0x15, (byte) 0x00,
            (byte) 0x25, (byte) 0x01, (byte) 0x95, (byte) 0x03, (byte) 0x75, (byte) 0x01,
            (byte) 0x81, (byte) 0x02, (byte) 0x95, (byte) 0x01, (byte) 0x75, (byte) 0x05,
            (byte) 0x81, (byte) 0x03, (byte) 0x05, (byte) 0x01, (byte) 0x09, (byte) 0x30,
            (byte) 0x09, (byte) 0x31, (byte) 0x15, (byte) 0x81, (byte) 0x25, (byte) 0x7F,
            (byte) 0x75, (byte) 0x08, (byte) 0x95, (byte) 0x02, (byte) 0x81, (byte) 0x06,
            (byte) 0xC0, (byte) 0xC0
    };

    public BluetoothMouseHelper(Context context, UIDebugListener logger) {
        this.context = context;
        this.uiLogger = logger;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            uiLogger.onDebugStep("S1: 獲取全新代理通道...", false);
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE);
        }
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                uiLogger.onDebugStep("S2: 全新服務掛載，註冊 SDP...", false);
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
                "GestureMouse", "Smart AI Edge Mouse", "Project Group",
                BluetoothHidDevice.SUBCLASS1_MOUSE, MOUSE_REPORT_DESC);

        hidDevice.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), new BluetoothHidDevice.Callback() {

            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                super.onAppStatusChanged(pluggedDevice, registered);
                isAppRegistered = registered;
                if (registered) {
                    uiLogger.onDebugStep("R1: 🟢 滑鼠身分註冊成功！", false);
                    mainHandler.postDelayed(() -> checkAndReconnect(), 1000);
                } else {
                    uiLogger.onDebugStep("R-ERR: 🔴 註冊被系統拒絕！", false);
                }
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                super.onConnectionStateChanged(device, state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedHost = device;
                    uiLogger.onDebugStep("T2: 🟢 成功與 Windows 交握！通道解鎖", false);
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

    // 🌟 讓主程式確認背景管線是否被 Android 省電機制殺掉了
    public boolean isReady() {
        return hidDevice != null && isAppRegistered;
    }

    public void checkAndReconnect() {
        if (!isReady()) {
            uiLogger.onDebugStep("W1: 服務遭系統回收，交由主程式重建", false);
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getBondedDevices() != null) {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (device.getBluetoothClass() != null &&
                        device.getBluetoothClass().getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER) {

                    int state = hidDevice.getConnectionState(device);
                    uiLogger.onDebugStep("W2: 盤點電腦狀態 -> " +
                            (state == BluetoothProfile.STATE_CONNECTED ? "已連線" : "中斷"), false);

                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        connectedHost = device;
                        startHeartbeat();
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        uiLogger.onDebugStep("W3: 偵測到實體斷線，發起標準連線請求...", false);
                        try { hidDevice.connect(device); } catch (Exception e) {}
                    }
                }
            }
        }
    }

    private void startHeartbeat() {
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            uiLogger.onDebugStep("💓 保活心跳運作中，無縫接軌！", true);
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
            heartbeatExecutor.scheduleAtFixedRate(() -> {
                if (hidDevice != null && connectedHost != null) {
                    try { hidDevice.sendReport(connectedHost, 0, new byte[]{0, 0, 0}); } catch (Exception e) {}
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

    public void sendMouseEvent(int dx, int dy, boolean isLeftClick) {
        if (hidDevice == null || connectedHost == null) return;
        byte[] report = new byte[3];
        report[0] = (byte) (isLeftClick ? 1 : 0);
        report[1] = (byte) Math.max(-127, Math.min(127, dx));
        report[2] = (byte) Math.max(-127, Math.min(127, dy));
        try { hidDevice.sendReport(connectedHost, 0, report); } catch (Exception e) {}
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