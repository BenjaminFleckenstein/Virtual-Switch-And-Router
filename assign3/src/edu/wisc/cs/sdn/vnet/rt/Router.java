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
import java.util.concurrent.ScheduledExecutorService;
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

	private final int RIP_INFINITY = 16;

	private static class RouteKey
	{
		int destination;
		int mask;

		RouteKey(int d, int m)
		{
			this.destination = d;
			this.mask = m;
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) return true;
			if (!(o instanceof RouteKey)) return false;
			RouteKey other = (RouteKey) o;
			return this.destination == other.destination && this.mask == other.mask;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(destination) * 31 + Integer.hashCode(mask);
		}
	}

	private Map<RouteKey, Long> routeTimestamps; // for expiring old routes

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
		this.ripTable = new RIPv2();
		this.routeTimestamps = new HashMap<RouteKey, Long>();
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
			if (udp.getDestinationPort() == UDP.RIP_PORT)
			{
				this.handleRIPPacket(etherPacket, inIface);
				return;
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

		if (outIface == inIface)
		{
			return;
		}

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

	/**
	 * Handle a RIPv2 packet.
	 * @param etherPacket the Ethernet packet containing the RIPv2 packet
	 * @param inIface the interface on which the packet was received
	 */
	public void handleRIPPacket(Ethernet etherPacket, Iface inIface)
	{	
		RIPv2 pkt = extractRIPFromPacket(etherPacket);
		if (pkt == null) return;

		if (pkt.getCommand() == RIPv2.COMMAND_REQUEST)
		{
			sendRIPResponse(etherPacket, inIface);
			return;
		}
		else if (pkt.getCommand() == RIPv2.COMMAND_RESPONSE)
		{
			mergeRIP(etherPacket, pkt, inIface);
			return;
		}
	}

	private RIPv2 extractRIPFromPacket(Ethernet etherPacket)
	{
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) return null;

		IPv4 ipPkt = (IPv4) etherPacket.getPayload();

		if (ipPkt.getProtocol() != IPv4.PROTOCOL_UDP) return null;

		UDP udp = (UDP) ipPkt.getPayload();
		if (udp.getDestinationPort() != UDP.RIP_PORT) return null;

		return (RIPv2) udp.getPayload();
	}

	private IPv4 extractIPFromPacket(Ethernet etherPacket)
	{
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) return null;

		return (IPv4) etherPacket.getPayload();
	}

	/*
	 * Implements the distance-vector routing algorithm.
	 * Merge RIPv2 entries into this routing table.
	 * 
	 * @param pkt contains the sender's RIP table
	 */
	private void mergeRIP(Ethernet etherPacket, RIPv2 pkt, Iface inIface)
	{
		for (RIPv2Entry ripEntry : pkt.getEntries())
		{
			int subnet = ripEntry.getAddress();
			int subnetMask = ripEntry.getSubnetMask();
			RouteKey routeKey = new RouteKey(subnet, subnetMask);
			int senderMetric = ripEntry.getMetric();
			
			RIPv2Entry thisDestination = this.ripTable.find(subnet, subnetMask);

			if (thisDestination == null)
			{
				if (senderMetric + 1 >= RIP_INFINITY)
				{
					continue; // Don't add unreachable routes to RIP table
				}
				// New destination: add to routing table and RIPv2 table with metric = sender's metric + 1
				this.routeTable.insert(subnet, extractIPFromPacket(etherPacket).getSourceAddress(), subnetMask, inIface);
				RIPv2Entry newRipEntry = new RIPv2Entry(subnet, subnetMask, senderMetric + 1);
				this.ripTable.addEntry(newRipEntry);
				// Add timestamp for this route for future expiration
				this.routeTimestamps.put(routeKey, System.currentTimeMillis());
			}
			else
			{
				if (this.routeTable.findEntry(subnet, subnetMask).getGatewayAddress() == 0)
				continue; // ignore directly connected routes

				// Existing destination: update if sender's metric + 1 is better than current metric
				if (senderMetric + 1 < thisDestination.getMetric())
				{
					if (senderMetric + 1 >= RIP_INFINITY)
					{
						thisDestination.setMetric(RIP_INFINITY);
					} 
					else 
					{
						thisDestination.setMetric(senderMetric + 1);
					}
					this.routeTable.update(subnet, subnetMask, extractIPFromPacket(etherPacket).getSourceAddress(), inIface);
					//update this RIP table entry
					this.ripTable.replaceEntry(thisDestination);
					//update timestamp for this route for future expiration
					this.routeTimestamps.put(routeKey, System.currentTimeMillis());
				} 
				else if (this.routeTable.findEntry(subnet, subnetMask).getGatewayAddress() == extractIPFromPacket(etherPacket).getSourceAddress())
				{
					if (senderMetric + 1 >= RIP_INFINITY)
					{
						thisDestination.setMetric(RIP_INFINITY);
					} 
					else 
					{
						thisDestination.setMetric(senderMetric + 1);
					}
					// If the sender is the next hop for this destination, update metric to sender's metric + 1 even if it's not better, to account for changes in sender's metric
					this.routeTable.update(subnet, subnetMask, extractIPFromPacket(etherPacket).getSourceAddress(), inIface);
					//update this RIP table entry
					this.ripTable.replaceEntry(thisDestination);
					//update timestamp for this route for future expiration
					this.routeTimestamps.put(routeKey, System.currentTimeMillis());
				}
			}
		}
	}
	
	public void startRIP()
	{
		//Initialize directly reachable subnets in routetable and RIP table
		initTables();
		//Bbroadcast an initial rip request
		RIPBroadcast(RIPv2.COMMAND_REQUEST);
		//Broadcast a rip response every 10 seconds
		initTimedResponse();
	}

	private ScheduledExecutorService ripScheduler;

	private void initTimedResponse()
	{
		this.ripScheduler = Executors.newSingleThreadScheduledExecutor();

		Runnable sendResponses = new Runnable()
		{
			@Override
			public void run()
			{
				expireStaleRoutes();
				RIPBroadcast(RIPv2.COMMAND_RESPONSE);
			}
		};

		this.ripScheduler.scheduleAtFixedRate(
			sendResponses,
			10,
			10,
			TimeUnit.SECONDS
		);	
	}

	/* Initialize the routing table and RIP table with directly reachable subnets */
	private void initTables()
	{
		for (Iface iface : this.interfaces.values())
		{
			int subnetMask = iface.getSubnetMask();
			int subnet = iface.getIpAddress() & subnetMask;
			this.routeTable.insert(subnet, 0, subnetMask, iface);
			this.ripTable.addEntry(new RIPv2Entry(subnet, subnetMask, 1));
		}
	}

	/*
	 * Broadcast a RIPv2 packet on all interfaces
	 * @param pktType: RIPv2.COMMAND_REQUEST for request, RIPv2.COMMAND_RESPONSE for response
	 */
	private void RIPBroadcast(byte pktType)
	{
		if (pktType != RIPv2.COMMAND_REQUEST && pktType != RIPv2.COMMAND_RESPONSE)
		{
				System.err.println("RIPBroadcast(byte pktType) // Invalid RIPv2 packet type for broadcast: " + pktType);
				return;
		}

		for (Iface iface : this.interfaces.values())
		{
			Ethernet ripRequest = buildRIPPkt(pktType, iface);
			this.sendPacket(ripRequest, iface);
		}	
	}
	/*
	 * Build a RIP request or response packet to broadcast on all interfaces
	 * @param pktType: RIPv2.COMMAND_REQUEST for request, RIPv2.COMMAND_RESPONSE for response
	 */
	private Ethernet buildRIPPkt(byte pktType, Iface outIface)
	{
		// Build Rip packet
		RIPv2 rip = new RIPv2();
		rip.setCommand(pktType);
		rip.setEntries(this.ripTable.getEntries()); //Send most recent RIPv2 table entries in the packet payload
		// Encapsulate in UDP
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);
		// Encapsulate in IPv4
		IPv4 ip = new IPv4();
		ip.setSourceAddress(outIface.getIpAddress());
		ip.setDestinationAddress("224.0.0.9");
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);
		// Encapsulate in Ethernet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(outIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(MACAddress.valueOf("FF:FF:FF:FF:FF:FF").toBytes());
		ether.setPayload(ip);

		return ether;
	}

	/*
	 * Build a RIP request or response packet based on an incoming packet
	 * @param pktType: RIPv2.COMMAND_REQUEST for request, RIPv2.COMMAND_RESPONSE for response
	 * @param in: The incoming Ethernet packet
	 */
	private Ethernet buildRIPPkt(byte pktType, Ethernet in)
	{
		// Build Rip packet
		RIPv2 rip = new RIPv2();
		rip.setCommand(pktType);
		rip.setEntries(this.ripTable.getEntries()); //Send most recent RIPv2 table entries in the packet payload
		// Encapsulate in UDP
		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);
		// Encapsulate in IPv4
		IPv4 ip = new IPv4();
		int sourceIp = ((IPv4) in.getPayload()).getSourceAddress();
		int destinationIp = ((IPv4) in.getPayload()).getDestinationAddress();
		ip.setSourceAddress(destinationIp);
		ip.setDestinationAddress(sourceIp);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);
		// Encapsulate in Ethernet
		Ethernet ether = new Ethernet();
		ether.setEtherType(Ethernet.TYPE_IPv4);
		byte[] sourceMACAddress = in.getSourceMACAddress();
		byte[] dstMACAddress = in.getDestinationMACAddress();
		ether.setSourceMACAddress(dstMACAddress);
		ether.setDestinationMACAddress(sourceMACAddress);
		ether.setPayload(ip);

		return ether;
	}

	private void sendRIPResponse(Ethernet packet, Iface outIface)
	{
		Ethernet ripResponse = buildRIPPkt(RIPv2.COMMAND_RESPONSE, packet);
		this.sendPacket(ripResponse, outIface);
	}

	private void expireStaleRoutes()
	{
		long currentTime = System.currentTimeMillis();
		Iterator<Map.Entry<RouteKey, Long>> it = this.routeTimestamps.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<RouteKey, Long> entry = it.next();
			RouteKey routeKey = entry.getKey();
			int destination = routeKey.destination;
			int mask = routeKey.mask;
			long timestamp = entry.getValue();
			if (currentTime - timestamp > 30000) //expire routes that haven't been updated in 30 seconds
			{
				this.routeTable.remove(destination, mask);
				this.ripTable.replaceEntry(new RIPv2Entry(destination, mask, RIP_INFINITY)); //set metric to infinity in RIP table instead of deleting, to notify neighbors of unreachable route
				it.remove();
			}
		}
	}
}
