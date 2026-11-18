# 沐禾健身 release 混淆规则（2026-11-18 从占位转正：release 已开 minify+shrink 且 versionName
# 1.0.1 已申报——规则空置属出货风险面。第三方库自带 consumer 规则的（kotlinx-serialization 1.7+、
# Retrofit 2.11 自带 retrofit2.pro、OkHttp 4.12）不在此重复；以下只补本项目自留面。
# ⚠️ 首次 assembleRelease 后必须真机冒烟：配对→拉档→推送 + 扫码两条链路。

# ── 反射面通用属性（Retrofit 泛型签名 / 注解处理） ──
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, AnnotationDefault

# ── kotlinx-serialization：本项目 @Serializable DTO（net/ + viewmodel/ 的契约 DTO） ──
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers @kotlinx.serialization.Serializable class com.muhe.fit.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.muhe.fit.app.**$$serializer { *; }

# ── zxing core（打卡/配对扫码）：纯算法库无反射，通常无需 keep；留位防 R8 full mode 误裁 ──
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── Room schema 导出目录与 assets 静态包不受混淆影响，无需规则 ──
