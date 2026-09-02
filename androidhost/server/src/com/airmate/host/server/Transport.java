package com.airmate.host.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * The one socket, carrying all three message families.
 *
 * Video goes out, control comes in, status goes back, and pairing is decided here: the client being
 * sent the picture is the client whose commands are obeyed, and a datagram from any other address is
 * discarded. That is authorisation, not authentication — the transport is unencrypted and anyone who
 * can forge a source address, or who wins the race to be the paired client, can drive this host.
 * See `docs/SECURITY.md`.
 */
public final class Transport implements Runnable {
    public interface Listener {
        void onCommand(Protocol.Command command);
        void onClientChanged();
    }

    private final DatagramChannel channel;
    private final Selector selector;
    private final Listener listener;
    private final ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(2048);
    private final byte[] receiveBytes = new byte[2048];

    /** Reused for every fragment: see the note in {@link Protocol#writeVideo}. */
    private final byte[] fragment = new byte[Protocol.MAX_DATAGRAM_BYTES];
    private final ByteBuffer fragmentBuffer = ByteBuffer.wrap(fragment);

    private volatile InetSocketAddress destination;
    private volatile boolean running = true;

    public Transport(Listener listener) throws Exception {
        this.listener = listener;
        channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, 256 * 1024);
        channel.bind(new InetSocketAddress(Protocol.PORT));
        channel.configureBlocking(false);
        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);
        Ln.i("NET", "Listening on UDP " + Protocol.PORT);
    }

    public boolean paired() {
        return destination != null;
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Blocks until a datagram arrives rather than polling: this process runs on a phone
                // that is also the thing being streamed, and a spin loop would be paid for in
                // battery by the device doing the work.
                if (selector.select(500) == 0) continue;
                selector.selectedKeys().clear();
                SocketAddress from;
                while ((from = receive()) != null) handle(from);
            } catch (Exception error) {
                if (running) Ln.e("NET", "receive failed", error);
            }
        }
    }

    private SocketAddress receive() throws Exception {
        receiveBuffer.clear();
        SocketAddress from = channel.receive(receiveBuffer);
        if (from == null) return null;
        receiveBuffer.flip();
        receiveBuffer.get(receiveBytes, 0, receiveBuffer.limit());
        return from;
    }

    private void handle(SocketAddress from) {
        int length = receiveBuffer.limit();
        if (!(from instanceof InetSocketAddress)) return;
        InetSocketAddress peer = (InetSocketAddress) from;

        if (Protocol.isHello(receiveBytes, length)) {
            adopt(peer);
            return;
        }
        Protocol.Command command = Protocol.parseControl(receiveBytes, length);
        if (command == null) return;
        if (!command.changesState()) {
            adopt(peer);
            return;
        }
        // The paired client — the one already being sent video — is the one that may change it.
        InetSocketAddress current = destination;
        if (current == null || !current.getAddress().equals(peer.getAddress())) return;
        Diagnostics.lastClientHelloNanos = System.nanoTime();
        listener.onCommand(command);
    }

    /**
     * Take this peer as the video destination.
     *
     * Always allowed: it only says where to send video, which the broadcast hello already does. The
     * hello repeats once a second, so only a genuinely new client is worth telling anyone about.
     */
    private void adopt(InetSocketAddress peer) {
        Diagnostics.lastClientHelloNanos = System.nanoTime();
        InetSocketAddress current = destination;
        boolean changed = current == null
                || !current.getAddress().equals(peer.getAddress())
                || current.getPort() != peer.getPort();
        destination = peer;
        if (changed) {
            Ln.i("NET", "Client paired: " + peer);
            listener.onClientChanged();
        }
    }

    /**
     * Fragment one access unit across the wire.
     *
     * The socket is non-blocking, so a full send buffer returns zero rather than waiting. When that
     * happens the rest of this access unit is abandoned: a frame delivered late is worth less than
     * the frame behind it, and the client's next keyframe repairs the gap.
     */
    public void sendVideo(byte[] accessUnit, int length, long sessionId, long frameId,
                          long captureNanos, boolean keyframe, boolean hevc) {
        InetSocketAddress target = destination;
        if (target == null || length <= 0) return;
        int count = (length + Protocol.MAX_PAYLOAD_BYTES - 1) / Protocol.MAX_PAYLOAD_BYTES;
        if (count <= 0 || count > 65535) return;
        int flags = (keyframe ? Protocol.FLAG_KEYFRAME | Protocol.FLAG_CODEC_CONFIG : 0)
                | (hevc ? Protocol.FLAG_HEVC : 0);

        for (int index = 0; index < count; index++) {
            int start = index * Protocol.MAX_PAYLOAD_BYTES;
            int size = Math.min(Protocol.MAX_PAYLOAD_BYTES, length - start);
            int total = Protocol.writeVideo(fragment, sessionId, frameId, captureNanos,
                    index, count, flags, accessUnit, start, size);
            fragmentBuffer.limit(total).position(0);
            try {
                if (channel.send(fragmentBuffer, target) == 0) {
                    Diagnostics.droppedNetwork++;
                    return;
                }
            } catch (Exception error) {
                Diagnostics.droppedNetwork++;
                return;
            }
        }
    }

    public void sendStatus(boolean running, boolean hiDPI, int width, int height, long encodedFrames) {
        InetSocketAddress target = destination;
        if (target == null) return;
        byte[] packet = Protocol.status(running, hiDPI, true, width, height, encodedFrames);
        try {
            channel.send(ByteBuffer.wrap(packet), target);
        } catch (Exception ignored) {
            // Status is repeated every second; a lost one costs the client nothing.
        }
    }

    public void close() {
        running = false;
        try { selector.wakeup(); selector.close(); } catch (Exception ignored) {}
        try { channel.close(); } catch (Exception ignored) {}
    }
}
