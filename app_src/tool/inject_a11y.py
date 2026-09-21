"""Register Lumi's accessibility service in the Flutter-generated manifest.

`flutter create` scaffolds android/ on every CI run, so the <service> element
cannot live in a committed manifest. This script is idempotent and fails loudly
if the generated manifest has drifted into a shape it cannot handle.
"""

import pathlib
import sys

MANIFEST = pathlib.Path("android/app/src/main/AndroidManifest.xml")

SERVICE = """
    <service
        android:name=".LumiAccessibilityService"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
        android:exported="true"
        android:label="Lumi 屏幕操控">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_service_config" />
    </service>
"""


def main() -> int:
    if not MANIFEST.exists():
        print(f"error: {MANIFEST} not found", file=sys.stderr)
        return 1

    text = MANIFEST.read_text(encoding="utf-8")
    if "LumiAccessibilityService" in text:
        print("accessibility service already registered")
        return 0
    if "</application>" not in text:
        print("error: manifest has no </application> tag", file=sys.stderr)
        return 1

    MANIFEST.write_text(text.replace("</application>", SERVICE + "    </application>", 1), encoding="utf-8")
    print("accessibility service registered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
