package com.wixtar.qa.whoscallhook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Whoscall QA 訂閱狀態模擬模組。
 *
 * 背景：
 * 依據 訂閱流程.txt 的分析，Whoscall (gogolook.callgogolook2) 全 App 是否顯示「已訂閱 / Premium」
 * 功能，最終都是透過 EntitlementRepository.isPremium 這條 Flow<Boolean> 來判斷：
 *
 *   val isPremium = userEntitlements.any {
 *       it.status != EntitlementStatus.FREE && it.status != EntitlementStatus.UNKNOWN
 *   }
 *
 * 在目前這支 APK（已反編譯放在 AndroidStudioProjects\Whoscall）中，上述邏輯對應到以下混淆類別：
 *   - co5              EntitlementRepository 介面
 *   - oh4              DefaultEntitlementRepository 實作
 *   - mh4              isPremium 這個 Flow<Boolean> 本體（co5.c() 回傳）
 *   - mh4$a            mh4 內部的 FlowCollector，實際計算 isPremium 布林值的地方
 *                       （原始碼：emit() 內逐一比對 meb.a=FREE / meb.g=UNKNOWN）
 *   - meb              EntitlementStatus enum（a=FREE, c=PREMIUM, g=UNKNOWN...）
 *   - ztf              UserEntitlement 資料類別（建構子：ztf(meb, String, boolean, List)）
 *
 * 做法：
 * 我們不去猜測/竄改個別功能的旗標，而是直接 Hook 住「原始清單 -> isPremium 布林值」的唯一
 * 匯聚點（mh4$a.emit），在真正的清單被拿去判斷之前，動態塞入一筆狀態為 PREMIUM 的假資料，
 * 讓判斷邏輯「只要有一筆不是 FREE / UNKNOWN 就視為已訂閱」自然成立為 true。
 * 這樣無論使用者實際的訂閱資料庫內容為何，全 App 讀到的 isPremium 都會是 true。
 *
 * 注意事項（重要）：
 * 1. 這只會影響「App 本地判斷是否已訂閱」的邏輯（client 端 UI 顯示 / 功能開關），
 *    不會偽造 Google Play 的購買憑證，也不會影響 NexOrder 後端伺服器實際記錄的訂單狀態。
 *    若測項需要驗證「伺服器端也認定已訂閱」（例如 verifyToken 相關流程），此 Hook 無法涵蓋。
 * 2. mh4 / oh4 / co5 / meb / ztf 都是 ProGuard/R8 混淆後的名稱，只保證對應「這次反編譯出來的
 *    這一版 APK」。日後 App 改版重新打包，混淆名稱極可能改變，屆時需要：
 *      a. 用 jadx 反編譯新版 APK
 *      b. 搜尋 "EntitlementStatus" 或 "isPremium" 相關 Kotlin 檔案（DefaultEntitlementRepository.kt）
 *      c. 對照更新下方 CLASS_* 常數
 * 3. 本模組使用新版 LSPosed（libxposed）API 102 撰寫，需搭配支援新版 API 的 LSPosed 版本，
 *    並在 LSPosed Manager 內手動啟用本模組、勾選 Whoscall 為作用範圍後，重新啟動 Whoscall App。
 *
 * ---------------------------------------------------------------------------------------
 * 第二個 Hook：強制開通「自動更新號碼資料庫」(offlinedb) 功能
 * ---------------------------------------------------------------------------------------
 * 這個 App 內部其實有兩套「這個功能是否可用」的判斷邏輯：
 *   1. hrd（實作介面 xj1）：同步、以快取為準的判斷。
 *      hrd.a(v26 featureType) 內部把 v26 轉成字串 key（v26.b=OFFLINE_DB -> "offlinedb"），
 *      再去讀 SharedPreferences（key: "cached_enabled_feature_keys"，此快取由 oh4 在每次
 *      訂閱資料同步時寫入）判斷該 key 是否存在。App 內廣泛使用的靜態工具
 *      ch8.b(v26) 就是直接呼叫這裡（ch8 對應反編譯前的 LegacyPremiumUtils）。
 *   2. gy4（實作介面 puf）：非同步、直接查本地方案(UserPlan)資料庫的版本，邏輯較複雜，
 *      牽涉 Kotlin coroutine 掛起機制，不適合用 Hook 直接竄改回傳值（掛起後真正把結果
 *      resume 回去的路徑不會再次經過這個方法，Hook 會攔不到）。
 *
 * 因此這裡選擇 Hook 第 1 套（hrd.a），這也是全 App 最廣泛共用的判斷點，多數「功能是否
 * 解鎖」的 UI 顯示（含「自動更新號碼資料庫」開關）都是直接或間接讀這裡的結果。
 * 依原始碼追蹤，並未在別處另外找到一個獨立存放「使用者手動開/關」的 SharedPreferences
 * 欄位，因此此功能的「開/關」狀態很可能就是直接綁定這個可用性判斷結果 —— 也就是說，
 * 這個 Hook 套用後，介面上的開關預期會直接顯示為「已開啟」。若實際測試後開關仍顯示關閉，
 * 代表畫面另外還讀了其他狀態來源，需要再進一步追蹤（例如沿著
 * CallerIdAutoUpdateView.kt 反編譯後的 tu1.java 往呼叫端繼續往上找 ViewModel）。
 */
public final class WhoscallPremiumHookModule extends XposedModule {

    private static final String TAG = "WhoscallPremiumHook";

    /** 要 Hook 的目標 App 套件名稱（取自 AndroidManifest.xml 的 package 屬性） */
    private static final String TARGET_PACKAGE = "gogolook.callgogolook2";

    // ↓↓↓ 以下為混淆類別名稱，APK 改版後若 Hook 失效，請優先檢查/更新這幾個常數 ↓↓↓

    /** mh4$a：isPremium Flow 內部 FlowCollector，emit() 內計算最終布林值 */
    private static final String CLASS_FLOW_COLLECTOR = "mh4$a";

    /** z63：Kotlin coroutine 的 Continuation 介面（混淆後名稱），emit() 的第二個參數型別 */
    private static final String CLASS_CONTINUATION = "z63";

    /** ztf：UserEntitlement 資料類別，建構子為 (meb status, String expireAt, boolean isOwner, List features) */
    private static final String CLASS_USER_ENTITLEMENT = "ztf";

    /** meb：EntitlementStatus enum */
    private static final String CLASS_ENTITLEMENT_STATUS = "meb";

    /** meb.c：EntitlementStatus.PREMIUM（可算作已訂閱的狀態之一） */
    private static final String FIELD_STATUS_PREMIUM = "c";

    /** hrd：實作 xj1 介面，isFeatureAvailable(v26) 的同步/快取版判斷邏輯所在類別 */
    private static final String CLASS_FEATURE_AVAILABILITY_CHECKER = "hrd";

    /** v26：FeatureType enum（ADS/OFFLINE_DB/SPAM_HAMMER/... ） */
    private static final String CLASS_FEATURE_TYPE = "v26";

    /** v26.b：FeatureType.OFFLINE_DB（自動更新號碼資料庫功能） */
    private static final String FIELD_FEATURE_TYPE_OFFLINE_DB = "b";

    // ↑↑↑ 混淆類別名稱設定區結束 ↑↑↑

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        log(Log.INFO, TAG, "WhoscallPremiumHookModule 已載入，process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        super.onPackageLoaded(param);

        // 只處理 Whoscall 這個目標 App，避免誤 Hook 到其他作用範圍內的 App
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            hookIsPremiumFlow(param.getDefaultClassLoader());
            log(Log.INFO, TAG, "isPremium 強制訂閱 Hook 套用成功，Whoscall 將視為已訂閱狀態");
        } catch (Throwable t) {
            // Hook 失敗最常見原因：APK 改版導致混淆類別/方法名稱變動，需要重新反編譯比對
            log(Log.ERROR, TAG, "isPremium Hook 套用失敗，請重新用 jadx 反編譯確認混淆類別名稱是否變動", t);
        }

        // 兩個 Hook 各自獨立 try/catch，避免其中一個因 APK 改版失效時連帶另一個也失效
        try {
            hookAutoUpdateDatabaseFeature(param.getDefaultClassLoader());
            log(Log.INFO, TAG, "自動更新號碼資料庫 Hook 套用成功，該功能將強制視為已開通");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "自動更新號碼資料庫 Hook 套用失敗，請重新用 jadx 反編譯確認混淆類別名稱是否變動", t);
        }
    }

    private Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            if (className.startsWith("defpackage.")) {
                return Class.forName(className.substring("defpackage.".length()), false, classLoader);
            } else {
                return Class.forName("defpackage." + className, false, classLoader);
            }
        }
    }

    /**
     * 核心 Hook 邏輯：
     * 攔截 mh4$a.emit(Object list, Continuation)，在原始清單交給判斷邏輯之前，
     * 動態塞入一筆 EntitlementStatus.PREMIUM 的假資料，讓 isPremium 永遠算出 true。
     */
    private void hookIsPremiumFlow(ClassLoader classLoader) throws Throwable {
        Class<?> flowCollectorClass = findClass(CLASS_FLOW_COLLECTOR, classLoader);
        Class<?> continuationClass = findClass(CLASS_CONTINUATION, classLoader);
        Class<?> entitlementClass = findClass(CLASS_USER_ENTITLEMENT, classLoader);
        Class<?> statusEnumClass = findClass(CLASS_ENTITLEMENT_STATUS, classLoader);

        // 對應原始碼：public final Object emit(Object obj, z63 z63Var)
        Method emitMethod = flowCollectorClass.getDeclaredMethod("emit", Object.class, continuationClass);
        emitMethod.setAccessible(true);

        // 取出 EntitlementStatus.PREMIUM 這個 enum 常數，之後要塞進假資料裡
        Field premiumStatusField = statusEnumClass.getDeclaredField(FIELD_STATUS_PREMIUM);
        premiumStatusField.setAccessible(true);
        Object premiumStatusValue = premiumStatusField.get(null);

        // 對應原始碼：public ztf(meb status, String expireAt, boolean isOwner, List<a26> features)
        Constructor<?> entitlementConstructor = entitlementClass.getDeclaredConstructor(
                statusEnumClass, String.class, boolean.class, List.class);
        entitlementConstructor.setAccessible(true);

        hook(emitMethod).intercept(chain -> {
            // 取出目前呼叫的原始參數：index 0 = 使用者訂閱清單(List)，index 1 = coroutine Continuation
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] instanceof List) {
                List<?> originalEntitlements = (List<?>) args[0];

                // 複製一份原始清單，避免直接修改到可能被其他地方共用的 List 物件
                List<Object> forcedEntitlements = new ArrayList<>(originalEntitlements);

                // 建立一筆假的 PREMIUM 訂閱紀錄：
                // status = PREMIUM, expireAt = null（不設過期時間）, isOwner = true, features = 空清單
                Object fakePremiumEntitlement = entitlementConstructor.newInstance(
                        premiumStatusValue, null, true, new ArrayList<Object>());
                forcedEntitlements.add(fakePremiumEntitlement);

                log(Log.INFO, TAG, "isPremium emit() 被攔截，原清單筆數=" + originalEntitlements.size() + " -> 修改後筆數=" + forcedEntitlements.size());
                args[0] = forcedEntitlements;
            }

            // 用修改後的參數繼續呼叫原始方法，讓後續流程（emit 給下游 collector）正常運作，
            // 差別只在於它算出來的 isPremium 一定是 true。
            return chain.proceed(args);
        });
    }

    /**
     * 第二個 Hook 邏輯：
     * 攔截 hrd.a(v26 featureType)（isFeatureAvailable 的同步/快取版判斷），
     * 當傳入的 featureType 是 OFFLINE_DB（自動更新號碼資料庫）時，直接強制回傳 true，
     * 略過原本讀取 SharedPreferences "cached_enabled_feature_keys" 的判斷。
     * 其餘 FeatureType（ads、spamhammer、family_sharing...）不受影響，仍執行原始邏輯。
     */
    private void hookAutoUpdateDatabaseFeature(ClassLoader classLoader) throws Throwable {
        Class<?> featureCheckerClass = findClass(CLASS_FEATURE_AVAILABILITY_CHECKER, classLoader);
        Class<?> featureTypeClass = findClass(CLASS_FEATURE_TYPE, classLoader);

        // 對應原始碼：public final boolean a(v26 v26Var)  (hrd implements xj1)
        Method isFeatureAvailableMethod = featureCheckerClass.getDeclaredMethod("a", featureTypeClass);
        isFeatureAvailableMethod.setAccessible(true);

        // 取出 v26.b = FeatureType.OFFLINE_DB 這個 enum 常數，用來比對呼叫方傳入的是不是這個功能
        Field offlineDbFeatureField = featureTypeClass.getDeclaredField(FIELD_FEATURE_TYPE_OFFLINE_DB);
        offlineDbFeatureField.setAccessible(true);
        Object offlineDbFeatureValue = offlineDbFeatureField.get(null);

        hook(isFeatureAvailableMethod).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] == offlineDbFeatureValue) {
                // 目標就是「自動更新號碼資料庫」，直接強制視為已開通，不呼叫原始方法
                return Boolean.TRUE;
            }

            // 其他 FeatureType 維持原本判斷邏輯
            return chain.proceed(args);
        });
    }
}
