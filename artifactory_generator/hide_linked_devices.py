import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder


class HideLinkedDevicesFinder(SimpleArtifactoryFinder):
    """Locate the method that returns the list rendered by the "Linked devices"
    screen so it can be hooked to return an empty list.

    Anchoring notes:
      * ``com.whatsapp.companiondevice`` is a real (feature) package, so the
        class name survives obfuscation even though method names do not.
      * The screen's list accessor is a ``public`` method returning
        ``java.util.List``. We prefer the no-argument getter (the accessor the
        adapter reads); if none exists we fall back to the first List method.

    IMPORTANT: this only targets the UI list accessor. It does NOT touch the
    companion-device data used for end-to-end encryption/message routing.
    """

    LIST_METHOD_RE = re.compile(
        r'\.method public (?:final |static |bridge |synthetic )*'
        r'(?P<method_name>\w+)(?P<sig>\((?P<params>[^)]*)\)Ljava/util/List;)'
    )

    # Own class-name extractor. The shared CLASS_NAME_RE uses a greedy ``.*L``
    # which mis-parses real (non-obfuscated) names that contain capital "L"
    # (e.g. LinkedDevicesSharedViewModel). This anchors on the first ``L`` after
    # the .class modifiers and stops at the terminating ``;``.
    CLASS_LINE_RE = re.compile(r'\.class[^\n]*?\sL(?P<name>[\w/$]+);')

    # Only inspect classes in the (non-obfuscated) companion-device package.
    COMPANION_PACKAGE = 'Lcom/whatsapp/companiondevice/'
    # Preferred class: the shared view model that backs the linked-devices list.
    PREFERRED_CLASS = 'Lcom/whatsapp/companiondevice/LinkedDevicesSharedViewModel;'

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        # Fast path: the well-known shared view model.
        if self.PREFERRED_CLASS in class_data:
            return True
        # Fallback: any companion-device class that exposes a List method and
        # mentions "device" in a string literal (helps when the class is renamed
        # across versions). Kept conservative to avoid false positives.
        if self.COMPANION_PACKAGE not in class_data:
            return False
        return 'Ljava/util/List;' in class_data and 'companiondevice' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.LIST_METHOD_RE.finditer(class_data))
        if not matches:
            return
        # Prefer a no-argument getter; otherwise take the first List method.
        chosen = next((m for m in matches if m.groupdict().get('params') == ''), matches[0])

        class_match = self.CLASS_LINE_RE.search(class_data)
        if class_match is None:
            return
        class_name = class_match.groupdict().get('name').replace('/', '.')
        artifacts['LINKED_DEVICES_CLASS_NAME'] = class_name
        artifacts['LINKED_DEVICES_METHOD_NAME'] = chosen.groupdict().get('method_name')
        artifacts['LINKED_DEVICES_METHOD_SIG'] = chosen.groupdict().get('sig')
        print(
            '[+] HideLinkedDevices: hooking '
            f"{class_name}.{artifacts['LINKED_DEVICES_METHOD_NAME']}"
            f"{artifacts['LINKED_DEVICES_METHOD_SIG']}"
        )
        self.is_found = True
