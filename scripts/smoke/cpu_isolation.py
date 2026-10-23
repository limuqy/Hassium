"""Benchmark process affinity; topology is shared with the PowerShell smoke runner."""
import ctypes
import json
import os
from pathlib import Path
import subprocess


def cpu_plan(policy="Auto", server_ccd=0, client_ccd=1):
    if os.name != "nt":
        if policy == "Required":
            raise RuntimeError("CPU isolation currently requires Windows")
        return {"policy": policy, "enabled": False, "reason": "Windows required", "server": None, "client": None}
    helper = Path(__file__).with_name("describe-cpu-isolation.ps1")
    result = subprocess.run(["pwsh", "-File", str(helper), "-Policy", policy,
                             "-ServerCcd", str(server_ccd), "-ClientCcd", str(client_ccd)],
                            capture_output=True, text=True, encoding="utf-8", check=True,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    return json.loads(result.stdout.lstrip("\ufeff"))


def _kernel():
    native = ctypes.WinDLL("kernel32", use_last_error=True)
    native.GetCurrentProcess.restype = ctypes.c_void_p
    native.GetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
    native.GetProcessAffinityMask.restype = ctypes.c_int
    native.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    native.SetProcessAffinityMask.restype = ctypes.c_int
    return native


def _mask(native, handle):
    process, system = ctypes.c_size_t(), ctypes.c_size_t()
    if not native.GetProcessAffinityMask(handle, ctypes.byref(process), ctypes.byref(system)):
        raise ctypes.WinError(ctypes.get_last_error())
    return process.value


def affinity(process):
    return _mask(_kernel(), int(process._handle))


def popen_pinned(command, domain=None, **kwargs):
    if domain is None:
        return subprocess.Popen(command, **kwargs)
    native = _kernel()
    parent = native.GetCurrentProcess()
    previous = _mask(native, parent)
    mask = int(domain["mask"], 16)
    if not native.SetProcessAffinityMask(parent, mask):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        process = subprocess.Popen(command, **kwargs)
    finally:
        if not native.SetProcessAffinityMask(parent, previous):
            raise ctypes.WinError(ctypes.get_last_error())
    return process


def verify_affinity(process, domain):
    if domain is None:
        return {"pid": process.pid, "verified": False, "reason": "isolation disabled"}
    actual = affinity(process)
    if actual != int(domain["mask"], 16):
        raise RuntimeError(f"PID {process.pid} affinity {actual:#x} differs from {domain['mask']}")
    return {"pid": process.pid, "mask": f"0x{actual:X}", "verified": True}
