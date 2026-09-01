using Microsoft.Win32;

namespace AirMate;

/// <summary>
/// Whether AirMate starts with Windows.
/// </summary>
/// <remarks>
/// The per-user Run key rather than a scheduled task or a service: it needs no elevation, the user
/// can see it in Task Manager's Startup tab and turn it off there, and removing the value is the
/// whole uninstall. A switch here can therefore disagree with reality, so the state is read back
/// rather than remembered.
/// </remarks>
internal static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "AirMate";

    public static bool IsEnabled
    {
        get
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKey);
                return key?.GetValue(ValueName) is string value && value.Length > 0;
            }
            catch { return false; }
        }
    }

    /// <summary>Returns the state actually in force afterwards, which is not always the one asked for.</summary>
    public static bool Set(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true)
                ?? Registry.CurrentUser.CreateSubKey(RunKey);
            if (key is null) return IsEnabled;

            if (enabled)
            {
                var path = Environment.ProcessPath;
                if (string.IsNullOrEmpty(path)) return false;
                // Quoted: Program Files has a space in it and an unquoted path there starts a
                // different program.
                key.SetValue(ValueName, $"\"{path}\"");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch
        {
            // A locked-down machine may refuse the write. Report what is true, not what was asked.
        }
        return IsEnabled;
    }
}

/// <summary>The handful of things this client remembers between runs.</summary>
internal sealed class AppSettings
{
    private const string Key = @"Software\AirMate";

    public bool Onboarded
    {
        get => Read("Onboarded") == "1";
        set => Write("Onboarded", value ? "1" : "0");
    }

    /// <summary>The display AirMate captures, by its device name.</summary>
    /// <remarks>Stored by name rather than by index: indices shuffle when a monitor is unplugged,
    /// and silently capturing a different screen than last time is the worst outcome available.</remarks>
    public string SelectedDisplay
    {
        get => Read("Display") ?? string.Empty;
        set => Write("Display", value);
    }

    private static string? Read(string name)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(Key);
            return key?.GetValue(name) as string;
        }
        catch { return null; }
    }

    private static void Write(string name, string value)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(Key);
            key?.SetValue(name, value);
        }
        catch { }
    }
}
