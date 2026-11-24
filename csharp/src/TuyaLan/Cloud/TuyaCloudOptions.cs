using System;

namespace TuyaLan.Cloud;

/// <summary>Tuya data centre a project is hosted in. The endpoint must match the account region.</summary>
public enum TuyaCloudRegion
{
    China,
    WesternAmerica,
    EasternAmerica,
    CentralEurope,
    WesternEurope,
    India,
    Singapore,
}

public static class TuyaCloudRegionExtensions
{
    /// <summary>Base URL for the region, as listed in the Tuya cloud API documentation.</summary>
    public static string ToBaseUrl(this TuyaCloudRegion region) => region switch
    {
        TuyaCloudRegion.China => "https://openapi.tuyacn.com",
        TuyaCloudRegion.WesternAmerica => "https://openapi.tuyaus.com",
        TuyaCloudRegion.EasternAmerica => "https://openapi-ueaz.tuyaus.com",
        TuyaCloudRegion.CentralEurope => "https://openapi.tuyaeu.com",
        TuyaCloudRegion.WesternEurope => "https://openapi-weaz.tuyaeu.com",
        TuyaCloudRegion.India => "https://openapi.tuyain.com",
        TuyaCloudRegion.Singapore => "https://openapi-sg.iotbing.com",
        _ => throw new ArgumentOutOfRangeException(nameof(region), region, "unknown region"),
    };
}

/// <summary>Credentials and endpoint for the Tuya Cloud API.</summary>
public sealed class TuyaCloudOptions
{
    /// <summary>Access ID of the cloud project's authorisation key pair.</summary>
    public string ClientId { get; set; } = string.Empty;

    /// <summary>Access secret of the cloud project's authorisation key pair.</summary>
    public string Secret { get; set; } = string.Empty;

    /// <summary>Data centre hosting the project.</summary>
    public TuyaCloudRegion Region { get; set; } = TuyaCloudRegion.India;

    /// <summary>Overrides <see cref="Region"/> when set, e.g. for a private endpoint.</summary>
    public string? BaseUrlOverride { get; set; }

    /// <summary>Value for the <c>lang</c> header. "en" outside mainland China.</summary>
    public string Language { get; set; } = "en";

    /// <summary>Per-request timeout for the HTTP client created by this library.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);

    /// <summary>The base URL that will be used.</summary>
    public string BaseUrl => BaseUrlOverride?.TrimEnd('/') ?? Region.ToBaseUrl();

    /// <summary>Throws when credentials are missing.</summary>
    public void Validate()
    {
        if (string.IsNullOrWhiteSpace(ClientId)) throw new ArgumentException("ClientId is required", nameof(ClientId));
        if (string.IsNullOrWhiteSpace(Secret)) throw new ArgumentException("Secret is required", nameof(Secret));
    }
}
