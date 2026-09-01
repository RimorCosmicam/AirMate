namespace AirMate;

/// <summary>
/// A row of the window: a word, full width, that does something.
/// </summary>
/// <remarks>
/// Bright when it can be used and dim when it cannot. There is no box, pill or border and there is
/// not meant to be one — under Mont the type is what makes a word act as a button, and putting a
/// container back is the single thing the language exists to do without.
/// </remarks>
internal sealed class MontRow : Control
{
    private bool hot;

    public string Trailing { get; set; } = string.Empty;
    public bool Dim { get; set; }

    public MontRow(string text, float size = 13f)
    {
        Text = text;
        Font = MontFont.Black(size);
        ForeColor = Mont.Active;
        BackColor = Color.Transparent;
        Height = (int)(size * 2.6f);
        Cursor = Cursors.Hand;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
    }

    protected override void OnMouseEnter(EventArgs e) { hot = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hot = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var colour = !Enabled ? Mont.Disabled : Dim && !hot ? Mont.Dim : Mont.Active;
        TextRenderer.DrawText(e.Graphics, Text.ToUpperInvariant(), Font,
            new Rectangle(0, 0, Width, Height), colour,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);

        if (Trailing.Length > 0)
        {
            TextRenderer.DrawText(e.Graphics, Trailing.ToUpperInvariant(), MontFont.Black(11f),
                new Rectangle(0, 0, Width, Height), Mont.Dim,
                TextFormatFlags.Right | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
        }
    }
}

/// <summary>An explanatory line under a row.</summary>
internal sealed class MontDetail : Label
{
    public MontDetail(string text = "", float size = 11f)
    {
        Text = text;
        Font = MontFont.Black(size);
        ForeColor = Mont.Detail;
        AutoSize = false;
        BackColor = Color.Transparent;
    }
}

/// <summary>
/// A choice, as a row of words. Selected is bright, unselected is dim — exactly the rule a row
/// follows, because a choice is a row that happens to sit beside others.
/// </summary>
internal sealed class MontChips : Control
{
    private string[] options = [];
    private int selected = -1;
    private readonly List<Rectangle> hits = [];

    public event Action<int>? SelectionChanged;

    public MontChips()
    {
        BackColor = Color.Transparent;
        Font = MontFont.Black(11f);
        Height = 26;
        Cursor = Cursors.Hand;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
    }

    public void SetOptions(IEnumerable<string> values, int selectedIndex)
    {
        options = values.Select(v => v.ToUpperInvariant()).ToArray();
        selected = selectedIndex;
        Invalidate();
    }

    public int Selected => selected;

    protected override void OnPaint(PaintEventArgs e)
    {
        hits.Clear();
        int x = 0;
        for (int index = 0; index < options.Length; index++)
        {
            var size = TextRenderer.MeasureText(e.Graphics, options[index], Font);
            var bounds = new Rectangle(x, 0, size.Width, Height);
            hits.Add(bounds);
            TextRenderer.DrawText(e.Graphics, options[index], Font, bounds,
                index == selected ? Mont.Active : Mont.Dim,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
            x += size.Width + 16;
        }
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        for (int index = 0; index < hits.Count; index++)
        {
            if (!hits[index].Contains(e.Location)) continue;
            selected = index;
            Invalidate();
            SelectionChanged?.Invoke(index);
            break;
        }
        base.OnMouseDown(e);
    }
}

/// <summary>
/// The slider stopped at two positions: a white block filling one half, with the state written in
/// the half it has left.
/// </summary>
/// <remarks>
/// The word names what the control currently is, not what pressing it would do. A switch labelled
/// with its own opposite is a puzzle every single time you meet it. Windows' own CheckBox is a
/// rounded, bordered, animated object — every decoration Mont removed — so it is not used here.
/// </remarks>
internal sealed class MontToggle : Control
{
    private bool isOn;

    public event Action<bool>? Toggled;

    public MontToggle()
    {
        Size = new Size(56, 18);
        BackColor = Color.Transparent;
        Font = MontFont.Black(10f);
        Cursor = Cursors.Hand;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
    }

    public bool IsOn
    {
        get => isOn;
        set { isOn = value; Invalidate(); }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        int half = Width / 2;
        using var track = new SolidBrush(Enabled ? Mont.Track : Color.FromArgb(12, 255, 255, 255));
        using var block = new SolidBrush(Enabled ? Mont.Active : Mont.Disabled);
        e.Graphics.FillRectangle(track, 0, 0, Width, Height);
        e.Graphics.FillRectangle(block, isOn ? 0 : half, 0, half, Height);

        var text = isOn ? "ON" : "OFF";
        var bounds = isOn ? new Rectangle(half, 0, half, Height) : new Rectangle(0, 0, half, Height);
        TextRenderer.DrawText(e.Graphics, text, Font, bounds,
            Enabled ? Mont.Active : Mont.Disabled,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPrefix);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        if (Enabled)
        {
            isOn = !isOn;
            Invalidate();
            Toggled?.Invoke(isOn);
        }
        base.OnMouseDown(e);
    }
}

/// <summary>A measurement: a small dim label over the figure it names.</summary>
internal sealed class MontMetric : Control
{
    public string Title { get; set; } = string.Empty;
    public string Value { get; set; } = string.Empty;

    public MontMetric(string title)
    {
        Title = title;
        BackColor = Color.Transparent;
        Size = new Size(120, 40);
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        TextRenderer.DrawText(e.Graphics, Title.ToUpperInvariant(), MontFont.Black(9f),
            new Rectangle(0, 0, Width, 14), Mont.Dim, TextFormatFlags.Left | TextFormatFlags.NoPrefix);
        TextRenderer.DrawText(e.Graphics, Value, MontFont.Black(13f),
            new Rectangle(0, 14, Width, 22), Mont.Primary, TextFormatFlags.Left | TextFormatFlags.NoPrefix);
    }
}

/// <summary>The wordmark: the lightest weight over the heaviest at one size.</summary>
internal sealed class MontWordmark : Control
{
    private readonly float size;

    public MontWordmark(float size = 28f)
    {
        this.size = size;
        BackColor = Color.Transparent;
        Height = (int)(size * 1.6f);
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.UserPaint | ControlStyles.SupportsTransparentBackColor, true);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        using var thin = MontFont.Thin(size);
        using var black = MontFont.Black(size);
        var airWidth = TextRenderer.MeasureText(e.Graphics, "air", thin,
            new Size(int.MaxValue, int.MaxValue), TextFormatFlags.NoPadding).Width;
        TextRenderer.DrawText(e.Graphics, "air", thin, new Point(0, 0), Mont.Active, TextFormatFlags.NoPadding);
        TextRenderer.DrawText(e.Graphics, "Mate", black, new Point(airWidth, 0), Mont.Active, TextFormatFlags.NoPadding);
    }
}
