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
 * Whoscall QA 訂閱狀態模擬與功能開通模組。
 *
 * 支援版本：Whoscall 8.41+ 與 8.38
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

    // 常數設定（混淆列舉欄位名稱）
    private static final String FIELD_STATUS_PREMIUM = "c";
    private static final String FIELD_FEATURE_TYPE_OFFLINE_DB = "b";

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

    /**
     * 核心 Hook 邏輯 1：
     * 攔截 FlowCollector.emit(Object list, Continuation)，在原始清單交給判斷邏輯之前，
     * 動態塞入一筆 EntitlementStatus.PREMIUM 的假資料，讓 isPremium 永遠算出 true。
     */
    private void hookIsPremiumFlow(ClassLoader classLoader) throws Throwable {
        Method emitMethod = null;
        Class<?> statusEnumClass = null;
        Constructor<?> entitlementConstructor = null;

        // 嘗試 8.41+ 混淆組合
        try {
            Class<?> flowCollectorClass = findClass("jg4$a", classLoader);
            Class<?> continuationClass = findClass("x43", classLoader);
            statusEnumClass = findClass("fbb", classLoader);
            Class<?> entitlementClass = findClass("dtf", classLoader);

            emitMethod = flowCollectorClass.getDeclaredMethod("emit", Object.class, continuationClass);
            entitlementConstructor = entitlementClass.getDeclaredConstructor(
                    statusEnumClass, String.class, boolean.class, List.class);
            log(Log.INFO, TAG, "檢測到 Whoscall 8.41+ 版本混淆特徵 (jg4$a / x43 / dtf / fbb)");
        } catch (Throwable ignored) {
        }

        // 若 8.41+ 失敗，嘗試 8.38 混淆組合
        if (emitMethod == null) {
            Class<?> flowCollectorClass = findClass("mh4$a", classLoader);
            Class<?> continuationClass = findClass("z63", classLoader);
            statusEnumClass = findClass("meb", classLoader);
            Class<?> entitlementClass = findClass("ztf", classLoader);

            emitMethod = flowCollectorClass.getDeclaredMethod("emit", Object.class, continuationClass);
            entitlementConstructor = entitlementClass.getDeclaredConstructor(
                    statusEnumClass, String.class, boolean.class, List.class);
            log(Log.INFO, TAG, "檢測到 Whoscall 8.38 版本混淆特徵 (mh4$a / z63 / ztf / meb)");
        }

        if (emitMethod == null || statusEnumClass == null || entitlementConstructor == null) {
            throw new ClassNotFoundException("無法找到相容的 isPremium 混淆類別名稱");
        }

        emitMethod.setAccessible(true);
        entitlementConstructor.setAccessible(true);

        Field premiumStatusField = statusEnumClass.getDeclaredField(FIELD_STATUS_PREMIUM);
        premiumStatusField.setAccessible(true);
        Object premiumStatusValue = premiumStatusField.get(null);

        final Constructor<?> finalConstructor = entitlementConstructor;
        final Object finalPremiumStatusValue = premiumStatusValue;

        hook(emitMethod).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] instanceof List) {
                List<?> originalEntitlements = (List<?>) args[0];
                List<Object> forcedEntitlements = new ArrayList<>(originalEntitlements);

                Object fakePremiumEntitlement = finalConstructor.newInstance(
                        finalPremiumStatusValue, null, true, new ArrayList<Object>());
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
        Method isFeatureAvailableMethod = null;
        Class<?> featureTypeClass = null;

        // 嘗試 8.41+ 混淆組合
        try {
            Class<?> featureCheckerClass = findClass("qpd", classLoader);
            featureTypeClass = findClass("q16", classLoader);
            isFeatureAvailableMethod = featureCheckerClass.getDeclaredMethod("a", featureTypeClass);
            log(Log.INFO, TAG, "檢測到 Whoscall 8.41+ 版本 Feature 判斷類別 (qpd / q16)");
        } catch (Throwable ignored) {
        }

        // 若 8.41+ 失敗，嘗試 8.38 混淆組合
        if (isFeatureAvailableMethod == null) {
            Class<?> featureCheckerClass = findClass("hrd", classLoader);
            featureTypeClass = findClass("v26", classLoader);
            isFeatureAvailableMethod = featureCheckerClass.getDeclaredMethod("a", featureTypeClass);
            log(Log.INFO, TAG, "檢測到 Whoscall 8.38 版本 Feature 判斷類別 (hrd / v26)");
        }

        if (isFeatureAvailableMethod == null || featureTypeClass == null) {
            throw new ClassNotFoundException("無法找到相容的 FeatureAvailabilityChecker 混淆類別名稱");
        }

        isFeatureAvailableMethod.setAccessible(true);

        Field offlineDbFeatureField = featureTypeClass.getDeclaredField(FIELD_FEATURE_TYPE_OFFLINE_DB);
        offlineDbFeatureField.setAccessible(true);
        Object finalOfflineDbFeatureValue = offlineDbFeatureField.get(null);

        hook(isFeatureAvailableMethod).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();

            if (args.length > 0 && args[0] == finalOfflineDbFeatureValue) {
                return Boolean.TRUE;
            }

            return chain.proceed(args);
        });
    }
}
