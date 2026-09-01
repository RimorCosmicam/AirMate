using System.Diagnostics;
using System.Runtime.InteropServices;
using AirMate.Capture;
using AirMate.Net;
using SharpGen.Runtime;
using Vortice.Direct3D11;
using Vortice.MediaFoundation;

namespace AirMate.Encode;

/// <summary>
/// Hardware H.264, latest frame wins.
/// </summary>
/// <remarks>
/// The same contract the macOS host holds to: at most one frame inside the encoder and one
/// replaceable frame waiting behind it. A new frame arriving while one is in flight replaces the
/// waiting one rather than joining a queue, because a queue here is latency that never comes back.
///
/// Hardware encoders on Windows are asynchronous transforms: they ask for input and announce
/// output through an event generator rather than accepting a call whenever you have a frame. That
/// is the protocol this drives.
/// </remarks>
internal sealed class LatestFrameEncoder : IDisposable
{
    private readonly object gate = new();
    private readonly UdpSender sender;
    private readonly ColourConverter converter;
    private readonly IMFTransform transform;
    private readonly IMFMediaEventGenerator? events;
    private readonly IMFDXGIDeviceManager deviceManager;
    private readonly CancellationTokenSource cancellation = new();
    private readonly int width;
    private readonly int height;
    private readonly bool asynchronous;

    private byte[] parameterSets = [];
    private CapturedFrame? pending;
    private bool needsInput;
    private bool forceKeyframe;

    public ulong Encoded { get; private set; }
    public ulong DroppedPending { get; private set; }

    private LatestFrameEncoder(
        UdpSender sender,
        ColourConverter converter,
        IMFTransform transform,
        IMFDXGIDeviceManager deviceManager,
        bool asynchronous,
        int width,
        int height)
    {
        this.sender = sender;
        this.converter = converter;
        this.transform = transform;
        this.deviceManager = deviceManager;
        this.asynchronous = asynchronous;
        this.width = width;
        this.height = height;
        events = asynchronous ? transform.QueryInterfaceOrNull<IMFMediaEventGenerator>() : null;
        if (asynchronous && events is not null)
        {
            var pump = new Thread(EventLoop) { IsBackground = true, Name = "AirMate.Encoder" };
            pump.Priority = ThreadPriority.AboveNormal;
            pump.Start();
        }
    }

    /// <summary>Throws with a readable message rather than an HRESULT, so the window can show it.</summary>
    public static LatestFrameEncoder Create(
        ID3D11Device device,
        ID3D11DeviceContext context,
        UdpSender sender,
        int width,
        int height,
        int bitrate = 0)
    {
        MediaFactory.MFStartup();

        var deviceManager = MediaFactory.MFCreateDXGIDeviceManager();
        deviceManager.ResetDevice(device);

        var transform = FindEncoder()
            ?? throw new InvalidOperationException(
                "No hardware H.264 encoder is available on this machine. AirMate does not fall back to software encoding, which cannot hold the latency budget.");

        // Hand the encoder the same Direct3D device the capture and conversion already use, so
        // frames never leave the GPU on their way in.
        transform.ProcessMessage(TMessageType.MessageSetD3DManager, (UIntPtr)(nuint)deviceManager.NativePointer);

        bool asynchronous = false;
        var attributes = transform.Attributes;
        if (attributes is not null)
        {
            try { asynchronous = attributes.GetUInt32(TransformAttributeKeys.TransformAsync) != 0; }
            catch (SharpGenException) { }
            // An asynchronous transform will not accept work until it is unlocked. Missing this is
            // the classic reason a hardware encoder appears to be present and never produces a frame.
            if (asynchronous) attributes.Set(TransformAttributeKeys.TransformAsyncUnlock, 1u);
        }

        // Output type first: the encoder cannot judge an input type until it knows what it is
        // being asked to produce.
        using (var output = MediaFactory.MFCreateMediaType())
        {
            output.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            output.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
            // Scaled with the picture rather than fixed: the same budget spread over twice the
            // pixels is half the quality, so a larger size would otherwise look worse than a
            // smaller one.
            var chosen = bitrate > 0
                ? bitrate
                : (int)Math.Clamp((long)width * height * 60 * 18 / 100, 12_000_000, 40_000_000);
            output.Set(MediaTypeAttributeKeys.AvgBitrate, chosen);
            output.Set(MediaTypeAttributeKeys.InterlaceMode, 2);
            output.Set(MediaTypeAttributeKeys.AllSamplesIndependent, 0);
            SetFrameSize(output, width, height);
            SetFrameRate(output, 60);
            transform.SetOutputType(0, output, 0);
        }

        using (var input = MediaFactory.MFCreateMediaType())
        {
            input.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            input.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
            input.Set(MediaTypeAttributeKeys.InterlaceMode, 2);
            SetFrameSize(input, width, height);
            SetFrameRate(input, 60);
            transform.SetInputType(0, input, 0);
        }

        var converter = new ColourConverter(device, context, width, height);
        var encoder = new LatestFrameEncoder(sender, converter, transform, deviceManager, asynchronous, width, height);
        encoder.CaptureParameterSets();

        transform.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero);
        transform.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        transform.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
        return encoder;
    }

    // Both are packed pairs of 32-bit values in one 64-bit attribute, high word first.
    private static void SetFrameSize(IMFMediaType type, int width, int height) =>
        type.Set(MediaTypeAttributeKeys.FrameSize, ((ulong)(uint)width << 32) | (uint)height);

    private static void SetFrameRate(IMFMediaType type, int fps) =>
        type.Set(MediaTypeAttributeKeys.FrameRate, ((ulong)(uint)fps << 32) | 1);

    /// <summary>
    /// Prefer a hardware encoder, and take nothing else.
    /// </summary>
    private static IMFTransform? FindEncoder()
    {
        var input = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.NV12 };
        var output = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 };

        MediaFactory.MFTEnumEx(
            TransformCategoryGuids.VideoEncoder,
            (uint)(EnumFlag.EnumFlagHardware | EnumFlag.EnumFlagSortandfilter),
            input,
            output,
            out var array,
            out var count);
        if (array == IntPtr.Zero || count == 0) return null;

        try
        {
            for (uint index = 0; index < count; index++)
            {
                var pointer = Marshal.ReadIntPtr(array, (int)(index * (uint)IntPtr.Size));
                if (pointer == IntPtr.Zero) continue;
                using var activate = new IMFActivate(pointer);
                try { return activate.ActivateObject<IMFTransform>(); }
                catch (SharpGenException) { }
            }
        }
        finally
        {
            Marshal.FreeCoTaskMem(array);
        }
        return null;
    }

    /// <summary>
    /// The sequence header, kept so every keyframe can carry it.
    /// </summary>
    /// <remarks>
    /// A receiver that joins mid-stream, or that lost the frame carrying them, cannot decode
    /// anything until SPS and PPS arrive again — and there is no retransmission to ask for them.
    /// </remarks>
    private void CaptureParameterSets()
    {
        try
        {
            using var current = transform.GetOutputCurrentType(0);
            if (current.GetBlobSize(MediaTypeAttributeKeys.MpegSequenceHeader) == 0) return;
            parameterSets = current.GetBlob(MediaTypeAttributeKeys.MpegSequenceHeader);
        }
        catch (SharpGenException)
        {
            // Some encoders only publish it after the first frame; keyframes then carry whatever
            // the encoder itself prepends.
        }
    }

    public void RequestKeyframe()
    {
        lock (gate) forceKeyframe = true;
    }

    /// <summary>Hand over a captured frame, replacing any frame still waiting.</summary>
    public void Submit(CapturedFrame frame)
    {
        lock (gate)
        {
            if (pending is not null) DroppedPending++;
            pending = frame;
            if (!needsInput) return;
            needsInput = false;
        }
        Feed();
    }

    private void EventLoop()
    {
        var token = cancellation.Token;
        while (!token.IsCancellationRequested && events is not null)
        {
            IMFMediaEvent mediaEvent;
            try { mediaEvent = events.GetEvent(0); }
            catch (SharpGenException) { break; }

            using (mediaEvent)
            {
                switch (mediaEvent.EventType)
                {
                    case MediaEventTypes.TransformNeedInput:
                        bool ready;
                        lock (gate)
                        {
                            ready = pending is not null;
                            needsInput = !ready;
                        }
                        if (ready) Feed();
                        break;
                    case MediaEventTypes.TransformHaveOutput:
                        Drain();
                        break;
                }
            }
        }
    }

    private void Feed()
    {
        CapturedFrame frame;
        bool keyframe;
        lock (gate)
        {
            if (pending is null) return;
            frame = pending.Value;
            pending = null;
            keyframe = forceKeyframe;
            forceKeyframe = false;
        }

        try
        {
            converter.Convert(frame.Texture);
            using (var buffer = MediaFactory.MFCreateDXGISurfaceBuffer(
                typeof(ID3D11Texture2D).GUID, converter.Output, 0, false))
            {
                using var sample = MediaFactory.MFCreateSample();
                sample.AddBuffer(buffer);
                sample.SampleTime = (long)(frame.Id * 10_000_000L / 60);
                sample.SampleDuration = 10_000_000L / 60;
                if (keyframe) sample.Set(SampleAttributeKeys.CleanPoint, 1u);
                transform.ProcessInput(0, sample, 0);
            }
            if (!asynchronous) Drain();
        }
        catch (SharpGenException)
        {
            // A frame the encoder would not take is a frame we drop, in keeping with everything
            // else on this path.
        }
    }

    private void Drain()
    {
        while (true)
        {
            IMFSample? sample = null;
            try
            {
                // A transform that provides its own samples must not be handed one, and one that
                // does not will fail without it. Which it is has to be asked, not assumed.
                var streamInfo = transform.GetOutputStreamInfo(0);
                const int ProvidesOrCanProvide = 0x100 | 0x200;
                if ((streamInfo.Flags & ProvidesOrCanProvide) == 0) sample = MediaFactory.MFCreateSample();

                var buffer = new OutputDataBuffer { StreamID = 0, Sample = sample! };
                var result = transform.ProcessOutput(ProcessOutputFlags.None, 1, ref buffer, out _);
                if (result.Failure) break;

                using var produced = buffer.Sample;
                if (produced is null) break;
                Emit(produced);
            }
            catch (SharpGenException) { break; }
            finally { sample?.Dispose(); }
            if (!asynchronous) break;
        }
    }

    private void Emit(IMFSample sample)
    {
        using var buffer = sample.ConvertToContiguousBuffer();
        buffer.Lock(out var pointer, out _, out int length);
        try
        {
            bool keyframe = false;
            try { keyframe = sample.GetUInt32(SampleAttributeKeys.CleanPoint) != 0; }
            catch (SharpGenException) { }

            // Copied out rather than handed on as a pointer: the buffer is unlocked the moment
            // this returns, and the sender fragments across several calls.
            var prefix = keyframe ? parameterSets.Length : 0;
            var encoded = new byte[prefix + length];
            if (prefix > 0) parameterSets.CopyTo(encoded, 0);
            Marshal.Copy(pointer, encoded, prefix, length);

            sender.SendAccessUnit(encoded, Encoded + 1, Timestamp(), keyframe, false);
            Encoded++;
        }
        finally
        {
            buffer.Unlock();
        }
    }

    private static ulong Timestamp() =>
        (ulong)(Stopwatch.GetTimestamp() * (1_000_000_000.0 / Stopwatch.Frequency));

    public void Dispose()
    {
        cancellation.Cancel();
        try { transform.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); } catch (SharpGenException) { }
        try { transform.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero); } catch (SharpGenException) { }
        events?.Dispose();
        transform.Dispose();
        deviceManager.Dispose();
        converter.Dispose();
        cancellation.Dispose();
        try { MediaFactory.MFShutdown(); } catch (SharpGenException) { }
    }
}
