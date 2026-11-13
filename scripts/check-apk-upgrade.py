"""Comprueba una actualización real conservando tinta y ajustes; solo en emulador."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request


PACKAGE = "net.micapum.neopen"
PROBE = "net.micapum.neopen.app.UpgradeStorageProbeTest"
BASELINE_URL = "https://github.com/borborborja/neopen-companion/releases/download/v0.26.0/neopen-debug.apk"
BASELINE_SHA256 = "e2779e506914dc4e089fc26b17b01864c3ca273bcabfdc5a29486a7deb6d0306"


def run(command, *, timeout=180):
    result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Falló {command[0]} (exit={result.returncode}):\n{result.stdout}\n{result.stderr}")
    return result.stdout


def find_aapt(explicit):
    if explicit:
        return explicit
    if shutil.which("aapt"):
        return shutil.which("aapt")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        versions = sorted((Path(sdk) / "build-tools").glob("*/aapt"))
        if versions:
            return str(versions[-1])
    raise RuntimeError("Indica --aapt o configura ANDROID_HOME con Android build-tools.")


def apk_info(aapt, apk, *, versioned=True):
    badging = run([aapt, "dump", "badging", str(apk)])
    line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    values = dict(re.findall(r"(\w+)='([^']*)'", line))
    if "name" not in values or (versioned and not all(values.get(key) for key in ("versionCode", "versionName"))):
        raise RuntimeError(f"No se pudo leer el manifest de {apk}.")
    values["versionCode"] = int(values.get("versionCode") or 0)
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Serial explícito del emulador; nunca selecciona un teléfono automáticamente.")
    parser.add_argument("--candidate-apk", required=True, type=Path)
    parser.add_argument("--baseline-test-apk", required=True, type=Path, help="APK androidTest debug actual, sin minificar, para sembrar datos en v0.26.0.")
    parser.add_argument("--test-apk", required=True, type=Path, help="APK androidTest de la variante github con UpgradeStorageProbeTest.")
    parser.add_argument("--baseline-apk", type=Path, help="APK v0.26.0; si se omite se descarga el asset público.")
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--aapt")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    if run(adb + ["shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
        raise RuntimeError("Se rechaza la prueba: el dispositivo no es un emulador Android.")
    for apk in (args.candidate_apk, args.baseline_test_apk, args.test_apk):
        if not apk.is_file():
            raise RuntimeError(f"APK no encontrado: {apk}")
    output = args.output_dir or Path(tempfile.mkdtemp(prefix="neopen-upgrade-"))
    output.mkdir(parents=True, exist_ok=True)
    baseline = args.baseline_apk
    if baseline is None:
        baseline = output / "baseline-v0.26.0.apk"
        request = urllib.request.Request(BASELINE_URL, headers={"User-Agent": "neopen-upgrade-check"})
        with urllib.request.urlopen(request, timeout=60) as response:
            with baseline.open("wb") as target:
                shutil.copyfileobj(response, target)
        if hashlib.sha256(baseline.read_bytes()).hexdigest() != BASELINE_SHA256:
            raise RuntimeError("El APK descargado no coincide con el checksum verificado de v0.26.0.")
    aapt = find_aapt(args.aapt)
    old = apk_info(aapt, baseline)
    new = apk_info(aapt, args.candidate_apk)
    baseline_test = apk_info(aapt, args.baseline_test_apk, versioned=False)
    test = apk_info(aapt, args.test_apk, versioned=False)
    if old["name"] != PACKAGE or old["versionName"] != "0.26.0":
        raise RuntimeError("El baseline debe ser neopen v0.26.0.")
    if new["name"] != PACKAGE or new["versionCode"] < old["versionCode"]:
        raise RuntimeError("El candidato debe conservar applicationId y no reducir versionCode.")
    if any(info["name"] != PACKAGE + ".test" for info in (baseline_test, test)):
        raise RuntimeError("Los dos APK de instrumentación deben corresponder a neopen.")
    installed = run(adb + ["shell", "dumpsys", "package", PACKAGE])
    existing = re.search(r"\bversionCode=(\d+)", installed)
    if existing and int(existing.group(1)) > old["versionCode"]:
        raise RuntimeError("Se rechaza instalar el baseline sobre una versión superior; no se permiten downgrades.")

    def install(apk, label):
        result = run(adb + ["install", "-r", str(apk)])
        (output / f"install-{label}.log").write_text(result)
        if not re.search(r"(?m)^Success\s*$", result):
            raise RuntimeError(f"La instalación de {label} no terminó con Success.")

    def probe(stage):
        result = run(adb + [
            "shell", "am", "instrument", "-w", "-r", "-e", "class", PROBE,
            "-e", "upgradeStage", stage,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
        ])
        (output / f"probe-{stage}.log").write_text(result)
        errors = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "Process crashed", "shortMsg=")
        if any(error in result for error in errors) or not re.search(r"OK \(1 test\)", result):
            raise RuntimeError(f"El probe {stage} falló; consulta {output / f'probe-{stage}.log'}.")

    install(baseline, "baseline")
    # Cada runner debe enlazar con las clases de su propio APK destino: el mapa
    # R8 del test github no es compatible con las dependencias del baseline debug.
    install(args.baseline_test_apk, "baseline-test")
    probe("seed")
    install(args.candidate_apk, "candidate")
    install(args.test_apk, "candidate-test")
    probe("verify")
    result = {
        "serial": args.serial,
        "baseline": old,
        "candidate": new,
        "baselineSha256": hashlib.sha256(baseline.read_bytes()).hexdigest(),
        "candidateSha256": hashlib.sha256(args.candidate_apk.read_bytes()).hexdigest(),
        "inkPreserved": True,
        "encryptedSettingsPreserved": True,
        "output": str(output.resolve()),
    }
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired, OSError) as error:
        raise SystemExit(str(error)) from error
