#!/usr/bin/env python3
"""
diagnose.py — Analiza un APK y reporta todo lo que Android chequea en la instalación.

Uso:
    python diagnose.py PatchedWhatsApp.apk
"""
import sys
import zipfile
from pathlib import Path


def check_compression(zf: zipfile.ZipFile):
    print("\n=== Compresión de libs nativas ===")
    so_files = [i for i in zf.infolist() if i.filename.endswith('.so')]
    if not so_files:
        print("  [!] No hay archivos .so en el APK")
        return
    compressed = [i for i in so_files if i.compress_type != 0]
    stored    = [i for i in so_files if i.compress_type == 0]
    print(f"  Stored (sin compresión, OK):     {len(stored)}")
    print(f"  Deflated (comprimidos, PROBLEMA): {len(compressed)}")
    if compressed:
        for i in compressed[:5]:
            print(f"    - {i.filename}")
        if len(compressed) > 5:
            print(f"    ... y {len(compressed)-5} más")
    # Check page alignment for stored libs
    unaligned = []
    for info in stored:
        # The actual file offset in the ZIP (local header size + filename + extra)
        # We can approximate it from header_offset + 30 + len(filename) + len(extra)
        local_header_size = 30 + len(info.filename.encode()) + len(info.extra)
        data_offset = info.header_offset + local_header_size
        if data_offset % 4096 != 0:
            unaligned.append((info.filename, data_offset))
    if unaligned:
        print(f"\n  [!] PROBLEMA: {len(unaligned)} .so NO están alineados a 4096 bytes (page-alignment):")
        for name, off in unaligned[:5]:
            print(f"    - {name}  (offset {off}, mod4096={off%4096})")
        if len(unaligned) > 5:
            print(f"    ... y {len(unaligned)-5} más")
        print("      → Si extractNativeLibs=false, Android 13+ rechaza el APK al instalar.")
    else:
        print("  Todos los .so almacenados están correctamente alineados a 4096 bytes ✓")


def check_abis(zf: zipfile.ZipFile):
    print("\n=== ABIs presentes ===")
    names = zf.namelist()
    libs = [n for n in names if n.startswith('lib/') and '/' in n[4:]]
    abis = sorted(set(n.split('/')[1] for n in libs if n.count('/') >= 2))
    if abis:
        for abi in abis:
            abi_libs = [n for n in libs if n.startswith(f'lib/{abi}/')]
            print(f"  {abi}: {len(abi_libs)} archivos")
    else:
        print("  [!] No hay directorio lib/ — el APK no tiene código nativo")


def check_manifest(zf: zipfile.ZipFile):
    print("\n=== Manifest (vía androguard) ===")
    try:
        from androguard.core.apk import APK
        import tempfile, shutil, os
        tmp = tempfile.mkdtemp()
        tmp_apk = os.path.join(tmp, 'tmp.apk')
        try:
            shutil.copy(apk_path, tmp_apk)
            a = APK(tmp_apk)
            print(f"  Package:          {a.get_package()}")
            print(f"  Version name:     {a.get_androidversion_name()}")
            print(f"  Version code:     {a.get_androidversion_code()}")
            print(f"  minSdkVersion:    {a.get_min_sdk_version()}")
            print(f"  targetSdkVersion: {a.get_target_sdk_version()}")

            # Check extractNativeLibs
            app = a.get_android_manifest_xml().find('application')
            if app is not None:
                enl = app.get('{http://schemas.android.com/apk/res/android}extractNativeLibs')
                print(f"  extractNativeLibs: {enl!r}  {'✓ true = sin restricción de page-align' if enl == 'true' else '[!] false = .so deben estar page-aligned (4096B)' if enl == 'false' else '(no declarado, default=true en SDK<23, false en SDK>=23)'}")

            # Check split-related attributes
            manifest_root = a.get_android_manifest_xml()
            split_attrs = ['isSplitRequired', 'splitName', 'featureSplit', 'requiredSplitTypes', 'splitTypes']
            found_splits = []
            for attr in split_attrs:
                val = manifest_root.get(f'{{http://schemas.android.com/apk/res/android}}{attr}')
                if val is not None:
                    found_splits.append(f"{attr}={val!r}")
            if found_splits:
                print(f"  [!] ATRIBUTOS DE SPLITS PRESENTES: {', '.join(found_splits)}")
            else:
                print("  Sin atributos de split ✓")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    except ImportError:
        print("  [!] androguard no instalado — ejecutá: pip install androguard")
    except Exception as e:
        print(f"  [!] Error parseando manifest: {e}")


def check_signature(zf: zipfile.ZipFile):
    print("\n=== Firma ===")
    names = zf.namelist()
    meta = [n for n in names if n.startswith('META-INF/')]
    v1_sigs = [n for n in meta if n.endswith(('.RSA', '.DSA', '.EC', '.SF'))]
    has_v1 = bool(v1_sigs)
    # V2/V3 can't be checked from ZipFile alone
    print(f"  V1 (JAR signing):  {'sí — ' + str(v1_sigs) if has_v1 else 'no'}")
    print("  V2/V3: no verificable sin apksigner")
    if has_v1:
        print("  V1 presente ✓ (necesario para Android < 7)")


def main(path: str):
    global apk_path
    apk_path = path
    p = Path(path)
    if not p.exists():
        print(f"[ERROR] Archivo no encontrado: {path}")
        sys.exit(1)

    print(f"Analizando: {p.name}  ({p.stat().st_size / 1024 / 1024:.1f} MB)")

    try:
        zf = zipfile.ZipFile(path, 'r')
    except zipfile.BadZipFile:
        print("[ERROR] El archivo no es un ZIP/APK válido")
        sys.exit(1)

    with zf:
        check_manifest(zf)
        check_abis(zf)
        check_compression(zf)
        check_signature(zf)

    print("\n=== Resumen ===")
    print("Si todos los puntos muestran ✓, el problema puede ser del teléfono o de ADB.")
    print("Si ves algún [!], ese es el motivo del error de instalación.")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Uso: python diagnose.py <ruta_al_apk>")
        sys.exit(1)
    main(sys.argv[1])
