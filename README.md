# Whoscall 訂閱模擬與自動更新資料庫 LSPosed 模組 (QA 專用)

## 這支模組做什麼

讓 Whoscall App（`gogolook.callgogolook2`）在裝置上「以為自己已訂閱 Premium」，方便 QA 測試訂閱
限定功能（進階版頁面、移除廣告、自動封鎖、家庭共享等）的 UI 與互動流程，不需要真的走完 Google
Play 購買。

**原理（Hook 1：全域已訂閱狀態）**：依 `..\訂閱流程.txt` 的分析，全 App 是否顯示已訂閱功能，
最終都經由 `EntitlementRepository.isPremium`（`Flow<Boolean>`）判斷；本模組直接 Hook 住這條 Flow
內部「原始訂閱清單 → isPremium 布林值」的唯一計算點（混淆類別 `mh4$a.emit`），在計算前偷偷塞入
一筆 `EntitlementStatus.PREMIUM` 的假資料，使判斷結果永遠為 `true`。

**原理（Hook 2：強制開通「自動更新號碼資料庫」）**：這個功能是否可用另外還有一層獨立的
Feature-level 判斷（`hrd.a(v26 featureType)`，實作介面 `xj1`），會把 `v26.b`
（`FeatureType.OFFLINE_DB`）轉成字串 key `"offlinedb"`，再去讀 SharedPreferences
（key：`cached_enabled_feature_keys`）判斷是否已開通。本模組直接 Hook 住這個方法，當傳入的
featureType 是 OFFLINE_DB 時強制回傳 `true`，其餘 FeatureType（ads、spamhammer、
family_sharing…）不受影響。依原始碼追蹤，找不到另一個獨立的「使用者手動開關」設定值，
UI 上的開關預期會直接跟著這個判斷結果顯示為「已開啟」；若實測後仍顯示關閉，代表畫面還讀了
其他狀態來源，需要再往 `tu1.java`（`CallerIdAutoUpdateView.kt` 反編譯後的檔案）的呼叫端往上追。

## 這支模組做不到什麼（重要限制）

- **不會偽造 Google Play 購買憑證**，也**不會影響 NexOrder 後端伺服器**實際記錄的訂單/權益資料。
- 若測項需要驗證「伺服器端也認定已訂閱」（例如收據驗證 `verifyToken`、後端 API 回傳的訂單狀態），
  此 Hook 無法涵蓋，需另外準備測試帳號或後端測試資料。
- 僅影響「App 本地讀取到的訂閱狀態」，適合拿來測試 client 端依 `isPremium` 決定顯示/隱藏的功能。

## 目錄結構

```
lsposed-whoscall-premium/
├── build.gradle / settings.gradle / gradle.properties     專案層級設定
└── app/
    ├── build.gradle                                        依賴 io.github.libxposed:api:102.0.0
    ├── src/main/AndroidManifest.xml
    ├── src/main/res/values/strings.xml                     模組名稱/描述
    ├── src/main/resources/META-INF/xposed/
    │   ├── java_init.list                                  模組進入點類別（LSPosed 讀取用）
    │   ├── scope.list                                       預設作用範圍：gogolook.callgogolook2
    │   └── module.prop                                      minApiVersion / targetApiVersion = 102
    └── src/main/java/com/wixtar/qa/whoscallhook/
        └── WhoscallPremiumHookModule.java                   Hook 核心邏輯
```

## 建置與安裝步驟

1. 用 Android Studio 開啟 `lsposed-whoscall-premium` 資料夾（File > Open）。
   - 若提示缺少 Gradle Wrapper，選擇讓 Android Studio 使用內建 Gradle 自動產生即可，不影響建置。
2. 等待 Gradle Sync 完成後，Build > Build Bundle(s) / APK(s) > Build APK(s)。
3. 將產出的 `app-debug.apk`（或 release apk）安裝到已安裝 LSPosed 的測試裝置／模擬器上。
   - **需求**：裝置需已 root 並安裝支援「新版 libxposed API」的 LSPosed 版本（較舊版本僅支援
     legacy Xposed API，無法載入本模組，請先確認 LSPosed 版本／更新到最新版）。
4. 開啟 LSPosed Manager App：
   - 在「模組」列表中找到「Whoscall 訂閱狀態模擬 (QA)」，開啟啟用開關。
   - 進入該模組的作用範圍設定，勾選 Whoscall（`gogolook.callgogolook2`）。
     （`scope.list` 已預設此套件，但多數 LSPosed 版本仍需要在 Manager 內手動勾選一次才會生效）
5. 強制停止並重新開啟 Whoscall App。進入任一訂閱限定功能頁面確認是否已顯示為「已訂閱」狀態。
6. 可用 `adb logcat -s WhoscallPremiumHook` 查看模組是否成功套用 Hook（成功/失敗都會記 log）。

## APK 改版後如何維護（混淆類別名稱會變）

`mh4` / `oh4` / `co5` / `meb` / `ztf` / `z63` / `hrd` / `xj1` / `v26` 都是 ProGuard/R8 混淆後的
類別名稱，只保證對應「這次反編譯出來的這一版 Whoscall APK」。Whoscall 改版重新打包後，這些名稱
極可能改變，屆時：

1. 用 jadx（或同等工具）反編譯新版 APK。
2. Hook 1（isPremium）：搜尋 `EntitlementStatus`、`isPremium`、`DefaultEntitlementRepository`
   等關鍵字，找到目前對應的混淆類別。可比對邏輯特徵：一個 enum 有 `FREE / AD_FREE / PREMIUM /
   AD_SUPPORTED / PREMIUM_FAMILY / PREMIUM_SHARE / UNKNOWN` 七個值，就是 `meb`
   （EntitlementStatus）的新對應類別；其建構子為 `(status, String, boolean, List)` 的資料類別
   就是 `ztf`（UserEntitlement）的新對應類別。
3. Hook 2（自動更新號碼資料庫）：搜尋 `"cached_enabled_feature_keys"`、`"offlinedb"` 這兩個字串常
   數，會找到 `hrd.java`（或改版後的新類別）。可比對邏輯特徵：一個 enum 有 `ADS / OFFLINE_DB /
   SPAM_HAMMER / SMS_URL_AUTO_SCAN / CALL_LOG / CALLER_ID / SMS_AUTO_FILTER / FAMILY_SHARING /
   UNKNOWN` 九個值，就是 `v26`（FeatureType）的新對應類別，其中 `OFFLINE_DB` 的順序（ordinal）
   對應的欄位名稱就是要更新的 `FIELD_FEATURE_TYPE_OFFLINE_DB`。
4. 更新 `WhoscallPremiumHookModule.java` 最上方「混淆類別名稱設定區」的 `CLASS_*` / `FIELD_*` 常數
   （`findClass()` 會自動嘗試加/去 `defpackage.` 前綴，所以常數本身可以只填類別簡名）。
5. 重新 Build APK 並安裝覆蓋。

## 安全與使用範圍提醒

本模組僅供內部 QA 測試環境使用，用於在測試裝置上模擬訂閱狀態，不應安裝於正式環境或他人裝置，
也不應用於規避正式付費機制。
