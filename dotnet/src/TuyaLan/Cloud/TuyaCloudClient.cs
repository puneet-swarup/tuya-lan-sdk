using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace TuyaLan.Cloud;

/// <summary>
/// Client for the Tuya Cloud API (openapi.tuyacn.com and the regional
/// equivalents): token management, device listing, status and control.
/// </summary>
/// <remarks>
/// <para>
/// Every request is signed with HMAC-SHA256 as described in
/// <see cref="TuyaCloudSigner"/>. The client caches the access token, refreshes
/// it with the refresh token when it is close to expiry, and retries once with a
/// fresh token when the API answers with a token error.
/// </para>
/// <para>
/// This is the cloud path, which needs internet access and a Tuya IoT project.
/// For local control without the cloud, use <see cref="Devices.TuyaLocalDevice"/>.
/// </para>
/// </remarks>
/// <example>
/// <code>
/// using var cloud = new TuyaCloudClient(new TuyaCloudOptions
/// {
///     ClientId = "your-access-id",
///     Secret = "your-access-secret",
///     Region = TuyaCloudRegion.India,
/// });
///
/// var devices = await cloud.GetDevicesAsync();
/// var status = await cloud.GetStatusAsync(devices[0].Id);
/// await cloud.SendCommandsAsync(devices[0].Id, new[] { new TuyaCloudCommand("switch_1", true) });
/// </code>
/// </example>
public sealed class TuyaCloudClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    private static readonly HashSet<string> TokenErrorCodes = new(StringComparer.OrdinalIgnoreCase)
    {
        "1010", // token invalid
        "1011", // token expired
        "1012", // token not exist
    };

    private readonly TuyaCloudOptions _options;
    private readonly HttpClient _http;
    private readonly bool _ownsHttpClient;
    private readonly SemaphoreSlim _tokenGate = new(1, 1);

    private TuyaCloudToken? _token;
    private bool _disposed;

    public TuyaCloudClient(TuyaCloudOptions options, HttpClient? httpClient = null)
    {
        _options = options ?? throw new ArgumentNullException(nameof(options));
        _options.Validate();
        _ownsHttpClient = httpClient is null;
        _http = httpClient ?? new HttpClient { Timeout = options.Timeout };
    }

    /// <summary>The endpoint currently in use.</summary>
    public string BaseUrl => _options.BaseUrl;

    // ------------------------------------------------------------- tokens

    /// <summary>Returns a usable access token, fetching or refreshing it when needed.</summary>
    public async Task<TuyaCloudToken> GetTokenAsync(bool forceRefresh = false, CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        await _tokenGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!forceRefresh && _token is { } cached && cached.IsFresh) return cached;

            if (!forceRefresh && _token is { } expiring && !string.IsNullOrEmpty(expiring.RefreshToken))
            {
                try
                {
                    return await RefreshTokenAsync(expiring.RefreshToken, cancellationToken).ConfigureAwait(false);
                }
                catch (TuyaCloudException)
                {
                    // fall through to a full token request
                }
            }

            return await RequestTokenAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _tokenGate.Release();
        }
    }

    private async Task<TuyaCloudToken> RequestTokenAsync(CancellationToken cancellationToken)
    {
        var query = new Dictionary<string, string?> { ["grant_type"] = "1" };
        var token = await ExecuteAsync<TuyaCloudToken>(HttpMethod.Get, "/v1.0/token", query, null, useToken: false, cancellationToken)
            .ConfigureAwait(false);

        return StoreToken(token);
    }

    private async Task<TuyaCloudToken> RefreshTokenAsync(string refreshToken, CancellationToken cancellationToken)
    {
        var token = await ExecuteAsync<TuyaCloudToken>(
            HttpMethod.Get,
            $"/v1.0/token/{Uri.EscapeDataString(refreshToken)}",
            null,
            null,
            useToken: false,
            cancellationToken).ConfigureAwait(false);

        return StoreToken(token);
    }

    private TuyaCloudToken StoreToken(TuyaCloudToken? token)
    {
        if (token is null || string.IsNullOrEmpty(token.AccessToken))
        {
            throw new TuyaCloudException("the token endpoint returned no access token");
        }

        // expire_time is a duration in seconds; keep a minute of headroom.
        long lifetime = Math.Max(60, token.ExpireTime - 60);
        token.ExpiresAt = DateTimeOffset.UtcNow.AddSeconds(lifetime);
        _token = token;
        return token;
    }

    // -------------------------------------------------------------- devices

    /// <summary>All devices on the project's linked account.</summary>
    public async Task<IReadOnlyList<TuyaCloudDevice>> GetDevicesAsync(CancellationToken cancellationToken = default) =>
        await ExecuteAsync<List<TuyaCloudDevice>>(HttpMethod.Get, "/v1.0/devices", null, null, true, cancellationToken)
            .ConfigureAwait(false) ?? new List<TuyaCloudDevice>();

    /// <summary>Devices owned by one app user.</summary>
    public async Task<IReadOnlyList<TuyaCloudDevice>> GetUserDevicesAsync(string userId, CancellationToken cancellationToken = default) =>
        await ExecuteAsync<List<TuyaCloudDevice>>(
            HttpMethod.Get,
            $"/v1.0/users/{Uri.EscapeDataString(userId)}/devices",
            null,
            null,
            true,
            cancellationToken).ConfigureAwait(false) ?? new List<TuyaCloudDevice>();

    /// <summary>One device, including its local key - useful to bootstrap local control.</summary>
    public Task<TuyaCloudDevice?> GetDeviceAsync(string deviceId, CancellationToken cancellationToken = default) =>
        ExecuteAsync<TuyaCloudDevice>(
            HttpMethod.Get,
            $"/v1.0/devices/{Uri.EscapeDataString(deviceId)}",
            null,
            null,
            true,
            cancellationToken);

    /// <summary>Current data points of a device.</summary>
    public async Task<IReadOnlyList<TuyaCloudStatusItem>> GetStatusAsync(string deviceId, CancellationToken cancellationToken = default) =>
        await ExecuteAsync<List<TuyaCloudStatusItem>>(
            HttpMethod.Get,
            $"/v1.0/devices/{Uri.EscapeDataString(deviceId)}/status",
            null,
            null,
            true,
            cancellationToken).ConfigureAwait(false) ?? new List<TuyaCloudStatusItem>();

    /// <summary>Sends one or more instructions to a device.</summary>
    public async Task<bool> SendCommandsAsync(
        string deviceId,
        IEnumerable<TuyaCloudCommand> commands,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(commands);
        var body = new { commands = commands.ToList() };
        return await ExecuteAsync<bool>(
            HttpMethod.Post,
            $"/v1.0/devices/{Uri.EscapeDataString(deviceId)}/commands",
            null,
            body,
            true,
            cancellationToken).ConfigureAwait(false);
    }

    /// <summary>The functions and status set defined by the device's product.</summary>
    public Task<TuyaCloudDeviceSpecification?> GetSpecificationAsync(string deviceId, CancellationToken cancellationToken = default) =>
        ExecuteAsync<TuyaCloudDeviceSpecification>(
            HttpMethod.Get,
            $"/v1.0/iot-03/devices/{Uri.EscapeDataString(deviceId)}/specification",
            null,
            null,
            true,
            cancellationToken);

    /// <summary>
    /// Escape hatch for endpoints this client does not model. Returns the
    /// <c>result</c> member of the response, deserialised into
    /// <typeparamref name="T"/>.
    /// </summary>
    public Task<T?> RequestAsync<T>(
        HttpMethod method,
        string path,
        IDictionary<string, string?>? query = null,
        object? body = null,
        CancellationToken cancellationToken = default) =>
        ExecuteAsync<T>(method, path, query, body, true, cancellationToken);

    // ------------------------------------------------------------ transport

    private async Task<T?> ExecuteAsync<T>(
        HttpMethod method,
        string path,
        IDictionary<string, string?>? query,
        object? body,
        bool useToken,
        CancellationToken cancellationToken)
    {
        using var document = await SendAsync(method, path, query, body, useToken, useToken, cancellationToken)
            .ConfigureAwait(false);

        if (!document.RootElement.TryGetProperty("result", out var result)) return default;
        if (result.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined) return default;

        return result.Deserialize<T>(JsonOptions);
    }

    private async Task<JsonDocument> SendAsync(
        HttpMethod method,
        string path,
        IDictionary<string, string?>? query,
        object? body,
        bool useToken,
        bool retryOnTokenError,
        CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        var queryPairs = query?.ToList();
        string url = TuyaCloudSigner.BuildUrl(path, queryPairs);

        byte[]? bodyBytes = body is null ? null : JsonSerializer.SerializeToUtf8Bytes(body, JsonOptions);

        string? accessToken = useToken
            ? (await GetTokenAsync(false, cancellationToken).ConfigureAwait(false)).AccessToken
            : null;

        long timestamp = TuyaCloudSigner.CurrentTimestamp();
        string nonce = TuyaCloudSigner.CreateNonce();
        string stringToSign = TuyaCloudSigner.BuildStringToSign(method.Method, bodyBytes, null, url);
        string sign = accessToken is null
            ? TuyaCloudSigner.SignToken(_options.ClientId, _options.Secret, timestamp, nonce, stringToSign)
            : TuyaCloudSigner.SignBusiness(_options.ClientId, accessToken, _options.Secret, timestamp, nonce, stringToSign);

        using var request = new HttpRequestMessage(method, _options.BaseUrl + url);
        if (bodyBytes is not null)
        {
            var content = new ByteArrayContent(bodyBytes);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            request.Content = content;
        }

        request.Headers.Add("client_id", _options.ClientId);
        request.Headers.Add("sign", sign);
        request.Headers.Add("sign_method", "HMAC-SHA256");
        request.Headers.Add("t", timestamp.ToString(CultureInfo.InvariantCulture));
        request.Headers.Add("nonce", nonce);
        request.Headers.Add("lang", _options.Language);
        if (accessToken is not null) request.Headers.Add("access_token", accessToken);

        using var response = await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
        string text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);

        if (!response.IsSuccessStatusCode)
        {
            throw new TuyaCloudException(
                $"HTTP {(int)response.StatusCode} from {method.Method} {path}",
                null,
                (int)response.StatusCode,
                text);
        }

        var document = JsonDocument.Parse(text);
        var root = document.RootElement;

        bool success = !root.TryGetProperty("success", out var successElement) ||
                       successElement.ValueKind != JsonValueKind.False;

        if (success) return document;

        string? code = root.TryGetProperty("code", out var codeElement) ? codeElement.ToString() : null;
        string? message = root.TryGetProperty("msg", out var msgElement) ? msgElement.GetString() : null;
        document.Dispose();

        if (retryOnTokenError && IsTokenError(code, message))
        {
            await GetTokenAsync(true, cancellationToken).ConfigureAwait(false);
            return await SendAsync(method, path, query, body, useToken, false, cancellationToken).ConfigureAwait(false);
        }

        throw new TuyaCloudException(
            message ?? "the cloud API returned success=false",
            code,
            (int)response.StatusCode,
            text);
    }

    private static bool IsTokenError(string? code, string? message)
    {
        if (code is not null && TokenErrorCodes.Contains(code)) return true;
        return (code?.Contains("TOKEN", StringComparison.OrdinalIgnoreCase) ?? false) ||
               (message?.Contains("token", StringComparison.OrdinalIgnoreCase) ?? false);
    }

    /// <inheritdoc />
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_ownsHttpClient) _http.Dispose();
        _tokenGate.Dispose();
    }
}
