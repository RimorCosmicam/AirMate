using System.Buffers.Binary;

namespace AirMate.Protocol;

/// <summary>The video datagram, byte for byte the same as the macOS host's.</summary>
/// <remarks>See <c>protocol/PROTOCOL.md</c>. Big endian throughout, 40-byte header, 1200-byte cap
/// so the datagram does not fragment on a typical LAN.</remarks>
internal static class VideoPacket
{
    public const uint Magic = 0x414D5631;
    public const byte Version = 1;
    public const int HeaderBytes = 40;
    public const int MaximumDatagramBytes = 1200;
    public const int MaximumPayloadBytes = MaximumDatagramBytes - HeaderBytes;

    public const byte FlagKeyframe = 1;
    public const byte FlagCodecConfig = 2;
    public const byte FlagHevc = 4;

    /// <summary>Writes one fragment into <paramref name="destination"/> and returns its length.</summary>
    public static int Write(
        Span<byte> destination,
        ulong sessionId,
        ulong frameId,
        ulong captureNanos,
        ushort fragmentIndex,
        ushort fragmentCount,
        byte flags,
        ReadOnlySpan<byte> payload)
    {
        BinaryPrimitives.WriteUInt32BigEndian(destination[..4], Magic);
        destination[4] = Version;
        destination[5] = flags;
        BinaryPrimitives.WriteUInt16BigEndian(destination.Slice(6, 2), HeaderBytes);
        BinaryPrimitives.WriteUInt64BigEndian(destination.Slice(8, 8), sessionId);
        BinaryPrimitives.WriteUInt64BigEndian(destination.Slice(16, 8), frameId);
        BinaryPrimitives.WriteUInt64BigEndian(destination.Slice(24, 8), captureNanos);
        BinaryPrimitives.WriteUInt16BigEndian(destination.Slice(32, 2), fragmentIndex);
        BinaryPrimitives.WriteUInt16BigEndian(destination.Slice(34, 2), fragmentCount);
        BinaryPrimitives.WriteUInt16BigEndian(destination.Slice(36, 2), (ushort)payload.Length);
        BinaryPrimitives.WriteUInt16BigEndian(destination.Slice(38, 2), 0);
        payload.CopyTo(destination[HeaderBytes..]);
        return HeaderBytes + payload.Length;
    }
}
