using Vortice.DXGI;

namespace AirMate.Capture;

/// <param name="AdapterIndex">Which adapter owns the output.</param>
/// <param name="OutputIndex">Which output on that adapter.</param>
/// <param name="DeviceName">The stable identifier, e.g. <c>\\.\DISPLAY1</c>.</param>
/// <param name="Label">What to show a person.</param>
internal readonly record struct DisplayTarget(
    int AdapterIndex,
    int OutputIndex,
    string DeviceName,
    string Label,
    int Width,
    int Height);

/// <summary>Every display Windows can hand us, including virtual ones.</summary>
/// <remarks>
/// A display added by an indirect display driver appears here exactly like a physical one, which
/// is the whole reason this list is a picker rather than a fixed choice: install such a driver and
/// AirMate mirrors that instead of a real screen, and the tablet becomes a second monitor rather
/// than a copy of a first.
/// </remarks>
internal static class DisplayEnumerator
{
    public static IReadOnlyList<DisplayTarget> Enumerate()
    {
        var targets = new List<DisplayTarget>();
        try
        {
            using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            for (uint adapterIndex = 0; factory.EnumAdapters1(adapterIndex, out var adapter).Success; adapterIndex++)
            {
                using (adapter)
                {
                    for (uint outputIndex = 0; adapter.EnumOutputs(outputIndex, out var output).Success; outputIndex++)
                    {
                        using (output)
                        {
                            var description = output.Description;
                            var bounds = description.DesktopCoordinates;
                            int width = bounds.Right - bounds.Left;
                            int height = bounds.Bottom - bounds.Top;
                            if (width <= 0 || height <= 0) continue;

                            var name = description.DeviceName ?? $"Display {targets.Count + 1}";
                            targets.Add(new DisplayTarget(
                                (int)adapterIndex,
                                (int)outputIndex,
                                name,
                                $"{Trim(name)} · {width} × {height}",
                                width,
                                height));
                        }
                    }
                }
            }
        }
        catch
        {
            // No adapters we can talk to. The caller shows a failure rather than an empty picker.
        }
        return targets;
    }

    /// <summary>`\\.\DISPLAY1` is what Windows calls it; `DISPLAY1` is what a person reads.</summary>
    private static string Trim(string deviceName)
    {
        var trimmed = deviceName.TrimStart('\\', '.');
        return trimmed.Length > 0 ? trimmed : deviceName;
    }
}
