# Native OCR registers Java methods and resolves class/member names directly via JNI.
# Tesseract4Android 4.9.0 does not bundle consumer rules.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
