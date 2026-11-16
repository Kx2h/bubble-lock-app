"""Keep factory-only uploads outside the retained NVS partition.

Arduino's ESP32 uploader otherwise adds boot_app0.bin at 0xe000 and defaults
the application to 0x10000. Both addresses fall inside this project's NVS.
"""
from pathlib import Path
import csv


def address(value):
    text = str(value).strip()
    suffix = text[-1:].upper()
    if suffix in ("K", "M"):
        return int(text[:-1], 0) * (1024 if suffix == "K" else 1024 * 1024)
    return int(text, 0)


def factory_layout(path):
    rows = []
    with Path(path).open(encoding="utf-8") as stream:
        for row in csv.reader(stream):
            if not row or row[0].lstrip().startswith("#"):
                continue
            if len(row) < 5 or not row[3].strip() or not row[4].strip():
                raise ValueError("Factory upload requires explicit partition offsets and sizes")
            name, kind, subtype = (item.strip() for item in row[:3])
            rows.append((name, kind, subtype, address(row[3]), address(row[4])))
    apps = [row for row in rows if row[1] in ("0", "app")]
    if len(apps) != 1 or apps[0][2] not in ("0", "factory"):
        raise ValueError("This upload policy requires exactly one factory application, without OTA")
    if any(row[1] in ("1", "data") and row[2] in ("0", "ota") for row in rows):
        raise ValueError("OTA metadata must use a separate upload policy")
    nvs = [row for row in rows if row[1] in ("1", "data") and row[2] in ("2", "nvs")]
    if len(nvs) != 1:
        raise ValueError("Expected one retained NVS partition")
    for left in rows:
        if left[3] < 0x9000 or left[4] <= 0:
            raise ValueError("Partition occupies boot metadata or has invalid size")
        for right in rows:
            if left is not right and max(left[3], right[3]) < min(left[3] + left[4], right[3] + right[4]):
                raise ValueError("Partition ranges overlap")
    return apps[0], nvs[0]


def image_name(value):
    return str(value).replace("\\", "/").rsplit("/", 1)[-1]


def remove_ota_image(images, flags):
    retained = []
    for offset, path in images:
        if image_name(path) == "boot_app0.bin":
            if address(offset) != 0xE000:
                raise ValueError("Unexpected OTA helper offset")
            continue
        retained.append((offset, path))
    cleaned = []
    index = 0
    while index < len(flags):
        if index + 1 < len(flags) and image_name(flags[index + 1]) == "boot_app0.bin":
            if address(flags[index]) != 0xE000:
                raise ValueError("Unexpected OTA helper uploader arguments")
            index += 2
            continue
        if "boot_app0.bin" in str(flags[index]):
            raise ValueError("Unsupported embedded OTA helper command")
        cleaned.append(flags[index])
        index += 1
    return retained, cleaned


def configure(environment):
    app, nvs = factory_layout(environment.subst("$PARTITIONS_TABLE_CSV"))
    if address(environment.subst("$ESP32_APP_OFFSET")) != app[3]:
        raise ValueError("Upload application offset differs from the factory partition")
    if environment.subst("$UPLOAD_PROTOCOL") != "esptool":
        raise ValueError("Factory/NVS-preserving uploads currently require esptool")
    extras, flags = remove_ota_image(environment.get("FLASH_EXTRA_IMAGES", []), environment.get("UPLOADERFLAGS", []))
    boot_offset = 0x1000 if environment.BoardConfig().get("build.mcu") in ("esp32", "esp32s2") else 0
    expected = {boot_offset: "bootloader.bin", 0x8000: "partitions.bin"}
    if len(extras) != 2 or any(address(offset) not in expected or image_name(path) != expected[address(offset)] for offset, path in extras):
        raise ValueError("Unexpected extra flash image; refuse to modify retained device state")
    environment.Replace(FLASH_EXTRA_IMAGES=extras, UPLOADERFLAGS=flags)

    def before_upload(source, target, env):
        # Run after build dependencies exist, immediately before serial upload.
        if str(env.get("PIOENV", "")).startswith("keyring_compile_"):
            raise ValueError("Compile-only profiles do not contain per-device provisioning")
        if address(env.subst("$ESP32_APP_OFFSET")) != app[3]:
            raise ValueError("Application offset changed after factory upload validation")
        for offset, path in env.get("FLASH_EXTRA_IMAGES", []):
            start = address(offset)
            end = start + Path(env.subst(str(path))).stat().st_size
            if max(start, nvs[3]) < min(end, nvs[3] + nvs[4]):
                raise ValueError("Flash image overlaps retained NVS")
        image = Path(env.subst("$BUILD_DIR/${PROGNAME}.bin"))
        if not image.is_file() or not 0 < image.stat().st_size <= app[4]:
            raise ValueError("Application image does not fit the factory partition")

    environment.AddPreAction("upload", before_upload)


if "Import" in globals():
    Import("env")
    configure(env)
