using System.Drawing.Text;
using System.Runtime.InteropServices;

namespace AirMate;

/// <summary>Mont, carried inside the executable.</summary>
/// <remarks>
/// Installed fonts cannot be relied on and a private collection costs nothing, so all three
/// AirMate clients ship the same files and none is typeset by whatever happens to be on the
/// machine. The pinned bytes must outlive the collection that was handed their address, so they
/// are held for the life of the process rather than left to be collected — let them move and the
/// font vanishes mid-run.
/// </remarks>
internal static class MontFont
{
    private static readonly PrivateFontCollection Collection = new();
    private static readonly List<GCHandle> Pinned = [];

    static MontFont()
    {
        foreach (var name in new[] { "mont_thin.ttf", "mont_black.ttf" })
        {
            try
            {
                using var stream = typeof(MontFont).Assembly.GetManifestResourceStream(name);
                if (stream is null) continue;
                var bytes = new byte[stream.Length];
                stream.ReadExactly(bytes);
                var handle = GCHandle.Alloc(bytes, GCHandleType.Pinned);
                Pinned.Add(handle);
                Collection.AddMemoryFont(handle.AddrOfPinnedObject(), bytes.Length);
            }
            catch
            {
                // A missing weight falls back below rather than taking the window down with it.
            }
        }
    }

    public static Font Thin(float size) => Make("Mont Thin", size);
    public static Font Black(float size) => Make("Mont Black", size);

    private static Font Make(string name, float size)
    {
        var family = Collection.Families.FirstOrDefault(f => f.Name == name);
        if (family is not null)
        {
            try { return new Font(family, size, FontStyle.Regular, GraphicsUnit.Point); }
            catch { }
        }
        var fallback = (SystemFonts.MessageBoxFont ?? SystemFonts.DefaultFont).FontFamily;
        return new Font(fallback, size, name.EndsWith("Black") ? FontStyle.Bold : FontStyle.Regular);
    }
}

/// <summary>White carries all the hierarchy, through opacity alone.</summary>
internal static class Mont
{
    public static readonly Color Ground = Color.Black;
    public static readonly Color Active = Color.FromArgb(255, 255, 255, 255);
    public static readonly Color Primary = Color.FromArgb(235, 255, 255, 255);
    public static readonly Color Detail = Color.FromArgb(158, 255, 255, 255);
    public static readonly Color Dim = Color.FromArgb(148, 255, 255, 255);
    public static readonly Color Disabled = Color.FromArgb(89, 255, 255, 255);
    public static readonly Color Track = Color.FromArgb(23, 255, 255, 255);

    public static readonly Color Mustard = Color.FromArgb(0xD8, 0xA6, 0x28);
    public static readonly Color Live = Color.FromArgb(0x2E, 0x9E, 0x5B);
    public static readonly Color Danger = Color.FromArgb(0xC0, 0x39, 0x2B);

    /// <summary>Text hangs off a generous left margin and nothing needs the right one.</summary>
    public const int PadLeft = 22;
    public const int PadRight = 14;
}
