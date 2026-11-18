using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using TuyaLan.Cloud;
using TuyaLan.Devices;
using TuyaLan.Protocol;

namespace TuyaLan.Demo;

/// <summary>
/// Console playground for the library.
/// </summary>
/// <example>
/// <code>
/// dotnet run -- scan
/// dotnet run -- status 192.168.1.42 eb0123456789abcdefghij 0123456789abcdef 3.5
/// dotnet run -- toggle 192.168.1.42 eb0123456789abcdefghij 0123456789abcdef 3.5 1
/// dotnet run -- cloud &lt;clientId&gt; &lt;secret&gt; &lt;region&gt;
/// </code>
/// </example>
internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        if (args.Length == 0)
        {
            PrintUsage();
            return 1;
        }

        try
        {
            return args[0].ToLowerInvariant() switch
            {
                "scan" => await ScanAsync().ConfigureAwait(false),
                "status" => await StatusAsync(args).ConfigureAwait(false),
                "toggle" => await ToggleAsync(args).ConfigureAwait(false),
                "cloud" => await CloudAsync(args).ConfigureAwait(false),
                _ => Fail($"unknown command '{args[0]}'"),
            };
        }
        catch (TuyaException ex)
        {
            Console.Error.WriteLine($"Tuya error: {ex.Message}");
            return 2;
        }
    }

    private static async Task<int> ScanAsync()
    {
        Console.WriteLine("Scanning the local network for Tuya devices (6s)...");
        var devices = await TuyaDiscovery.ScanAsync(TimeSpan.FromSeconds(6)).ConfigureAwait(false);

        if (devices.Count == 0)
        {
            Console.WriteLine("No devices answered. Some 3.5 firmware stays silent - try again while");
            Console.WriteLine("toggling the device in the Smart Life app, or use the cloud API for keys.");
            return 0;
        }

        foreach (var device in devices)
        {
            Console.WriteLine($"  {device.IpAddress,-16} {device.DeviceId,-24} v{device.Version.ToLabel()}  productKey={device.ProductKey}");
        }

        return 0;
    }

    private static async Task<int> StatusAsync(string[] args)
    {
        if (args.Length < 5) return Fail("usage: status <ip> <deviceId> <localKey> <version>");

        await using var device = Build(args[1], args[2], args[3], args[4]);
        device.StatusUpdated += (_, e) => Console.WriteLine($"  push: {e.Status}");

        Console.WriteLine($"Connecting to {args[1]} (protocol {device.Version.ToLabel()})...");
        await device.ConnectAsync().ConfigureAwait(false);
        Console.WriteLine($"Connected. Session key negotiated: {device.HasSessionKey}");

        var status = await device.GetStatusAsync().ConfigureAwait(false);
        Console.WriteLine($"Status ({status.Dps.Count} data points):");
        foreach (var pair in status.Dps)
        {
            Console.WriteLine($"  DP {pair.Key,-4} = {pair.Value?.ToJsonString() ?? "null"}");
        }

        Console.WriteLine("Listening for pushes for 10s (press Ctrl+C to stop)...");
        await Task.Delay(TimeSpan.FromSeconds(10)).ConfigureAwait(false);
        return 0;
    }

    private static async Task<int> ToggleAsync(string[] args)
    {
        if (args.Length < 6) return Fail("usage: toggle <ip> <deviceId> <localKey> <version> <dpId>");

        await using var device = Build(args[1], args[2], args[3], args[4]);
        await device.ConnectAsync().ConfigureAwait(false);

        var status = await device.GetStatusAsync().ConfigureAwait(false);
        string dpId = args[5];
        bool current = status.GetBool(dpId) ?? false;

        Console.WriteLine($"DP {dpId} is {current}; setting it to {!current}");
        await device.SetDpAsync(dpId, !current).ConfigureAwait(false);

        await Task.Delay(TimeSpan.FromSeconds(1)).ConfigureAwait(false);
        var after = await device.GetStatusAsync().ConfigureAwait(false);
        Console.WriteLine($"DP {dpId} is now {after.GetBool(dpId)}");
        return 0;
    }

    private static async Task<int> CloudAsync(string[] args)
    {
        if (args.Length < 4) return Fail("usage: cloud <clientId> <secret> <region>");

        if (!Enum.TryParse<TuyaCloudRegion>(args[3], ignoreCase: true, out var region))
        {
            return Fail($"unknown region '{args[3]}'. Try: {string.Join(", ", Enum.GetNames<TuyaCloudRegion>())}");
        }

        using var cloud = new TuyaCloudClient(new TuyaCloudOptions
        {
            ClientId = args[1],
            Secret = args[2],
            Region = region,
        });

        var devices = await cloud.GetDevicesAsync().ConfigureAwait(false);
        Console.WriteLine($"{devices.Count} device(s) on this project:");
        foreach (var device in devices)
        {
            Console.WriteLine($"  {device.Name} [{device.Category}] id={device.Id} online={device.Online} key={Mask(device.LocalKey)}");
        }

        if (devices.Count > 0)
        {
            var status = await cloud.GetStatusAsync(devices[0].Id).ConfigureAwait(false);
            Console.WriteLine($"Status of {devices[0].Name}:");
            foreach (var item in status) Console.WriteLine($"  {item}");
        }

        return 0;
    }

    private static TuyaLocalDevice Build(string host, string deviceId, string localKey, string version) =>
        new(new TuyaDeviceOptions
        {
            Host = host,
            DeviceId = deviceId,
            LocalKey = localKey,
            Version = TuyaProtocolVersionExtensions.ParseLabel(version),
        });

    private static string Mask(string? localKey) =>
        string.IsNullOrEmpty(localKey) ? "-" : localKey[..4] + new string('*', Math.Max(0, localKey.Length - 4));

    private static int Fail(string message)
    {
        Console.Error.WriteLine(message);
        PrintUsage();
        return 1;
    }

    private static void PrintUsage()
    {
        Console.WriteLine("TuyaLan demo");
        Console.WriteLine("  scan");
        Console.WriteLine("  status <ip> <deviceId> <localKey> <version>");
        Console.WriteLine("  toggle <ip> <deviceId> <localKey> <version> <dpId>");
        Console.WriteLine("  cloud  <clientId> <secret> <region>");
    }
}
