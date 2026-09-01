using System.Net;
using System.Net.Sockets;
using AirMate.Protocol;

namespace AirMate.Net;

/// <summary>
/// The one socket: video and status out, hello and control in.
/// </summary>
/// <remarks>
/// A direct port of the macOS host's sender, including the part that matters most — sends are
/// non-blocking and an access unit is abandoned the moment the socket pushes back, rather than
/// queued. A queue here is latency that never comes back.
/// </remarks>
internal sealed class UdpSender : IDisposable
{
    private readonly object gate = new();
    private readonly Socket socket;
    private readonly ulong sessionId;
    private readonly byte[] datagram = new byte[VideoPacket.MaximumDatagramBytes];
    private readonly CancellationTokenSource cancellation = new();

    private IPEndPoint? destination;

    /// <summary>The one address allowed to change what this machine is doing.</summary>
    /// <remarks>Not authentication: the transport is unencrypted and a forged source address
    /// defeats it. It exists so a device on the LAN cannot restart your display without a person
    /// agreeing once. See <c>docs/SECURITY.md</c>.</remarks>
    private IPAddress? authorisedControl;
    private IPAddress? pendingControl;

    /// <summary>A command from an authorised client.</summary>
    public event Action<ControlCommand>? CommandReceived;
    /// <summary>A client is asking for control and needs a person.</summary>
    public event Action<string>? ControlRequested;

    public UdpSender(int port = 48620)
    {
        sessionId = (ulong)Random.Shared.NextInt64(1, long.MaxValue);
        socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
        {
            Blocking = false,
            SendBufferSize = 256 * 1024
        };
        socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        // A closed remote port answers a datagram with ICMP port-unreachable, which Windows turns
        // into ConnectionReset on the *next* receive. Left on, the receive loop dies the first
        // time the tablet is not listening yet.
        try { socket.IOControl(unchecked((int)0x9800000C), new byte[] { 0, 0, 0, 0 }, null); }
        catch (SocketException) { }
        socket.Bind(new IPEndPoint(IPAddress.Any, port));
        Task.Run(() => ReceiveLoop(cancellation.Token));
    }

    private async Task ReceiveLoop(CancellationToken token)
    {
        var buffer = new byte[256];
        var from = new IPEndPoint(IPAddress.Any, 0);
        while (!token.IsCancellationRequested)
        {
            SocketReceiveFromResult result;
            try
            {
                result = await socket.ReceiveFromAsync(buffer, SocketFlags.None, from, token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { continue; }

            // Spans cannot live across an await, so the parsing happens in its own frame.
            Dispatch(buffer, result.ReceivedBytes, (IPEndPoint)result.RemoteEndPoint);
        }
    }

    private void Dispatch(byte[] buffer, int length, IPEndPoint peer)
    {
        var received = buffer.AsSpan(0, length);
        if (received.Length == 8 && System.Text.Encoding.ASCII.GetString(received) == "AMHELLO1")
        {
            Adopt(peer);
            return;
        }
        var command = ControlPacket.Parse(received);
        if (command is not null) Handle(command.Value, peer);
    }

    /// <summary>Take this peer as the video destination. Always allowed: it only says where.</summary>
    private void Adopt(IPEndPoint peer)
    {
        lock (gate) destination = peer;
        LastHelloUtc = DateTime.UtcNow;
    }

    public DateTime LastHelloUtc { get; private set; } = DateTime.MinValue;

    public bool ClientIsConnected => DateTime.UtcNow - LastHelloUtc < TimeSpan.FromSeconds(3);

    private void Handle(ControlCommand command, IPEndPoint peer)
    {
        if (!command.ChangesState) { Adopt(peer); return; }

        bool authorised;
        bool alreadyAsking;
        lock (gate)
        {
            authorised = authorisedControl is not null && authorisedControl.Equals(peer.Address);
            alreadyAsking = pendingControl is not null && pendingControl.Equals(peer.Address);
            if (!authorised) pendingControl = peer.Address;
        }

        if (authorised) { CommandReceived?.Invoke(command); return; }
        // Raise the question once per address and drop the datagram. It is not queued and not
        // replayed once permission is given — a command that arrived before anyone agreed to it
        // should not take effect the moment they do.
        if (!alreadyAsking) ControlRequested?.Invoke(peer.Address.ToString());
    }

    public void ResolveControlRequest(bool allow)
    {
        lock (gate)
        {
            if (allow) authorisedControl = pendingControl;
            pendingControl = null;
        }
    }

    public void SendStatus(bool running, bool hiDpi, int width, int height, ulong encodedFrames)
    {
        IPEndPoint? target;
        bool authorised;
        lock (gate)
        {
            target = destination;
            authorised = target is not null && authorisedControl is not null
                && authorisedControl.Equals(target.Address);
        }
        if (target is null) return;
        TrySend(StatusPacket.Build(running, hiDpi, authorised, width, height, encodedFrames), target);
    }

    /// <summary>Fragment one access unit and send it, abandoning the rest on backpressure.</summary>
    public void SendAccessUnit(ReadOnlySpan<byte> accessUnit, ulong frameId, ulong captureNanos, bool keyframe, bool hevc)
    {
        IPEndPoint? target;
        lock (gate) target = destination;
        if (target is null) return;

        int count = (accessUnit.Length + VideoPacket.MaximumPayloadBytes - 1) / VideoPacket.MaximumPayloadBytes;
        if (count <= 0 || count > ushort.MaxValue) return;

        byte flags = (byte)((keyframe ? VideoPacket.FlagKeyframe : 0) | (hevc ? VideoPacket.FlagHevc : 0));
        if (keyframe) flags |= VideoPacket.FlagCodecConfig;

        for (int index = 0; index < count; index++)
        {
            int start = index * VideoPacket.MaximumPayloadBytes;
            int end = Math.Min(start + VideoPacket.MaximumPayloadBytes, accessUnit.Length);
            int length = VideoPacket.Write(
                datagram, sessionId, frameId, captureNanos,
                (ushort)index, (ushort)count, flags, accessUnit[start..end]);

            if (!TrySend(datagram.AsSpan(0, length), target))
            {
                DroppedNetwork++;
                return;
            }
        }
    }

    public ulong DroppedNetwork { get; private set; }

    private bool TrySend(ReadOnlySpan<byte> packet, IPEndPoint target)
    {
        try
        {
            return socket.SendTo(packet, SocketFlags.None, target) == packet.Length;
        }
        catch (SocketException)
        {
            // WouldBlock and everything else alike: the frame is gone and the next one is already
            // on its way. There is nothing worth retrying.
            return false;
        }
        catch (ObjectDisposedException) { return false; }
    }

    public void Dispose()
    {
        cancellation.Cancel();
        socket.Dispose();
        cancellation.Dispose();
    }
}
