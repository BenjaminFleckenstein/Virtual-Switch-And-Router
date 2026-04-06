# Software-Based Router And Switch Implementation
## **Author**: Benjamin Fleckenstein
## **Email**: bflec27@gmail.com    |   University: bfleckenstei@wisc.edu

---
### Overview
This project was part of my *Comp Sci 640 - Introduction To Computer Networks* course at The University of Wisconsin-Madison. This project was built over the span of several weeks, as more features were added throughout the semester.

The main goals and learning targets of this project were as follows:
- Implement Layer 2 switching logic including MAC address learning and packet forwarding.
- Design a virtual router using longest prefix match for IP routing decisions and distance-vector routing protocol.
- Analyze interface traffic and routing behavior to validate correct packet delivery.

---
### How To Run The Simulations:
Required tools:
- Mininet
- POX controller
- Java (VirtualNetwork.jar)
- Ant (for building/compiling)

Setup:
1. Symlink POX
    ```
    cd ~cs640-a3/assign3
    ln -s ~/pox
    ./config.sh
    ```
2. Start POX controller
    ```
    cd ~cs640-a3/assign3
    ./run_pox.sh
    ...
    connected
    VNet server listening on <IP>:<port>    // signifies successful connection
    ```
3. Start Mininet
    ```
    cd ~cs640-a3/assign3
    sudo ./run_mininet.py topos/<topology>.topo -a
    // replace <topology> with your desired network topology to simulate
    // see cs640-a3/assign3/topos for different provided topologies
    ```
4. Start routers/switches
    ```
    cd ~cs640-a3/assign3
    ant
    java -jar VirtualNetwork.jar -v <router> -a arp_cache //router
    java -jar VirtualNetwork.jar -v <switch> //switch
    // replace <router>/<switch> with desired router or switch (ex: r1, s1, r2, s2, r3, s3, etc.)
    // see cs640-a3/assign3/topos for different provided topologies
    // open a new shell session for each router/switch
    ```
---
Now that you have your virtual network set up, you can send packets across the network. From your terminal running Mininet, send ping requests from host to host. Watch your terminals running each router/switch as they send and receive packets in real time.
```
<sender host name (example: h1)> ping -c <# ping messages> <receiver IP>
// see cs640-a3/assign3/topos for different provided topologies
```
While simulating a router topology with at least 3 routers, try killing one router process (via ```ctrl+c```) and observe as the packets reroute.

---
### Source Code:
The source code for routing and switching logic can be found in:
    ~/cs640/projects/cs640-a3/assign3/src/edu/wisc/cs/sdn/vnet/*
    
    &
    
    ~/cs640/projects/cs640-a3/assign3/src/net/floodlightcontroller/packet/*

The most interesting files, where routing/switching protocol and packet forwarding logic are defined, are named "Switch.java" & "Router.java".

---
### Other Notes:
- Make sure to start POX before the routers/switches.
- Make sure you are running the routers/switches corresponding to the topology you are simulating.
- You must run 1 instance per device.
- Some of the code in this project was provided by instructors. Our main focus in this course was to implement the routing and switching protocols, as well as packet forwarding logic.
- This README.md was last updated on 4.5.2026.
