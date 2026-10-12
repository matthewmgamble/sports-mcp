package ca.hex42labs.sports.api.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** An IPv4 or IPv6 CIDR block, e.g. {@code 173.245.48.0/20} or {@code 2400:cb00::/32}. */
record Cidr(byte[] network, int prefix) {

    static Cidr parse(String s) {
        String[] parts = s.trim().split("/", 2);
        try {
            byte[] addr = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : addr.length * 8;
            if (prefix < 0 || prefix > addr.length * 8) {
                throw new IllegalArgumentException("Bad prefix in " + s);
            }
            return new Cidr(addr, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Bad CIDR '" + s + "'", e);
        }
    }

    static List<Cidr> parseAll(List<String> specs) {
        return specs.stream().map(Cidr::parse).toList();
    }

    /** Only literal IPs are matched; no DNS lookups. */
    boolean contains(String ip) {
        byte[] addr;
        try {
            if (ip == null || ip.isBlank() || !ip.matches("[0-9a-fA-F:.]+")) {
                return false;
            }
            addr = InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        if (addr.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        if (!Arrays.equals(Arrays.copyOf(addr, full), Arrays.copyOf(network, full))) {
            return false;
        }
        int rem = prefix % 8;
        if (rem == 0) {
            return true;
        }
        int mask = 0xff << (8 - rem) & 0xff;
        return (addr[full] & mask) == (network[full] & mask);
    }
}
