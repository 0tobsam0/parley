namespace Parley;

// ADAPTIVE silence VAD — port of the macOS SilenceVAD. Tracks a running average of the
// user's own speaking loudness and only ends when the level drops FAR below that average
// (a big relative drop) and stays there for TrailingSilence — no mid-sentence cutoff.
// Windows adds a noise-floor guard: quiet speech pulls the average (and the threshold with it)
// down to the room's own noise, which then never counts as silence. The threshold stays
// FloorMarginDb above the measured floor, and the level is smoothed so a noise spike doesn't
// restart the trailing silence.
public sealed class Vad
{
    public double TrailingSilence { get; init; } = 1.8;
    public float DropDb { get; init; } = 22f;
    public float FloorMarginDb { get; init; } = 8f;
    private const float FloorRiseDbPerSecond = 1f;   // the floor drops at once, rises slowly
    private const double SmoothSeconds = 0.15;

    private float _speechAvgDb = -32f;
    private bool _hasAvg;
    private float _floorDb = -120f, _smoothDb = -120f;
    private bool _hasLevel;
    public bool Started { get; private set; }
    private double _silenceElapsed;

    public enum Decision { Waiting, Speaking, Ended }

    /// The room's quietest level so far (smoothed), for the log.
    public float FloorDb => _floorDb;

    /// The level below which a chunk counts as silence (follows Sir's speaking loudness,
    /// never closer to the noise floor than FloorMarginDb).
    public float Threshold => Math.Min(-45f, Math.Max(-70f, Math.Max(_speechAvgDb - DropDb, _floorDb + FloorMarginDb)));

    public Decision Process(float rmsDb, double duration)
    {
        if (_hasLevel)
        {
            _smoothDb += (float)(1 - Math.Exp(-duration / SmoothSeconds)) * (rmsDb - _smoothDb);
            _floorDb = _smoothDb < _floorDb ? _smoothDb : _floorDb + (float)(FloorRiseDbPerSecond * duration);
        }
        else { _smoothDb = _floorDb = rmsDb; _hasLevel = true; }

        var threshold = Threshold;
        if (_smoothDb >= threshold)
        {
            Started = true;
            _silenceElapsed = 0;
            if (rmsDb > -50f)   // only real speech feeds the average
            {
                if (_hasAvg) _speechAvgDb = _speechAvgDb * 0.95f + rmsDb * 0.05f;
                else { _speechAvgDb = rmsDb; _hasAvg = true; }
            }
            return Decision.Speaking;
        }
        if (!Started) return Decision.Waiting;
        _silenceElapsed += duration;
        return _silenceElapsed >= TrailingSilence ? Decision.Ended : Decision.Speaking;
    }

    public void Reset()
    {
        Started = false;
        _silenceElapsed = 0;
        _speechAvgDb = -32f;
        _hasAvg = false;
        _floorDb = _smoothDb = -120f;
        _hasLevel = false;
    }

    public static float Rms(ReadOnlySpan<float> samples)
    {
        if (samples.Length == 0) return 0;
        double sum = 0;
        foreach (var s in samples) sum += s * s;
        return (float)Math.Sqrt(sum / samples.Length);
    }

    public static float Db(float rms) => rms > 0 ? MathF.Max(-120f, 20f * MathF.Log10(rms)) : -120f;
}

public static class Wav
{
    /// 16 kHz mono 16-bit PCM → WAV bytes (44-byte header + data).
    public static byte[] Encode(ReadOnlySpan<short> samples, int sampleRate = 16000)
    {
        var dataLen = samples.Length * 2;
        using var ms = new MemoryStream(44 + dataLen);
        using var w = new BinaryWriter(ms);
        w.Write("RIFF"u8); w.Write(36 + dataLen); w.Write("WAVE"u8);
        w.Write("fmt "u8); w.Write(16); w.Write((short)1); w.Write((short)1);
        w.Write(sampleRate); w.Write(sampleRate * 2); w.Write((short)2); w.Write((short)16);
        w.Write("data"u8); w.Write(dataLen);
        foreach (var s in samples) w.Write(s);
        return ms.ToArray();
    }
}
