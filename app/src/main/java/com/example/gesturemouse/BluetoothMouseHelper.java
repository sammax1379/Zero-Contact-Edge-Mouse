package com.example.gesturemouse;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executors;

@SuppressLint("MissingPermission") // 權限會在 MainActivity 處理，這裡先略過警告
public class BluetoothMouseHelper {

    private static final String TAG = "BluetoothMouse";
    private final Context context;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedHost;

    // 🎯 核心靈魂：標準滑鼠的硬體描述符 (Report Descriptor)
    // 這段 Byte 陣列等同於寫死在實體滑鼠 MCU 裡的韌體宣告
    private static final byte[] MOUSE_REPORT_DESC = {
            (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x02, // Usage (Mouse)
            (byte) 0xA1, (byte) 0x01, // Collection (Application)
            (byte) 0x09, (byte) 0x01, //   Usage (Pointer)
            (byte) 0xA1, (byte) 0x00, //   Collection (Physical)
            (byte) 0x05, (byte) 0x09, //     Usage Page (Button)
            (byte) 0x19, (byte) 0x01, //     Usage Minimum (1)
            (byte) 0x29, (byte) 0x03, //     Usage Maximum (3)
            (byte) 0x15, (byte) 0x00, //     Logical Minimum (0)
            (byte) 0x25, (byte) 0x01, //     Logical Maximum (1)
            (byte) 0x95, (byte) 0x03, //     Report Count (3)
            (byte) 0x75, (byte) 0x01, //     Report Size (1)
            (byte) 0x81, (byte) 0x02, //     Input (Data, Variable, Absolute)
            (byte) 0x95, (byte) 0x01, //     Report Count (1)
            (byte) 0x75, (byte) 0x05, //     Report Size (5)
            (byte) 0x81, (byte) 0x03, //     Input (Constant, Variable, Absolute)
            (byte) 0x05, (byte) 0x01, //     Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x30, //     Usage (X)
            (byte) 0x09, (byte) 0x31, //     Usage (Y)
            (byte) 0x15, (byte) 0x81, //     Logical Minimum (-127)
            (byte) 0x25, (byte) 0x7F, //     Logical Maximum (127)
            (byte) 0x75, (byte) 0x08, //     Report Size (8)
            (byte) 0x95, (byte) 0x02, //     Report Count (2)
            (byte) 0x81, (byte) 0x06, //     Input (Data, Variable, Relative)
            (byte) 0xC0,              //   End Collection
            (byte) 0xC0               // End Collection
    };

    public BluetoothMouseHelper(Context context) {
        this.context = context;
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE);
        }
    }

    // 監聽藍牙 HID 服務是否準備就緒
    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = (BluetoothHidDevice) proxy;
                registerApp(); // 服務連上後，把 App 註冊為滑鼠
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null;
            }
        }
    };

    // 註冊 SDP (服務發現協定)，讓電腦掃描時知道這是一隻滑鼠
    // 註冊 SDP (服務發現協定)，讓電腦掃描時知道這是一隻滑鼠
    // 註冊 SDP (服務發現協定)，讓電腦掃描時知道這是一隻滑鼠
    private void registerApp() {
        if (hidDevice != null) {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "GestureMouse", "Smart AI Edge Mouse", "Project Group",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, MOUSE_REPORT_DESC);

            hidDevice.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), new BluetoothHidDevice.Callback() {

                @Override
                public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                    super.onAppStatusChanged(pluggedDevice, registered);
                    if (registered) {
                        Log.i(TAG, "🟢 HID 服務註冊成功！");
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null && adapter.getBondedDevices() != null) {
                            for (BluetoothDevice device : adapter.getBondedDevices()) {
                                hidDevice.connect(device);
                            }
                        }
                    }
                }

                @Override
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    super.onConnectionStateChanged(device, state);
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        connectedHost = device;
                        Log.i(TAG, "🟢 成功連線到電腦: " + device.getName());
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        if (connectedHost != null && connectedHost.equals(device)) {
                            connectedHost = null;
                            Log.i(TAG, "🔴 電腦已斷線");
                        }
                    }
                }

                // ==========================================
                // 🛡️ 拯救斷線的核心：回應 Windows 的查勤指令！
                // ==========================================


                @Override
                public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
                    super.onGetReport(device, type, id, bufferSize);
                    Log.i(TAG, "收到 Windows 狀態請求 (GetReport)");
                    // 隨便回傳一個空的滑鼠狀態 (0,0,0) 敷衍它，告訴它我們沒按按鍵也沒移動
                    hidDevice.replyReport(device, type, id, new byte[]{0, 0, 0});
                }

                @Override
                public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
                    super.onSetReport(device, type, id, data);
                    Log.i(TAG, "收到 Windows 設定請求 (SetReport)");
                    hidDevice.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
                }
            });
        }
    }

    // ==========================================
    // 🚀 發送滑鼠訊號給電腦的封包函式
    // ==========================================
    public void sendMouseEvent(int dx, int dy, boolean isLeftClick) {
        if (hidDevice == null || connectedHost == null) return;

        // 限制位移量在 -127 到 +127 之間 (8-bit signed integer)
        dx = Math.max(-127, Math.min(127, dx));
        dy = Math.max(-127, Math.min(127, dy));

        // 封裝 3 Bytes 陣列：[按鍵狀態, X位移, Y位移]
        byte[] report = new byte[3];
        report[0] = (byte) (isLeftClick ? 1 : 0); // 1 = 左鍵按下，0 = 放開
        report[1] = (byte) dx;
        report[2] = (byte) dy;

        // 發送到電腦端
        hidDevice.sendReport(connectedHost, 0, report);
    }
}