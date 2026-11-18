using System;
using System.Text;
using TuyaLan.Cloud;
using TuyaLan.Devices;
using TuyaLan.Protocol;
using Xunit;

namespace TuyaLan.Tests;

/// <summary>
/// Known-answer tests. Every expected value below is produced by
/// <c>tools/reference_impl.py --emit-vectors</c>, which is an independent
/// implementation of the same wire format, so a mistake in this library cannot
/// silently define its own expectations.
/// </summary>
public class ProtocolVectorTests
{
    // "0123456789abcdef" - a stand-in local key.
    private const string KeyHex = "30313233343536373839616263646566";

    // "0123456789ab" - a fixed GCM nonce so frames are reproducible.
    private const string IvHex = "303132333435363738396162";

    private const string Plaintext = "{\"devId\":\"eb0123456789abcdefghij\",\"t\":1700000000,\"dps\":{\"1\":true}}";

    private static readonly byte[] Key = TuyaCrypto.FromHex(KeyHex);
    private static readonly byte[] Iv = TuyaCrypto.FromHex(IvHex);

    [Fact]
    public void Crc32MatchesReference()
    {
        Assert.Equal(2524178384u, TuyaCrc32.Compute(Encoding.UTF8.GetBytes(Plaintext)));
    }

    [Fact]
    public void Pack6699MatchesReference()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 7, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        Assert.Equal(
            "00006699000000000007000000070000005e3031323334353637383961621960699e582949ab39211286b4eb91c0a2bd405f5282246107fb141610fab2e4d9d58c73a1b6b8ef301af890e141a4b900938a107efb4eb841d994321b66c2f0478cd3b3916df1ada9d7c36cc7aeb1e64c2100009966",
            TuyaCrypto.ToHex(frame));
    }

    [Fact]
    public void Pack6699WithReturnCodeMatchesReference()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 8, TuyaCommand.Status, Encoding.UTF8.GetBytes(Plaintext), returnCode: 0, iv: Iv);
        Assert.Equal(
            "00006699000000000008000000080000006230313233343536373839616262420dfb554249ec754a13c6bef8c691a6b9445b5e8e73345ca6101214f6bee89c91c76da1a0abac2210f997e141a4b91c81de5021fb10b310ca8c734d2595af4e8394a77454c43d5b3fbe57e279a439fcf618a54ebc00009966",
            TuyaCrypto.ToHex(frame));
    }

    [Fact]
    public void Unpack6699RoundTrips()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 42, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        var message = TuyaFrameCodec.Unpack(TuyaProtocolVersion.V35, Key, frame);

        Assert.Equal(TuyaCommand.Control, message.Command);
        Assert.Equal(42u, message.SequenceNumber);
        Assert.Equal(Plaintext, message.PayloadText);
        Assert.Null(message.ReturnCode);
    }

    [Fact]
    public void Unpack6699StripsReturnCode()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 8, TuyaCommand.Status, Encoding.UTF8.GetBytes(Plaintext), returnCode: 0, iv: Iv);
        var message = TuyaFrameCodec.Unpack(TuyaProtocolVersion.V35, Key, frame);

        Assert.Equal(0u, message.ReturnCode);
        Assert.Equal(Plaintext, message.PayloadText);
    }

    [Fact]
    public void Unpack6699RejectsTampering()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 7, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        frame[30] ^= 0xFF;
        Assert.Throws<TuyaCryptoException>(() => TuyaFrameCodec.Unpack(TuyaProtocolVersion.V35, Key, frame));
    }

    [Fact]
    public void Unpack6699RejectsWrongKey()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 7, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        var wrongKey = TuyaCrypto.FromHex("66656463626139383736353433323130");
        Assert.Throws<TuyaCryptoException>(() => TuyaFrameCodec.Unpack(TuyaProtocolVersion.V35, wrongKey, frame));
    }

    [Fact]
    public void Pack55AaV34MatchesReference()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V34, Key, 3, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext));
        Assert.Equal(
            "000055aa0000000300000007000000844490b05d74be9368c24a038cbaeded8e93cdaa9ddbe759a2de1dd3364730e6e7b02174601734b32067a7df69e7fdae03b2068860fe6deff0bde9fdfc2e98273941fb8ef70a70a27b93a7e74ffe75426303000aeb4ff51d29188f82e4a162a1f627cdbca6f71f92a1bf0a6b965b8f203913db4fc95b759309b969660796980fd20000aa55",
            TuyaCrypto.ToHex(frame));
    }

    [Fact]
    public void Unpack55AaV34RoundTrips()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V34, Key, 3, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext));
        var message = TuyaFrameCodec.Unpack(TuyaProtocolVersion.V34, Key, frame, RetcodePolicy.Never);

        Assert.Equal(TuyaCommand.Control, message.Command);
        Assert.Equal(Plaintext, message.PayloadText);
    }

    [Fact]
    public void Pack55AaV33MatchesReference()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V33, Key, 4, TuyaCommand.DpQuery, Encoding.UTF8.GetBytes(Plaintext));
        Assert.Equal(
            "000055aa000000040000000a00000058f1a712fc1110fda4489c54a53046067e0a426a5d54a482ce0e3fbd3bdbec46141f2a18cc7295041d10eba653e1ff2e47f09a264f41ed2ed2962f8ff11444d4dd64215f365b0dad6b2ac8fab15b1d40bdeb39c6c20000aa55",
            TuyaCrypto.ToHex(frame));
    }

    [Fact]
    public void DeriveSessionKey35MatchesReference()
    {
        var session = new TuyaSession(TuyaProtocolVersion.V35, Key);
        var derived = session.DeriveSessionKey(
            TuyaCrypto.FromHex("30313233343536373839616263646566"),
            TuyaCrypto.FromHex("66656463626139383736353433323130"));

        Assert.Equal("34165bab783422860c0c23b2d48cf7a5", TuyaCrypto.ToHex(derived));
    }

    [Fact]
    public void DeriveSessionKey34MatchesReference()
    {
        var session = new TuyaSession(TuyaProtocolVersion.V34, Key);
        var derived = session.DeriveSessionKey(
            TuyaCrypto.FromHex("30313233343536373839616263646566"),
            TuyaCrypto.FromHex("66656463626139383736353433323130"));

        Assert.Equal("6575cf6b37479d9215337ff9767fe786", TuyaCrypto.ToHex(derived));
    }

    [Fact]
    public void DiscoveryKeyMatchesReference()
    {
        Assert.Equal("6c1ec8e2bb9bb59ab50b0daf649b410a", TuyaCrypto.ToHex(TuyaDiscovery.DiscoveryKey));
    }

    [Fact]
    public void TryReadFrameHandlesPartialInput()
    {
        var frame = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 1, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);

        Assert.False(TuyaFrameCodec.TryReadFrame(frame.AsSpan(0, 10), out _));
        Assert.True(TuyaFrameCodec.TryReadFrame(frame, out var parsed));
        Assert.Equal((uint)frame.Length, (uint)parsed.Bytes.Length);
        Assert.Equal(TuyaCommand.Control, parsed.Command);
    }

    [Fact]
    public void TryReadFrameHandlesTwoFramesInOneBuffer()
    {
        var first = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 1, TuyaCommand.Control, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        var second = TuyaFrameCodec.Pack(TuyaProtocolVersion.V35, Key, 2, TuyaCommand.Status, Encoding.UTF8.GetBytes(Plaintext), iv: Iv);
        var buffer = new byte[first.Length + second.Length];
        first.CopyTo(buffer, 0);
        second.CopyTo(buffer, first.Length);

        Assert.True(TuyaFrameCodec.TryReadFrame(buffer, out var parsed));
        Assert.Equal((uint)first.Length, (uint)parsed.Bytes.Length);
        Assert.True(TuyaFrameCodec.TryReadFrame(buffer.AsSpan(first.Length), out var next));
        Assert.Equal(2u, next.SequenceNumber);
    }
}

public class CloudSignerTests
{
    /// <summary>
    /// The worked example published by Tuya in "Sign Requests for Cloud
    /// Authorization". Reproducing this exact digest is the only way to be sure
    /// the concatenation order and the empty signature-header line are right.
    /// </summary>
    [Fact]
    public void TokenSignMatchesPublishedVector()
    {
        const string clientId = "1KAD46OrT9HafiKdsXeg";
        const string secret = "4OHBOnWOqaEC1mWXOpVL3yV50s0qGSRC";
        const long timestamp = 1588925778000;
        const string nonce = "5138cc3a9033d69856923fd07b491173";

        var stringToSign = TuyaCloudSigner.BuildStringToSign(
            "GET",
            Array.Empty<byte>(),
            new[]
            {
                new System.Collections.Generic.KeyValuePair<string, string>("area_id", "29a33e8796834b1efa6"),
                new System.Collections.Generic.KeyValuePair<string, string>("call_id", "8afdb70ab2ed11eb85290242ac130003"),
            },
            "/v1.0/token?grant_type=1");

        Assert.Equal(
            "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\narea_id:29a33e8796834b1efa6\ncall_id:8afdb70ab2ed11eb85290242ac130003\n\n/v1.0/token?grant_type=1",
            stringToSign);

        Assert.Equal(
            "9E48A3E93B302EEECC803C7241985D0A34EB944F40FB573C7B5C2A82158AF13E",
            TuyaCloudSigner.SignToken(clientId, secret, timestamp, nonce, stringToSign));
    }

    [Fact]
    public void EmptyBodyHashesToTheKnownSha256()
    {
        var stringToSign = TuyaCloudSigner.BuildStringToSign("GET", null, null, "/v1.0/devices");
        Assert.StartsWith("GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n", stringToSign);
    }

    [Fact]
    public void QueryParametersAreSorted()
    {
        var url = TuyaCloudSigner.BuildUrl("/v1.0/iot-03/devices/x/logs", new System.Collections.Generic.Dictionary<string, string?>
        {
            ["start_time"] = "1657160836000",
            ["event_types"] = "1",
            ["end_time"] = "1657263936000",
            ["ignored"] = null,
        });

        Assert.Equal(
            "/v1.0/iot-03/devices/x/logs?end_time=1657263936000&event_types=1&start_time=1657160836000",
            url);
    }
}

public class StatusParsingTests
{
    [Fact]
    public void ParsesBareDpsObject()
    {
        var status = TuyaStatus.Parse("{\"dps\":{\"1\":true,\"2\":500,\"3\":\"white\"}}");
        Assert.Equal(3, status.Dps.Count);
        Assert.True(status.GetBool("1"));
        Assert.Equal(500, status.GetInt("2"));
        Assert.Equal("white", status.GetString("3"));
    }

    [Fact]
    public void ParsesWrappedDataObject()
    {
        var status = TuyaStatus.Parse("{\"devId\":\"abc\",\"t\":1700000000,\"data\":{\"dps\":{\"20\":false}}}");
        Assert.Equal("abc", status.DeviceId);
        Assert.Equal(1700000000, status.Timestamp);
        Assert.False(status.GetBool("20"));
    }

    [Fact]
    public void UnparseablePayloadYieldsEmptyStatus()
    {
        var status = TuyaStatus.Parse("json obj data unvalid");
        Assert.False(status.HasData);
    }
}
