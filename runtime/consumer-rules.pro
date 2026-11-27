# Точки входа runtime вызываются из патченного хоста по имени и через manifest.
-keep class dev.moduled.runtime.RuntimeBootstrap { *; }
# Модули загружаются рефлективно по entrypoint из манифеста.
-keep interface dev.moduled.api.** { *; }
-keep class * implements dev.moduled.api.TModule { <init>(); }
