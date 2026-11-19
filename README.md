# blood-pressure-diary

「血壓日記」是以 Samsung Galaxy S25 為目標的 Android 個人紀錄 App。拍攝血壓計後，核對收縮壓、舒張壓和脈搏即可存入手機。也能直接手動新增，或在首頁依日期查詢、修改及刪除紀錄。

在首頁「歷史紀錄」旁點「管理 → 刪除全部」，確認後會永久刪除所有本機量測紀錄，包含日期篩選未顯示的紀錄。

## 最小可行架構

- **Kotlin 與 Jetpack Compose** 提供歷史列表、相機畫面和編輯表單。ViewModel 管理草稿與資料庫操作。
- **CameraX 與內建模型版 Google ML Kit** 以後鏡頭拍攝，在記憶體中辨識繁中與拉丁字元。安裝 APK 後即可離線辨識，照片不會寫入檔案。
- **Room / SQLite** 儲存確認後的三項數值、拍攝時間及當時的時區偏移量。手動新增以開啟表單的時間為準。修改數值會保留原紀錄時間。App 沒有網路權限、Firebase 或後端。

拍攝時請讓三組數字和標籤同時入鏡。初版支援與 `SYS`／`收縮壓`／`高壓`、`DIA`／`舒張壓`／`低壓`、`PUL`／`PULSE`／`脈搏`／`心率` 同列的清楚數字。不確定的欄位會留空，請對照血壓計補填或更正後再儲存。

## 匯入照片與查看趨勢

- 在首頁點「匯入照片」會開啟系統檔案瀏覽器。長按第一張後可勾選多張 HEIC、HEIF、JPG 或 JPEG。也可以先在相簿內多選，再從「分享」選擇「血壓日記」。
- App 逐張離線辨識。「核對」可看原圖及修改數字。核對後勾選要儲存的紀錄，或點「全選完整紀錄」，最後確認批次儲存。辨識不完整的紀錄須補齊後才能勾選。
- 拍攝與匯入皆會先嘗試定位並校正樣本 Omron 版型的 LCD，讀取七段筆畫。初次讀取不完整時，會增強外框對比、重新檢查細筆畫，再用 Google ML Kit OCR 補讀。表單保留各欄能確認的數值，結果衝突的欄位留空供核對。
- 匯入優先使用系統回報的檔案修改時間；系統未提供時，才讀取照片內的 EXIF 原始拍攝時間。核對表單會顯示時間來源。若同一量測時間（精確到秒）已存在於歷史或本次批次，後續照片會跳過。兩種時間都缺少時，該檔會在列表顯示失敗原因，其他檔案會繼續處理。
- 點「趨勢圖」可切換直式／橫式。血壓（mmHg）與脈搏（次／分）共用連續時間軸，點與點之間按實際經過時間排列。預設顯示最近 30 天，拖曳圖面或捲軸可跨月瀏覽，雙指或使用按鈕可縮放，「全部」顯示整段歷史。血壓圖的 Y 軸每格為 10 mmHg，收縮壓和舒張壓用不同顏色的實線。點圖可查看單筆日期時間與數值，「量測明細」可查閱目前範圍內的每一筆。
- 相鄰量測相隔超過 7 天時，折線在血壓及脈搏圖上斷開，並依時間長度顯示淡灰空檔。點空檔可看前後兩筆的日期與時間。

## 命令列建置

準備 JDK 17 與 [Android SDK Command-line Tools](https://developer.android.com/studio#command-tools)。設定 `JAVA_HOME`、`ANDROID_HOME`，並將 JDK 的 `bin`、SDK 的 `cmdline-tools/latest/bin` 和 `platform-tools` 加入 `PATH`。

```text
sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Windows PowerShell

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux 或 macOS

```bash
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

初次建置會下載 Gradle 和相依套件。測試報告位於 `app/build/reports/tests/testDebugUnitTest/index.html`，APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。建置與單元測試可在沒有 Android Studio 和模擬器的電腦執行。

## 安裝到 Samsung Galaxy S25

1. 試用可使用 debug APK。打算長期保留資料，請從第一次安裝就使用下節的個人簽署版。兩者的套件 ID 分別為 `com.bloodpressure.diary.debug` 和 `com.bloodpressure.diary`，各自有獨立資料庫。
2. 在手機啟用開發人員選項與 USB 偵錯，連接電腦後在手機上授權，再用 `adb devices` 確認顯示為 `device`。
3. 用以下命令安裝或覆蓋安裝。試用時將路徑換成上面的 debug APK。也可把 APK 傳到手機，從「我的檔案」開啟並依系統提示允許該來源安裝。

```text
adb install -r app/build/outputs/apk/release/app-release.apk
```

真機驗收時，先開飛航模式，再開啟 App 拍攝有明確標籤的血壓計，核對數值和拍攝時間。用遮住標籤的畫面確認缺漏欄位留空且能手動補填。儲存後重開 App，查詢、修改及刪除測試紀錄。最後以相同金鑰簽署較高版本並覆蓋安裝，確認既有紀錄仍可查詢。

## 個人 APK 簽署與更新

用 JDK 的 `keytool` 建立一次 PKCS#12 金鑰庫。請在專案外執行下列命令，並備份金鑰庫及密碼。

```text
keytool -genkeypair -storetype PKCS12 -keystore blood-pressure-diary.p12 -alias diary -keyalg RSA -keysize 3072 -validity 10000
```

建置前在目前終端機設定以下環境變數。

| 變數 | 內容 |
| --- | --- |
| `BP_KEYSTORE` | 金鑰庫絕對路徑 |
| `BP_KEY_ALIAS` | 上例為 `diary` |
| `BP_STORE_PASSWORD` | 金鑰庫密碼 |
| `BP_KEY_PASSWORD` | 以上例建立 PKCS#12 時使用同一密碼 |

以 Gradle Wrapper 執行 `assembleRelease`，再使用 SDK Build Tools 的 `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` 檢查簽署憑證。請勿提交金鑰庫或密碼。

每次發布都沿用相同的套件 ID 與簽署金鑰、提高 `app/build.gradle.kts` 的 `versionCode`，並用覆蓋安裝保留資料。變更 Room 結構時須提供資料遷移，初版的結構已匯出到 `app/schemas`。解除安裝會刪除手機上的紀錄。金鑰庫遺失時，Android 會拒絕以新金鑰簽署的 APK 覆蓋已安裝的個人版。

## GitHub Actions

推送或開啟 Pull Request 時，`Android APK` workflow 會執行測試、Lint、編譯並將 `blood-pressure-diary-debug` 上傳到該次執行的 **Artifacts**。不同執行可能使用不同的 debug 金鑰，適合短期試用。

若要由 GitHub 簽署個人版，建立 `personal-release` environment，設定 `BP_KEYSTORE_BASE64`（同一個金鑰庫檔案的 Base64）、`BP_STORE_PASSWORD`、`BP_KEY_ALIAS`、`BP_KEY_PASSWORD` 四個 Secrets。從預設分支手動執行 `Android APK` 後，已簽署的 APK 會出現在 `blood-pressure-diary-release` Artifact。請核對簽署憑證指紋與先前安裝的版本一致。

PDF 就醫報告與 CSV 匯入匯出保留給後續版本。
