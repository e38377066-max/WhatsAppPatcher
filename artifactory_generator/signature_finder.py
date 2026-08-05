import glob

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs7
from pathlib import Path
from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder
from stitch.common import EXTRACTED_PATH


class SignatureFinder(SimpleArtifactoryFinder):

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return True

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        bytes_signature = None

        # ── Strategy 1: v1 JAR signing (.DSA / .RSA / .EC in META-INF) ──────
        meta_inf = Path(self.args.temp_path) / EXTRACTED_PATH / 'unknown' / 'META-INF'
        for ext in ('*.DSA', '*.RSA', '*.EC'):
            matches = glob.glob(str(meta_inf / ext))
            if matches:
                print(f'[+] Found v1 signature: {matches[0]}')
                with open(matches[0], 'rb') as f:
                    raw = f.read()
                der_cert = pkcs7.load_der_pkcs7_certificates(raw)[0]
                bytes_signature = der_cert.public_bytes(serialization.Encoding.DER)
                break

        # ── Strategy 2: v2/v3 APK Signature Block via androguard ─────────────
        if bytes_signature is None:
            print('[+] No v1 signature found; reading certificate from APK signing block (v2/v3)...')
            from androguard.core.apk import APK as _AndroAPK
            from stitch.common import BUNDLE_APK_EXTRACTED_PATH

            # For APKM bundles the signed base.apk sits in temp/bundle/base.apk;
            # for plain APKs it's the input file itself.
            bundle_base = Path(self.args.temp_path) / BUNDLE_APK_EXTRACTED_PATH / 'base.apk'
            apk_src = str(bundle_base) if bundle_base.exists() else str(self.args.apk_path)

            apk_obj = _AndroAPK(apk_src)

            # androguard 4.x exposes v3 certs first, then v2.
            # get_certificates() / get_certificates_v3() return
            # cryptography.x509.Certificate objects.
            cert = None
            for getter in ('get_certificates_v3', 'get_certificates_v2', 'get_certificates'):
                fn = getattr(apk_obj, getter, None)
                if fn is None:
                    continue
                try:
                    certs = fn()
                    if certs:
                        cert = certs[0]
                        break
                except Exception:
                    continue

            if cert is None:
                raise RuntimeError(
                    f'Could not extract APK signing certificate from {apk_src}. '
                    'The APK may be unsigned or use an unsupported signing scheme.'
                )

            bytes_signature = cert.public_bytes(serialization.Encoding.DER)

        # Convert DER bytes → lowercase hex string (what Android expects)
        artifacts['PACKAGE_SIGNATURE'] = ''.join(f'{b:02x}' for b in bytes_signature)
        self.is_found = True
