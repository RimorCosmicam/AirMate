using Vortice.Direct3D11;
using Vortice.DXGI;

namespace AirMate.Encode;

/// <summary>
/// BGRA to NV12, on the GPU.
/// </summary>
/// <remarks>
/// Desktop Duplication hands back BGRA; every hardware H.264 encoder wants NV12. Doing that
/// conversion on the CPU would mean reading a 1080p frame back off the GPU sixty times a second,
/// which is precisely the kind of per-frame copy the rest of AirMate is built to avoid. The
/// Direct3D video processor does it in place, on the same device the encoder already holds.
/// </remarks>
internal sealed class ColourConverter : IDisposable
{
    private readonly ID3D11VideoDevice videoDevice;
    private readonly ID3D11VideoContext videoContext;
    private readonly ID3D11VideoProcessor processor;
    private readonly ID3D11VideoProcessorEnumerator enumerator;
    private readonly ID3D11Texture2D output;
    private readonly ID3D11VideoProcessorOutputView outputView;
    private readonly int width;
    private readonly int height;

    public ID3D11Texture2D Output => output;

    public ColourConverter(ID3D11Device device, ID3D11DeviceContext context, int width, int height)
    {
        this.width = width;
        this.height = height;

        videoDevice = device.QueryInterface<ID3D11VideoDevice>();
        videoContext = context.QueryInterface<ID3D11VideoContext>();

        var description = new VideoProcessorContentDescription
        {
            InputFrameFormat = VideoFrameFormat.Progressive,
            InputWidth = (uint)width,
            InputHeight = (uint)height,
            OutputWidth = (uint)width,
            OutputHeight = (uint)height,
            Usage = VideoUsage.PlaybackNormal
        };
        enumerator = videoDevice.CreateVideoProcessorEnumerator(description);
        processor = videoDevice.CreateVideoProcessor(enumerator, 0);

        output = device.CreateTexture2D(new Texture2DDescription
        {
            Width = (uint)width,
            Height = (uint)height,
            MipLevels = 1,
            ArraySize = 1,
            Format = Format.NV12,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Default,
            BindFlags = BindFlags.RenderTarget,
            CPUAccessFlags = CpuAccessFlags.None,
            MiscFlags = ResourceOptionFlags.None
        });

        outputView = videoDevice.CreateVideoProcessorOutputView(
            output, enumerator, new VideoProcessorOutputViewDescription { ViewDimension = VideoProcessorOutputViewDimension.Texture2D });
    }

    /// <summary>Converts one BGRA frame into the shared NV12 texture.</summary>
    public void Convert(ID3D11Texture2D source)
    {
        using var inputView = videoDevice.CreateVideoProcessorInputView(
            source,
            enumerator,
            new VideoProcessorInputViewDescription { ViewDimension = VideoProcessorInputViewDimension.Texture2D });

        var stream = new VideoProcessorStream
        {
            Enable = true,
            InputSurface = inputView
        };
        videoContext.VideoProcessorBlt(processor, outputView, 0, 1, new[] { stream });
    }

    public void Dispose()
    {
        outputView.Dispose();
        output.Dispose();
        processor.Dispose();
        enumerator.Dispose();
        videoContext.Dispose();
        videoDevice.Dispose();
    }
}
