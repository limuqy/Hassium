package io.github.limuqy.mc.hassium.network.dataplane;

import io.github.limuqy.mc.hassium.config.HassiumConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 将已绑定 UDP listener 与 control 可达地址转换为 append-only S2C 握手尾部。
 *
 * <p>此类不读取配置、不触碰 loader API；调用方负责以同一个配置快照和当前 bind 结果传入参数。
 */
public final class DataPlaneHandshakeAdvertisement {

    private static final int TOKEN_BYTES = 16;

    private DataPlaneHandshakeAdvertisement() {
    }

    /**
     * 构造握手广告。UDP 只在服务器已绑定且双方都声明支持时协商；control candidates 独立协商。
     */
    public static UdpDataPlaneHandshakeTail.S2CTail create(
            List<UdpDataPlaneHandshakeTail.ControlEndpoint> controlEndpoints,
            List<DataPlaneUdpServer.BoundEndpoint> boundEndpoints,
            byte[] token,
            long connectionEpoch,
            boolean udpSupported,
            boolean controlFailoverSupported) {
        List<UdpDataPlaneHandshakeTail.ControlEndpoint> controls = controlEndpoints == null
                ? List.of() : List.copyOf(controlEndpoints);
        List<DataPlaneUdpServer.BoundEndpoint> bound = boundEndpoints == null
                ? List.of() : boundEndpoints.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(DataPlaneUdpServer.BoundEndpoint::endpointId))
                .toList();
        boolean hasUdp = udpSupported && !bound.isEmpty();
        boolean hasControl = controlFailoverSupported && !controls.isEmpty();
        if (!hasUdp) {
            return new UdpDataPlaneHandshakeTail.S2CTail(
                    false, hasControl, connectionEpoch, UdpDataPlaneHandshakeTail.PROTOCOL_VERSION,
                    new byte[TOKEN_BYTES], controls, List.of(), List.of());
        }
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("bound UDP listeners require a 16-byte session token");
        }

        List<UdpDataPlaneHandshakeTail.UdpListenerGroup> groups = bound.stream()
                .map(DataPlaneHandshakeAdvertisement::toGroup)
                .toList();
        List<UdpDataPlaneHandshakeTail.UdpEndpointInfo> legacy = groups.stream()
                .map(group -> {
                    UdpDataPlaneHandshakeTail.UdpReachableEndpoint primary = group.reachableEndpoints().get(0);
                    return new UdpDataPlaneHandshakeTail.UdpEndpointInfo(
                            primary.host(), primary.port(), group.weight(), group.endpointId());
                })
                .toList();
        return new UdpDataPlaneHandshakeTail.S2CTail(
                true, hasControl, connectionEpoch, UdpDataPlaneHandshakeTail.PROTOCOL_VERSION,
                token, controls, legacy, groups);
    }

    private static UdpDataPlaneHandshakeTail.UdpListenerGroup toGroup(
            DataPlaneUdpServer.BoundEndpoint endpoint) {
        List<UdpDataPlaneHandshakeTail.UdpReachableEndpoint> reachable = endpoint.reachableEndpoints().stream()
                .map(DataPlaneHandshakeAdvertisement::toReachable)
                .toList();
        return new UdpDataPlaneHandshakeTail.UdpListenerGroup(
                endpoint.endpointId(), endpoint.weight(), reachable);
    }

    private static UdpDataPlaneHandshakeTail.UdpReachableEndpoint toReachable(
            HassiumConfig.ReachableEndpoint endpoint) {
        return new UdpDataPlaneHandshakeTail.UdpReachableEndpoint(
                endpoint.host(), endpoint.port(), endpoint.priority());
    }
}
