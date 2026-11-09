package io.github.tehcneko.telespeed;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Last-resort hook discovery for Telegram builds whose obfuscation map is unknown.
 *
 * <p>Telegram keeps a few diagnostic strings in FileLoadOperation. DexKit finds the class that
 * contains one of those strings, then the class's no-arg methods are classified by the fields
 * they write. The actual field values are matched at runtime, so no obfuscated field names are
 * required.</p>
 */
final class GenericHookFinder {
    private static final String TAG = "TeleSpeed";

    private static final String[] ANCHORS = {
            "debug_loading: restart with small chunk",
            "debug_loading: ",
            "FileLoadOperation "
    };

    interface Callback {
        void onFound(HookSpec spec);

        void onNotFound(String reason);
    }

    static final class HookSpec {
        final String className;
        final Method updateParams;
        final Method updateByteBudget;
        final Method start;
        final List<Field> updateParamsIntFields;
        final List<Field> updateByteBudgetBooleanFields;
        final List<Field> updateByteBudgetIntFields;

        HookSpec(String className,
                 Method updateParams,
                 Method updateByteBudget,
                 Method start,
                 List<Field> updateParamsIntFields,
                 List<Field> updateByteBudgetBooleanFields,
                 List<Field> updateByteBudgetIntFields) {
            this.className = className;
            this.updateParams = updateParams;
            this.updateByteBudget = updateByteBudget;
            this.start = start;
            this.updateParamsIntFields = updateParamsIntFields;
            this.updateByteBudgetBooleanFields = updateByteBudgetBooleanFields;
            this.updateByteBudgetIntFields = updateByteBudgetIntFields;
        }
    }

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            Log.e(TAG, "Cannot load libdexkit", t);
        }
    }

    private GenericHookFinder() {
    }

    static void findAndHook(String packageName, ClassLoader classLoader, String apkPath, Callback callback) {
        new Thread(() -> {
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                ClassData classData = findTargetClass(bridge);
                if (classData == null) {
                    callback.onNotFound("FileLoadOperation class not found");
                    return;
                }

                MethodData start = findStartMethod(bridge, classData);
                if (start == null) {
                    callback.onNotFound("start method not found in " + classData.getName());
                    return;
                }

                MethodData updateParams = findUpdateParams(classData, start);
                MethodData updateByteBudget = findUpdateByteBudget(classData, start);
                if (updateParams == null) {
                    callback.onNotFound("updateParams not found in " + classData.getName());
                    return;
                }

                HookSpec spec = buildSpec(classData, start, updateParams, updateByteBudget, classLoader);
                callback.onFound(spec);
            } catch (Throwable t) {
                Log.e(TAG, "DexKit discovery failed for " + packageName, t);
                callback.onNotFound(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "TeleSpeed-DexKit").start();
    }

    private static ClassData findTargetClass(DexKitBridge bridge) {
        for (String anchor : ANCHORS) {
            ClassDataList classes = bridge.findClass(FindClass.create()
                    .searchPackages(Collections.singletonList("org.telegram.messenger"))
                    .matcher(ClassMatcher.create().usingStrings(Collections.singletonList(anchor))));

            ClassData best = null;
            for (ClassData candidate : classes) {
                // FileLoadOperation has dozens of fields; lambda/helper classes do not.
                if (candidate.getFieldCount() < 10) {
                    continue;
                }
                if (best == null || candidate.getFieldCount() > best.getFieldCount()) {
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static MethodData findStartMethod(DexKitBridge bridge, ClassData classData) {
        for (String anchor : ANCHORS) {
            MethodDataList methods = bridge.findMethod(FindMethod.create()
                    .searchInClass(Collections.singletonList(classData))
                    .matcher(MethodMatcher.create().usingStrings(Collections.singletonList(anchor))));

            MethodData fallback = null;
            for (MethodData method : methods) {
                if (method.getParamCount() == 0 && "void".equals(method.getReturnTypeName())) {
                    return method;
                }
                if (fallback == null) {
                    fallback = method;
                }
            }
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }

    private static MethodData findUpdateParams(ClassData classData, MethodData start) {
        List<MethodData> candidates = new ArrayList<>();
        for (MethodData method : classData.getMethods()) {
            if (method.getParamCount() != 0
                    || !"void".equals(method.getReturnTypeName())
                    || !method.isMethod()) {
                continue;
            }
            int writtenInts = writtenFields(method, "int").size();
            int writtenBooleans = writtenFields(method, "boolean").size();
            if (writtenInts >= 4 && writtenBooleans == 0) {
                candidates.add(method);
            }
        }
        return preferInvokedBy(start, candidates);
    }

    private static MethodData findUpdateByteBudget(ClassData classData, MethodData start) {
        List<MethodData> candidates = new ArrayList<>();
        for (MethodData method : classData.getMethods()) {
            if (method.getParamCount() != 0
                    || !"void".equals(method.getReturnTypeName())
                    || !method.isMethod()) {
                continue;
            }
            int writtenInts = writtenFields(method, "int").size();
            int writtenBooleans = writtenFields(method, "boolean").size();
            if (writtenInts >= 1 && writtenBooleans == 1) {
                candidates.add(method);
            }
        }
        return preferInvokedBy(start, candidates);
    }

    private static MethodData preferInvokedBy(MethodData start, List<MethodData> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        MethodDataList invokes = start.getInvokes();
        for (MethodData candidate : candidates) {
            for (MethodData invoke : invokes) {
                if (candidate.getDescriptor().equals(invoke.getDescriptor())) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private static List<FieldData> writtenFields(MethodData method, String typeName) {
        List<FieldData> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UsingFieldData usingField : method.getUsingFields()) {
            if (usingField.getUsingType() != FieldUsingType.Write) {
                continue;
            }
            FieldData field = usingField.getField();
            if (typeName.equals(field.getTypeName()) && seen.add(field.getDescriptor())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static HookSpec buildSpec(ClassData classData,
                                      MethodData start,
                                      MethodData updateParams,
                                      MethodData updateByteBudget,
                                      ClassLoader classLoader) throws Exception {
        Method startMethod = start.getMethodInstance(classLoader);
        Method updateParamsMethod = updateParams == null ? null : updateParams.getMethodInstance(classLoader);
        Method updateByteBudgetMethod = updateByteBudget == null ? null : updateByteBudget.getMethodInstance(classLoader);

        List<FieldData> paramFieldData = writtenFields(updateParams, "int");
        List<FieldData> byteBooleanFieldData = updateByteBudget == null
                ? Collections.emptyList()
                : writtenFields(updateByteBudget, "boolean");
        List<FieldData> byteIntFieldData = updateByteBudget == null
                ? Collections.emptyList()
                : writtenFields(updateByteBudget, "int");

        return new HookSpec(
                classData.getName(),
                updateParamsMethod,
                updateByteBudgetMethod,
                startMethod,
                toFields(paramFieldData, classLoader),
                toFields(byteBooleanFieldData, classLoader),
                toFields(byteIntFieldData, classLoader)
        );
    }

    private static List<Field> toFields(List<FieldData> fieldData, ClassLoader classLoader) {
        List<Field> fields = new ArrayList<>();
        for (FieldData data : fieldData) {
            try {
                Field field = data.getFieldInstance(classLoader);
                field.setAccessible(true);
                fields.add(field);
            } catch (Throwable t) {
                Log.w(TAG, "Cannot resolve field " + data.getFieldName(), t);
            }
        }
        return fields;
    }
}
