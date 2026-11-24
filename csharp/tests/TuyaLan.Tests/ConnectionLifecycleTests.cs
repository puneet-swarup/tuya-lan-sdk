using System;
using System.Threading.Tasks;
using TuyaLan.Devices;
using TuyaLan.Protocol;
using Xunit;

namespace TuyaLan.Tests;

/// <summary>
/// Guards the connection lifecycle, which is where the worst bug in this library
/// lived: IsConnected reported "the stream object exists", stayed true after the
/// peer closed, and every later command timed out against a socket nobody was
/// reading. Nothing reconnected, and the device looked unreachable for ever.
/// </summary>
public class ConnectionLifecycleTests
{
    private static TuyaDeviceOptions Options(int port) => new()
    {
        Host = "127.0.0.1",
        Port = port,
        DeviceId = "bf0123456789abcdefghij",
        LocalKey = "0123456789abcdef",
        // 3.3 uses the static local key and negotiates nothing, so the fake peer
        // only has to accept a connection.
        Version = TuyaProtocolVersion.V33,
        ConnectTimeout = TimeSpan.FromSeconds(2),
        CommandTimeout = TimeSpan.FromSeconds(2),
        EnableHeartbeat = false,
    };

    [Fact]
    public async Task ConnectingToAListeningPeerSucceeds()
    {
        using var peer = new FakeDevice();
        await using var device = new TuyaLocalDevice(Options(peer.Port));

        await device.ConnectAsync();

        Assert.True(device.IsConnected);

        // The kernel completes the TCP handshake before the accept loop gets
        // scheduled, so the count is waited for, never assumed.
        Assert.True(await WaitUntil(() => peer.AcceptedCount >= 1, TimeSpan.FromSeconds(2)));
        Assert.Equal(1, peer.AcceptedCount);
    }

    [Fact]
    public async Task IsConnectedTurnsFalseWhenThePeerGoesAway()
    {
        using var peer = new FakeDevice();
        await using var device = new TuyaLocalDevice(Options(peer.Port));
        await device.ConnectAsync();
        Assert.True(device.IsConnected);

        // The router timed out, the plug rebooted, the machine slept - all the
        // same thing at this level.
        peer.DropConnection();

        bool noticed = await WaitUntil(() => !device.IsConnected, TimeSpan.FromSeconds(5));
        Assert.True(noticed, "the device never noticed that its peer had gone");
    }

    [Fact]
    public async Task EnsureConnectedReconnectsAfterThePeerComesBack()
    {
        using var peer = new FakeDevice();
        await using var device = new TuyaLocalDevice(Options(peer.Port));
        await device.ConnectAsync();

        peer.DropConnection();
        await WaitUntil(() => !device.IsConnected, TimeSpan.FromSeconds(5));

        // The old code returned the cached device here, because IsConnected lied.
        await device.EnsureConnectedAsync();

        Assert.True(device.IsConnected);
        Assert.True(await WaitUntil(() => peer.AcceptedCount >= 2, TimeSpan.FromSeconds(2)));
        Assert.Equal(2, peer.AcceptedCount);
    }

    [Fact]
    public async Task EnsureConnectedDoesNotReconnectWhenTheSocketIsFine()
    {
        using var peer = new FakeDevice();
        await using var device = new TuyaLocalDevice(Options(peer.Port));
        await device.ConnectAsync();

        await device.EnsureConnectedAsync();
        await device.EnsureConnectedAsync();

        // Give a wrong implementation time to make a second connection before
        // declaring that it did not.
        await Task.Delay(200);
        Assert.Equal(1, peer.AcceptedCount);
    }

    private static async Task<bool> WaitUntil(Func<bool> condition, TimeSpan timeout)
    {
        DateTime deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (condition()) return true;
            await Task.Delay(25);
        }
        return condition();
    }
}
