using System.Drawing.Drawing2D;

namespace AirMate;

/// <summary>
/// Interleaved diagonal bands, scrolling.
/// </summary>
/// <remarks>
/// The one piece of ornament Mont allows, and only on full-screen moments — onboarding and pairing
/// — never behind content you have to read. Everything is drawn in a frame turned to the bands'
/// own slope, so within it they are plain horizontal rows. The same 26.565° and 34px the other two
/// clients use, so the three ends of a pairing match while you are looking at two of them.
/// </remarks>
internal sealed class MontStripes : Panel
{
    private const float Spacing = 34f;
    private const float Degrees = 26.565f;
    private const int PeriodMs = 5200;

    private readonly System.Windows.Forms.Timer timer = new() { Interval = 33 };
    private readonly int startTicks = Environment.TickCount;

    public Color First { get; set; } = Mont.Mustard;
    public Color Second { get; set; } = Color.Black;

    /// <summary>
    /// Where the Mont card sits, painted here rather than as a child panel.
    /// </summary>
    /// <remarks>
    /// The surface is black at 92%, and a WinForms panel cannot be translucent over a sibling —
    /// so the card is part of this control's own painting, and the rows placed on top of it carry
    /// a transparent background, which composites against exactly this.
    /// </remarks>
    public Rectangle CardBounds { get; set; } = Rectangle.Empty;

    public MontStripes()
    {
        DoubleBuffered = true;
        BackColor = Color.Black;
        timer.Tick += (_, _) => Invalidate();
    }

    protected override void OnHandleCreated(EventArgs e) { timer.Start(); base.OnHandleCreated(e); }

    protected override void Dispose(bool disposing)
    {
        if (disposing) { timer.Stop(); timer.Dispose(); }
        base.Dispose(disposing);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.None;
        g.Clear(Second);

        float travel = (Environment.TickCount - startTicks) % PeriodMs / (float)PeriodMs;
        float drift = travel * Spacing;
        float band = Spacing / 2f;
        float span = Width + Height;

        var state = g.Save();
        g.TranslateTransform(Width / 2f, Height / 2f);
        g.RotateTransform(Degrees);
        g.TranslateTransform(-Width / 2f, -Height / 2f);

        using var first = new SolidBrush(First);
        using var second = new SolidBrush(Second);
        for (float y = -span; y < Height + span; y += Spacing)
        {
            g.FillRectangle(first, -span, y + drift, span * 3f, band);
            g.FillRectangle(second, -span, y + drift + band, span * 3f, band);
        }
        g.Restore(state);

        if (CardBounds != Rectangle.Empty)
        {
            using var surface = new SolidBrush(Color.FromArgb(235, 0, 0, 0));
            g.FillRectangle(surface, CardBounds);
        }
    }
}
