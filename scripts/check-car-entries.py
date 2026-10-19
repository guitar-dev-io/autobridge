#!/usr/bin/env python3
"""Guard that AutoBridge (plus, on the sideload flavors, AutoBridge Browser) is the only Android
Auto entry this project lists.

Android Auto lists one launcher entry per enabled car component: every CarAppService, every
CATEGORY_PROJECTION service (the unofficial SDK's CarActivityService), and every activity with the
CAR_LAUNCHER category. The sideload flavors declare five such components - AutoBridge itself,
Multi-app (Duo Screen), Desktop Browser, Bridge Web and Bridge Mirror - and the four that are not
AutoBridge are reached from inside it, so they are declared android:enabled="false" (Bridge Web
and Bridge Mirror can be listed again from Settings, at runtime, by the user). Nothing in the build notices if one of
those attributes is dropped; this does.

With no arguments it reads the source manifests and computes each flavor's merge by hand (main for
safe; main + src/projection + :duoscreen for personal and lab). Given paths to merged manifests
(app/build/intermediates/merged_manifests/<variant>/process<Variant>Manifest/AndroidManifest.xml),
it checks those instead, which is what the user actually installs.

The one other entry allowed to be listed is AutoBridge Browser (an activity-alias of BrowserActivity
in src/projection): the browser as its own car app, the way AABrowser is listed, because only a real
window on the car display gets the phone's keyboard. It is optional (the safe flavor has none).

Usage: check-car-entries.py [merged-manifest ...]
"""
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

REQUIRED = {"dev.autobridge.car.AutoBridgeCarAppService"}
ALLOWED = REQUIRED | {"dev.autobridge.browser.CarBrowserEntry"}
CAR_APP_ACTION = "androidx.car.app.CarAppService"
PROJECTION_CATEGORY = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
CAR_LAUNCHER_CATEGORY = "android.intent.category.CAR_LAUNCHER"

FLAVORS = {
    "safe": ["app/src/main/AndroidManifest.xml"],
    "personal": [
        "app/src/main/AndroidManifest.xml",
        "app/src/projection/AndroidManifest.xml",
        "duoscreen/src/main/AndroidManifest.xml",
    ],
}
FLAVORS["lab"] = FLAVORS["personal"]


def qualified(name, package="dev.autobridge"):
    return package + name if name.startswith(".") else name


def car_entries(path):
    """(component, enabled) for every component in [path] that Android Auto would list."""
    tree = ET.parse(path)
    app = tree.getroot().find("application")
    if app is None:
        return
    for node in list(app):
        if node.tag not in ("service", "activity", "activity-alias"):
            continue
        actions, categories = set(), set()
        for intent_filter in node.findall("intent-filter"):
            actions |= {a.get(ANDROID + "name") for a in intent_filter.findall("action")}
            categories |= {c.get(ANDROID + "name") for c in intent_filter.findall("category")}
        is_car = (
            (node.tag == "service" and (CAR_APP_ACTION in actions or PROJECTION_CATEGORY in categories))
            or (node.tag != "service" and CAR_LAUNCHER_CATEGORY in categories)
        )
        if is_car:
            name = qualified(node.get(ANDROID + "name"))
            yield name, node.get(ANDROID + "enabled", "true") != "false"


def check(label, paths):
    listed = sorted({name for path in paths for name, enabled in car_entries(path) if enabled})
    declared = sorted({name for path in paths for name, _ in car_entries(path)})
    extra = [name for name in listed if name not in ALLOWED]
    missing = [name for name in REQUIRED if name not in listed]
    print(f"{label}: car components {len(declared)}, listed by default: {', '.join(listed) or 'none'}")
    problems = [f"{label}: {name} is listed on Android Auto by default; declare it android:enabled=\"false\"" for name in extra]
    problems += [f"{label}: {name} (the AutoBridge entry) is not listed" for name in missing]
    return problems


def main(argv):
    problems = []
    if argv[1:]:
        for path in argv[1:]:
            problems += check(os.path.relpath(path, ROOT), [path])
    else:
        for flavor, paths in FLAVORS.items():
            problems += check(flavor, [os.path.join(ROOT, p) for p in paths])
    for problem in problems:
        print("ERROR: " + problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
