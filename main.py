import argparse
import sys
from pathlib import Path

# Windows fix: stitch calls './gradlew' (Linux/Mac syntax). On Windows we
# prefer the system-installed 'gradle' (no wrapper download needed), and fall
# back to 'gradlew.bat' if the system gradle is not on PATH.
# Also auto-creates local.properties with the Android SDK path so Gradle can
# find it without needing ANDROID_HOME set manually.
if sys.platform == 'win32':
    import os as _os
    import shutil as _shutil
    import subprocess as _subprocess

    def _find_android_sdk():
        """Try to locate the Android SDK on this Windows machine."""
        # Explicit env variables first
        for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
            path = _os.environ.get(var, '')
            if path and _os.path.isdir(path):
                return path
        # Common install locations (Android Studio, Unity, standalone SDK)
        local = _os.environ.get('LOCALAPPDATA', '')
        user  = _os.environ.get('USERPROFILE', '')
        candidates = [
            _os.path.join(local, 'Android', 'Sdk'),
            _os.path.join(local, 'Android', 'android-sdk'),
            _os.path.join(user,  'AppData', 'Local', 'Android', 'Sdk'),
            'C:\\Android\\Sdk',
            'D:\\Android\\Sdk',
        ]
        for p in candidates:
            if _os.path.isdir(p):
                return p
        return None

    def _find_zipalign(sdk_path):
        """Return the path to zipalign.exe inside the Android SDK build-tools."""
        import glob as _glob
        build_tools = _os.path.join(sdk_path, 'build-tools')
        if not _os.path.isdir(build_tools):
            return None
        # Pick the highest available build-tools version
        candidates = sorted(_glob.glob(_os.path.join(build_tools, '*', 'zipalign.exe')), reverse=True)
        return candidates[0] if candidates else None

    _original_check_call = _subprocess.check_call
    def _windows_check_call(cmd, *args, **kwargs):
        if isinstance(cmd, list) and cmd and cmd[0] in ('./gradlew', 'gradlew'):
            # Write local.properties with sdk.dir so AGP can find the SDK
            cwd = kwargs.get('cwd', '')
            if cwd:
                local_props = _os.path.join(str(cwd), 'local.properties')
                if not _os.path.exists(local_props):
                    sdk_path = _find_android_sdk()
                    if sdk_path:
                        # Gradle requires forward slashes in sdk.dir
                        sdk_path_fwd = sdk_path.replace('\\', '/')
                        with open(local_props, 'w') as _f:
                            _f.write(f'sdk.dir={sdk_path_fwd}\n')
                        print(f'[+] Android SDK found at: {sdk_path}')
                    else:
                        print('[!] Android SDK not found. Install Android Studio or set ANDROID_HOME.')
            # Use system gradle (avoids wrapper download) or fall back to gradlew.bat
            if _shutil.which('gradle'):
                cmd = ['gradle'] + cmd[1:]
            else:
                cmd = ['gradlew.bat'] + cmd[1:]
            kwargs['shell'] = True

        elif isinstance(cmd, list) and len(cmd) >= 3 and cmd[0] == 'java' and '-jar' in cmd and '--apks' not in cmd:
            # apktool / any java -jar call: cap heap at 1 GB so the JVM doesn't
            # exhaust native OS memory on large APKs like WhatsApp (130 MB+).
            if not any(a.startswith('-Xmx') for a in cmd):
                jar_idx = cmd.index('-jar')
                cmd = cmd[:jar_idx] + ['-Xmx1g', '-Xss256k'] + cmd[jar_idx:]

        elif isinstance(cmd, list) and len(cmd) >= 3 and cmd[0] == 'java' and '--apks' in cmd:
            # uber-apk-signer call: inject --zipAlignPath from the SDK so Windows
            # doesn't block the embedded zipalign.exe that it extracts to %TEMP%.
            if '--zipAlignPath' not in cmd:
                sdk_path = _find_android_sdk()
                if sdk_path:
                    zipalign = _find_zipalign(sdk_path)
                    if zipalign:
                        print(f'[+] Using zipalign: {zipalign}')
                        # --zipAlignPath is the correct flag for uber-apk-signer 1.2.1
                        idx = cmd.index('--apks')
                        cmd = cmd[:idx] + ['--zipAlignPath', zipalign] + cmd[idx:]

        return _original_check_call(cmd, *args, **kwargs)
    _subprocess.check_call = _windows_check_call

# apktool fix: 3.0.2 (bundled with stitch) fails to round-trip WhatsApp's
# AndroidManifest.xml. We override it with apktool 2.10.0 if present, or
# download it automatically on the first run.
import urllib.request as _urllib_request
import stitch.common as _stitch_common
import stitch.apk_utils as _stitch_apk_utils

_APKTOOL_URL = (
    'https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar'
)
_APKTOOL_LOCAL = Path(__file__).parent / 'apktool_2.10.0.jar'

if not _APKTOOL_LOCAL.exists():
    print('[+] Downloading apktool 2.10.0 (first-time setup, ~17 MB)...')
    _urllib_request.urlretrieve(_APKTOOL_URL, _APKTOOL_LOCAL)
    print('[+] apktool 2.10.0 downloaded.')

# Override the path used by every stitch function
_stitch_common.APKTOOL_PATH = _APKTOOL_LOCAL
_stitch_apk_utils.APKTOOL_PATH = _APKTOOL_LOCAL

from stitch import Stitch
from stitch.common import ExternalModule

# ---------------------------------------------------------------------------
# Bundle → single-APK merge
# ---------------------------------------------------------------------------
import zipfile as _zipfile
import subprocess as _subprocess_merge

def _is_zip_bundle(path: Path) -> bool:
    """Return True if the file is a ZIP containing .apk entries (bundle output)."""
    try:
        with _zipfile.ZipFile(path, 'r') as z:
            return any(n.endswith('.apk') for n in z.namelist())
    except Exception:
        return False


def _merge_bundle_to_single_apk(bundle_zip_path: Path, temp_path: Path) -> None:
    """
    Merge all split APKs inside bundle_zip_path into a single installable APK,
    sign it with uber-apk-signer, and replace bundle_zip_path in-place.
    """
    import os as _os_merge

    merge_dir = temp_path / '_merge_splits'
    if merge_dir.exists():
        import shutil as _shutil_merge
        _shutil_merge.rmtree(merge_dir)
    merge_dir.mkdir(parents=True)

    # 1. Extract the bundle ZIP
    with _zipfile.ZipFile(bundle_zip_path, 'r') as bz:
        apk_names = [n for n in bz.namelist() if n.endswith('.apk')]
        bz.extractall(merge_dir)

    base_apk    = merge_dir / 'base.apk'
    split_apks  = [merge_dir / n for n in apk_names if n != 'base.apk']

    print(f'[+] Merging {len(split_apks)} splits into base.apk...')

    # Files / prefixes to skip when copying from splits into base
    _SKIP_NAMES    = {'AndroidManifest.xml', 'resources.arsc'}
    _SKIP_PREFIXES = ('META-INF/',)

    merged_path = merge_dir / 'merged_unsigned.apk'

    with _zipfile.ZipFile(base_apk, 'r') as base_z:
        existing = set(base_z.namelist())
        with _zipfile.ZipFile(merged_path, 'w', _zipfile.ZIP_DEFLATED) as out_z:
            # Copy every file from base.apk
            for info in base_z.infolist():
                out_z.writestr(info, base_z.read(info.filename))

            # Inject unique files from each split (lib/, res/, assets/, dex…)
            for split in split_apks:
                print(f'[+]   Adding from {split.name}')
                with _zipfile.ZipFile(split, 'r') as sp_z:
                    for info in sp_z.infolist():
                        name = info.filename
                        if name in _SKIP_NAMES:
                            continue
                        if any(name.startswith(p) for p in _SKIP_PREFIXES):
                            continue
                        if name not in existing:
                            out_z.writestr(info, sp_z.read(name))
                            existing.add(name)

    # 2. Sign merged APK with uber-apk-signer
    print('[+] Signing merged APK...')
    uber_jar = str(_stitch_apk_utils.UBER_APK_SIGNER_PATH)
    sign_args = ['java', '-jar', uber_jar, '--allowResign', '--apks', str(merged_path)]
    if _os_merge.environ.get('KEYSTORE_PATH'):
        sign_args += ['--ks', _os_merge.environ['KEYSTORE_PATH']]
    if _os_merge.environ.get('KEY_ALIAS'):
        sign_args += ['--ksAlias', _os_merge.environ['KEY_ALIAS']]
    if _os_merge.environ.get('KEYSTORE_PASSWORD'):
        sign_args += ['--ksPass', _os_merge.environ['KEYSTORE_PASSWORD']]
    if _os_merge.environ.get('KEY_PASSWORD'):
        sign_args += ['--ksKeyPass', _os_merge.environ['KEY_PASSWORD']]
    _subprocess_merge.check_call(sign_args)

    signed_suffix = '-aligned-debugSigned.apk' if not _os_merge.environ.get('KEYSTORE_PATH') else '-aligned-signed.apk'
    signed_path = Path(str(merged_path).removesuffix('.apk') + signed_suffix)

    # 3. Replace the bundle ZIP with the single signed APK
    bundle_zip_path.unlink()
    import shutil as _shutil_merge2
    _shutil_merge2.move(str(signed_path), str(bundle_zip_path))
    # Clean up the whole temp tree so the next run doesn't hit
    # "The temp path already exists" from stitch's startup check.
    _shutil_merge2.rmtree(temp_path, ignore_errors=True)
    print(f'[+] Single APK ready: {bundle_zip_path}')


from artifactory_generator.firebase_params import FirebaseParamsFinder
from artifactory_generator.fmessage import FMessage
from artifactory_generator.dex_copier import DexCopier
from artifactory_generator.signature_finder import SignatureFinder
from artifactory_generator.decrypt_protobuf_finder import DecryptProtobufFinder
from artifactory_generator.whatsapp_plus import WhatsAppPlusFinder


def get_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK path', required=True)
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False, default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('-g', '--google-api-key', dest='api_key', help='Custom google api key', required=False,
                        default=None)
    parser.add_argument('--artifactory', dest='artifactory', help='Artifactory path', required=False,
                        default='./artifactory.json')
    parser.add_argument('--no-sign', dest='should_sign', help='Whether to sign the output APK', action='store_false',
                        required=False, default=True)
    parser.add_argument('--extra-artifacts', dest='extra_artifacts',
                        help='Extra artifact to add to the artifactory, in the format "key:value"',
                        required=False, default=[], nargs='+')
    parser.add_argument('--paywall', dest='paywall', help='Whether to add the paywall patch', required=False,
                        default=None)
    args, _ = parser.parse_known_args()
    return args


def main():
    args = get_args()
    extra_artifacts = {artifact.split(':')[0]: artifact.split(':')[1] for artifact in args.extra_artifacts}
    external_modules = [
        ExternalModule(Path(__file__).parent / './smali_generator',
                       'invoke-static {}, Lcom/smali_generator/TheAmazingPatch;->on_load()V')
    ]
    if args.paywall is not None:
        external_modules.append(ExternalModule(Path(args.paywall),
                                               'invoke-static {}, Lcom/paywall/Paywall;->on_load()V'))
    artifactory_list = [
        FMessage(args),
        DexCopier(args),
        SignatureFinder(args),
        DecryptProtobufFinder(args),
        FirebaseParamsFinder(args),
        WhatsAppPlusFinder(args)
    ]
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=artifactory_list,
            google_api_key=args.api_key,
            external_modules=external_modules,
            should_sign=args.should_sign,
            extra_artifacts=extra_artifacts,
    ) as stitch:
        stitch.patch()

    # If the output is a split-APK bundle (ZIP), merge everything into one APK.
    output_path = Path(args.output)
    if output_path.exists() and _is_zip_bundle(output_path):
        _merge_bundle_to_single_apk(output_path, Path(args.temp_path))


if __name__ == '__main__':
    main()
