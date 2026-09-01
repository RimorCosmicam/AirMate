using System.Diagnostics;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace AirMate.Capture;

/// <summary>One captured desktop frame, still on the GPU.</summary>
internal readonly record struct CapturedFrame(ID3D11Texture2D Texture, ulong Id, ulong CaptureNanos);

/// <summary>
/// Mirrors one display with DXGI Desktop Duplication.
/// </summary>
/// <remarks>
/// Desktop Duplication rather than Windows.Graphics.Capture because this half of AirMate mirrors a
/// display it did not create, which is precisely what Duplication is for: it is per-output, it
/// hands back a Direct3D texture with no conversion, and it needs no WinRT interop at all.
///
/// The frame never leaves the GPU on the way to the encoder, so there is no bitmap, no readback
/// and no allocation per frame in the normal path — the same rule the macOS host holds to.
/// </remarks>
internal sealed class DuplicationCapture : IDisposable
{
    private readonly ID3D11Device device;
    private readonly ID3D11DeviceContext context;
    private readonly IDXGIOutputDuplication duplication;
    private readonly CancellationTokenSource cancellation = new();
    private readonly Action<CapturedFrame> onFrame;
    private ulong nextFrameId = 1;

    public int Width { get; }
    public int Height { get; }
    public ID3D11Device Device => device;

    private DuplicationCapture(
        ID3D11Device device,
        ID3D11DeviceContext context,
        IDXGIOutputDuplication duplication,
        int width,
        int height,
        Action<CapturedFrame> onFrame)
    {
        this.device = device;
        this.context = context;
        this.duplication = duplication;
        this.onFrame = onFrame;
        Width = width;
        Height = height;
    }

    /// <summary>Throws with a readable message rather than an HRESULT, so the window can show it.</summary>
    public static DuplicationCapture Start(DisplayTarget target, Action<CapturedFrame> onFrame)
    {
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        if (factory.EnumAdapters1((uint)target.AdapterIndex, out var adapter).Failure)
            throw new InvalidOperationException("That display's graphics adapter is no longer present.");

        using (adapter)
        {
            if (adapter.EnumOutputs((uint)target.OutputIndex, out var output).Failure)
                throw new InvalidOperationException("That display is no longer connected.");

            using (output)
            {
                // Typed rather than `var`: the two overloads differ only in their out parameters,
                // so inference cannot pick one.
                ID3D11Device device;
                ID3D11DeviceContext context;
                var result = D3D11.D3D11CreateDevice(
                    adapter,
                    DriverType.Unknown,
                    DeviceCreationFlags.BgraSupport,
                    new[] { FeatureLevel.Level_11_1, FeatureLevel.Level_11_0, FeatureLevel.Level_10_1 },
                    out device,
                    out context);
                if (result.Failure || device is null || context is null)
                    throw new InvalidOperationException("Direct3D 11 is unavailable on this adapter.");

                using var output1 = output.QueryInterface<IDXGIOutput1>();
                IDXGIOutputDuplication duplication;
                try
                {
                    duplication = output1.DuplicateOutput(device);
                }
                catch (SharpGen.Runtime.SharpGenException error)
                {
                    device.Dispose();
                    context.Dispose();
                    // The usual cause is another application already duplicating this output, and
                    // Windows allows only one at a time per output.
                    throw new InvalidOperationException(
                        "Windows would not hand over this display. Another screen-sharing or recording app may already be capturing it.",
                        error);
                }

                var bounds = output.Description.DesktopCoordinates;
                var capture = new DuplicationCapture(
                    device, context, duplication,
                    bounds.Right - bounds.Left,
                    bounds.Bottom - bounds.Top,
                    onFrame);
                var thread = new Thread(capture.Loop) { IsBackground = true, Name = "AirMate.Capture" };
                thread.Priority = ThreadPriority.AboveNormal;
                thread.Start();
                return capture;
            }
        }
    }

    private void Loop()
    {
        var token = cancellation.Token;
        while (!token.IsCancellationRequested)
        {
            IDXGIResource? resource = null;
            try
            {
                // 100ms rather than infinite so a still desktop still checks the cancellation flag.
                var result = duplication.AcquireNextFrame(100, out _, out resource);
                if (result.Failure || resource is null) continue;

                using var texture = resource.QueryInterface<ID3D11Texture2D>();
                var id = nextFrameId++;
                onFrame(new CapturedFrame(
                    texture,
                    id,
                    (ulong)(Stopwatch.GetTimestamp() * (1_000_000_000.0 / Stopwatch.Frequency))));
            }
            catch (SharpGen.Runtime.SharpGenException)
            {
                // A mode change or a lost device ends duplication; the window's own health check
                // restarts capture rather than this loop spinning on a dead object.
                break;
            }
            finally
            {
                resource?.Dispose();
                try { duplication.ReleaseFrame(); } catch (SharpGen.Runtime.SharpGenException) { }
            }
        }
    }

    public void Dispose()
    {
        cancellation.Cancel();
        duplication.Dispose();
        context.Dispose();
        device.Dispose();
        cancellation.Dispose();
    }
}
