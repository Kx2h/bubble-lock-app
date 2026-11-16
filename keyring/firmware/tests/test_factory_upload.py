"""Regression for the real factory/NVS overlap in Arduino uploader defaults."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEMP_ROOT = ROOT.parents[3] / ".local/tmp/firmware-upload-tests"
SPEC = importlib.util.spec_from_file_location("factory_upload", ROOT / "scripts/factory_upload.py")
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class Environment(dict):
    def subst(self, value):
        return {"$PARTITIONS_TABLE_CSV": str(ROOT / "partitions.csv"),
                "$ESP32_APP_OFFSET": self.get("offset", "0x20000"),
                "$UPLOAD_PROTOCOL": self.get("protocol", "esptool"),
                "$BUILD_DIR/${PROGNAME}.bin": self.get("image", "firmware.bin")}.get(value, value)
    def BoardConfig(self): return {"build.mcu": "esp32s3"}
    def Replace(self, **values): self.update(values)
    def AddPreAction(self, target, action): self["guard"] = action


def candidate():
    return Environment(FLASH_EXTRA_IMAGES=[("0x0000", "build/bootloader.bin"),
                                           ("0x8000", "build/partitions.bin"),
                                           ("0xe000", "/framework/tools/partitions/boot_app0.bin")],
                       UPLOADERFLAGS=["--chip", "esp32s3", "write_flash", "-z", "0x0000", "build/bootloader.bin",
                                      "0x8000", "build/partitions.bin", "0xe000", "/framework/tools/partitions/boot_app0.bin"])


def temporary_directory():
    TEMP_ROOT.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(dir=TEMP_ROOT)


def built_candidate(directory):
    directory = Path(directory)
    for filename, size in (("bootloader.bin", 0x4000), ("partitions.bin", 0xC00), ("firmware.bin", 0x2000)):
        (directory / filename).write_bytes(b"\xff" * size)
    env = candidate()
    env["FLASH_EXTRA_IMAGES"] = [(offset, str(directory / POLICY.image_name(path)))
                                  for offset, path in env["FLASH_EXTRA_IMAGES"]]
    env["image"] = str(directory / "firmware.bin")
    env["PIOENV"] = "keyring_r18_bringup"
    POLICY.configure(env)
    return env


class FactoryUploadTests(unittest.TestCase):
    def test_prior_arduino_extra_writes_are_removed_from_both_actual_inputs(self):
        env = candidate(); POLICY.configure(env)
        self.assertEqual([int(offset, 0) for offset, _ in env["FLASH_EXTRA_IMAGES"]], [0, 0x8000])
        self.assertNotIn("0xe000", env["UPLOADERFLAGS"])
        self.assertFalse(any("boot_app0.bin" in str(item) for item in env["UPLOADERFLAGS"]))
        self.assertTrue(callable(env["guard"]))

    def test_previous_default_app_address_is_rejected_before_upload(self):
        env = candidate(); env["offset"] = "0x10000"
        with self.assertRaisesRegex(ValueError, "factory partition"): POLICY.configure(env)

    def test_unknown_extra_flash_write_is_rejected(self):
        env = candidate(); env["FLASH_EXTRA_IMAGES"].append(("0x9000", "fresh-nvs.bin"))
        with self.assertRaisesRegex(ValueError, "retained device state"): POLICY.configure(env)

    def test_incompatible_upload_backend_is_rejected(self):
        env = candidate(); env["protocol"] = "esp-builtin"
        with self.assertRaisesRegex(ValueError, "esptool"): POLICY.configure(env)

    def test_compile_only_profile_is_rejected_before_any_image_or_device_access(self):
        env = candidate(); POLICY.configure(env)
        env["PIOENV"] = "keyring_compile_minimal"
        with self.assertRaisesRegex(ValueError, "per-device provisioning"): env["guard"]([], [], env)

    def test_final_write_ranges_preserve_nvs_and_fit_factory(self):
        with temporary_directory() as directory:
            env = built_candidate(directory)
            env["guard"]([], [], env)

    def test_actual_extra_image_length_cannot_cross_into_nvs(self):
        with temporary_directory() as directory:
            env = built_candidate(directory)
            (Path(directory) / "partitions.bin").write_bytes(b"\xff" * 0x1001)
            with self.assertRaisesRegex(ValueError, "overlaps retained NVS"): env["guard"]([], [], env)

    def test_oversized_actual_app_is_rejected(self):
        with temporary_directory() as directory:
            env = built_candidate(directory)
            with (Path(directory) / "firmware.bin").open("wb") as image:
                image.seek(0x600000); image.write(b"\xff")
            with self.assertRaisesRegex(ValueError, "factory partition"): env["guard"]([], [], env)

    def test_real_table_identifies_app_and_retained_nvs(self):
        app, nvs = POLICY.factory_layout(ROOT / "partitions.csv")
        self.assertEqual((app[3], app[4]), (0x20000, 0x600000))
        self.assertEqual((nvs[3], nvs[4]), (0x9000, 0x10000))
        self.assertLess(nvs[3], 0xE000)
        self.assertLess(0xE000, nvs[3] + nvs[4])

    def test_ota_layout_requires_its_own_policy(self):
        with temporary_directory() as directory:
            path = Path(directory) / "partitions.csv"
            path.write_text("nvs,data,nvs,0x9000,0x5000,\notadata,data,ota,0xe000,0x2000,\napp0,app,ota_0,0x10000,1M,\n")
            with self.assertRaisesRegex(ValueError, "factory application"): POLICY.factory_layout(path)


if __name__ == "__main__": unittest.main()
