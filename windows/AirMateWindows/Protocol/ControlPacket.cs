using System.Buffers.Binary;

namespace AirMate.Protocol;

internal enum ControlKind { Hello, Start, Stop, SetDisplay, RequestIdr }

/// <param name="Kind">Which command.</param>
/// <param name="Width">Set-display only.</param>
/// <param name="Height">Set-display only.</param>
/// <param name="HiDpi">Set-display only. Meaningless here — Windows mirrors a display it did not
/// create, so its size is the display's business — but it is carried so one client speaks to both
/// hosts without knowing which it reached.</param>
internal readonly record struct ControlCommand(ControlKind Kind, int Width, int Height, bool HiDpi)
{
    /// <summary>Whether obeying this would change what the host is doing.</summary>
    /// <remarks><see cref="ControlKind.Hello"/> only names a video destination, which the broadcast
    /// hello already does, so it is always honoured. Everything else waits for a person.</remarks>
    public bool ChangesState => Kind != ControlKind.Hello;
}

internal static class ControlPacket
{
    public const uint Magic = 0x414D4331;
    public const byte Version = 1;
    public const int HeaderBytes = 8;

    public static ControlCommand? Parse(ReadOnlySpan<byte> data)
    {
        if (data.Length < HeaderBytes) return null;
        if (BinaryPrimitives.ReadUInt32BigEndian(data[..4]) != Magic) return null;
        if (data[4] != Version) return null;
        int payloadLength = BinaryPrimitives.ReadUInt16BigEndian(data.Slice(6, 2));
        if (HeaderBytes + payloadLength > data.Length) return null;

        switch (data[5])
        {
            case 1: return new ControlCommand(ControlKind.Hello, 0, 0, false);
            case 2: return new ControlCommand(ControlKind.Start, 0, 0, false);
            case 3: return new ControlCommand(ControlKind.Stop, 0, 0, false);
            case 4:
            {
                if (payloadLength < 5) return null;
                var payload = data.Slice(HeaderBytes, 5);
                int width = BinaryPrimitives.ReadUInt16BigEndian(payload[..2]);
                int height = BinaryPrimitives.ReadUInt16BigEndian(payload.Slice(2, 2));
                if (width <= 0 || height <= 0) return null;
                return new ControlCommand(ControlKind.SetDisplay, width, height, (payload[4] & 1) != 0);
            }
            case 5: return new ControlCommand(ControlKind.RequestIdr, 0, 0, false);
            default: return null;
        }
    }
}

internal static class StatusPacket
{
    public const uint Magic = 0x414D5331;
    public const byte Version = 1;
    public const int Bytes = 20;

    public static byte[] Build(bool running, bool hiDpi, bool authorised, int width, int height, ulong encodedFrames)
    {
        var packet = new byte[Bytes];
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(0, 4), Magic);
        packet[4] = Version;
        packet[5] = (byte)((running ? 1 : 0) | (hiDpi ? 2 : 0) | (authorised ? 4 : 0));
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6, 2), (ushort)Math.Clamp(width, 0, ushort.MaxValue));
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(8, 2), (ushort)Math.Clamp(height, 0, ushort.MaxValue));
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(10, 2), 0);
        BinaryPrimitives.WriteUInt64BigEndian(packet.AsSpan(12, 8), encodedFrames);
        return packet;
    }
}
