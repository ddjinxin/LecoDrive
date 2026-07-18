# Keep custom View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
# Keep GlbParser and Accessor
-keep class com.jingxin.pandrive.gl.GlbParser { *; }
-keep class com.jingxin.pandrive.gl.GlbParser$Accessor { *; }
