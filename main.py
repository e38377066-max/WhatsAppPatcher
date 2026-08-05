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


if __name__ == '__main__':
    main()
