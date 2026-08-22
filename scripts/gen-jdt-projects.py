#!/usr/bin/env python3
"""Generate plain Eclipse .project/.classpath per module so jdtls can run WITHOUT
Gradle import, with a classpath pinned to the active MC version.

Inputs : build/jdt-cp/<module>.txt + <module>.java-version  (from jdt-dump-cp.init.gradle)
         build.properties                                   (MC_VER / MC_<v> symbols)
Writes : <module>/.project, <module>/.classpath             (only when content changes)

Usage:  python scripts/gen-jdt-projects.py [--cp-dir build/jdt-cp]

Version switch flow:
    gradlew.bat -Pmc_ver=<ver> --init-script scripts/jdt-dump-cp.init.gradle dumpAllJdtCp
    python scripts/gen-jdt-projects.py
    (then restart the LSP chain)
"""
from __future__ import annotations

import argparse
import sys
import xml.sax.saxutils as sax
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULES = ["common", "fabric", "forge", "neoforge"]
SRC_DIRS = [("src/main/java", "bin/main"), ("src/test/java", "bin/test")]


def read_symbols() -> dict[str, str]:
    props = REPO / "build.properties"
    sym: dict[str, str] = {}
    for line in props.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        k, _, v = t.partition("=")
        sym[k.strip()] = v.strip()
    return sym


def active_version(sym: dict[str, str]) -> str:
    idx = sym.get("MC_VER")
    if idx is None:
        sys.exit("MC_VER missing from build.properties")
    for k, v in sym.items():
        if k != "MC_VER" and k.startswith("MC_") and v == idx:
            return k[3:].replace("_", ".")
    sys.exit(f"no MC_* symbol matches index {idx}")


def xml_escape(s: str) -> str:
    return sax.escape(s, {'"': "&quot;"})


def project_xml(name: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
	<name>Hassium-{name}</name>
	<comment></comment>
	<projects>
	</projects>
	<buildSpec>
		<buildCommand>
			<name>org.eclipse.jdt.core.javabuilder</name>
			<arguments>
			</arguments>
		</buildCommand>
	</buildSpec>
	<natures>
		<nature>org.eclipse.jdt.core.javanature</nature>
	</natures>
</projectDescription>
"""


def classpath_xml(java_ver: str,
                  src_entries: list[tuple[str, str]],
                  project_deps: list[str],
                  jars: list[str]) -> str:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', "<classpath>"]
    for path, out in src_entries:
        lines.append(f'\t<classpathentry kind="src" output="{out}" path="{path}"/>')
    # Inter-project deps as source-folder references (shared access to their classes).
    for dep in project_deps:
        lines.append(
            f'\t<classpathentry combineaccessrules="false" kind="src" path="/Hassium-{dep}"/>')
    lines.append(
        f'\t<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/'
        f'org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-{java_ver}/"/>')
    for jar in jars:
        lines.append(f'\t<classpathentry kind="lib" path="{xml_escape(jar)}"/>')
    lines.append("</classpath>")
    return "\n".join(lines) + "\n"


def write_if_changed(path: Path, content: str) -> bool:
    if path.exists() and path.read_text(encoding="utf-8") == content:
        return False
    path.write_text(content, encoding="utf-8")
    return True


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--cp-dir", default="build/jdt-cp")
    args = ap.parse_args()
    cp_dir = REPO / args.cp_dir

    sym = read_symbols()
    ver = active_version(sym)
    print(f"active MC version: {ver}")

    ver_dir = ver.replace(".", "_")
    module_classes = {
        m: (REPO / m / f"build/{ver_dir}/classes/java/main").resolve()
        for m in MODULES
        if (REPO / m / f"build/{ver_dir}/classes/java/main").is_dir()
    }

    changed = 0
    for mod in MODULES:
        dump = cp_dir / f"{mod}.txt"
        if not dump.exists():
            # Stale Eclipse files would make jdtls import a dead project (e.g. forge at
            # versions where the loader is sunset); remove them so the module is ignored.
            removed = False
            for stale in (REPO / mod / ".project", REPO / mod / ".classpath"):
                if stale.exists():
                    stale.unlink()
                    removed = True
            print(f"{mod}: no dump — stale project files {'removed' if removed else 'absent'}")
            continue
        entries = [e.strip() for e in dump.read_text(encoding="utf-8").splitlines() if e.strip()]
        java_ver = (cp_dir / f"{mod}.java-version").read_text(encoding="utf-8").strip()
        src_entries = [(s, o) for s, o in SRC_DIRS if (REPO / mod / s).is_dir()]
        # Inter-project deps appear in the resolved classpath as sibling build/classes dirs.
        deps = sorted(
            other for other, cls_dir in module_classes.items()
            if other != mod and str(cls_dir) in entries
        )

        jars = [e for e in entries if e.lower().endswith(".jar")]

        p_changed = write_if_changed(REPO / mod / ".project", project_xml(mod))
        c_changed = write_if_changed(
            REPO / mod / ".classpath",
            classpath_xml(java_ver, src_entries, deps, jars),
        )
        changed += int(p_changed) + int(c_changed)
        print(f"{mod}: java={java_ver} src={len(src_entries)} deps={deps} jars={len(jars)}"
              + (" [updated]" if (p_changed or c_changed) else ""))
    print(f"{'updated' if changed else 'unchanged'}: {changed} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
