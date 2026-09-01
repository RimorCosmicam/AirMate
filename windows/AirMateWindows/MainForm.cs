using System.Net;
using System.Net.Sockets;
using AirMate.Capture;
using AirMate.Encode;
using AirMate.Input;
using AirMate.Net;
using AirMate.Protocol;
using QRCoder;

namespace AirMate;

/// <summary>
/// The AirMate window.
/// </summary>
/// <remarks>
/// Black, square, and set almost entirely in Mont Black — the same window the macOS host shows,
/// arranged the same way. There are no buttons drawn here: a row is a word that does something,
/// bright when it can and dim when it cannot. Stripes appear only while onboarding and pairing,
/// because Mont keeps them off anything you have to read.
///
/// Where the two hosts differ, they differ because the platforms do. macOS creates its own virtual
/// display and offers a resolution for it; Windows cannot create one at all, so this mirrors a
/// display that already exists and offers a picker instead. Install an indirect display driver and
/// its display appears in that picker like any other — which is how this becomes a second monitor
/// rather than a copy of a first.
/// </remarks>
internal sealed class MainForm : Form
{
    private readonly AppSettings settings = new();
    private readonly MontStripes stripes = new() { Dock = DockStyle.Fill };
    private readonly FlowLayoutPanel content = new()
    {
        FlowDirection = FlowDirection.TopDown,
        WrapContents = false,
        AutoScroll = true,
        BackColor = Color.Transparent
    };
    private readonly NotifyIcon tray;
    private readonly System.Windows.Forms.Timer heartbeat = new() { Interval = 1000 };

    private readonly MontWordmark wordmark = new();
    private readonly MontDetail detail = new();
    private readonly MontChips displayChips = new();
    private readonly PictureBox pairingCode = new()
    {
        Size = new Size(132, 132),
        SizeMode = PictureBoxSizeMode.Zoom,
        BackColor = Color.Transparent
    };
    private readonly MontMetric resolutionMetric = new("Resolution");
    private readonly MontMetric codecMetric = new("Codec");
    private readonly MontMetric framesMetric = new("Frames");
    private readonly MontToggle startupToggle = new();
    private readonly MontRow primaryRow = new("Start display");
    private readonly MontRow allowRow = new("Allow control");
    private readonly MontRow refuseRow = new("Refuse") { Dim = true };
    private readonly MontRow doneRow = new("All done");
    private readonly MontRow closeRow = new("Close") { Dim = true };

    private IReadOnlyList<DisplayTarget> displays = [];
    private DuplicationCapture? capture;
    private LatestFrameEncoder? encoder;
    private UdpSender? sender;
    private string? controlRequest;
    private string? lastError;
    private bool running;

    public MainForm()
    {
        Text = "AirMate";
        FormBorderStyle = FormBorderStyle.None;
        BackColor = Mont.Ground;
        ClientSize = new Size(460, 430);
        StartPosition = FormStartPosition.CenterScreen;
        KeyPreview = true;

        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.png");
        if (File.Exists(iconPath))
        {
            using var bitmap = new Bitmap(iconPath);
            Icon = Icon.FromHandle(bitmap.GetHicon());
        }

        tray = new NotifyIcon { Icon = Icon, Text = "AirMate", Visible = true, ContextMenuStrip = new ContextMenuStrip() };
        tray.ContextMenuStrip.Items.Add("Open AirMate", null, (_, _) => ShowFromTray());
        tray.ContextMenuStrip.Items.Add("Quit AirMate", null, (_, _) => Quit());
        tray.DoubleClick += (_, _) => ShowFromTray();

        content.Padding = new Padding(Mont.PadLeft, 26, Mont.PadRight, 16);
        content.Dock = DockStyle.Fill;
        stripes.Controls.Add(content);
        Controls.Add(stripes);

        BuildRows();

        displays = DisplayEnumerator.Enumerate();
        startupToggle.IsOn = StartupRegistration.IsEnabled;
        startupToggle.Toggled += value => startupToggle.IsOn = StartupRegistration.Set(value);

        heartbeat.Tick += (_, _) => Refresh();
        heartbeat.Start();

        // The window is the whole surface, so it is dragged by its ground.
        MouseDown += (_, e) => { if (e.Button == MouseButtons.Left) Drag(); };
        stripes.MouseDown += (_, e) => { if (e.Button == MouseButtons.Left) Drag(); };
        KeyDown += (_, e) => { if (e.Control && e.KeyCode == Keys.W) Hide(); };

        Render();
        if (settings.Onboarded) Start();
    }

    private void BuildRows()
    {
        detail.Width = 380;
        detail.Height = 34;

        displayChips.Width = 400;
        displayChips.SelectionChanged += index =>
        {
            if (index < 0 || index >= displays.Count) return;
            settings.SelectedDisplay = displays[index].DeviceName;
            if (running) { Stop(); Start(); }
        };

        primaryRow.Width = 400;
        primaryRow.Click += (_, _) => { if (running) Stop(); else Start(); };
        allowRow.Width = 400;
        allowRow.Click += (_, _) => ResolveControl(true);
        refuseRow.Width = 400;
        refuseRow.Click += (_, _) => ResolveControl(false);
        doneRow.Width = 400;
        doneRow.Click += (_, _) => { settings.Onboarded = true; Start(); Render(); };
        closeRow.Width = 400;
        closeRow.Click += (_, _) => Hide();

        var metrics = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            BackColor = Color.Transparent,
            Size = new Size(400, 46),
            Margin = new Padding(0, 10, 0, 6)
        };
        resolutionMetric.Width = 150;
        codecMetric.Width = 90;
        framesMetric.Width = 90;
        metrics.Controls.AddRange([resolutionMetric, codecMetric, framesMetric]);

        var startupLine = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            BackColor = Color.Transparent,
            Size = new Size(400, 26),
            Margin = new Padding(0, 6, 0, 6)
        };
        var startupLabel = new MontDetail("Start with Windows") { Width = 150, Height = 20 };
        startupLine.Controls.Add(startupLabel);
        startupLine.Controls.Add(startupToggle);

        content.Controls.AddRange([
            wordmark, detail, displayChips, pairingCode, metrics,
            allowRow, refuseRow, startupLine, primaryRow, doneRow, closeRow
        ]);
        wordmark.Width = 400;
    }

    // MARK: - State

    private void Render()
    {
        bool onboarding = !settings.Onboarded;
        bool pairing = running && sender is { ClientIsConnected: false };
        bool showStripes = onboarding || pairing;

        stripes.First = lastError is not null ? Mont.Danger : Mont.Mustard;
        stripes.CardBounds = showStripes
            ? new Rectangle(0, 0, ClientSize.Width, ClientSize.Height)
            : Rectangle.Empty;
        stripes.Visible = true;
        if (!showStripes) stripes.First = Color.Black;

        allowRow.Visible = refuseRow.Visible = controlRequest is not null;
        doneRow.Visible = onboarding;
        primaryRow.Visible = !onboarding;
        displayChips.Visible = displays.Count > 1;
        pairingCode.Visible = pairing;

        bool connected = sender is { ClientIsConnected: true };
        resolutionMetric.Visible = codecMetric.Visible = framesMetric.Visible = connected && encoder is not null;

        if (displays.Count > 1)
        {
            displayChips.SetOptions(displays.Select(d => d.Label), SelectedIndex());
        }

        detail.Text = controlRequest is not null
            ? $"{controlRequest} wants to control this display."
            : lastError
              ?? (onboarding
                  ? "A second screen for your PC, on the tablet you already own. Pick which display to send, then start."
                  : !running ? "Start AirMate when you want to send a display."
                  : !connected ? "Open AirMate on the tablet. Same Wi‑Fi is enough; the code is a shortcut."
                  : "AirMate is sending this display to the tablet.");

        primaryRow.Text = running ? "Stop display" : "Start display";

        if (connected && encoder is not null)
        {
            var target = SelectedTarget();
            resolutionMetric.Value = target is null ? "—" : $"{target.Value.Width} × {target.Value.Height}";
            codecMetric.Value = "H.264";
            framesMetric.Value = encoder.Encoded.ToString("N0");
        }

        if (pairing && pairingCode.Image is null) pairingCode.Image = BuildPairingCode();
        Invalidate(true);
    }

    private int SelectedIndex()
    {
        var index = displays.ToList().FindIndex(d => d.DeviceName == settings.SelectedDisplay);
        return index >= 0 ? index : 0;
    }

    private DisplayTarget? SelectedTarget() =>
        displays.Count == 0 ? null : displays[Math.Clamp(SelectedIndex(), 0, displays.Count - 1)];

    private new void Refresh()
    {
        sender?.SendStatus(running, false,
            SelectedTarget()?.Width ?? 0, SelectedTarget()?.Height ?? 0,
            encoder?.Encoded ?? 0);
        Render();
    }

    // MARK: - Running

    private void Start()
    {
        lastError = null;
        displays = DisplayEnumerator.Enumerate();
        var target = SelectedTarget();
        if (target is null)
        {
            lastError = "Windows reported no displays AirMate can capture.";
            Render();
            return;
        }

        try
        {
            var udp = new UdpSender();
            udp.ControlRequested += address => BeginInvoke(() => { controlRequest = address; Render(); });
            udp.CommandReceived += command => BeginInvoke(() => Perform(command));

            var started = DuplicationCapture.Start(target.Value, frame => encoder?.Submit(frame));
            var made = LatestFrameEncoder.Create(
                started.Device,
                started.Device.ImmediateContext,
                udp,
                started.Width,
                started.Height);

            sender = udp;
            capture = started;
            encoder = made;
            running = true;
        }
        catch (Exception error)
        {
            lastError = error.Message;
            Stop();
        }
        Render();
    }

    private void Stop()
    {
        running = false;
        capture?.Dispose();
        capture = null;
        encoder?.Dispose();
        encoder = null;
        sender?.Dispose();
        sender = null;
        controlRequest = null;
        PointerInput.Reset();
        pairingCode.Image = null;
        Render();
    }

    /// <summary>A command from the tablet, already checked against the authorised address.</summary>
    private void Perform(ControlCommand command)
    {
        switch (command.Kind)
        {
            case ControlKind.Start: if (!running) Start(); break;
            case ControlKind.Stop: if (running) Stop(); break;
            case ControlKind.RequestIdr: encoder?.RequestKeyframe(); break;
            case ControlKind.Click:
                if (SelectedTarget() is { } clickTarget) PointerInput.Click(command.X, command.Y, clickTarget);
                break;
            case ControlKind.Scroll:
                if (SelectedTarget() is { } scrollTarget) PointerInput.Scroll(command, scrollTarget);
                break;
            case ControlKind.ClientDisplay:
                break;
            case ControlKind.SetDisplay:
                // Windows mirrors a display it did not create, so its size is not ours to set. The
                // tablet's resolution chips are honoured on macOS and ignored here rather than
                // pretending to work.
                break;
        }
        Render();
    }

    private void ResolveControl(bool allow)
    {
        sender?.ResolveControlRequest(allow);
        controlRequest = null;
        Render();
    }

    private Image? BuildPairingCode()
    {
        var host = LocalAddress();
        if (host is null) return null;
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode($"airmate://pair?host={host}&port=48620", QRCodeGenerator.ECCLevel.M);
        using var code = new PngByteQRCode(data);
        using var stream = new MemoryStream(code.GetGraphic(6));
        return new Bitmap(stream);
    }

    /// <summary>The address the tablet should aim at, preferring a real LAN interface.</summary>
    private static string? LocalAddress()
    {
        try
        {
            using var probe = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            // Connecting a UDP socket sends nothing; it just asks the routing table which local
            // address would be used, which is exactly the one the tablet can reach.
            probe.Connect("8.8.8.8", 65530);
            return (probe.LocalEndPoint as IPEndPoint)?.Address.ToString();
        }
        catch (SocketException) { return null; }
    }

    private void ShowFromTray()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private void Quit()
    {
        Stop();
        tray.Visible = false;
        Application.Exit();
    }

    private void Drag()
    {
        // Dragging a border-less window: tell Windows the pointer is on the caption it does not have.
        NativeMethods.ReleaseCapture();
        NativeMethods.SendMessage(Handle, 0xA1, 0x2, 0);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Closing the window leaves the service running, the way the menu-bar host does.
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        Stop();
        tray.Visible = false;
        base.OnFormClosing(e);
    }
}

internal static partial class NativeMethods
{
    [System.Runtime.InteropServices.LibraryImport("user32.dll")]
    [return: System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.Bool)]
    public static partial bool ReleaseCapture();

    [System.Runtime.InteropServices.LibraryImport("user32.dll")]
    public static partial IntPtr SendMessage(IntPtr hWnd, uint msg, nint wParam, nint lParam);
}
