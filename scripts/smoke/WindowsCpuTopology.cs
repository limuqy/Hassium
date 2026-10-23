using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

// Windows x64 CACHE_RELATIONSHIP, including the legacy single GroupMask layout.
// L3 sharing domains are CCD boundaries on the project's Ryzen test machine.
// https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-cache_relationship
public static class SmokeWindowsCpuTopology
{
    public sealed class Domain
    {
        public ushort Group;
        public ulong Mask;
        public uint CacheBytes;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetLogicalProcessorInformationEx(int relationship, IntPtr buffer, ref uint length);

    [DllImport("kernel32.dll")]
    private static extern ushort GetActiveProcessorGroupCount();

    public static Domain[] ReadL3Domains()
    {
        if (!Environment.Is64BitProcess || GetActiveProcessorGroupCount() != 1)
            throw new NotSupportedException("CPU isolation currently requires Windows x64 with one processor group.");
        uint length = 0;
        if (GetLogicalProcessorInformationEx(2, IntPtr.Zero, ref length) || Marshal.GetLastWin32Error() != 122)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr buffer = Marshal.AllocHGlobal(checked((int)length));
        try
        {
            if (!GetLogicalProcessorInformationEx(2, buffer, ref length))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            var domains = new List<Domain>();
            for (int offset = 0; offset < length;)
            {
                IntPtr item = IntPtr.Add(buffer, offset);
                int size = Marshal.ReadInt32(item, 4);
                if (size < 8 || size > length - offset)
                    throw new InvalidOperationException("Invalid processor topology record.");
                if (Marshal.ReadInt32(item) == 2 && size >= 56 && Marshal.ReadByte(item, 8) == 3)
                {
                    int groups = (ushort)Marshal.ReadInt16(item, 38);
                    if (groups > 1 || Marshal.ReadInt16(item, 48) != 0)
                        throw new NotSupportedException("L3 domain spans unsupported processor groups.");
                    ulong mask = unchecked((ulong)Marshal.ReadInt64(item, 40));
                    if (mask != 0 && !domains.Exists(d => d.Mask == mask))
                        domains.Add(new Domain { Group = 0, Mask = mask, CacheBytes = unchecked((uint)Marshal.ReadInt32(item, 12)) });
                }
                offset += size;
            }
            domains.Sort((left, right) => left.Mask.CompareTo(right.Mask));
            return domains.ToArray();
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }
}
