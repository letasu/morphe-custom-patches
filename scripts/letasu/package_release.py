"""Stage only the installable bundle and Manager metadata, excluding sources/docs."""
from pathlib import Path
import json
import shutil
import zipfile

from metadata import ROOT, validate, validate_patch_list


data = json.loads((ROOT / "patches-bundle.json").read_text(encoding="utf-8"))
validate(data)
validate_patch_list(json.loads((ROOT / "patches-list.json").read_text(encoding="utf-8")), data["version"])
bundle = ROOT / "patches/build/libs" / f"patches-{data['version']}.mpp"
with zipfile.ZipFile(bundle) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode()
    if f"Version: {data['version']}" not in manifest or "Name: Letasu Patches" not in manifest:
        raise ValueError("Unexpected bundle manifest")
    names = archive.namelist()
    for class_name in ("SmartAudioFocusPatchKt.class", "HideMembersVideosPatchKt.class"):
        if not any(name.endswith("/" + class_name) for name in names):
            raise ValueError(f"Missing custom patch class: {class_name}")
output = ROOT / "build/distribution"
output.mkdir(parents=True, exist_ok=True)
if any(output.iterdir()):
    raise ValueError("Use a fresh build/distribution directory to avoid stale release files")
for source in (bundle, ROOT / "patches-bundle.json", ROOT / "patches-list.json"):
    shutil.copy2(source, output / source.name)
print(f"Staged installable bundle {bundle.name} and Manager metadata")
