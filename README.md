# P2P Messenger:
```ascii
                +----------------+
                |VPS (Rendezvous)|
                +----------------+
                    |       |
                +---+       +----+
                |                |
            +-----+          +-----+
            |Peer1|<========>|Peer2|
            +-----+          +-----+
```
VPS (Rendezvous) is used to connect both devices. It serves as STUN to punch holes.

## Messaging:
Messaging is E2E Encrypted. P2P if hole punching is succeeded.

## File sharing:
Peer can share files with `/file`. File sharing is also E2EE.

## Running on Linux:

To launch Peer
```bash
./run -p
```
Choose 3 to if you are behind NAT, and connect to your VPS.

---
To launch Rendezvous
```bash
./run -r
```
And do nothing, it runs on port 8888 by default. Or you can run with `java Rendezvous 1234` to run on your desired port.

---
To clean up
```bash
./run -c
```

---
To compile all
```bash
./run
```
