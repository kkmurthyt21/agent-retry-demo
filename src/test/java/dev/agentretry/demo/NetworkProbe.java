package dev.agentretry.demo;

/** Negative control: the offline test must deny a connection before network I/O. */
public final class NetworkProbe {
    private NetworkProbe() { }
    @SuppressWarnings("removal")
    public static void main(String[] args) throws Exception {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9), 200);
            throw new IllegalStateException("The offline policy did not block network access");
        } catch (java.security.AccessControlException expected) {
            System.out.println("NETWORK_ACCESS_DENIED");
        }
    }
}
