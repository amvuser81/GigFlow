# Accessibility services are instantiated by the system — keep entry points.
-keep class app.shiftlit.driver.ShiftlitAccessibilityService { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
