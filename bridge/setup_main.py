"""
Entry point for the V2Xtend Setup executable (its-g5-setup.exe).

Runs the setup wizard; if the user checks "Bridge automatisch starten",
launches its-g5-bridge.exe from the same directory afterwards.
"""
import os
import subprocess
import sys


def main():
    from setup_wizard import run_wizard
    result = run_wizard()
    if result is None:
        sys.exit(0)

    if result.get("auto_start"):
        exe_dir = os.path.dirname(
            sys.executable if getattr(sys, "frozen", False)
            else os.path.abspath(__file__)
        )
        bridge = os.path.join(exe_dir, "its-g5-bridge.exe")
        if not os.path.isfile(bridge):
            bridge = os.path.join(exe_dir, "its-g5-bridge")

        # Pass all settings explicitly — config file may not be readable yet
        # on the very first launch (race condition / path issues).
        cmd = [bridge, "--open-browser",
               "--node-id", result["node_id"]]
        if result.get("port"):
            cmd += ["--port", result["port"]]
        for url in result.get("brokers", []):
            cmd += ["--broker", url]

        try:
            subprocess.Popen(cmd)
        except Exception as exc:
            # tkinter root is already destroyed — use Windows MessageBox directly
            import ctypes
            ctypes.windll.user32.MessageBoxW(
                0,
                f"Could not launch its-g5-bridge.exe:\n{exc}\n\n"
                f"Make sure both EXE files are in the same folder:\n{exe_dir}",
                "V2Xtend — Launch failed",
                0x30,  # MB_ICONWARNING | MB_OK
            )


if __name__ == "__main__":
    main()
