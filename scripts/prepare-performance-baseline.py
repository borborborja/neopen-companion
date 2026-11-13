#!/usr/bin/env python3
"""Prepara un worktree histórico con el mismo harness aislado, sin aplicar optimizaciones de la app."""
import argparse
from pathlib import Path
import shutil
import subprocess


def block(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    return source[start:cursor]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="Directorio nuevo para el worktree de referencia")
    parser.add_argument("--ref", default="v0.26.0")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    target = args.path.resolve()
    if target.exists():
        raise SystemExit(f"El destino ya existe: {target}")
    subprocess.run(["git", "worktree", "add", "--detach", str(target), args.ref], cwd=root, check=True)
    for path in ("benchmark", "neopen/src/benchmark"):
        shutil.copytree(root / path, target / path, ignore=shutil.ignore_patterns("build", ".gradle"))
    shutil.copy2(root / "neopen/proguard-rules.pro", target / "neopen/proguard-rules.pro")
    p = target / "build.gradle.kts"
    source = p.read_text()
    if 'id("com.android.test")' not in source:
        source = source.replace('    id("com.android.library") version "8.5.2" apply false',
                                '    id("com.android.library") version "8.5.2" apply false\n    id("com.android.test") version "8.5.2" apply false')
    p.write_text(source)
    p = target / "settings.gradle.kts"
    p.write_text(p.read_text() + '\nif (providers.gradleProperty("performance").isPresent && !providers.gradleProperty("fdroid").isPresent) include(":benchmark")\n')
    p = target / "neopen/build.gradle.kts"
    source = p.read_text()
    current = (root / "neopen/build.gradle.kts").read_text()
    additions = []
    if 'create("github")' not in source:
        additions.append('        ' + block(current, 'create("github")'))
    if 'create("benchmark")' not in source:
        additions.append('        if (providers.gradleProperty("performance").isPresent && !providers.gradleProperty("fdroid").isPresent) {\n            ' + block(current, 'create("benchmark")') + '\n        }')
    marker = "    }\n    compileOptions {"
    if marker not in source:
        raise SystemExit("No se reconoce buildTypes de la referencia; revisa el worktree preparado.")
    source = source.replace(marker, "\n".join(additions) + "\n" + marker, 1)
    end = source.rfind("}")
    source = source[:end] + '    if (providers.gradleProperty("performance").isPresent && !providers.gradleProperty("fdroid").isPresent) {\n        add("benchmarkImplementation", "androidx.profileinstaller:profileinstaller:1.3.1")\n    }\n' + source[end:]
    p.write_text(source)
    # La referencia original no informa TTFD. Se añade únicamente instrumentación:
    # las fixtures no están vacías, por lo que la primera lista no vacía marca la carga local.
    p = target / "neopen/src/main/java/net/micapum/neopen/app/ui/Ui.kt"
    source = p.read_text()
    if "import androidx.activity.compose.ReportDrawnWhen" not in source:
        source = source.replace("package net.micapum.neopen.app.ui\n", "package net.micapum.neopen.app.ui\n\nimport androidx.activity.compose.ReportDrawnWhen\n", 1)
    for line, predicate in (
        ("    val items by vm.recent.collectAsStateWithLifecycle()", "items.isNotEmpty()"),
        ("    val pages by vm.capturesIn(notebookId).collectAsStateWithLifecycle(initialValue = emptyList())", "pages.isNotEmpty()"),
    ):
        if line in source:
            source = source.replace(line, line + "\n    ReportDrawnWhen { " + predicate + " }", 1)
    p.write_text(source)
    # El SDK es una configuración local del equipo, no forma parte de la referencia.
    if (root / "local.properties").exists():
        shutil.copy2(root / "local.properties", target / "local.properties")
    print(f"Referencia {args.ref} preparada en {target}; solo harness, distribución y señal TTFD añadidos.")


if __name__ == "__main__":
    main()
