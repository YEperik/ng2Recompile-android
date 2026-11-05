#!/usr/bin/env python3
"""Every `external fun` in NativeBridge.kt must have a JNI function of the same name with matching
parameter and return types in app/src/main/cpp/ng2_jni*.cpp, and vice versa."""
import pathlib, re, sys
root = pathlib.Path(__file__).resolve().parent.parent
kt = (root / "app/src/main/java/com/ng2/dawn/NativeBridge.kt").read_text()
M = {"Int": "jint", "Float": "jfloat", "Boolean": "jboolean", "String": "jstring", "Surface?": "jobject", "LongArray": "jlongArray"}
R = {"Int": "jint", "Boolean": "jboolean", "String": "jstring", "LongArray": "jlongArray", None: "void"}
kf = {}
for m in re.finditer(r"external fun (native\w+)\(([^)]*)\)\s*(?::\s*([\w?\.]+))?", kt, re.S):
    n, ps, r = m.groups()
    kf[n] = ([M[p.split(":")[1].strip().replace("android.view.", "")] for p in ps.split(",") if p.strip()], R[r])
cpp = "".join((root / "app/src/main/cpp" / f).read_text() for f in ("ng2_jni.cpp", "ng2_jni_input.cpp"))
cf = {m.group(2): ([re.sub(r"\s+", " ", p.strip()).split(" ")[0] for p in m.group(3).split(",")][2:], m.group(1))
      for m in re.finditer(r"JNIEXPORT\s+(\w+)\s+JNICALL\s+Java_com_ng2_dawn_NativeBridge_(\w+)\s*\(([^)]*)\)", cpp, re.S)}
bad = [n for n in sorted(set(kf) | set(cf)) if kf.get(n) != cf.get(n)]
for n in bad:
    print("MISMATCH", n, "kotlin:", kf.get(n), "c++:", cf.get(n))
print(f"JNI: {len(kf)} Kotlin natives, {len(cf)} C++ natives, {len(bad)} mismatches")
sys.exit(1 if bad else 0)
