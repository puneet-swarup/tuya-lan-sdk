using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace TuyaLan.Devices;

/// <summary>
/// A data-point snapshot reported by a device.
/// </summary>
/// <remarks>
/// A device sends either a bare <c>{"dps":{...}}</c> object or a wrapper such as
/// <c>{"devId":"...","t":1700000000,"data":{"dps":{...}}}</c>. Some gateways
/// double-encode the wrapper as a JSON string. All three shapes are handled.
/// </remarks>
public sealed class TuyaStatus
{
    /// <summary>Device id, when the payload carried one.</summary>
    public string? DeviceId { get; init; }

    /// <summary>Device-side Unix timestamp, or 0 when absent.</summary>
    public long Timestamp { get; init; }

    /// <summary>Data points keyed by DP id ("1", "20", ...).</summary>
    public IReadOnlyDictionary<string, JsonNode?> Dps { get; init; } = new Dictionary<string, JsonNode?>();

    /// <summary>The decoded payload, kept for debugging.</summary>
    public string RawJson { get; init; } = string.Empty;

    /// <summary>True when at least one data point was present.</summary>
    public bool HasData => Dps.Count > 0;

    /// <summary>Looks up a data point.</summary>
    public JsonNode? this[string dpId] => Dps.TryGetValue(dpId, out var value) ? value : null;

    /// <summary>True when the data point exists and is a JSON boolean.</summary>
    public bool? GetBool(string dpId) => this[dpId] is JsonValue value && value.TryGetValue(out bool result) ? result : null;

    /// <summary>True when the data point exists and is a JSON number.</summary>
    public double? GetNumber(string dpId) => this[dpId] is JsonValue value && value.TryGetValue(out double result) ? result : null;

    /// <summary>Reads a numeric data point as a 32-bit integer.</summary>
    public int? GetInt(string dpId)
    {
        var number = GetNumber(dpId);
        return number.HasValue ? (int)Math.Round(number.Value) : null;
    }

    /// <summary>True when the data point exists and is a JSON string.</summary>
    public string? GetString(string dpId) => this[dpId] is JsonValue value && value.TryGetValue(out string? result) ? result : null;

    /// <summary>Parses a payload. Never throws: an unparseable payload yields an empty status.</summary>
    public static TuyaStatus Parse(ReadOnlySpan<byte> payload) => Parse(Encoding.UTF8.GetString(payload));

    /// <summary>Parses a payload. Never throws: an unparseable payload yields an empty status.</summary>
    public static TuyaStatus Parse(string? json)
    {
        if (json is null || string.IsNullOrWhiteSpace(json)) return new TuyaStatus { RawJson = json ?? string.Empty };

        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return new TuyaStatus { RawJson = json };

            string? deviceId = root.TryGetProperty("devId", out var devIdElement) && devIdElement.ValueKind == JsonValueKind.String
                ? devIdElement.GetString()
                : null;

            long timestamp = root.TryGetProperty("t", out var tElement) && tElement.ValueKind == JsonValueKind.Number &&
                             tElement.TryGetInt64(out long parsedTimestamp)
                ? parsedTimestamp
                : 0;

            JsonElement dpsElement = default;
            bool found = false;

            if (root.TryGetProperty("dps", out var direct) && direct.ValueKind == JsonValueKind.Object)
            {
                dpsElement = direct;
                found = true;
            }
            else if (root.TryGetProperty("data", out var data))
            {
                if (data.ValueKind == JsonValueKind.Object && data.TryGetProperty("dps", out var nested) &&
                    nested.ValueKind == JsonValueKind.Object)
                {
                    dpsElement = nested;
                    found = true;
                }
                else if (data.ValueKind == JsonValueKind.String)
                {
                    var inner = Parse(data.GetString());
                    if (inner.HasData) return inner;
                }
            }

            var dps = new Dictionary<string, JsonNode?>(StringComparer.Ordinal);
            if (found)
            {
                foreach (var property in dpsElement.EnumerateObject())
                {
                    dps[property.Name] = JsonNode.Parse(property.Value.GetRawText());
                }
            }

            return new TuyaStatus { DeviceId = deviceId, Timestamp = timestamp, Dps = dps, RawJson = json };
        }
        catch (JsonException)
        {
            return new TuyaStatus { RawJson = json };
        }
    }

    public override string ToString()
    {
        var builder = new StringBuilder();
        foreach (var pair in Dps)
        {
            if (builder.Length > 0) builder.Append(", ");
            builder.Append(pair.Key).Append('=').Append(pair.Value?.ToJsonString() ?? "null");
        }

        return $"TuyaStatus({Dps.Count} dps: {builder})";
    }
}

/// <summary>
/// Fluent builder for a control payload's <c>dps</c> object.
/// </summary>
/// <example>
/// <code>
/// var dps = new TuyaDps()
///     .Set("1", true)
///     .Set("2", 500)
///     .SetEnum("3", "white")
///     .SetRaw("24", "014a03e803a9");
/// await device.SetDpsAsync(dps);
/// </code>
/// </example>
public sealed class TuyaDps : Dictionary<string, JsonNode?>
{
    public TuyaDps() : base(StringComparer.Ordinal) { }

    /// <summary>Sets a boolean data point (switches, toggles).</summary>
    public TuyaDps Set(string dpId, bool value)
    {
        this[dpId] = JsonValue.Create(value);
        return this;
    }

    /// <summary>Sets a numeric data point (brightness, temperature, colour data).</summary>
    public TuyaDps Set(string dpId, int value)
    {
        this[dpId] = JsonValue.Create(value);
        return this;
    }

    /// <summary>Sets a numeric data point with a fractional value.</summary>
    public TuyaDps Set(string dpId, double value)
    {
        this[dpId] = JsonValue.Create(value);
        return this;
    }

    /// <summary>Sets a string data point.</summary>
    public TuyaDps Set(string dpId, string value)
    {
        this[dpId] = JsonValue.Create(value);
        return this;
    }

    /// <summary>Sets an enum data point, which is a JSON string on the wire.</summary>
    public TuyaDps SetEnum(string dpId, string value) => Set(dpId, value);

    /// <summary>Sets a raw data point from a hex string (used by some DP types).</summary>
    public TuyaDps SetRaw(string dpId, string hexValue) => Set(dpId, hexValue);

    /// <summary>Sets a value of any supported CLR type.</summary>
    public TuyaDps SetObject(string dpId, object? value)
    {
        this[dpId] = value switch
        {
            null => null,
            bool b => JsonValue.Create(b),
            int i => JsonValue.Create(i),
            long l => JsonValue.Create(l),
            double d => JsonValue.Create(d),
            string s => JsonValue.Create(s),
            _ => JsonValue.Create(Convert.ToString(value, CultureInfo.InvariantCulture)),
        };
        return this;
    }
}
