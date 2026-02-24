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
Peer can share files with "/file". File sharing is also E2EE.

## Running on Linux:

To launch Peer
```bash
./run -p
```

To launch Rendezvous
```bash
./run -r
```

To clean up
```bash
./run -c
```

To compile all
```bash
./run
```
