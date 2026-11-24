using System;
using System.Collections.Generic;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace TuyaLan.Cloud;

/// <summary>An access token for the cloud API, plus the expiry the client tracks.</summary>
public sealed class TuyaCloudToken
{
    [JsonPropertyName("access_token")]
    public string AccessToken { get; set; } = string.Empty;

    [JsonPropertyName("refresh_token")]
    public string RefreshToken { get; set; } = string.Empty;

    [JsonPropertyName("expire_time")]
    public long ExpireTime { get; set; }

    [JsonPropertyName("uid")]
    public string? Uid { get; set; }

    /// <summary>Local clock time at which this token stops being usable.</summary>
    public DateTimeOffset ExpiresAt { get; set; }

    /// <summary>True when the token is still valid for at least another 30 seconds.</summary>
    public bool IsFresh => ExpiresAt > DateTimeOffset.UtcNow.AddSeconds(30);
}

/// <summary>A device as returned by the cloud device endpoints.</summary>
public sealed class TuyaCloudDevice
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("category")]
    public string? Category { get; set; }

    [JsonPropertyName("product_id")]
    public string? ProductId { get; set; }

    [JsonPropertyName("product_name")]
    public string? ProductName { get; set; }

    [JsonPropertyName("online")]
    public bool Online { get; set; }

    [JsonPropertyName("local_key")]
    public string? LocalKey { get; set; }

    [JsonPropertyName("ip")]
    public string? Ip { get; set; }

    [JsonPropertyName("time_zone")]
    public string? TimeZone { get; set; }

    [JsonPropertyName("active_time")]
    public long ActiveTime { get; set; }

    [JsonPropertyName("create_time")]
    public long CreateTime { get; set; }

    [JsonPropertyName("update_time")]
    public long UpdateTime { get; set; }

    [JsonPropertyName("uid")]
    public string? Uid { get; set; }

    public override string ToString() => $"{Name ?? Id} ({Id}) online={Online} category={Category}";
}

/// <summary>One data point in a cloud status response.</summary>
public sealed class TuyaCloudStatusItem
{
    [JsonPropertyName("code")]
    public string Code { get; set; } = string.Empty;

    [JsonPropertyName("value")]
    public JsonNode? Value { get; set; }

    public override string ToString() => $"{Code}={Value?.ToJsonString() ?? "null"}";
}

/// <summary>One instruction sent to a device through the cloud.</summary>
public sealed class TuyaCloudCommand
{
    public TuyaCloudCommand() { }

    public TuyaCloudCommand(string code, JsonNode? value)
    {
        Code = code;
        Value = value;
    }

    public TuyaCloudCommand(string code, bool value) : this(code, JsonValue.Create(value)) { }

    public TuyaCloudCommand(string code, int value) : this(code, JsonValue.Create(value)) { }

    public TuyaCloudCommand(string code, string value) : this(code, JsonValue.Create(value)) { }

    [JsonPropertyName("code")]
    public string Code { get; set; } = string.Empty;

    [JsonPropertyName("value")]
    public JsonNode? Value { get; set; }
}

/// <summary>A single function or status definition from a product's specification.</summary>
public sealed class TuyaCloudSpecification
{
    [JsonPropertyName("code")]
    public string Code { get; set; } = string.Empty;

    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("values")]
    public JsonNode? Values { get; set; }

    [JsonPropertyName("name")]
    public string? Name { get; set; }

    public override string ToString() => $"{Code} ({Type}) {Name}";
}

/// <summary>The functions and status set of a device's product.</summary>
public sealed class TuyaCloudDeviceSpecification
{
    [JsonPropertyName("category")]
    public string? Category { get; set; }

    [JsonPropertyName("functions")]
    public List<TuyaCloudSpecification> Functions { get; set; } = new();

    [JsonPropertyName("status")]
    public List<TuyaCloudSpecification> Status { get; set; } = new();
}
