package com.wixtar.qa.whoscallhook;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Whoscall QA 訂閱狀態模擬與功能開通模組。
 *
 * 支援版本：Whoscall 8.38 / 8.41+
 *
 * 背景與原理：
 * 1. isPremium 強制訂閱 Hook：
 *    Whoscall 全 App 是否顯示「已訂閱 / Premium」，最終都經由 EntitlementRepository.isPremium
 *    （Flow<Boolean>）判斷。本模組 Hook 住這條 Flow 內部的 FlowCollector.emit()，在原始清單被拿去
 *    判斷之前，動態塞入一筆狀態為 PREMIUM 的假資料，讓判斷邏輯「只要有一筆不是 FREE / UNKNOWN 就視為已訂閱」
 *    自然成立為 true。
 *
 * 2. 自動更新號碼資料庫 (offlinedb) 強制開通 Hook：
 *    攔截 FeatureAvailabilityChecker.isFeatureAvailable(FeatureType)，當傳入的 featureType
 *    為 OFFLINE_DB (自動更新號碼資料庫) 時，直接強制回傳 true。
 */
public final class WhoscallPremiumHookModule extends XposedModule {

    private static final String TAG = "WhoscallPremiumHook";

    /** 要 Hook 的目標 App 套件名稱（取自 AndroidManifest.xml 的 package 屬性） */
    private static final String TARGET_PACKAGE = "gogolook.callgogolook2";

    // ↓↓↓ 以下為混淆類別候選名稱列表（依序比對 8.41+ 與 8.38，相容多個 APK 版本） ↓↓↓

    /** isPremium Flow 內部 FlowCollector，emit() 內計算最終布林值 (8.41: jg4$a, 8.38: mh4$a) */
    private static final String[] CLASS_FLOW_COLLECTOR_CANDIDATES = {"jg4$a", "mh4$a"};

    /** Kotlin coroutine 的 Continuation 介面 (8.41: x43, 8.38: z63) */
    private static final String[] CLASS_CONTINUATION_CANDIDATES = {"x43", "z63"};

    /** UserEntitlement 資料類別，建構子為 (status, expireAt, isOwner, features) (8.41: dtf, 8.38: ztf) */
    private static final String[] CLASS_USER_ENTITLEMENT_CANDIDATES = {"dtf", "ztf"};

    /** EntitlementStatus enum (8.41: fbb, 8.38: meb) */
    private static final String[] CLASS_ENTITLEMENT_STATUS_CANDIDATES = {"fbb", "meb"};

    /** EntitlementStatus.PREMIUM 常數欄位 */
    private static final String FIELD_STATUS_PREMIUM = "c";

    /** FeatureAvailabilityChecker：isFeatureAvailable 的同步/快取版判斷類別 (8.41: qpd, 8.38: hrd) */
    private static final String[] CLASS_FEATURE_AVAILABILITY_CHECKER_CANDIDATES = {"qpd", "hrd"};

    /** FeatureType enum (8.41: q16, 8.38: v26) */
    private static final String[] CLASS_FEATURE_TYPE_CANDIDATES = {"q16", "v26"};

    /** FeatureType.OFFLINE_DB（自動更新號碼資料庫功能）常數欄位 */
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
            log(Log.ERROR, TAG, "isPremium Hook 套用失敗，請確認混淆類別名稱是否變動", t);
        }

        // 兩個 Hook 各自獨立 try/catch，避免其中一個因 APK 改版失效時連帶另一個也失效
        try {
            hookAutoUpdateDatabaseFeature(param.getDefaultClassLoader());
            log(Log.INFO, TAG, "自動更新號碼資料庫 Hook 套用成功，該功能將強制視為已開通");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "自動更新號碼資料庫 Hook 套用失敗，請確認混淆類別名稱是否變動", t);
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

    private Class<?> findFirstClass(String[] candidateNames, ClassLoader classLoader) throws ClassNotFoundException {
        for (String name : candidateNames) {
            try {
                return findClass(name, classLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException("無法找到混淆類別，候選清單: " + Arrays.toString(candidateNames));
    }

    /**
     * 核心 Hook 邏輯 1：
     * 攔截 FlowCollector.emit(Object list, Continuation)，在原始清單交給判斷邏輯之前，
     * 動態塞入一筆 EntitlementStatus.PREMIUM 的假資料，讓 isPremium 永遠算出 true。
     */
    private void hookIsPremiumFlow(ClassLoader classLoader) throws Throwable {
        Class<?> flowCollectorClass = findFirstClass(CLASS_FLOW_COLLECTOR_CANDIDATES, classLoader);
        Class<?> continuationClass = findFirstClass(CLASS_CONTINUATION_CANDIDATES, classLoader);
        Class<?> entitlementClass = findFirstClass(CLASS_USER_ENTITLEMENT_CANDIDATES, classLoader);
        Class<?> statusEnumClass = findFirstClass(CLASS_ENTITLEMENT_STATUS_CANDIDATES, classLoader);

        Method emitMethod = flowCollectorClass.getDeclaredMethod("emit", Object.class, continuationClass);
        emitMethod.setAccessible(true);

        Field premiumStatusField = statusEnumClass.getDeclaredField(FIELD_STATUS_PREMIUM);
        premiumStatusField.setAccessible(true);
        Object premiumStatusValue = premiumStatusField.get(null);

        Constructor<?> entitlementConstructor = entitlementClass.getDeclaredConstructor(
                statusEnumClass, String.class, boolean.class, List.class);
        entitlementConstructor.setAccessible(true);

        hook(emitMethod).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] instanceof List) {
                List<?> originalEntitlements = (List<?>) args[0];
                List<Object> forcedEntitlements = new ArrayList<>(originalEntitlements);

                Object fakePremiumEntitlement = entitlementConstructor.newInstance(
                        premiumStatusValue, null, true, new ArrayList<Object>());
                forcedEntitlements.add(fakePremiumEntitlement);

                log(Log.INFO, TAG, "isPremium emit() 被攔截，原清單筆數=" + originalEntitlements.size() + " -> 修改後筆數=" + forcedEntitlements.size());
                args[0] = forcedEntitlements;
            }

            return chain.proceed(args);
        });
    }

    /**
     * 核心 Hook 邏輯 2：
     * 攔截 FeatureAvailabilityChecker.a(FeatureType)（isFeatureAvailable），
     * 當傳入的 featureType 是 OFFLINE_DB（自動更新號碼資料庫）時，直接強制回傳 true。
     */
    private void hookAutoUpdateDatabaseFeature(ClassLoader classLoader) throws Throwable {
        Class<?> featureCheckerClass = findFirstClass(CLASS_FEATURE_AVAILABILITY_CHECKER_CANDIDATES, classLoader);
        Class<?> featureTypeClass = findFirstClass(CLASS_FEATURE_TYPE_CANDIDATES, classLoader);

        Method isFeatureAvailableMethod = featureCheckerClass.getDeclaredMethod("a", featureTypeClass);
        isFeatureAvailableMethod.setAccessible(true);

        Field offlineDbFeatureField = featureTypeClass.getDeclaredField(FIELD_FEATURE_TYPE_OFFLINE_DB);
        offlineDbFeatureField.setAccessible(true);
        Object offlineDbFeatureValue = offlineDbFeatureField.get(null);

        hook(isFeatureAvailableMethod).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] == offlineDbFeatureValue) {
                return Boolean.TRUE;
            }

            return chain.proceed(args);
        });
    }
}
