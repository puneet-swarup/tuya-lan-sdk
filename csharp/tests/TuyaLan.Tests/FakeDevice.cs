using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace TuyaLan.Tests;

/// <summary>
/// A socket that accepts connections and nothing else.
/// </summary>
/// <remarks>
/// Enough to test the connection lifecycle, which is what the 2.1.0 bug was
/// about: the device never noticed a socket dying, so it reused it for ever.
///
/// It speaks protocol 3.3 on purpose. 3.4 and 3.5 negotiate a session key, which
/// would mean faking the whole handshake; 3.3 uses the static local key, so the
/// device connects without negotiating and this only has to accept and close.
/// </remarks>
public sealed class FakeDevice : IDisposable
{
    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _stop = new();
    private readonly Task _acceptLoop;

    public FakeDevice()
    {
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _acceptLoop = Task.Run(AcceptLoopAsync);
    }

    public int Port { get; }

    public int AcceptedCount => Volatile.Read(ref _accepted);
    private int _accepted;

    /// <summary>The most recent accepted connection, so a test can close it.</summary>
    public TcpClient? Current { get; private set; }

    /// <summary>Closes the peer's side, the way a router timeout or a reboot would.</summary>
    /// <summary>
    /// Closes the peer's side. Waits for the accept loop to have stored the
    /// socket first: the kernel completes the TCP handshake before AcceptAsync
    /// returns, so dropping immediately after a connect can otherwise close
    /// nothing and leave the device connected - the same race that made the
    /// Kotlin test fail intermittently.
    /// </summary>
    public void DropConnection()
    {
        DateTime deadline = DateTime.UtcNow.AddSeconds(2);
        while (Current is null && DateTime.UtcNow < deadline) Thread.Sleep(5);
        try { Current?.Close(); } catch { /* already gone */ }
        Current = null;
    }

    private async Task AcceptLoopAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { return; }

            Interlocked.Increment(ref _accepted);
            Current = client;
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        try { _listener.Stop(); } catch { /* already stopped */ }
        DropConnection();
        try { _acceptLoop.Wait(TimeSpan.FromSeconds(2)); } catch { /* best effort */ }
        _stop.Dispose();
    }
}
