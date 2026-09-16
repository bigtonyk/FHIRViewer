# Check for any running Java processes with FHIRViewer in the command line.
# Also enumerate visible windows to find the FHIRViewer window.
$ErrorActionPreference = 'SilentlyContinue'

Write-Host "=== Running Java processes ==="
Get-Process -Name java, javaw | ForEach-Object {
    $proc = $_
    try {
        $cmd = (Get-WmiObject Win32_Process -Filter "ProcessId = $($proc.Id)").CommandLine
        if ($cmd -and ($cmd -like '*FHIRViewer*' -or $cmd -like '*fhirviewer*' -or $cmd -like '*Main*')) {
            Write-Output ("PID=" + $proc.Id + " Cmd=" + $cmd)
        }
    } catch {}
}

Write-Host ""
Write-Host "=== Visible JavaFX windows (Win32 enumeration) ==="
Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public class VisibleWindowFinder {
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc enumProc, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern int GetWindowThreadProcessId(IntPtr hWnd, out int processId);

    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
    
    [DllImport("user32.dll")]
    public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    public static void FindVisibleJavaFXWindows(int targetPid) {
        var sb = new StringBuilder(256);
        var sbClass = new StringBuilder(256);
        bool found = false;
        EnumWindows((hWnd, lParam) => {
            int pid;
            GetWindowThreadProcessId(hWnd, out pid);
            if (pid == targetPid && IsWindowVisible(hWnd)) {
                int len = GetWindowText(hWnd, sb, sb.Capacity);
                string title = sb.ToString();
                int classLen = GetClassName(hWnd, sbClass, sbClass.Capacity);
                string className = sbClass.ToString();
                Console.WriteLine("PID=" + pid + " Title='" + title + "' Class='" + className + "' Handle=" + hWnd);
                found = true;
            }
            return true;
        }, IntPtr.Zero);
        if (!found) {
            Console.WriteLine("No visible windows found for PID " + targetPid);
        }
    }
}
"@

[VisibleWindowFinder]::FindVisibleJavaFXWindows(18368)
