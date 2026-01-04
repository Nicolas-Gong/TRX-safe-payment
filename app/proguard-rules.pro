# ProGuard 规则
# 保留 TRON SDK 相关类
-keep class org.tron.** { *; }
-keep class com.google.protobuf.** { *; }

# 保留应用核心安全类（不混淆，便于审计）
-keep class com.trxsafe.payment.security.** { *; }
-keep class com.trxsafe.payment.wallet.TransactionSigner { *; }
-keep class com.trxsafe.payment.wallet.WalletManager { *; }

# 保留 Kotlin 相关
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# 保留枚举类
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Reown AppKit ProGuard 规则
-keepattributes *Annotation*

-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** {
    native <methods>;
    *;
}

-keep class uniffi.** { *; }

# Preserve all public and protected fields and methods
-keepclassmembers class ** {
    public *;
    protected *;
}

-dontwarn uniffi.**
-dontwarn com.sun.jna.**
