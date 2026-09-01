using System.Runtime.InteropServices;
using AirMate.Capture;
using AirMate.Protocol;

namespace AirMate.Input;

/// <summary>
/// Reading mode: taps and scrolls on the tablet, as a pointer on this PC.
/// </summary>
/// <remarks>
/// The pointer is borrowed rather than taken. A tap on the second screen moves the cursor there,
/// clicks, and puts it back exactly where it was, so the tablet behaves like a page being read
/// rather than a second mouse fighting the first. Without the restore, turning a page on the tablet
/// would drag the cursor off whatever is being worked on.
/// </remarks>
internal static partial class PointerInput
{
    private static POINT? restore;
    private static bool scrolling;

    public static void Click(ushort x, ushort y, DisplayTarget target)
    {
        if (!GetCursorPos(out var origin)) return;
        var point = Map(x, y, target);
        SetCursorPos(point.X, point.Y);
        mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
        mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
        SetCursorPos(origin.X, origin.Y);
    }

    public static void Scroll(ControlCommand command, DisplayTarget target)
    {
        switch (command.Phase)
        {
            case ScrollPhase.Begin:
                if (!GetCursorPos(out var origin)) return;
                // Saved once, at the start: warping on every delta of a flick would make the cursor
                // strobe between two screens for the length of the gesture.
                restore = origin;
                scrolling = true;
                var point = Map(command.X, command.Y, target);
                SetCursorPos(point.X, point.Y);
                break;

            case ScrollPhase.Continue:
                if (!scrolling) return;
                Wheel(command.Dx, command.Dy);
                break;

            case ScrollPhase.End:
                if (command.Dx != 0 || command.Dy != 0) Wheel(command.Dx, command.Dy);
                Reset();
                break;
        }
    }

    /// <summary>Drops a half-finished gesture, so a client that vanishes cannot strand the cursor.</summary>
    public static void Reset()
    {
        if (restore is { } origin) SetCursorPos(origin.X, origin.Y);
        restore = null;
        scrolling = false;
    }

    private static void Wheel(short dx, short dy)
    {
        // Windows counts a wheel notch as 120, and a drag downward moves the page down, the way
        // touching paper would.
        if (dy != 0) mouse_event(MOUSEEVENTF_WHEEL, 0, 0, dy * 3, UIntPtr.Zero);
        if (dx != 0) mouse_event(MOUSEEVENTF_HWHEEL, 0, 0, dx * 3, UIntPtr.Zero);
    }

    /// <summary>Normalised in, desktop coordinates out, against the display being sent.</summary>
    private static POINT Map(ushort x, ushort y, DisplayTarget target) => new()
    {
        X = target.Left + (int)((long)x * target.Width / ushort.MaxValue),
        Y = target.Top + (int)((long)y * target.Height / ushort.MaxValue)
    };

    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_HWHEEL = 0x1000;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetCursorPos(out POINT point);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetCursorPos(int x, int y);

    [LibraryImport("user32.dll")]
    private static partial void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);
}
