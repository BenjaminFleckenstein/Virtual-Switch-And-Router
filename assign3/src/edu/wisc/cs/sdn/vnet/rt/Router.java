package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.floodlightcontroller.packet.UDP;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

	// RIPv2 table
	private RIPv2 ripTable;
	// map of RIP entry key -> last update time (ms)
	private Map<String, Long> ripEntryTimestamps;

	// scheduler for unsolicited RIP responses
	private ScheduledExecutorService ripScheduler;
	private ScheduledFuture<?> ripSenderFuture;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ripTable = null;
		this.ripEntryTimestamps = new HashMap<String, Long>();
		this.ripScheduler = null;
		this.ripSenderFuture = null;
	}

	public void initRIP()
	{
		// Start unsolicited RIP response sender (every 10 seconds)
		if (this.ripScheduler == null)
		{
			this.ripScheduler = Executors.newSingleThreadScheduledExecutor();
			this.ripSenderFuture = this.ripScheduler.scheduleAtFixedRate(new Runnable() {
				public void run() {
					sendUnsolicitedRIPResponses();
				}
			}, 10, 10, TimeUnit.SECONDS);
		}
	}

	private void sendUnsolicitedRIPResponses()
	{
		if (this.ripTable == null) return;
		// take a snapshot copy of ripTable entries to avoid concurrent modification
		List<RIPv2Entry> entriesCopy = new ArrayList<RIPv2Entry>();
		synchronized(this.ripTable) {
			entriesCopy.addAll(this.ripTable.getEntries());
		}

		for (Iface iface : this.interfaces.values())
		{
			Ethernet pkt = buildRIPResponseFromEntries(entriesCopy, iface);
			this.sendPacket(pkt, iface);
		}
	}

	/**
	 * Build a RIP response Ethernet packet from a list of entries (thread-safe caller).
	 */
	public Ethernet buildRIPResponseFromEntries(List<RIPv2Entry> entries, Iface inIface)
	{
		RIPv2 ripResponse = new RIPv2();
		ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);
		ripResponse.setEntries(entries);
		// encapsulate it in a UDP packet
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(ripResponse);
		//encapsulate in an IPv4 packet with appropriate source and multicast dest
		IPv4 ip = new IPv4();
		ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(IPv4.toIPv4Address("224.0.0.9"));
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);
		//encapsulate the UDP packet in an Ethernet frame
		Ethernet ether = new Ethernet();
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes());
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setPayload(ip);
		return ether;
	}

	@Override
	public void destroy()
	{
		if (this.ripSenderFuture != null) {
			this.ripSenderFuture.cancel(false);
			this.ripSenderFuture = null;
		}
		if (this.ripScheduler != null) {
			this.ripScheduler.shutdownNow();
			this.ripScheduler = null;
		}
		super.destroy();
	}

	private boolean isDirectlyConnected(RIPv2Entry e)
	{
		int entryNet = e.getAddress() & e.getSubnetMask();
		for (Iface iface : this.interfaces.values())
		{
			int mask = iface.getSubnetMask();
			if (mask == 0) continue;
			int ifaceNet = iface.getIpAddress() & mask;
			if (mask == e.getSubnetMask() && ifaceNet == entryNet)
			{
				return true;
			}
			// Also consider case where entry's mask is within iface's mask (iface covers the entry)
			if ((iface.getIpAddress() & e.getSubnetMask()) == entryNet)
			{
				return true;
			}
		}
		return false;
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
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

		// Check if packet is IPv4
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) return;

		IPv4 ipPkt = (IPv4) etherPacket.getPayload();

		// Verify IPv4 header checksum
		short receivedCksum = ipPkt.getChecksum();
		ipPkt.setChecksum((short)0);

		// Recompute checksum using the packet library's serialization logic.
		ipPkt.serialize();
		short computedCksum = ipPkt.getChecksum();

		if (computedCksum != receivedCksum) return; // drop: packet is corrupt
		
		// If packet is a UDP packet, determine if it's a RIP request/response and call the appropriate handler
		if (ipPkt.getProtocol() == IPv4.PROTOCOL_UDP)
		{
			UDP udp = (UDP) ipPkt.getPayload();
			if (udp.getSourcePort() == UDP.RIP_PORT || udp.getDestinationPort() == UDP.RIP_PORT)
			{
				RIPv2 rip = (RIPv2) udp.getPayload();
				if (rip.getCommand() == RIPv2.COMMAND_REQUEST)
				{
					this.ripRequestHandler(inIface);
				}
				else if (rip.getCommand() == RIPv2.COMMAND_RESPONSE)
				{
					this.ripResponseHandler(inIface, rip);
				}
			}
		}

		// Decrement TTL and drop if 0
		byte ttl = ipPkt.getTtl();
		ttl--;
		if (ttl == 0) return;
		ipPkt.setTtl(ttl);

		// Recompute checksum after modifying TTL
		ipPkt.setChecksum((short)0);
		ipPkt.serialize();

		// Drop if packet is destined to one of this router's interfaces
		int dstIp = ipPkt.getDestinationAddress();
		for (Iface iface : this.interfaces.values())
		{
			if (iface.getIpAddress() == dstIp) return;
		}

		// Longest-prefix-match route lookup
		RouteEntry bestRoute = this.routeTable.lookup(dstIp);
		if (bestRoute == null) return;

		Iface outIface = bestRoute.getInterface();

		// Determine next-hop IP (gateway or direct)
		int nextHopIp = bestRoute.getGatewayAddress();
		if (nextHopIp == 0)
		{
			// Directly connected network: next hop is the destination itself
			nextHopIp = dstIp;
		}

		// ARP lookup for next hop
		ArpEntry arpEntry = this.arpCache.lookup(nextHopIp);
		if (arpEntry == null) return; // static ARP cache missing entry

		MACAddress nextHopMac = arpEntry.getMac();

		// Rewrite Ethernet header and forward
		etherPacket.setDestinationMACAddress(nextHopMac.toBytes());
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

		this.sendPacket(etherPacket, outIface);
	}

	public void ripRequestHandler(Iface inIface)
	{returnRipResponse(inIface);}

	public void ripResponseHandler(Iface inIface, RIPv2 ripResponse)
	{
		if (this.ripTable == null)
		{
			this.ripTable = new RIPv2();
		}
		merge(ripResponse);

		// update timestamps for entries seen in this response
		long now = System.currentTimeMillis();
		for (RIPv2Entry entry : ripResponse.getEntries())
		{
			String key = entry.getAddress() + "/" + entry.getSubnetMask();
			this.ripEntryTimestamps.put(key, now);
		}

		// Age out entries not updated for >30s, but never remove directly connected subnets
		long timeoutMs = 30 * 1000;
		Iterator<RIPv2Entry> it = this.ripTable.getEntries().iterator();
		while (it.hasNext())
		{
			RIPv2Entry e = it.next();
			String key = e.getAddress() + "/" + e.getSubnetMask();
			Long last = this.ripEntryTimestamps.get(key);
			if (last == null || (now - last) > timeoutMs)
			{
				if (!isDirectlyConnected(e))
				{
					it.remove();
					this.ripEntryTimestamps.remove(key);
				}
			}
		}
	}

	public void returnRipResponse(Iface inIface)
	{
		Ethernet ripResponsePkt = buildRIPResponse(this.ripTable, inIface);
		this.sendPacket(ripResponsePkt, inIface);
	}

	public Ethernet buildRIPResponse(RIPv2 ripTable, Iface inIface)
	{
		// Create a RIPv2 packet
		RIPv2 ripResponse = new RIPv2();
		ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);
		ripResponse.setEntries(ripTable.getEntries());
		// encapsulate it in a UDP packet
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(ripResponse);
		//encapsulate in an IPv4 packet with the appropriate source and destination IP addresses
		IPv4 ip = new IPv4();
		ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(inIface.getIpAddress()); // Send back to requester
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);
		//encapsulate the UDP packet in an Ethernet frame with the appropriate source and destination MAC addresses
		Ethernet ether = new Ethernet();
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(inIface.getMacAddress().toBytes()); // Send back
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setPayload(ip);

		return ether;
	}

	public RIPv2 getRipTable()
	{return this.ripTable;}

	public void merge(RIPv2 ripResponse)
	{
		for (RIPv2Entry entry : ripResponse.getEntries())
		{
			int addr = entry.getAddress();
			int mask = entry.getSubnetMask();
			int newMetric = entry.getMetric() + 1;
			if (newMetric > 16) newMetric = 16;

			boolean found = false;
			for (RIPv2Entry myEntry : this.ripTable.getEntries())
			{
				if (myEntry.getAddress() == addr && myEntry.getSubnetMask() == mask)
				{
					found = true;
					if (newMetric < myEntry.getMetric())
					{
						myEntry.setMetric(newMetric);
						myEntry.setNextHopAddress(entry.getNextHopAddress());
					}
					break;
				}
			}

			if (!found)
			{
				RIPv2Entry newEntry = new RIPv2Entry();
				newEntry.setAddress(addr);
				newEntry.setSubnetMask(mask);
				newEntry.setNextHopAddress(entry.getNextHopAddress());
				newEntry.setMetric(newMetric);
				this.ripTable.addEntry(newEntry);
			}
		}
	}

	public Ethernet buildRIPRequest(Iface inIface)
	{
		// Create a RIPv2 packet
		RIPv2 ripRequest = new RIPv2();
		ripRequest.setCommand(RIPv2.COMMAND_REQUEST);

		// encapsulate it in a UDP packet
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(ripRequest);

		//encapsulate in an IPv4 packet with the appropriate source and destination IP addresses
		IPv4 ip = new IPv4();
		ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(IPv4.toIPv4Address("224.0.0.9")); // RIP multicast address
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);

		//encapsulate the UDP packet in an Ethernet frame with the appropriate source and destination MAC addresses
		Ethernet ether = new Ethernet();
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes()); // Broadcast MAC address
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setPayload(ip);

		return ether;
	}

	public void broadcastRIPRequest()
	{
		for (Iface iface : this.interfaces.values())
		{
			Ethernet ripRequestPkt = buildRIPRequest(iface);
			this.sendPacket(ripRequestPkt, iface);
		}
	}

	/**
 * Add route-table entries for all directly connected subnets.
 * Each entry:
 *   destination = iface IP & iface mask
 *   gateway     = 0 (directly connected)
 *   mask        = iface subnet mask
 *   interface   = iface
 *
 * These routes should be installed before sending initial RIP requests.
 */
public void initializeDirectRoutes()
{
    for (Iface iface : this.interfaces.values())
    {
        int ifaceIp = iface.getIpAddress();
        int mask = iface.getSubnetMask();

        // Skip interfaces that are not fully configured
        if (ifaceIp == 0 || mask == 0)
        {
            continue;
        }

        // Compute directly connected network prefix
        int network = ifaceIp & mask;

        // Avoid duplicate entries if this gets called more than once
        RouteEntry existing = this.routeTable.lookup(network);
        if (existing != null
                && existing.getDestinationAddress() == network
                && existing.getMaskAddress() == mask
                && existing.getGatewayAddress() == 0
                && existing.getInterface() == iface)
        {
            continue;
        }

        // Insert direct route: no gateway because the subnet is directly attached
        this.routeTable.insert(network, 0, mask, iface);
    }
}

	public void startRIP()
	{
		initializeDirectRoutes();
		broadcastRIPRequest();
		initRIP();
	}
}
