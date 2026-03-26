package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import net.floodlightcontroller.packet.MACAddress;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{
	private static final long MAC_TIMEOUT_MS = 15_000;

	// MAC table entry: which port + when last seen
	private static class MacEntry {
		final Iface iface;
		final long lastSeenMs;
		MacEntry(Iface iface, long lastSeenMs) {
			this.iface = iface;
			this.lastSeenMs = lastSeenMs;
		}
	}

	// Learned MAC -> (port, timestamp)
	private final Map<MACAddress, MacEntry> macTable = new HashMap<>();

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		final long now = System.currentTimeMillis();

		// Age out stale MAC entries (15s)
		expireStaleEntries(now);

		// Learn source MAC -> incoming interface (refresh timer)
		MACAddress src = MACAddress.valueOf(etherPacket.getSourceMACAddress());
		macTable.put(src, new MacEntry(inIface, now));

		// Forward based on destination MAC
		MACAddress dst = MACAddress.valueOf(etherPacket.getDestinationMACAddress());
		MacEntry dstEntry = macTable.get(dst);

		if (dstEntry != null) {
			// Known destination
			Iface outIface = dstEntry.iface;

			// If it would go back out the same interface it arrived on, drop packet
			if (!outIface.getName().equals(inIface.getName())) {
				sendPacket(etherPacket, outIface);
			}
			return;
		}

		// Unknown destination -> flood out all ports except inIface
		for (Iface outIface : this.interfaces.values()) {
			if (!outIface.getName().equals(inIface.getName())) {

				// clone packet per output interface
				byte[] bytes = etherPacket.serialize();
				Ethernet copy = new Ethernet();
				copy.deserialize(bytes, 0, bytes.length);

				sendPacket(etherPacket, outIface);
			}
		}
	}

	private void expireStaleEntries(long nowMs)
	{
        macTable.entrySet().removeIf(e -> nowMs - e.getValue().lastSeenMs > MAC_TIMEOUT_MS);
	}
}
