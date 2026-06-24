# GestureCombo：基於 AI 視覺之雙手全息體感控制終端

![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android)
![Java](https://img.shields.io/badge/Language-Java-007396?style=flat-square&logo=java)
![MediaPipe](https://img.shields.io/badge/AI_Framework-MediaPipe-00BFFF?style=flat-square)
![Bluetooth](https://img.shields.io/badge/Protocol-Bluetooth_HID-0082FC?style=flat-square&logo=bluetooth)

## 1. 專題簡介
本專題整合「嵌入式系統」、「電腦視覺」與「藍牙通訊協定」，開發出一套無須額外實體硬體的外掛式雙手人機介面。系統透過行動裝置鏡頭捕捉雙手姿態，右手游標具備防手震與點擊功能，左手具備 WASD 虛擬搖桿與跳躍複合按鍵。搭配自定義的「AR 雷達全息疊加層」，為使用者帶來直覺、低延遲且具備科幻感的零接觸操控體驗。

## 2. 系統需求
* **輸入設備端 (手機)**：支援 Android 11 (API Level 30) 或以上版本，並具備前置/後置攝影機及藍牙模組之行動裝置。
* **被控設備端 (電腦)**：支援標準 Bluetooth HID (Human Interface Device) 協定之 Windows / Mac / Linux 作業系統。
* **權限需求**：相機權限 (`CAMERA`)、藍牙連線與廣播權限 (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`)。

## 3. 硬體與軟體環境
* **硬體測試平台**：Samsung Galaxy S20 FE 5G (作為 AI 推論與訊號發射終端)
* **開發環境**：Android Studio (Iguana / Jellyfish 或更新版本)
* **程式語言**：Java, XML
* **核心函式庫**：
  * `androidx.camera:camera-camera2` (CameraX 影像串流)
  * `com.google.mediapipe:tasks-vision` (MediaPipe 視覺推論引擎)
  * `android.bluetooth.BluetoothHidDevice` (藍牙底層通訊)

## 4. 安裝方式
1. 將本 Repository Clone 至本地端：
   ```bash
   git clone [https://github.com/your-account/GestureCombo.git](https://github.com/your-account/GestureCombo.git)

   ```

2. 使用 Android Studio 開啟專案。
3. 確保 Gradle 同步完成後，將 Android 手機連接至電腦（開啟 USB 偵錯），點擊 `Run 'app'` 進行編譯與安裝。

## 5. 執行方式

1. **藍牙配對**：首次使用需於 Windows 藍牙設定中，搜尋並配對名為 `GestureCombo` 或手機名稱之裝置。
2. **開啟 App**：同意相機與藍牙權限，將手機橫向擺放。
3. **左手搖桿休眠與喚醒**：左手進入畫面預設為休眠。比出「👌 OK 手勢」即可在當下座標釘下 AR 圖釘並啟動 WASD 虛擬搖桿，隨後掌心平移即可觸發方向鍵，握拳可觸發 Space 鍵。
4. **右手滑鼠控制**：張開手掌平移控制游標，食指與大拇指捏合觸發左鍵，中指與大拇指捏合觸發右鍵，四指握拳進入滾輪模式。

## 6. 資料集說明

本專題之核心 AI 模型採用 Google 開源之 **MediaPipe Hand Landmarker** 預訓練模型 (`hand_landmarker.task`)。

* **資料集來源**：由 Google 建立之跨型態手部資料集，包含約 30,000 張真實世界手部影像與多種合成手部模型。
* **資料標註**：每張手部影像皆標註 21 個精確的 3D 關節點 (x, y, z座標)，涵蓋各種光照環境與複雜背景，確保在行動端具備極高之強健性。

## 7. 模型與演算法說明

* **AI 推論模型**：採用 BlazePalm 進行掌心偵測，再串接 Hand Landmark Model 迴歸 42 個關鍵點。
* **幾何特徵防呆機制**：利用掌寬與掌高比值 ($Ratio < 0.40$) 實作「防側翻」機制，避免手掌側放時造成游標暴衝。
* **適應性低通濾波 (Adaptive Low-pass Filter)**：透過計算前一幀與當前幀的座標變化率 (Velocity)，動態調整平滑權重 ($\alpha$)。速度慢時增加阻尼穩定游標，速度快時降低阻尼確保操作跟手性。
* **非同步藍牙管線管理**：針對 Android OOM 與背景休眠機制，設計 `onPause` 強制斷開與 `onResume` 冷開機 (Cold Boot) 握手重連邏輯，解決 HID 死鎖問題。

## 8. 測試結果

* **推論效能 (FPS)**：在 640x480 解析度下，雙手同時追蹤可穩定維持 **26 ~ 28 FPS**。
* **系統延遲 (Latency)**：從影像擷取、推論到藍牙封包發送，端到端平均延遲約 **38 ~ 45 ms**，達到即時(Real-time)無感延遲標準。
* **辨識準確率 (Accuracy)**：靜態狀態切換（點擊、握拳、OK手勢）於一般室內光源下實測 400 次，準確率達 **96.5%**。

## 9. Demo 影片

[點此觀看 GestureCombo 系統實際運作展示影片 (YouTube 連結)](https://www.google.com/search?q=%23) *(請替換為你們的影片連結)*

## 10. Team Members

* **[B11100034謝承祐]**：系統整合、AI 演算法與狀態機邏輯開發、藍牙 HID 底層協定撰寫。
* **[B11100011翁傑聖]**：UI/UX 設計、AR 全息雷達畫布 (Canvas) 開發、硬體測試與效能參數調適。
